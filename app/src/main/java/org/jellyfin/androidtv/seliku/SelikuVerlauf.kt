package org.jellyfin.androidtv.seliku

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import org.jellyfin.androidtv.ui.base.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.util.apiclient.getUrl
import org.jellyfin.androidtv.util.apiclient.primaryImage
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.koin.compose.koinInject
import timber.log.Timber
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

/** Jellyfin liefert LastPlayedDate in UTC (ohne Zone); hier in Ortszeit. */
fun BaseItemDto.zuletztOrtszeit(): LocalDateTime? = userData?.lastPlayedDate
	?.atOffset(ZoneOffset.UTC)?.atZoneSameInstant(ZoneId.systemDefault())?.toLocalDateTime()

/** Sehzeit in Minuten; [geschaetzt]: aus Laufzeit und Fortschritt hochgerechnet, nicht vom Plugin «Playback Reporting». */
data class Sehzeit(val minuten: Int, val geschaetzt: Boolean)

/** Überblick oben auf «Mein Verlauf». */
data class VerlaufUeberblick(val woche: Sehzeit, val monat: Sehzeit, val serien: List<String>, val genres: List<String>)

/**
 * Echte Sehzeit vom Plugin «Playback Reporting» (Tabelle PlaybackActivity, PlayDuration in Sekunden).
 * Liefert null, wenn das Plugin fehlt, der Benutzer es nicht abfragen darf oder es erst nach [ab] Daten hat.
 */
private suspend fun pluginSehzeit(api: ApiClient, benutzer: UUID, ab: LocalDate): Int? = withContext(Dispatchers.IO) {
	runCatching {
		val id = benutzer.toString().replace("-", "").lowercase()
		fun abfrage(sql: String): org.json.JSONArray {
			val v = java.net.URL("${api.baseUrl?.trimEnd('/')}/user_usage_stats/submit_custom_query").openConnection() as java.net.HttpURLConnection
			v.requestMethod = "POST"; v.doOutput = true; v.connectTimeout = 5_000; v.readTimeout = 10_000
			v.setRequestProperty("Authorization", "MediaBrowser Token=\"${api.accessToken.orEmpty()}\"")
			v.setRequestProperty("Content-Type", "application/json")
			v.outputStream.use { it.write(org.json.JSONObject().put("CustomQueryString", sql).put("ReplaceUserId", false).toString().toByteArray()) }
			if (v.responseCode !in 200..299) error("Playback Reporting: ${v.responseCode}")
			return org.json.JSONObject(v.inputStream.bufferedReader().readText()).getJSONArray("results")
		}
		val wer = "replace(lower(UserId),'-','') = '$id'"
		// Erst ab dem ersten Eintrag ist die Zahl vollständig; davor schätzen
		val erster = abfrage("SELECT MIN(DateCreated) FROM PlaybackActivity WHERE $wer").optJSONArray(0)?.optString(0).orEmpty()
		if (erster.length < 10 || erster.take(10) > ab.toString()) return@runCatching null
		val sekunden = abfrage("SELECT SUM(PlayDuration) FROM PlaybackActivity WHERE $wer AND DateCreated >= '$ab'")
			.optJSONArray(0)?.optString(0)?.toDoubleOrNull() ?: 0.0
		(sekunden / 60).toInt()
	}.onFailure { Timber.d(it, "Playback Reporting") }.getOrNull()
}

/** Geschätzte Minuten seit [ab]: Gesehenes mit voller Laufzeit, Angefangenes bis zur Position. */
private fun geschaetzt(verlauf: List<BaseItemDto>, ab: LocalDate): Int = verlauf
	.filter { (it.zuletztOrtszeit()?.toLocalDate() ?: LocalDate.MIN) >= ab }
	.sumOf { i -> ((if (i.userData?.played == true) i.runTimeTicks else i.userData?.playbackPositionTicks) ?: 0L) / 600_000_000L }
	.toInt()

suspend fun verlaufUeberblick(api: ApiClient, daten: SelikuDaten, verlauf: List<BaseItemDto>): VerlaufUeberblick {
	val heute = LocalDate.now()
	val wochenAnfang = heute.with(java.time.DayOfWeek.MONDAY)
	val monatsAnfang = heute.withDayOfMonth(1)
	val ich = runCatching { daten.benutzer().id }.getOrNull()
	fun zeit(ab: LocalDate, echt: Int?) = if (echt != null) Sehzeit(echt, false) else Sehzeit(geschaetzt(verlauf, ab), true)
	val woche = zeit(wochenAnfang, ich?.let { pluginSehzeit(api, it, wochenAnfang) })
	val monat = zeit(monatsAnfang, ich?.let { pluginSehzeit(api, it, monatsAnfang) })
	// Meistgeschaute Serien der letzten 30 Tage (nach Anzahl Folgen)
	val grenze = heute.minusDays(30)
	val serien = verlauf.filter { it.type == BaseItemKind.EPISODE && (it.zuletztOrtszeit()?.toLocalDate() ?: LocalDate.MIN) >= grenze }
		.groupingBy { it.seriesName.orEmpty() }.eachCount().filterKeys { it.isNotBlank() }
		.entries.sortedByDescending { it.value }.take(3).map { it.key }
	val genres = runCatching { daten.lieblingsGenres().take(4) }.getOrDefault(emptyList())
	return VerlaufUeberblick(woche, monat, serien, genres)
}

/** Zuletzt im Banner gezeigte Titel (nur auf diesem Gerät), damit sie ein paar Tage nicht gleich wiederkommen. */
object BannerGedaechtnis {
	private const val DATEI = "seliku_banner"
	private const val TAGE = 3L

	fun gezeigt(c: Context): Set<String> = runCatching {
		val grenze = System.currentTimeMillis() - TAGE * 24 * 3600_000L
		c.getSharedPreferences(DATEI, Context.MODE_PRIVATE).getString("gezeigt", "").orEmpty().split(",")
			.mapNotNull { e -> e.split(":").takeIf { it.size == 2 }?.let { (id, t) -> id.takeIf { (t.toLongOrNull() ?: 0) > grenze } } }.toSet()
	}.getOrDefault(emptySet())

	fun merken(c: Context, items: List<BaseItemDto>) = merkenSchluessel(c, items.map { it.id.toString() })

	fun merkenSchluessel(c: Context, schluessel: List<String>) {
		runCatching {
			val p = c.getSharedPreferences(DATEI, Context.MODE_PRIVATE)
			val grenze = System.currentTimeMillis() - TAGE * 24 * 3600_000L
			val alt = p.getString("gezeigt", "").orEmpty().split(",").filter { e -> (e.substringAfter(":", "").toLongOrNull() ?: 0) > grenze }
			val jetzt = System.currentTimeMillis()
			val neu = (alt.filter { e -> schluessel.none { e.startsWith("$it:") } } + schluessel.map { "$it:$jetzt" }).takeLast(400)
			p.edit().putString("gezeigt", neu.joinToString(",")).apply()
		}.onFailure { Timber.d(it, "Banner merken") }
	}
}

/** Banner laden: kürzlich Gezeigtes meiden und das Gezeigte merken. */
suspend fun bannerLaden(context: Context, daten: SelikuDaten, bib: BaseItemDto? = null): List<BaseItemDto> {
	val liste = daten.bannerEmpfehlungen(bib, ausser = BannerGedaechtnis.gezeigt(context))
	BannerGedaechtnis.merken(context, liste)
	return liste
}

/** Wie oft der Banner neu gezogen wird, solange die Seite offen ist (der Fernseher lässt die App tagelang laufen). */
const val BANNER_NEU_MS = 3 * 60 * 60 * 1000L

/**
 * «Mein Seliku» wie «Mein Netflix»: oben das Profil mit Sehzeit und Lieblingsgenres, darunter Reihen wie auf der
 * Startseite – Weiterschauen, Meine Liste, Gefällt mir, Zuletzt gesehen und (falls vorhanden) Nicht mehr empfehlen.
 * Erreichbar über das Profilbild oben in der Seitenleiste; dort auch «Profil wechseln».
 */
@Composable
fun VerlaufSeite(vm: SelikuViewModel, oeffnen: (BaseItemDto) -> Unit) {
	val daten = koinInject<SelikuDaten>()
	val api = koinInject<ApiClient>()
	val context = LocalContext.current
	val sessionRepository = koinInject<org.jellyfin.androidtv.auth.repository.SessionRepository>()
	val mediaManager = koinInject<org.jellyfin.androidtv.ui.playback.MediaManager>()
	val k = Seite.Verlauf.schluessel
	@Suppress("UNCHECKED_CAST")
	fun gemerkt(n: String) = vm.wahl["$k|$n"] as? List<BaseItemDto> ?: emptyList()
	var weiter by remember { mutableStateOf(gemerkt("weiter")) }
	var liste by remember { mutableStateOf(gemerkt("liste")) }
	var gefallen by remember { mutableStateOf(gemerkt("gefallen")) }
	var zuletzt by remember { mutableStateOf(gemerkt("zuletzt")) }
	var nicht by remember { mutableStateOf(gemerkt("nicht")) }
	var ueberblick by remember { mutableStateOf(vm.wahl["$k|ueberblick"] as? VerlaufUeberblick) }
	var benutzer by remember { mutableStateOf<org.jellyfin.sdk.model.api.UserDto?>(null) }
	var geladen by remember { mutableStateOf(vm.wahl["$k|zuletzt"] != null) }
	// Beim ersten Öffnen ist noch nichts zum Anwählen da: den Fokus holen, sobald die erste Reihe steht
	val seitenFokus = remember { androidx.compose.ui.focus.FocusRequester() }
	// Ob schon eine Kachel den Fokus hatte (die Liste selbst meldet «hat Fokus», auch wenn keine Kachel ihn hat)
	var kachelFokus by remember { mutableStateOf(false) }
	// Die erste Kachel der ersten nicht leeren Reihe trägt den Anfangsfokus. Sobald sie da ist, den Fokus holen
	// (das Laden aller Reihen dauert auf dem Chromecast mehrere Sekunden – nicht darauf warten)
	val ersteReihe = listOf("weiter" to weiter, "liste" to liste, "gefallen" to gefallen, "zuletzt" to zuletzt, "nicht" to nicht).firstOrNull { it.second.isNotEmpty() }?.first
	LaunchedEffect(ersteReihe) {
		// Kommt man zurück (gemerkte Kachel), stellt [Waehlbar] die Kachel selbst wieder her
		if (ersteReihe == null || vm.fokus[k] != null) return@LaunchedEffect
		val ab = System.currentTimeMillis()
		repeat(20) {
			if (kachelFokus || System.currentTimeMillis() - ab > 3_000) return@LaunchedEffect
			kotlinx.coroutines.delay(150)
			runCatching { seitenFokus.requestFocus() }
		}
	}
	@Composable
	fun reihe(name: String, titel: String, items: List<BaseItemDto>) = Reihe(titel, items, vm, Seite.Verlauf, onClick = oeffnen,
		kachel = { item, klick, merken, fokus ->
			Box(if (name == ersteReihe && item.id == items.first().id) Modifier.focusRequester(seitenFokus) else Modifier) {
				Kachel(item, klick, merken = merken, onFokus = { kachelFokus = true; fokus() }, onMenue = { vm.menue = item })
			}
		})

	LaunchedEffect(vm.datenStand) {
		benutzer = runCatching { daten.benutzer() }.getOrNull()
		suspend fun laden(n: String, holen: suspend () -> List<BaseItemDto>): List<BaseItemDto>? =
			runCatching { holen() }.onFailure { Timber.w(it, "Mein Seliku: $n") }.getOrNull()?.also { vm.wahl["$k|$n"] = it }
		laden("weiter") { daten.weiterschauen() }?.let { weiter = it }
		laden("liste") { daten.favoriten() }?.let { liste = it }
		laden("gefallen") { daten.gefallen() }?.let { gefallen = it }
		val verlauf = runCatching { daten.verlauf() }.getOrDefault(emptyList())
		// Zuletzt gesehen: je Serie bzw. Film einmal, Gesehenes (Angefangenes steht schon unter «Weiterschauen»)
		zuletzt = verlauf.filter { it.userData?.played == true }.distinctBy { it.geschmackId() }.take(30).also { vm.wahl["$k|zuletzt"] = it }
		laden("nicht") { daten.nichtGemocht() }?.let { nicht = it }
		geladen = true
		runCatching { verlaufUeberblick(api, daten, verlauf) }.getOrNull()?.let { ueberblick = it; vm.wahl["$k|ueberblick"] = it }
	}

	LazyColumn(state = vm.liste(Seite.Verlauf), contentPadding = PaddingValues(bottom = 48.dp), modifier = Modifier.fillMaxSize().background(Farben.Hintergrund)) {
		item(key = "kopf") {
			Row(Modifier.fillMaxWidth().padding(start = 48.dp, end = 48.dp, top = 32.dp, bottom = 26.dp), verticalAlignment = Alignment.CenterVertically) {
				Avatar(benutzer?.primaryImage?.getUrl(api), benutzer?.name, Modifier.size(88.dp))
				Spacer(Modifier.width(22.dp))
				Column(Modifier.weight(1f)) {
					Text(benutzer?.name.orEmpty(), style = Schrift.Titel)
					ueberblick?.let { u ->
						Text("${stringResource(R.string.seliku_history_week)} ${dauer(u.woche)}   ·   ${stringResource(R.string.seliku_history_month)} ${dauer(u.monat)}",
							style = Schrift.Leise, modifier = Modifier.padding(top = 4.dp))
						if (u.genres.isNotEmpty()) Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
							u.genres.forEach { g ->
								Text(g, style = Schrift.Klein, modifier = Modifier.clip(RoundedCornerShape(50)).background(Farben.Flaeche).padding(horizontal = 12.dp, vertical = 4.dp))
							}
						}
					}
				}
			}
		}
		item(key = "weiter") { reihe("weiter", stringResource(R.string.seliku_continue_watching), weiter) }
		item(key = "liste") { reihe("liste", stringResource(R.string.seliku_my_list), liste) }
		item(key = "gefallen") { reihe("gefallen", stringResource(R.string.seliku_liked), gefallen) }
		item(key = "zuletzt") { reihe("zuletzt", stringResource(R.string.seliku_recently_watched), zuletzt) }
		item(key = "nicht") { reihe("nicht", stringResource(R.string.seliku_not_recommended), nicht) }
		if (geladen && weiter.isEmpty() && liste.isEmpty() && gefallen.isEmpty() && zuletzt.isEmpty()) item(key = "leer") {
			Text(stringResource(R.string.seliku_history_empty), style = Schrift.Leise, modifier = Modifier.padding(start = 48.dp))
		}
		// Ganz unten, damit der Fokus beim Öffnen nicht darauf landet (ein OK zu viel meldete sonst ab)
		item(key = "profil") {
			Box(Modifier.padding(start = 48.dp, top = 12.dp)) {
				Knopf(stringResource(R.string.seliku_switch_profile), symbol = "⇄", onClick = {
					mediaManager.clearAudioQueue()
					sessionRepository.destroyCurrentSession()
					context.startActivity(org.jellyfin.androidtv.ui.navigation.ActivityDestinations.startup(context))
				})
			}
		}
	}
}

/** Für «Gefällt mir»: bei Folgen gilt die Serie, sonst der Titel selbst. */
fun BaseItemDto.geschmackId(): UUID = if (type == BaseItemKind.EPISODE) seriesId ?: id else id

@Composable
private fun dauer(s: Sehzeit): String {
	val text = if (s.minuten >= 60) stringResource(R.string.seliku_runtime_hours, s.minuten / 60, s.minuten % 60)
	else stringResource(R.string.seliku_runtime_minutes, s.minuten)
	return if (s.geschaetzt) stringResource(R.string.seliku_history_approx, text) else text
}

/** Genre-Nummern von Seerr/TMDb je Name (deutsch), einmal geholt. */
private val seerrGenres = java.util.concurrent.ConcurrentHashMap<String, Int>()

/**
 * Banner: 1–2 Titel, die es auf dem Server (noch) nicht gibt, die aber gefallen könnten – TMDb-Empfehlungen zu
 * Lieblingstiteln und Aktuelles in den Lieblingsgenres, über Seerr. Ohne Seerr oder abgeschaltet: keine.
 * [typ] «movie»/«tv» schränkt ein (Bibliotheksseiten).
 */
suspend fun seerrBanner(context: Context, daten: SelikuDaten, seerr: SeerrDaten, typ: String? = null): List<SeerrTitel> {
	if (!SelikuEinstellungen.bannerSeerr(context)) return emptyList()
	if (!SeerrVerbindung.vermittelt.value && !SeerrVerbindung.verbunden(context)) return emptyList()
	return runCatching {
		val nicht = daten.magNicht()
		val gezeigt = BannerGedaechtnis.gezeigt(context)
		// Empfehlungen zu ein paar zufälligen Lieblingstiteln (Favoriten, 4–5 Sterne)
		val gemocht = daten.gemochteTitel().mapNotNull { i ->
			val tmdb = i.providerIds?.entries?.firstOrNull { it.key.equals("Tmdb", true) }?.value?.toIntOrNull() ?: return@mapNotNull null
			val t = if (i.type == BaseItemKind.SERIES) "tv" else "movie"
			if (typ != null && t != typ) null else t to tmdb
		}.shuffled().take(4)
		val empfohlen = gemocht.flatMap { (t, tmdb) -> runCatching { seerr.mitGenres("/$t/$tmdb/recommendations?page=1&language=de", t) }.getOrDefault(emptyList()) }
		// Aktuelles nur in den Lieblingsgenres
		if (seerrGenres.isEmpty()) listOf("movie", "tv").forEach { t -> runCatching { seerr.genres(t).forEach { seerrGenres[it.name.lowercase()] = it.id } } }
		val lieblinge = daten.lieblingsGenres().take(6).mapNotNull { seerrGenres[it.lowercase()] }.toSet()
		val trend = if (lieblinge.isEmpty()) emptyList()
		else runCatching { seerr.mitGenres("/discover/trending?page=1&language=de", null) }.getOrDefault(emptyList()).filter { (_, g) -> g.any { it in lieblinge } }
		val passend = (empfohlen.shuffled() + trend.shuffled()).map { it.first }
			// Nur, was nicht schon da ist (Status 4/5 = auf dem Server), mit Bild, nicht abgelehnt
			.filter { it.status < 4 && it.hintergrund != null && (typ == null || it.typ == typ) && "seerr-${it.schluessel}" !in nicht }
			// Ohne deutsche Beschreibung (TMDb fällt dann auf Englisch zurück) lieber nicht in den Banner
			.filter { deutscheBeschreibung(it.text) }
			.distinctBy { it.schluessel }
		val frisch = passend.filter { "seerr-${it.schluessel}" !in gezeigt }.ifEmpty { passend }
		// Mal einer, mal zwei
		val wahl = frisch.take(if (Math.random() < 0.5) 1 else 2)
		BannerGedaechtnis.merkenSchluessel(context, wahl.map { "seerr-${it.schluessel}" })
		wahl
	}.onFailure { Timber.d(it, "Seerr-Banner") }.getOrDefault(emptyList())
}

/** Grobe Prüfung, ob eine Beschreibung deutsch ist: mehr typisch deutsche als typisch englische Füllwörter. */
private fun deutscheBeschreibung(text: String?): Boolean {
	val w = text.orEmpty().lowercase().split(Regex("[^a-zäöüß]+"))
	if (w.size < 5) return false
	val de = setOf("der", "die", "das", "und", "ist", "ein", "eine", "nicht", "mit", "sich", "auf", "zu", "den", "von")
	val en = setOf("the", "and", "is", "of", "to", "a", "with", "his", "her", "their", "in", "who")
	return w.count { it in de } > w.count { it in en }
}
