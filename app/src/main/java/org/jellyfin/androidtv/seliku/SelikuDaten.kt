package org.jellyfin.androidtv.seliku

import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.artistsApi
import org.jellyfin.sdk.api.client.extensions.genresApi
import org.jellyfin.sdk.api.client.extensions.displayPreferencesApi
import org.jellyfin.sdk.api.client.extensions.instantMixApi
import org.jellyfin.sdk.api.client.extensions.playlistsApi
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.api.client.extensions.liveTvApi
import org.jellyfin.sdk.api.client.extensions.playStateApi
import org.jellyfin.sdk.api.client.extensions.tvShowsApi
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.api.client.extensions.userViewsApi
import org.jellyfin.sdk.api.client.extensions.userApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemDtoQueryResult
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemFilter
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.MediaType
import org.jellyfin.sdk.model.api.SortOrder
import java.util.UUID
import org.jellyfin.androidtv.R

/** Farben der Seliku-Oberfläche: eigene Gestaltung, warm – dunkles Braunschwarz, Akzent Bernstein bis Koralle. */
object Farben {
	val Hintergrund = Color(0xFF14100E)
	val HintergrundOben = Color(0xFF2A1C15)
	val Flaeche = Color(0xFF221A16)
	val FlaecheHell = Color(0xFF342821)
	val Akzent = Color(0xFFF08A4B)
	val AkzentDunkel = Color(0xFFA9552C)
	val Text = Color(0xFFF7F2EE)
	val TextLeise = Color(0xFFB8AAA0)
	val Schatten = Color(0xCC14100E)
}

/** Sortierungen der Bibliotheksansicht. */
enum class Sortierung(@androidx.annotation.StringRes val titel: Int, val nach: List<ItemSortBy>, val reihenfolge: SortOrder) {
	NEU(R.string.seliku_sort_recently_added, listOf(ItemSortBy.DATE_CREATED, ItemSortBy.SORT_NAME), SortOrder.DESCENDING),
	NAME(R.string.seliku_sort_name, listOf(ItemSortBy.SORT_NAME), SortOrder.ASCENDING),
	JAHR(R.string.seliku_sort_year, listOf(ItemSortBy.PRODUCTION_YEAR, ItemSortBy.SORT_NAME), SortOrder.DESCENDING),
	BEWERTUNG(R.string.seliku_sort_rating, listOf(ItemSortBy.COMMUNITY_RATING, ItemSortBy.SORT_NAME), SortOrder.DESCENDING),
	ZULETZT(R.string.seliku_sort_last_watched, listOf(ItemSortBy.DATE_PLAYED, ItemSortBy.SORT_NAME), SortOrder.DESCENDING),
}

/** Alle Serverabfragen der Seliku-Oberfläche an einer Stelle. */
class SelikuDaten(private val api: ApiClient) {
	companion object {
		/** Haupt-Interpret -> alle zusammengefassten Interpreten (inkl. sich selbst). */
		val verwandte = java.util.concurrent.ConcurrentHashMap<UUID, List<UUID>>()

		fun basisName(name: String): String =
			name.split(Regex("\\s+(feat\\.?|ft\\.?|featuring|&|and|with|x|und|vs\\.?)\\s+", RegexOption.IGNORE_CASE)).first().trim()
	}

	private val felder = setOf(
		ItemFields.OVERVIEW,
		ItemFields.PRIMARY_IMAGE_ASPECT_RATIO,
		ItemFields.GENRES,
		ItemFields.CHILD_COUNT,
		ItemFields.DATE_CREATED,
		ItemFields.MEDIA_SOURCE_COUNT,
		ItemFields.TAGLINES,
		ItemFields.PROVIDER_IDS,
	)

	private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

	/** Sichtbare Bibliotheken (ohne die in den Benutzereinstellungen ausgeblendeten). */
	suspend fun bibliotheken(): List<BaseItemDto> {
		val aus = versteckteBibliotheken()
		return alleBibliotheken().filter { it.id !in aus }
	}

	suspend fun alleBibliotheken(): List<BaseItemDto> = io { api.userViewsApi.getUserViews().content.items }

	suspend fun versteckteBibliotheken(): Set<UUID> = io {
		api.userApi.getCurrentUser().content.configuration?.myMediaExcludes?.toSet().orEmpty()
	}

	/** Ausblenden gilt für Leiste/«Meine Medien» und für die «Neu in …»-Reihen. */
	suspend fun versteckteSetzen(ids: Set<UUID>) = io {
		val ich = api.userApi.getCurrentUser().content
		val cfg = ich.configuration ?: return@io
		api.userApi.updateUserConfiguration(ich.id, cfg.copy(myMediaExcludes = ids.toList(), latestItemsExcludes = ids.toList()))
	}

	suspend fun benutzer() = io { api.userApi.getCurrentUser().content }

	/** Inhalt eines Ordners: Unterordner zuerst, dann Dateien nach Name. */
	suspend fun ordnerInhalt(id: UUID): List<BaseItemDto> = io {
		api.itemsApi.getItems(
			parentId = id,
			sortBy = listOf(ItemSortBy.IS_FOLDER, ItemSortBy.SORT_NAME),
			sortOrder = listOf(SortOrder.DESCENDING, SortOrder.ASCENDING),
			fields = felder,
			enableUserData = true,
		).content.items
	}

	/** Startseiten-Sammlungen (Tag «seliku-startseite», z. B. «bald gelöscht» aus Maintainerr) mit Löschdatum je Titel. */
	suspend fun startseitenSammlungen(): List<Triple<String, List<BaseItemDto>, Map<UUID, String>>> = io {
		api.itemsApi.getItems(
			includeItemTypes = setOf(BaseItemKind.BOX_SET),
			recursive = true,
			tags = setOf("seliku-startseite"),
			fields = setOf(ItemFields.OVERVIEW),
		).content.items.map { s ->
			val daten = s.overview.orEmpty().lines().mapNotNull { z ->
				val t = z.trim().split(" ")
				runCatching { UUID.fromString(t[0]) to t[1] }.getOrNull()
			}.toMap()
			val inhalt = api.itemsApi.getItems(parentId = s.id, fields = felder, enableUserData = true).content.items
			Triple(s.name.orEmpty(), inhalt, daten)
		}.filter { it.second.isNotEmpty() }
	}

	// «Aus Weiterschauen entfernen» (nur diese persönliche Liste – andere Reihen bleiben dynamisch, S 04.11.):
	// Jellyfin kann «Als Nächstes»-Folgen nicht ausblenden. Deshalb eine eigene Liste
	// (Serie bzw. Film), gespeichert in den Anzeige-Einstellungen des Kontos – gilt auf allen Geräten.
	// Spielt man später wieder etwas davon ab, verschwindet der Eintrag von selbst (Abspielen → wiederAufnehmen).
	private val ausblendId = "seliku-weiterschauen"
	private val ausblendClient = "seliku"

	private suspend fun ausgeblendet(): Set<String> = runCatching {
		api.displayPreferencesApi.getDisplayPreferences(displayPreferencesId = ausblendId, client = ausblendClient).content
			.customPrefs["ausgeblendet"].orEmpty().split(",").filter { it.isNotBlank() }.toSet()
	}.getOrDefault(emptySet())

	private suspend fun ausgeblendetSetzen(ids: Set<String>) {
		val alt = api.displayPreferencesApi.getDisplayPreferences(displayPreferencesId = ausblendId, client = ausblendClient).content
		api.displayPreferencesApi.updateDisplayPreferences(displayPreferencesId = ausblendId, client = ausblendClient,
			data = alt.copy(customPrefs = alt.customPrefs + ("ausgeblendet" to ids.joinToString(","))))
	}

	private fun BaseItemDto.ausblendSchluessel() = (seriesId ?: id).toString()

	private suspend fun ohneAusgeblendete(liste: List<BaseItemDto>): List<BaseItemDto> {
		val weg = ausgeblendet(); return if (weg.isEmpty()) liste else liste.filter { it.ausblendSchluessel() !in weg }
	}

	/** Aus «Weiterschauen» entfernen: ganze Serie bzw. den Film ausblenden und angefangenen Fortschritt zurücksetzen. */
	suspend fun ausWeiterschauen(item: BaseItemDto) = io {
		ausgeblendetSetzen(ausgeblendet() + item.ausblendSchluessel())
		if ((item.userData?.playbackPositionTicks ?: 0L) > 0L)
			api.itemsApi.updateItemUserData(itemId = item.id, data = org.jellyfin.sdk.model.api.UpdateUserItemDataDto(playbackPositionTicks = 0))
	}

	/** Wieder aufnehmen, sobald man etwas aus der Serie bzw. den Film abspielt. */
	suspend fun wiederAufnehmen(item: BaseItemDto) = io {
		val weg = ausgeblendet(); val k = item.ausblendSchluessel()
		if (k in weg) ausgeblendetSetzen(weg - k)
	}

	suspend fun item(id: UUID): BaseItemDto = io { api.userLibraryApi.getItem(itemId = id).content }

	/**
	 * «Weiterschauen»: je Serie nur ein Eintrag (die zuletzt angefangene Folge bzw. die nächste Folge), Filme einzeln.
	 * Reihenfolge: zuletzt geschaut zuerst, dann die nächsten Folgen begonnener Serien (Jellyfin sortiert sie nach
	 * zuletzt geschaut), am Schluss angefangene Titel ohne Datum (aus Plex übernommen, also alt).
	 */
	suspend fun weiterschauen(): List<BaseItemDto> = io {
		val angefangen = api.itemsApi.getResumeItems(
			limit = 60,
			mediaTypes = setOf(MediaType.VIDEO),
			fields = felder,
			enableUserData = true,
		).content.items.distinctBy { it.seriesId ?: it.id }
		val naechste = api.tvShowsApi.getNextUp(
			limit = 60,
			fields = felder,
			enableUserData = true,
			enableResumable = false,
		).content.items
		val serien = angefangen.mapNotNull { it.seriesId }.toSet()
		// Serien der letzten 30 Tage, für die Jellyfin keine nächste Folge kennt (siehe folgeNachZuletztGeschauter)
		val bekannt = serien + naechste.mapNotNull { it.seriesId }
		val grenze = java.time.LocalDateTime.now().minusDays(30)
		val ohneNaechste = api.itemsApi.getItems(
			recursive = true,
			includeItemTypes = setOf(BaseItemKind.EPISODE),
			filters = setOf(ItemFilter.IS_PLAYED),
			sortBy = listOf(ItemSortBy.DATE_PLAYED),
			sortOrder = listOf(SortOrder.DESCENDING),
			enableUserData = true,
			limit = 40,
		).content.items
			.filter { (it.userData?.lastPlayedDate ?: grenze) > grenze }
			.mapNotNull { it.seriesId }.distinct().filter { it !in bekannt }.take(8)
			.mapNotNull { runCatching { folgeNachZuletztGeschauter(it) }.getOrNull() }
		val (mitDatum, ohneDatum) = angefangen.partition { it.userData?.lastPlayedDate != null }
		ohneAusgeblendete((mitDatum + ohneNaechste + naechste.filter { it.seriesId !in serien } + ohneDatum).distinctBy { it.seriesId ?: it.id }).take(40)
	}

	/** «Wahre Geschichten»: Filme und Serien mit den TMDb-Schlagwörtern für wahre Begebenheiten und Biografien. */
	suspend fun wahreGeschichten(): List<BaseItemDto> = io {
		api.itemsApi.getItems(
			recursive = true,
			includeItemTypes = setOf(BaseItemKind.MOVIE, BaseItemKind.SERIES),
			tags = listOf("based on true story", "inspired by true events", "based on memoir or autobiography", "biography", "biographical drama", "biographical comedy", "true crime"),
			sortBy = listOf(ItemSortBy.RANDOM),
			fields = felder,
			enableUserData = true,
			limit = 40,
		).content.items
	}

	/** Eigene Wertung (1–5 Sterne, bei Jellyfin 2–10 je Benutzer gespeichert); 0 = keine. */
	suspend fun wertung(id: UUID, sterne: Int) = io {
		api.itemsApi.updateItemUserData(itemId = id, data = org.jellyfin.sdk.model.api.UpdateUserItemDataDto(rating = sterne * 2.0))
	}

	suspend fun neuIn(bibliothek: BaseItemDto): List<BaseItemDto> = io {
		api.userLibraryApi.getLatestMedia(
			parentId = bibliothek.id,
			limit = 24,
			fields = felder,
			enableUserData = true,
			groupItems = true,
		).content
	}

	suspend fun inhalt(
		bibliothek: BaseItemDto,
		typen: Set<BaseItemKind>,
		sortierung: Sortierung,
		nurUngesehen: Boolean,
		start: Int,
		anzahl: Int,
		genre: String? = null,
		nurFavoriten: Boolean = false,
	): BaseItemDtoQueryResult = io {
		api.itemsApi.getItems(
			parentId = bibliothek.id,
			includeItemTypes = typen,
			recursive = true,
			sortBy = sortierung.nach,
			sortOrder = listOf(sortierung.reihenfolge),
			filters = setOfNotNull(ItemFilter.IS_UNPLAYED.takeIf { nurUngesehen }, ItemFilter.IS_FAVORITE.takeIf { nurFavoriten }),
			genres = setOfNotNull(genre),
			fields = felder,
			enableUserData = true,
			startIndex = start,
			limit = anzahl,
		).content
	}

	/** Genres einer Bibliothek, nach Name. */
	suspend fun genres(bibliothek: BaseItemDto, typen: Set<BaseItemKind>): List<String> = io {
		api.genresApi.getGenres(parentId = bibliothek.id, includeItemTypes = typen, sortBy = listOf(ItemSortBy.SORT_NAME)).content.items.mapNotNull { it.name }
	}

	// --- Startseiten der Bibliotheken ---

	suspend fun weiterschauenIn(bib: BaseItemDto): List<BaseItemDto> = io {
		ohneAusgeblendete(api.itemsApi.getResumeItems(parentId = bib.id, limit = 24, mediaTypes = setOf(MediaType.VIDEO), fields = felder, enableUserData = true).content.items)
	}

	suspend fun alsNaechstesIn(bib: BaseItemDto): List<BaseItemDto> = io {
		ohneAusgeblendete(api.tvShowsApi.getNextUp(parentId = bib.id, limit = 24, fields = felder, enableUserData = true, enableResumable = false).content.items)
	}

	private suspend fun liste(
		bib: BaseItemDto, typen: Set<BaseItemKind>, sortBy: List<ItemSortBy>, absteigend: Boolean,
		filter: Set<ItemFilter> = emptySet(), genre: String? = null, anzahl: Int = 24,
	) = api.itemsApi.getItems(
		parentId = bib.id, includeItemTypes = typen, recursive = true, sortBy = sortBy,
		sortOrder = listOf(if (absteigend) SortOrder.DESCENDING else SortOrder.ASCENDING),
		filters = filter, genres = setOfNotNull(genre), fields = felder, enableUserData = true, limit = anzahl,
	).content.items

	suspend fun topUngesehen(bib: BaseItemDto, typen: Set<BaseItemKind>) = io {
		liste(bib, typen, listOf(ItemSortBy.COMMUNITY_RATING), true, setOf(ItemFilter.IS_UNPLAYED))
	}

	suspend fun wiederentdecken(bib: BaseItemDto, typen: Set<BaseItemKind>) = io {
		liste(bib, typen, listOf(ItemSortBy.RANDOM), true)
	}

	suspend fun genreReihe(bib: BaseItemDto, typen: Set<BaseItemKind>, genre: String) = io {
		liste(bib, typen, listOf(ItemSortBy.RANDOM), true, genre = genre)
	}

	/** Zuletzt gesehen; bei Serien über die Folgen, je Serie einmal. */
	suspend fun zuletztGesehen(bib: BaseItemDto, serien: Boolean) = io {
		if (serien) liste(bib, setOf(BaseItemKind.EPISODE), listOf(ItemSortBy.DATE_PLAYED), true, setOf(ItemFilter.IS_PLAYED), anzahl = 60)
			.distinctBy { it.seriesId }.take(24)
		else liste(bib, setOf(BaseItemKind.MOVIE), listOf(ItemSortBy.DATE_PLAYED), true, setOf(ItemFilter.IS_PLAYED))
	}

	/** «Weil du X geschaut hast»: Ähnliches zum zuletzt Gesehenen der Bibliothek. Liefert den Namen von X (die Überschrift setzt der Aufrufer). */
	suspend fun weilDuGeschaut(bib: BaseItemDto, serien: Boolean): Pair<String, List<BaseItemDto>>? = io {
		val zuletzt = zuletztGesehen(bib, serien).firstOrNull() ?: return@io null
		val basis = if (serien) zuletzt.seriesId ?: return@io null else zuletzt.id
		val name = if (serien) zuletzt.seriesName.orEmpty() else zuletzt.name.orEmpty()
		val treffer = api.libraryApi.getSimilarItems(itemId = basis, limit = 20, fields = felder).content.items
		if (treffer.isEmpty()) null else name to treffer
	}

	// --- Empfehlungen für den grossen Banner ---

	/** Lieblings-Genres des Benutzers aus dem zuletzt Gesehenen (Filme und Folgen), häufigste zuerst. */
	suspend fun lieblingsGenres(): List<String> = io {
		val gesehen = api.itemsApi.getItems(
			includeItemTypes = setOf(BaseItemKind.MOVIE, BaseItemKind.EPISODE), recursive = true,
			filters = setOf(ItemFilter.IS_PLAYED), sortBy = listOf(ItemSortBy.DATE_PLAYED), sortOrder = listOf(SortOrder.DESCENDING),
			fields = setOf(ItemFields.GENRES), limit = 150,
		).content.items
		// Folgen tragen oft keine Genres – dann die der Serie
		val serienGenres = gesehen.mapNotNull { it.seriesId }.distinct().take(30).flatMap { id ->
			runCatching { api.userLibraryApi.getItem(itemId = id).content.genres.orEmpty() }.getOrDefault(emptyList())
		}
		genreVerlauf(gesehen, serienGenres).entries.sortedByDescending { it.value }.map { it.key }
	}

	private fun genreVerlauf(gesehen: List<BaseItemDto>, serienGenres: List<String>): Map<String, Int> =
		(gesehen.flatMap { it.genres.orEmpty() } + serienGenres).groupingBy { it }.eachCount()

	/** Anteil jedes Genres am Sehverlauf (0..1). */
	private suspend fun genreAnteile(): Map<String, Double> {
		val gesehen = api.itemsApi.getItems(
			includeItemTypes = setOf(BaseItemKind.MOVIE, BaseItemKind.EPISODE), recursive = true,
			filters = setOf(ItemFilter.IS_PLAYED), sortBy = listOf(ItemSortBy.DATE_PLAYED), sortOrder = listOf(SortOrder.DESCENDING),
			fields = setOf(ItemFields.GENRES), limit = 300,
		).content.items
		val serienGenres = gesehen.mapNotNull { it.seriesId }.distinct().take(40).flatMap { id ->
			runCatching { api.userLibraryApi.getItem(itemId = id).content.genres.orEmpty() }.getOrDefault(emptyList())
		}
		val zaehler = genreVerlauf(gesehen, serienGenres)
		val summe = zaehler.values.sum().toDouble().coerceAtLeast(1.0)
		return zaehler.mapValues { it.value / summe }
	}

	/**
	 * Banner-Empfehlungen: noch nicht gesehene Filme/Serien mit Hintergrundbild, bevorzugt in den Lieblings-Genres
	 * des Benutzers und gut bewertet; sonst zufällig Ungesehenes. [bib] schränkt auf eine Bibliothek ein.
	 */
	/**
	 * Banner-Empfehlungen: noch nicht Gesehenes mit Hintergrundbild, bewertet nach dem eigenen Sehverlauf.
	 * Jedes Genre zählt mit seinem Anteil am Verlauf; Genres, die man kaum schaut, ziehen ab. «Spezial-Genres»
	 * (Animation, Horror, Kinder, Doku …) fliegen ganz raus, wenn sie im Verlauf praktisch nicht vorkommen.
	 * Kein Auffüllen mit Zufälligem mehr (brachte Horror und Trickfilme, die nie jemand schaut). [bib] schränkt ein.
	 */
	suspend fun bannerEmpfehlungen(bib: BaseItemDto? = null, anzahl: Int = 6): List<BaseItemDto> = io {
		val typen = bib?.let { typenFuer(it) } ?: setOf(BaseItemKind.MOVIE, BaseItemKind.SERIES)
		val anteile = runCatching { genreAnteile() }.getOrDefault(emptyMap())
		val kandidaten = api.itemsApi.getItems(
			parentId = bib?.id, includeItemTypes = typen, recursive = true,
			filters = setOf(ItemFilter.IS_UNPLAYED), imageTypes = setOf(org.jellyfin.sdk.model.api.ImageType.BACKDROP),
			sortBy = listOf(ItemSortBy.COMMUNITY_RATING), sortOrder = listOf(SortOrder.DESCENDING),
			fields = felder + ItemFields.GENRES, enableUserData = true, limit = 300,
		).content.items
		if (anteile.isEmpty()) return@io kandidaten.take(40).shuffled().take(anzahl)
		val spezial = listOf("animation", "anime", "horror", "kinder", "familie", "family", "dokumentar", "documentary", "dokumentation", "reality", "musik", "music")
		fun anteil(g: String) = anteile[g] ?: 0.0
		val bewertet = kandidaten.mapNotNull { k ->
			val g = k.genres.orEmpty()
			if (g.isEmpty()) return@mapNotNull null
			// Spezial-Genre, das man so gut wie nie schaut → weg
			if (g.any { x -> spezial.any { x.lowercase().contains(it) } && anteil(x) < 0.03 }) return@mapNotNull null
			val treffer = g.sumOf { anteil(it) }
			val fremd = g.count { anteil(it) < 0.02 }
			val wert = treffer - 0.08 * fremd + (k.communityRating ?: 6f) / 100.0
			if (treffer <= 0.0) null else k to wert
		}.sortedByDescending { it.second }.map { it.first }
		// Aus den besten zwanzig zufällig wählen, damit der Banner nicht jeden Tag gleich ist
		bewertet.take(20).shuffled().take(anzahl)
	}


	// --- Musik ---

	/** Eigene Playlists; Playlist-Dateien aus den Medienordnern (z. B. .m3u in Album-Ordnern) bleiben aussen vor. */
	suspend fun playlists(): List<BaseItemDto> = io {
		api.itemsApi.getItems(includeItemTypes = setOf(BaseItemKind.PLAYLIST), recursive = true, sortBy = listOf(ItemSortBy.SORT_NAME),
			fields = felder + ItemFields.PATH, enableUserData = true).content.items.filter { it.path?.startsWith("/mnt/") != true }
	}

	/** Musik-Favoriten: Alben und Interpreten der Bibliothek, dazu die eigenen Playlists mit Herz. */
	suspend fun musikFavoriten(bib: BaseItemDto): List<BaseItemDto> = io {
		val alben = api.itemsApi.getItems(parentId = bib.id, recursive = true, includeItemTypes = setOf(BaseItemKind.MUSIC_ALBUM, BaseItemKind.MUSIC_ARTIST),
			filters = setOf(ItemFilter.IS_FAVORITE), sortBy = listOf(ItemSortBy.SORT_NAME), fields = felder, enableUserData = true).content.items
		val listen = api.itemsApi.getItems(includeItemTypes = setOf(BaseItemKind.PLAYLIST), recursive = true, filters = setOf(ItemFilter.IS_FAVORITE),
			sortBy = listOf(ItemSortBy.SORT_NAME), fields = felder + ItemFields.PATH, enableUserData = true).content.items.filter { it.path?.startsWith("/mnt/") != true }
		listen + alben
	}

	/** Favoriten unter den automatischen Mixen (Schlüssel), im Konto gespeichert wie die Radio-Favoriten. */
	suspend fun mixFavoriten(): Set<String> = io {
		runCatching {
			api.displayPreferencesApi.getDisplayPreferences(displayPreferencesId = "seliku-musik", client = "seliku").content
				.customPrefs["mixfavoriten"].orEmpty().split("\n").filter { it.isNotBlank() }.toSet()
		}.getOrDefault(emptySet())
	}

	suspend fun mixFavoritenSetzen(schluessel: Set<String>) = io {
		val alt = api.displayPreferencesApi.getDisplayPreferences(displayPreferencesId = "seliku-musik", client = "seliku").content
		api.displayPreferencesApi.updateDisplayPreferences(displayPreferencesId = "seliku-musik", client = "seliku",
			data = alt.copy(customPrefs = alt.customPrefs + ("mixfavoriten" to schluessel.joinToString("\n"))))
	}

	suspend fun playlistTitel(id: UUID): List<BaseItemDto> = io {
		api.playlistsApi.getPlaylistItems(playlistId = id, fields = felder, enableUserData = true).content.items
	}

	suspend fun genreMix(name: String): List<BaseItemDto> = io {
		api.instantMixApi.getInstantMixFromMusicGenreByName(name = name, limit = 100, fields = felder).content.items
	}

	suspend fun musikTitel(bib: BaseItemDto, sortBy: ItemSortBy, absteigend: Boolean, filter: Set<ItemFilter> = emptySet(), anzahl: Int = 100) = io {
		liste(bib, setOf(BaseItemKind.AUDIO), listOf(sortBy), absteigend, filter, anzahl = anzahl)
	}

	/** Eigene Favoriten (Filme und Serien), nach Name. */
	suspend fun favoriten(): List<BaseItemDto> = io {
		api.itemsApi.getItems(
			recursive = true,
			includeItemTypes = setOf(BaseItemKind.MOVIE, BaseItemKind.SERIES),
			filters = setOf(ItemFilter.IS_FAVORITE),
			sortBy = listOf(ItemSortBy.SORT_NAME),
			fields = felder,
			enableUserData = true,
			limit = 60,
		).content.items
	}

	suspend fun favorit(id: UUID, ja: Boolean) = io {
		if (ja) api.userLibraryApi.markFavoriteItem(itemId = id) else api.userLibraryApi.unmarkFavoriteItem(itemId = id)
	}

	suspend fun staffeln(serie: UUID): List<BaseItemDto> = io {
		api.tvShowsApi.getSeasons(seriesId = serie, fields = felder, enableUserData = true).content.items
	}

	suspend fun folgen(serie: UUID, staffel: UUID): List<BaseItemDto> = io {
		api.tvShowsApi.getEpisodes(seriesId = serie, seasonId = staffel, fields = felder, enableUserData = true).content.items
	}

	suspend fun naechsteFolge(serie: UUID): BaseItemDto? = io {
		api.tvShowsApi.getNextUp(seriesId = serie, limit = 1, fields = felder, enableUserData = true).content.items.firstOrNull()
			?: folgeNachZuletztGeschauter(serie)
	}

	/**
	 * Jellyfin nimmt für «Als Nächstes» die höchste gesehene Folgennummer: Ist die letzte Folge der Serie gesehen
	 * (quer geschaut, aus Plex übernommen), kommt nichts – Hubert und Staller, Gipfeltreffen (S 08.11.). Dann zählt die
	 * zuletzt geschaute Folge: ist sie angefangen, die; sonst die nächste ungesehene danach. Nie geschaut: die erste ungesehene.
	 */
	private suspend fun folgeNachZuletztGeschauter(serie: UUID): BaseItemDto? {
		val alle = api.tvShowsApi.getEpisodes(seriesId = serie, isMissing = false, fields = felder, enableUserData = true).content.items
		val folgen = alle.filter { it.parentIndexNumber != 0 }.ifEmpty { alle }
		val zuletzt = folgen.filter { it.userData?.lastPlayedDate != null }.maxByOrNull { it.userData!!.lastPlayedDate!! }
			?: return folgen.firstOrNull { it.userData?.played != true }
		if (zuletzt.userData?.played != true) return zuletzt
		val nr = folgen.indexOf(zuletzt)
		return (folgen.drop(nr + 1) + folgen.take(nr)).firstOrNull { it.userData?.played != true }
	}

	suspend fun aehnlich(id: UUID): List<BaseItemDto> = io {
		api.libraryApi.getSimilarItems(itemId = id, limit = 16, fields = felder).content.items
	}

	/**
	 * Interpreten ohne Gastauftritte: «Kygo feat. Conrad» oder «Kygo & Zak Abel» erscheinen unter «Kygo», wenn es den gibt.
	 * Die zusammengefassten Kennungen merkt sich [verwandte] für die Interpretenseite.
	 */
	suspend fun interpreten(bibliothek: BaseItemDto): List<BaseItemDto> {
		val alle = alleInterpreten(bibliothek)
		val namen = alle.associateBy { it.name.orEmpty().lowercase() }
		val gruppen = alle.groupBy { basisName(it.name.orEmpty()).lowercase().let { b -> if (b in namen) b else it.name.orEmpty().lowercase() } }
		return gruppen.map { (basis, mitglieder) ->
			val kopf = namen[basis] ?: mitglieder.first()
			verwandte[kopf.id] = mitglieder.map { it.id }
			kopf
		}.sortedBy { it.sortName ?: it.name }
	}

	private suspend fun alleInterpreten(bibliothek: BaseItemDto): List<BaseItemDto> = io {
		api.artistsApi.getAlbumArtists(
			parentId = bibliothek.id,
			sortBy = listOf(ItemSortBy.SORT_NAME),
			sortOrder = listOf(SortOrder.ASCENDING),
			fields = felder,
			enableUserData = true,
		).content.items
	}

	suspend fun alben(bibliothek: BaseItemDto?, interpret: UUID?): List<BaseItemDto> = io {
		api.itemsApi.getItems(
			parentId = bibliothek?.id,
			albumArtistIds = interpret?.let { verwandte[it] ?: listOf(it) }?.toSet() ?: emptySet(),
			includeItemTypes = setOf(BaseItemKind.MUSIC_ALBUM),
			recursive = true,
			sortBy = if (interpret != null) listOf(ItemSortBy.PRODUCTION_YEAR, ItemSortBy.SORT_NAME) else listOf(ItemSortBy.SORT_NAME),
			sortOrder = listOf(if (interpret != null) SortOrder.DESCENDING else SortOrder.ASCENDING),
			fields = felder,
			enableUserData = true,
		).content.items
	}

	suspend fun titel(album: UUID): List<BaseItemDto> = io {
		api.itemsApi.getItems(
			parentId = album,
			includeItemTypes = setOf(BaseItemKind.AUDIO),
			recursive = true,
			sortBy = listOf(ItemSortBy.PARENT_INDEX_NUMBER, ItemSortBy.INDEX_NUMBER, ItemSortBy.SORT_NAME),
			fields = felder,
			enableUserData = true,
		).content.items
	}

	/** Titel eines Interpreten: als Mitwirkender oder als Album-Interpret (Gastauftritte zählen mit). */
	suspend fun titelVon(interpret: UUID, beliebteste: Boolean): List<BaseItemDto> = io {
		val ids = (verwandte[interpret] ?: listOf(interpret)).toSet()
		suspend fun abfrage(alsAlbumInterpret: Boolean) = api.itemsApi.getItems(
			artistIds = if (alsAlbumInterpret) emptySet() else ids,
			albumArtistIds = if (alsAlbumInterpret) ids else emptySet(),
			includeItemTypes = setOf(BaseItemKind.AUDIO),
			recursive = true,
			sortBy = if (beliebteste) listOf(ItemSortBy.PLAY_COUNT, ItemSortBy.COMMUNITY_RATING) else listOf(ItemSortBy.ALBUM, ItemSortBy.PARENT_INDEX_NUMBER, ItemSortBy.INDEX_NUMBER),
			sortOrder = listOf(if (beliebteste) SortOrder.DESCENDING else SortOrder.ASCENDING),
			limit = if (beliebteste) 10 else 500,
			fields = felder,
			enableUserData = true,
		).content.items
		(abfrage(false) + abfrage(true)).distinctBy { it.id }.let { if (beliebteste) it.take(10) else it }
	}

	suspend fun suche(text: String): List<BaseItemDto> = io {
		api.itemsApi.getItems(
			searchTerm = text,
			recursive = true,
			includeItemTypes = setOf(
				BaseItemKind.MOVIE, BaseItemKind.SERIES, BaseItemKind.EPISODE, BaseItemKind.MUSIC_ARTIST,
				BaseItemKind.MUSIC_ALBUM, BaseItemKind.AUDIO, BaseItemKind.TV_CHANNEL,
			),
			limit = 80,
			fields = felder,
			enableUserData = true,
		).content.items
	}

	suspend fun sender(): List<BaseItemDto> = io {
		api.liveTvApi.getLiveTvChannels(
			addCurrentProgram = true,
			enableUserData = true,
			sortBy = listOf(ItemSortBy.SORT_NAME),
			sortOrder = SortOrder.ASCENDING,
		).content.items.sortedBy { (it.channelNumber ?: it.number)?.toDoubleOrNull() ?: Double.MAX_VALUE }
	}

	/** Nächste Sendung je Sender (die erste, die nach jetzt beginnt) – für «Danach» in der Senderliste. */
	suspend fun naechsteSendungen(sender: List<BaseItemDto>): Map<UUID, BaseItemDto> = io {
		if (sender.isEmpty()) emptyMap()
		else {
			val jetzt = java.time.LocalDateTime.now()
			api.liveTvApi.getLiveTvPrograms(
				channelIds = sender.map { it.id },
				enableImages = false,
				sortBy = setOf(ItemSortBy.START_DATE),
				minStartDate = jetzt,
				maxStartDate = jetzt.plusHours(8),
			).content.items.filter { it.channelId != null }.groupBy { it.channelId!! }.mapValues { it.value.minBy { p -> p.startDate ?: jetzt } }
		}
	}

	/**
	 * Namen der Senderblöcke, im Konto gespeichert (Anzeige-Einstellung «seliku-livetv», Wert «gruppen»):
	 * eine Zeile je Block, «Startnummer=Name», z. B. «1=Deutsch», «100=Shqip», «200=Ελληνικά».
	 */
	suspend fun senderGruppenNamen(): Map<Int, String> = io {
		runCatching {
			api.displayPreferencesApi.getDisplayPreferences(displayPreferencesId = "seliku-livetv", client = "seliku").content
				.customPrefs["gruppen"].orEmpty().lines().mapNotNull { z ->
					val (nr, name) = z.split("=", limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
					nr.trim().toIntOrNull()?.let { senderBlock(it.toString()) to name.trim() }
				}.toMap()
		}.getOrDefault(emptyMap())
	}

	suspend fun gesehen(id: UUID, ja: Boolean) = io {
		if (ja) api.playStateApi.markPlayedItem(itemId = id) else api.playStateApi.markUnplayedItem(itemId = id)
	}
}
