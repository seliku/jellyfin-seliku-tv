package ch.seliku.handy.musik

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import ch.seliku.handy.Sitzung
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.extensions.displayPreferencesApi
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.api.client.extensions.playStateApi
import org.jellyfin.sdk.api.client.extensions.userViewsApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CollectionType
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemFilter
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder
import java.util.UUID

/**
 * Hören wie am TV (Podcasts wie Pocket Casts, Hörbücher wie Audible): eigene Stellen im Konto, weil Jellyfin Audio schon
 * beim Start abhakt und Bücher aus vielen Dateien bestehen. DisplayPreferences «seliku-hoeren»:
 * b:<Buch> = Datei|ms|zeit · f:<Buch> = fertig · p:<Folge> = ms|zeit · t:<Buch/Podcast> = Tempo.
 * Geschrieben werden nur geänderte Schlüssel (was ein anderes Gerät gemerkt hat, bleibt).
 */
object Hoeren {
	private const val ID = "seliku-hoeren"
	private val werte = HashMap<String, String>()
	private val geaendert = HashMap<String, String?>()
	private var geladenFuer: String? = null
	private val sperre = Mutex()
	var version by mutableIntStateOf(0); private set

	suspend fun laden(neu: Boolean = false) = withContext(Dispatchers.IO) {
		sperre.withLock {
			val wer = Sitzung.benutzerId.toString()
			if (!neu && geladenFuer == wer) return@withLock
			val p = runCatching { Sitzung.api!!.displayPreferencesApi.getDisplayPreferences(displayPreferencesId = ID, client = "seliku").content.customPrefs }.getOrNull() ?: return@withLock
			werte.clear(); p.forEach { (k, v) -> if (v != null) werte[k] = v }
			geladenFuer = wer; version++
		}
	}
	fun wert(k: String) = werte[k]
	fun setzen(k: String, v: String?) { if (v == null) werte.remove(k) else werte[k] = v; geaendert[k] = v; version++ }

	suspend fun hochladen() = withContext(Dispatchers.IO) {
		sperre.withLock {
			if (geaendert.isEmpty()) return@withLock
			val api = Sitzung.api ?: return@withLock
			runCatching {
				val alt = api.displayPreferencesApi.getDisplayPreferences(displayPreferencesId = ID, client = "seliku").content
				val neu = alt.customPrefs.toMutableMap()
				geaendert.forEach { (k, v) -> if (v == null) neu.remove(k) else neu[k] = v }
				api.displayPreferencesApi.updateDisplayPreferences(displayPreferencesId = ID, client = "seliku", data = alt.copy(customPrefs = neu))
				geaendert.clear()
			}
		}
	}

	/** Zurückspulen nach Pausenlänge (wie BookPlayer): < 10 s 0, < 5 min 3 s, < 1 h 10 s, sonst 30 s. */
	fun rueckspulen(seit: Long): Long { val p = System.currentTimeMillis() - seit; return when { p < 10_000 -> 0; p < 300_000 -> 3_000; p < 3_600_000 -> 10_000; else -> 30_000 } }

	private val felder = setOf(ItemFields.OVERVIEW, ItemFields.DATE_CREATED, ItemFields.PRIMARY_IMAGE_ASPECT_RATIO)

	/** Podcast-Bibliotheken (Musik mit «podcast» im Namen) und Hörbuch-Bibliotheken. */
	suspend fun bibliotheken(): Pair<List<BaseItemDto>, List<BaseItemDto>> = withContext(Dispatchers.IO) {
		val alle = Sitzung.api!!.userViewsApi.getUserViews().content.items
		alle.filter { it.collectionType == CollectionType.MUSIC && it.name.orEmpty().contains("podcast", true) } to alle.filter { it.collectionType == CollectionType.BOOKS }
	}

	/** Dateien eines Buchs (Ordner); ohne Dateien die Unterordner (CD 1, CD 2 …) der Reihe nach. */
	suspend fun buchDateien(buch: UUID): List<BaseItemDto> = withContext(Dispatchers.IO) {
		val api = Sitzung.api!!
		val inhalt = api.itemsApi.getItems(parentId = buch, sortBy = listOf(ItemSortBy.SORT_NAME), fields = felder, enableUserData = true).content.items
		val dateien = inhalt.filter { it.type == BaseItemKind.AUDIO_BOOK || it.type == BaseItemKind.AUDIO }
		// Unterordner nur, wenn es Teile eines Buchs sind (CD 1, Disc 2, Teil 3 …); sonst sind es eigene Bücher
		val teile = Regex("(?i)^(cd|disc|disk|teil|part|tape)\\s*\\d+")
		if (dateien.isNotEmpty()) dateien else inhalt.filter { it.isFolder == true && teile.containsMatchIn(it.name.orEmpty()) }.flatMap { o ->
			api.itemsApi.getItems(parentId = o.id, sortBy = listOf(ItemSortBy.SORT_NAME), fields = felder, enableUserData = true).content.items
				.filter { it.type == BaseItemKind.AUDIO_BOOK || it.type == BaseItemKind.AUDIO }
		}
	}

	suspend fun folgen(podcast: UUID, nurNeue: Boolean = false, anzahl: Int? = null): List<BaseItemDto> = withContext(Dispatchers.IO) {
		Sitzung.api!!.itemsApi.getItems(parentId = podcast, recursive = true, includeItemTypes = setOf(BaseItemKind.AUDIO),
			sortBy = listOf(ItemSortBy.PREMIERE_DATE, ItemSortBy.DATE_CREATED, ItemSortBy.SORT_NAME), sortOrder = listOf(SortOrder.DESCENDING),
			filters = if (nurNeue) setOf(ItemFilter.IS_UNPLAYED) else emptySet(), fields = felder, enableUserData = true, limit = anzahl).content.items
	}

	/** Podcast-Folge gehört = Jellyfin «played» und keine eigene Stelle mehr (Jellyfin hakt schon beim Start ab). */
	fun gehoert(f: BaseItemDto) = f.userData?.played == true && wert("p:${f.id}") == null

	/** «Weiterhören»: alle angefangenen Bücher und Folgen, zuletzt gehörte zuerst, höchstens 20. */
	suspend fun weiterhoeren(): List<BaseItemDto> = withContext(Dispatchers.IO) {
		laden()
		val eintraege = werte.entries.mapNotNull { (k, v) ->
			val zeit = v.split("|").lastOrNull()?.toLongOrNull() ?: return@mapNotNull null
			when { k.startsWith("b:") -> k.drop(2) to zeit; k.startsWith("p:") -> k.drop(2) to zeit; else -> null }
		}.sortedByDescending { it.second }.take(20)
		val ids = eintraege.mapNotNull { runCatching { UUID.fromString(it.first) }.getOrNull() }
		if (ids.isEmpty()) return@withContext emptyList()
		val items = Sitzung.api!!.itemsApi.getItems(ids = ids, fields = felder, enableUserData = true).content.items.associateBy { it.id }
		ids.mapNotNull { items[it] }
	}

	suspend fun alsGehoert(f: BaseItemDto, ja: Boolean) {
		setzen("p:${f.id}", null)
		withContext(Dispatchers.IO) { runCatching { if (ja) Sitzung.api!!.playStateApi.markPlayedItem(itemId = f.id) else Sitzung.api!!.playStateApi.markUnplayedItem(itemId = f.id) } }
		hochladen()
	}
}
