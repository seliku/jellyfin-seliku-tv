package org.jellyfin.androidtv.seliku

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.jellyfin.androidtv.auth.repository.UserRepository
import org.jellyfin.androidtv.ui.playback.rewrite.RewriteMediaManager
import org.jellyfin.playback.core.PlaybackManager
import org.jellyfin.playback.core.model.PlayState
import org.jellyfin.playback.core.model.PlaybackOrder
import org.jellyfin.playback.core.model.RepeatMode
import org.jellyfin.playback.core.queue.Queue
import org.jellyfin.playback.core.queue.queue
import org.jellyfin.playback.jellyfin.queue.baseItem
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.displayPreferencesApi
import org.jellyfin.sdk.api.client.extensions.instantMixApi
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.api.client.extensions.playStateApi
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.api.client.extensions.userViewsApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CollectionType
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemFilter
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.MediaType
import org.jellyfin.sdk.model.api.SortOrder
import org.koin.java.KoinJavaComponent.get
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.milliseconds

/**
 * Was gerade gehört wird. Jede Art verhält sich wie die besten Apps ihrer Sorte:
 * Musik wie Plexamp, Hörbücher wie Audible/BookPlayer, Podcasts wie Pocket Casts.
 */
enum class HoerArt { MUSIK, PODCAST, HOERBUCH }

/** Ein Hörbuch: der Ordner und seine Dateien (Kapitel) in Reihenfolge. */
class Buch(val ordner: BaseItemDto, val dateien: List<BaseItemDto>) {
	val id: UUID get() = ordner.id
	val gesamtMs: Long get() = dateien.sumOf { dauerMs(it) }
	fun index(datei: UUID) = dateien.indexOfFirst { it.id == datei }
	/** Wie weit man im ganzen Buch ist, wenn man in Datei [nr] bei [ms] steht. */
	fun bisMs(nr: Int, ms: Long) = dateien.take(nr).sumOf { dauerMs(it) } + ms
}

fun dauerMs(item: BaseItemDto) = (item.runTimeTicks ?: 0L) / 10_000L

/** Gemerkter Stand: Datei (bei Hörbüchern), Stelle darin und wann. */
data class HoerStand(val datei: UUID?, val ms: Long, val zeit: Long)

/** Fortschritt für Kacheln: Anteil 0..1 und Restzeit. */
data class HoerFortschritt(val anteil: Float, val restMs: Long, val kapitel: Int = 0, val kapitelVon: Int = 0)

/** Schlaf-Timer: bis zu einer Uhrzeit oder bis zum Ende der Datei [endeVon] (Kapitel, Folge, Titel). */
data class SchlafTimer(val bis: Long?, val endeVon: UUID?, val minuten: Int)

object Hoeren {
	private val api by lazy { get<ApiClient>(ApiClient::class.java) }
	private val pm by lazy { get<PlaybackManager>(PlaybackManager::class.java) }
	private val benutzer by lazy { get<UserRepository>(UserRepository::class.java) }
	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
	private lateinit var app: Context

	/** Sprungweiten in Sekunden (zurück, vor): Audible 30/30, Podcasts kürzer zurück, Musik 10/10. */
	fun sprung(art: HoerArt): Pair<Int, Int> = when (art) {
		HoerArt.HOERBUCH -> 30 to 30
		HoerArt.PODCAST -> 10 to 30
		HoerArt.MUSIK -> 10 to 10
	}

	/** Tempo-Stufen für Hörbücher und Podcasts; Musik läuft immer normal. */
	val tempi = listOf(0.8f, 1.0f, 1.1f, 1.25f, 1.5f, 1.75f, 2.0f)

	// --- Art erkennen ---

	private val podcastAlben = ConcurrentHashMap.newKeySet<UUID>()
	@Volatile private var podcastsGeladen = 0L

	fun art(item: BaseItemDto?): HoerArt = when {
		item == null -> HoerArt.MUSIK
		item.type == BaseItemKind.AUDIO_BOOK -> HoerArt.HOERBUCH
		item.albumId?.let { it in podcastAlben } == true || item.id in podcastAlben -> HoerArt.PODCAST
		else -> HoerArt.MUSIK
	}

	fun istPodcastAlbum(id: UUID) = id in podcastAlben

	/** Alben der Podcast-Bibliotheken (Musik-Bibliothek mit «Podcast» im Namen); stündlich neu. */
	suspend fun podcastsLaden(erzwingen: Boolean = false) {
		if (!erzwingen && System.currentTimeMillis() - podcastsGeladen < 3_600_000L) return
		runCatching {
			io {
				val bibs = api.userViewsApi.getUserViews().content.items
					.filter { it.collectionType == CollectionType.MUSIC && it.name.orEmpty().contains("podcast", true) }
				bibs.flatMap { b ->
					api.itemsApi.getItems(parentId = b.id, recursive = true, includeItemTypes = setOf(BaseItemKind.MUSIC_ALBUM)).content.items.map { it.id }
				}
			}
		}.onSuccess { podcastAlben.addAll(it); podcastsGeladen = System.currentTimeMillis() }.onFailure { Timber.w(it, "Podcasts laden") }
	}

	// --- Gemerkter Stand (im Konto, gilt auf allen Geräten) ---
	// b:<Buch> = Datei|ms|Zeit, f:<Buch> = Zeit (fertig), p:<Folge> = ms|Zeit, t:<Buch/Podcast> = Tempo

	private const val PREF_ID = "seliku-hoeren"
	private const val PREF_CLIENT = "seliku"
	private val sperre = Mutex()
	private val stand = ConcurrentHashMap<String, String>()
	private val geaendert = ConcurrentHashMap.newKeySet<String>()
	@Volatile private var geladenFuer: UUID? = null
	/** Zählt bei jeder Änderung hoch: Seiten laden ihre Fortschritte neu. */
	val version = MutableStateFlow(0)

	private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

	private suspend fun geladen() {
		val ich = benutzer.currentUser.value?.id ?: return
		if (geladenFuer == ich) return
		sperre.withLock {
			if (geladenFuer == ich) return
			val prefs = runCatching { io { api.displayPreferencesApi.getDisplayPreferences(displayPreferencesId = PREF_ID, client = PREF_CLIENT).content.customPrefs } }
				.onFailure { Timber.w(it, "Hörstand laden") }.getOrNull() ?: return
			stand.clear(); geaendert.clear(); fortschritte.clear()
			prefs.forEach { (k, v) -> if (!v.isNullOrEmpty()) stand[k] = v }
			geladenFuer = ich
		}
	}

	private fun setzen(k: String, v: String?) {
		if (v == null) stand.remove(k) else stand[k] = v
		geaendert += k
	}

	/** Geänderte Werte ins Konto schreiben (nur diese – was ein anderes Gerät inzwischen gemerkt hat, bleibt). */
	suspend fun hochladen() {
		if (geaendert.isEmpty() || geladenFuer == null) return
		sperre.withLock {
			val schluessel = geaendert.toList()
			runCatching {
				io {
					val alt = api.displayPreferencesApi.getDisplayPreferences(displayPreferencesId = PREF_ID, client = PREF_CLIENT).content
					val neu = alt.customPrefs.toMutableMap()
					schluessel.forEach { k -> stand[k]?.let { neu[k] = it } ?: neu.remove(k) }
					api.displayPreferencesApi.updateDisplayPreferences(displayPreferencesId = PREF_ID, client = PREF_CLIENT, data = alt.copy(customPrefs = neu))
				}
			}.onSuccess { geaendert.removeAll(schluessel.toSet()) }.onFailure { Timber.w(it, "Hörstand speichern") }
		}
	}

	suspend fun buchStand(buch: UUID): HoerStand? {
		geladen()
		val t = stand["b:$buch"]?.split("|") ?: return null
		return runCatching { HoerStand(UUID.fromString(t[0]), t[1].toLong(), t[2].toLong()) }.getOrNull()
	}

	suspend fun buchFertig(buch: UUID): Boolean { geladen(); return stand.containsKey("f:$buch") }

	suspend fun folgeStand(folge: UUID): HoerStand? {
		geladen()
		val t = stand["p:$folge"]?.split("|") ?: return null
		return runCatching { HoerStand(null, t[0].toLong(), t[1].toLong()) }.getOrNull()
	}

	suspend fun tempo(id: UUID?): Float { geladen(); return id?.let { stand["t:$it"]?.toFloatOrNull() } ?: 1f }

	/** Tempo für das Laufende eine Stufe weiter (gemerkt pro Buch bzw. Podcast). */
	fun tempoWeiter() {
		val item = laufend() ?: return
		val art = art(item)
		if (art == HoerArt.MUSIK) return
		val jetzt = pm.state.speed.value
		val neu = tempi.firstOrNull { it > jetzt + 0.01f } ?: tempi.first()
		pm.state.setSpeed(neu)
		tempoSchluessel(item)?.let { setzen("t:$it", if (neu == 1f) null else neu.toString()) }
		scope.launch { hochladen() }
	}

	private fun tempoSchluessel(item: BaseItemDto): UUID? = when (art(item)) {
		HoerArt.HOERBUCH -> buchId(item)
		HoerArt.PODCAST -> item.albumId
		HoerArt.MUSIK -> null
	}

	/** Angefangene Bücher und Folgen, zuletzt gehörte zuerst: (Kennung, Stand, ist Buch). */
	suspend fun angefangen(): List<Triple<UUID, HoerStand, Boolean>> {
		geladen()
		return stand.entries.mapNotNull { (k, v) ->
			val t = v.split("|")
			runCatching {
				when {
					k.startsWith("b:") -> Triple(UUID.fromString(k.drop(2)), HoerStand(UUID.fromString(t[0]), t[1].toLong(), t[2].toLong()), true)
					k.startsWith("p:") -> Triple(UUID.fromString(k.drop(2)), HoerStand(null, t[0].toLong(), t[1].toLong()), false)
					else -> null
				}
			}.getOrNull()
		}.sortedByDescending { it.second.zeit }
	}

	/** Buch als gehört markieren (fällt aus «Weiterhören») bzw. wieder auf Anfang setzen. */
	suspend fun buchGehoert(buch: UUID, ja: Boolean) {
		geladen()
		setzen("b:$buch", null)
		setzen("f:$buch", if (ja) System.currentTimeMillis().toString() else null)
		hochladen(); version.value++
	}

	/** Folge als gehört bzw. ungehört markieren. */
	suspend fun folgeGehoert(folge: UUID, ja: Boolean) {
		geladen()
		setzen("p:$folge", null)
		runCatching { io { if (ja) api.playStateApi.markPlayedItem(itemId = folge) else api.playStateApi.markUnplayedItem(itemId = folge) } }
		hochladen(); version.value++
	}

	/** Fortschritt eines angefangenen Buchs bzw. einer Folge, für Kacheln. Null = nicht angefangen. */
	private val fortschritte = ConcurrentHashMap<UUID, HoerFortschritt>()
	fun fortschritt(id: UUID): HoerFortschritt? = fortschritte[id]

	// --- Bücher ---

	private val buecher = ConcurrentHashMap<UUID, Buch>()

	/** Dateien eines Buchordners; liegen sie in Unterordnern (CD 1, CD 2 …), werden diese der Reihe nach angehängt. */
	suspend fun buch(id: UUID): Buch? = buecher[id] ?: runCatching {
		io {
			val ordner = api.userLibraryApi.getItem(itemId = id).content
			suspend fun inhalt(p: UUID): List<BaseItemDto> {
				val alle = api.itemsApi.getItems(parentId = p, sortBy = listOf(ItemSortBy.SORT_NAME), enableUserData = true,
					fields = setOf(ItemFields.PRIMARY_IMAGE_ASPECT_RATIO)).content.items
				val dateien = alle.filter { it.type == BaseItemKind.AUDIO_BOOK || it.type == BaseItemKind.AUDIO }
				return dateien.ifEmpty { alle.filter { it.isFolder == true }.flatMap { inhalt(it.id) } }
			}
			Buch(ordner, inhalt(id))
		}
	}.onFailure { Timber.w(it, "Buch laden") }.getOrNull()?.takeIf { it.dateien.isNotEmpty() }?.also { buecher[id] = it }

	/** Zu welchem Buch eine Datei gehört (die Datei aus der Warteschlange kennt ihren Ordner nicht immer). */
	fun buchId(item: BaseItemDto): UUID? =
		buecher.values.firstOrNull { b -> b.dateien.any { it.id == item.id } }?.id ?: item.parentId

	/** Das Buch zur laufenden Datei (geladen, falls nötig). */
	suspend fun buchVon(item: BaseItemDto): Buch? = buchId(item)?.let { buch(it) }

	/** Liegen in diesem Ordner Hörbuch-Dateien (dann ist er ein Buch)? */
	fun istBuch(inhalt: List<BaseItemDto>) = inhalt.any { it.type == BaseItemKind.AUDIO_BOOK }

	// --- Abspielen ---

	/** Was zuletzt als Liste gestartet wurde (für «Als Nächstes», Kapitel und Folgen im Player). */
	var liste: List<BaseItemDto> = emptyList(); private set
	private var ausstehend: Pair<UUID, Long>? = null
	private var ausstehendBis = 0L

	fun laufend(): BaseItemDto? = pm.queue.entry.value?.baseItem?.takeIf { it.mediaType == MediaType.AUDIO }

	/**
	 * Spielt [titel] ab Nummer [ab] (die Titel davor bleiben in der Liste, «Zurück» geht also auch dorthin) und springt
	 * auf [startMs]. Mit [zufall] gemischt.
	 */
	fun spielen(titel: List<BaseItemDto>, ab: Int = 0, startMs: Long = 0, zufall: Boolean = false) {
		if (titel.isEmpty()) return
		RadioSpieler.stoppen()
		val nr = ab.coerceIn(0, titel.lastIndex)
		liste = titel
		ausstehend = if (startMs > 0) titel[nr].id to startMs else null
		ausstehendBis = System.currentTimeMillis() + 15_000L
		endlosFuer = null
		schlaf.value = null
		pm.state.setRepeatMode(RepeatMode.NONE)
		pm.state.setPlaybackOrder(if (zufall) PlaybackOrder.SHUFFLE else PlaybackOrder.DEFAULT)
		pm.queue.clear()
		pm.queue.addSupplier(RewriteMediaManager.BaseItemQueueSupplier(api, titel, true))
		pm.state.play()
		if (nr > 0 && !zufall) scope.launch {
			// Die Warteschlange beginnt selbst bei 0; danach zur gewünschten Nummer
			withTimeoutOrNull(5_000) { pm.queue.entryIndex.first { it != Queue.INDEX_NONE } }
			pm.queue.setIndex(nr)
		}
	}

	/** Hörbuch weiterhören (dort, wo man war, ein paar Sekunden davor) oder ab Kapitel [ab]. */
	suspend fun buchSpielen(id: UUID, ab: Int? = null): Boolean {
		val buch = buch(id) ?: return false
		val s = buchStand(id)
		var nr = 0
		var ms = 0L
		if (ab != null) nr = ab
		else if (s?.datei != null) {
			val i = buch.index(s.datei)
			if (i >= 0) { nr = i; ms = (s.ms - rueckspulen(s.zeit)).coerceAtLeast(0) }
		}
		// Wieder von vorn: «fertig» zurücknehmen
		if (stand.containsKey("f:$id")) setzen("f:$id", null)
		spielen(buch.dateien, nr, ms)
		return true
	}

	/**
	 * Jellyfin hakt eine Folge (Audio) schon beim Start als gespielt ab. Gehört ist sie darum erst, wenn sie gespielt ist
	 * und keine Stelle mehr gemerkt ist (die wird kurz vor Schluss gelöscht).
	 */
	fun folgeGehoert(folge: BaseItemDto): Boolean = folge.userData?.played == true && !stand.containsKey("p:${folge.id}")

	/** Podcast-Folge (an der gemerkten Stelle), danach die nächsten ungehörten Folgen des Podcasts in Erscheinungsfolge. */
	suspend fun folgeSpielen(folge: BaseItemDto, alle: List<BaseItemDto>? = null) {
		geladen()
		val folgen = alle ?: folge.albumId?.let { a -> runCatching { podcastFolgen(a) }.getOrNull() } ?: listOf(folge)
		val chrono = folgen.sortedBy { it.premiereDate ?: it.dateCreated }
		val nr = chrono.indexOfFirst { it.id == folge.id }
		val danach = if (nr < 0) emptyList() else chrono.drop(nr + 1).filter { !folgeGehoert(it) }
		val s = folgeStand(folge.id)
		val ms = if (s == null) 0L else (s.ms - rueckspulen(s.zeit)).coerceAtLeast(0)
		spielen(listOf(folge) + danach, 0, ms)
	}

	/** Folgen eines Podcasts, neueste zuerst. */
	suspend fun podcastFolgen(album: UUID): List<BaseItemDto> = io {
		api.itemsApi.getItems(parentId = album, recursive = true, includeItemTypes = setOf(BaseItemKind.AUDIO),
			sortBy = listOf(ItemSortBy.PREMIERE_DATE, ItemSortBy.DATE_CREATED, ItemSortBy.SORT_NAME), sortOrder = listOf(SortOrder.DESCENDING),
			fields = setOf(ItemFields.DATE_CREATED, ItemFields.OVERVIEW), enableUserData = true).content.items
	}

	/** Ungehörte Folgen aller Podcasts, neueste zuerst. */
	suspend fun neueFolgen(bib: UUID, anzahl: Int = 30): List<BaseItemDto> = io {
		api.itemsApi.getItems(parentId = bib, recursive = true, includeItemTypes = setOf(BaseItemKind.AUDIO), filters = setOf(ItemFilter.IS_UNPLAYED),
			sortBy = listOf(ItemSortBy.PREMIERE_DATE, ItemSortBy.DATE_CREATED), sortOrder = listOf(SortOrder.DESCENDING),
			fields = setOf(ItemFields.DATE_CREATED), enableUserData = true, limit = anzahl).content.items
	}

	/** «Weiterhören»: angefangene Bücher (als Ordner) und Folgen, zuletzt gehörte zuerst; füllt die Fortschritte. */
	suspend fun weiterhoeren(anzahl: Int = 20): List<BaseItemDto> {
		val offen = angefangen().take(anzahl)
		if (offen.isEmpty()) return emptyList()
		val items = runCatching {
			io { api.itemsApi.getItems(ids = offen.map { it.first }, enableUserData = true, fields = setOf(ItemFields.DATE_CREATED, ItemFields.PRIMARY_IMAGE_ASPECT_RATIO)).content.items }
		}.getOrDefault(emptyList()).associateBy { it.id }
		return offen.mapNotNull { (id, s, istBuch) ->
			val item = items[id] ?: return@mapNotNull null
			if (istBuch) {
				val b = buch(id) ?: return@mapNotNull null
				val nr = s.datei?.let { b.index(it) }?.takeIf { it >= 0 } ?: 0
				val gesamt = b.gesamtMs.coerceAtLeast(1)
				val bis = b.bisMs(nr, s.ms)
				fortschritte[id] = HoerFortschritt(bis.toFloat() / gesamt, gesamt - bis, nr + 1, b.dateien.size)
			} else {
				val d = dauerMs(item).coerceAtLeast(1)
				fortschritte[id] = HoerFortschritt(s.ms.toFloat() / d, d - s.ms)
			}
			item
		}
	}

	/** Je länger die Pause, desto weiter zurück (wie Smart Rewind bei BookPlayer/Audiobookshelf). */
	fun rueckspulen(zeit: Long): Long {
		val pause = System.currentTimeMillis() - zeit
		return when {
			pause < 10_000L -> 0L
			pause < 5 * 60_000L -> 3_000L
			pause < 60 * 60_000L -> 10_000L
			else -> 30_000L
		}
	}

	// --- Bedienung (Player und Medientasten) ---

	fun position() = pm.state.positionInfo.active.inWholeMilliseconds
	fun dauer() = pm.state.positionInfo.duration.inWholeMilliseconds

	fun springen(sekunden: Int) {
		val d = dauer()
		val ziel = (position() + sekunden * 1000L).coerceAtLeast(0L).let { if (d > 0) it.coerceAtMost(d - 1_000L) else it }
		pm.state.seek(ziel.milliseconds)
	}

	fun spielenPause() {
		when (pm.state.playState.value) {
			PlayState.PLAYING -> pm.state.pause()
			else -> pm.state.unpause()
		}
	}

	/** «Zurück»: nach den ersten Sekunden an den Anfang, sonst zum vorigen Titel/Kapitel. */
	fun zurueck() {
		if (position() > 3_000L) { pm.state.seek(kotlin.time.Duration.ZERO); return }
		val i = pm.queue.entryIndex.value
		scope.launch {
			val ok = if (pm.state.playbackOrder.value == PlaybackOrder.DEFAULT && i > 0) pm.queue.setIndex(i - 1) != null else pm.queue.previous() != null
			if (!ok) pm.state.seek(kotlin.time.Duration.ZERO)
		}
	}

	fun vor() { scope.launch { pm.queue.next() } }

	/** Medientasten im Player: Spulen um die Sprungweite der Art, ⏮/⏭ wechseln Titel, Kapitel bzw. Folge. */
	fun taste(e: android.view.KeyEvent): Boolean {
		val (zurueckS, vorS) = sprung(art(laufend()))
		val aktion: (() -> Unit) = when (e.keyCode) {
			android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, android.view.KeyEvent.KEYCODE_MEDIA_PLAY, android.view.KeyEvent.KEYCODE_MEDIA_PAUSE -> ::spielenPause
			android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { { springen(vorS) } }
			android.view.KeyEvent.KEYCODE_MEDIA_REWIND -> { { springen(-zurueckS) } }
			android.view.KeyEvent.KEYCODE_MEDIA_NEXT -> ::vor
			android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS -> ::zurueck
			else -> return false
		}
		if (e.action == android.view.KeyEvent.ACTION_DOWN && e.repeatCount == 0) aktion()
		return true
	}

	/** Titel Nummer [nr] der gestarteten Liste spielen (Kapitel, Folge, «Als Nächstes»). */
	fun springeZu(nr: Int) { scope.launch { pm.queue.setIndex(nr) } }

	// --- Schlaf-Timer ---

	val schlaf = MutableStateFlow<SchlafTimer?>(null)

	/** Nächste Stufe: 15, 30, 45, 60 Minuten, «Ende des Kapitels/der Folge», aus. */
	fun schlafWeiter() {
		val jetzt = schlaf.value
		schlaf.value = when {
			jetzt == null -> SchlafTimer(System.currentTimeMillis() + 15 * 60_000L, null, 15)
			jetzt.endeVon != null -> null
			jetzt.minuten >= 60 -> laufend()?.let { SchlafTimer(null, it.id, 0) }
			else -> (jetzt.minuten + 15).let { SchlafTimer(System.currentTimeMillis() + it * 60_000L, null, it) }
		}
	}

	private fun schlafPruefen(item: BaseItemDto, pos: Long, dauer: Long) {
		val t = schlaf.value ?: return
		val faellig = (t.bis != null && System.currentTimeMillis() >= t.bis) ||
			(t.endeVon != null && (item.id != t.endeVon || (dauer > 0 && dauer - pos < 1_500L)))
		if (faellig) { pm.state.pause(); schlaf.value = null }
	}

	// --- Mitlesen, was läuft ---

	private var gestartet = false
	private var letztes: BaseItemDto? = null
	private var letztePos = 0L
	private var letzteDauer = 0L
	private var letztesMerken = 0L
	private var letztesHochladen = 0L
	private var lief = false
	private var gehoertMarkiert: UUID? = null
	private var endlosFuer: UUID? = null

	/** Einmal beim App-Start: merkt Hörbuch- und Podcast-Stellen, setzt das Tempo, füllt Musik endlos auf. */
	fun starten(context: Context) {
		if (gestartet) return
		gestartet = true
		app = context.applicationContext
		scope.launch { podcastsLaden() }
		scope.launch {
			while (true) {
				delay(1_000)
				runCatching { schritt() }.onFailure { Timber.w(it, "Hören") }
			}
		}
	}

	private suspend fun schritt() {
		val item = laufend()
		val spielt = pm.state.playState.value == PlayState.PLAYING
		val pos = position()
		val dauer = dauer()
		if (item?.id != letztes?.id) {
			letztes?.let { abschliessen(it, letztePos, letzteDauer) }
			letztes = item; gehoertMarkiert = null; letztePos = 0; letzteDauer = 0
			if (item != null) {
				// Von anderswo gestartet (z. B. Jellyfins eigene Seiten): unsere Liste gilt nicht mehr
				if (liste.none { it.id == item.id }) liste = listOf(item)
				podcastsLaden()
				val tempo = if (art(item) == HoerArt.MUSIK) 1f else tempo(tempoSchluessel(item))
				if (pm.state.speed.value != tempo) pm.state.setSpeed(tempo)
			}
		}
		if (item == null) {
			if (lief) hochladen()
			lief = false
			return
		}
		// Gemerkte Stelle anspringen, sobald die Datei geladen ist
		ausstehend?.let { (id, ms) ->
			if (id == item.id && dauer > 0) {
				pm.state.seek(ms.milliseconds); ausstehend = null
				// Erst wieder merken, wenn der Sprung sicher gewirkt hat
				letztesMerken = System.currentTimeMillis()
			} else if (System.currentTimeMillis() > ausstehendBis) ausstehend = null
			return
		}
		if (dauer > 0) { letztePos = pos; letzteDauer = dauer }
		schlafPruefen(item, pos, dauer)
		val art = art(item)
		if (art == HoerArt.MUSIK) {
			endlosPruefen(item, pos, dauer)
		} else {
			val jetzt = System.currentTimeMillis()
			if (spielt && pos > 0 && jetzt - letztesMerken > 5_000L) { merken(item, pos); letztesMerken = jetzt }
			if (!spielt && lief) { merken(item, pos); hochladen() }
			if (spielt && jetzt - letztesHochladen > 30_000L) { hochladen(); letztesHochladen = jetzt }
			// Podcast: kurz vor Schluss gilt die Folge als gehört (wie Pocket Casts)
			if (art == HoerArt.PODCAST && dauer > 0 && gehoertMarkiert != item.id && (pos > dauer * 95 / 100 || dauer - pos < 45_000L)) {
				gehoertMarkiert = item.id
				setzen("p:${item.id}", null)
				runCatching { io { api.playStateApi.markPlayedItem(itemId = item.id) } }
				version.value++
			}
		}
		lief = spielt
	}

	private fun merken(item: BaseItemDto, pos: Long) {
		val jetzt = System.currentTimeMillis()
		when (art(item)) {
			HoerArt.HOERBUCH -> buchId(item)?.let { b ->
				setzen("b:$b", "${item.id}|$pos|$jetzt")
				if (stand.containsKey("f:$b")) setzen("f:$b", null)
			}
			HoerArt.PODCAST -> if (gehoertMarkiert != item.id) setzen("p:${item.id}", "$pos|$jetzt")
			HoerArt.MUSIK -> Unit
		}
	}

	/** Eine Datei ist zu Ende oder wurde gewechselt. Letztes Kapitel zu Ende: Buch fertig. */
	private suspend fun abschliessen(item: BaseItemDto, pos: Long, dauer: Long) {
		// Beim Start mitten im Buch läuft kurz die erste Datei an: das ist kein Stand
		if (ausstehend != null) return
		val amEnde = dauer > 0 && dauer - pos < 5_000L
		when (art(item)) {
			HoerArt.HOERBUCH -> {
				val b = buchId(item)
				val buch = b?.let { buch(it) }
				if (amEnde && buch != null && buch.dateien.lastOrNull()?.id == item.id) {
					setzen("b:$b", null); setzen("f:$b", System.currentTimeMillis().toString())
				} else if (pos > 0) merken(item, pos)
			}
			HoerArt.PODCAST -> if (amEnde) setzen("p:${item.id}", null) else if (pos > 0) merken(item, pos)
			HoerArt.MUSIK -> Unit
		}
		hochladen()
		version.value++
	}

	/** Wie Plexamp: geht die Warteschlange zu Ende, kommen passende Titel dazu (Jellyfins «Instant Mix»). */
	private suspend fun endlosPruefen(item: BaseItemDto, pos: Long, dauer: Long) {
		if (!SelikuEinstellungen.endlosMusik(app)) return
		val amEnde = pm.state.playState.value == PlayState.STOPPED
		if ((!amEnde && (dauer <= 0 || dauer - pos > 20_000L)) || endlosFuer == item.id) return
		if (pm.state.repeatMode.value != RepeatMode.NONE) return
		if (pm.queue.peekNext() != null) return
		endlosFuer = item.id
		val schon = liste.map { it.id }.toSet()
		val mix = runCatching {
			io { api.instantMixApi.getInstantMixFromItem(itemId = item.id, limit = 50, enableUserData = true).content.items }
		}.getOrDefault(emptyList()).filter { it.id !in schon && it.type == BaseItemKind.AUDIO }.take(25)
		if (mix.isEmpty()) return
		val erster = pm.queue.estimatedSize
		liste = liste + mix
		pm.queue.addSupplier(RewriteMediaManager.BaseItemQueueSupplier(api, mix, true))
		// Schon zu Ende (z. B. ans Ende gespult, bevor der Mix da war): gleich weiterspielen. Pausiert bleibt pausiert.
		if (pm.state.playState.value == PlayState.STOPPED) { pm.queue.setIndex(erster); pm.state.play() }
	}
}
