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
enum class Sortierung(val titel: String, val nach: List<ItemSortBy>, val reihenfolge: SortOrder) {
	NEU("Neu hinzugefügt", listOf(ItemSortBy.DATE_CREATED, ItemSortBy.SORT_NAME), SortOrder.DESCENDING),
	NAME("A–Z", listOf(ItemSortBy.SORT_NAME), SortOrder.ASCENDING),
	JAHR("Erscheinungsjahr", listOf(ItemSortBy.PRODUCTION_YEAR, ItemSortBy.SORT_NAME), SortOrder.DESCENDING),
	BEWERTUNG("Bewertung", listOf(ItemSortBy.COMMUNITY_RATING, ItemSortBy.SORT_NAME), SortOrder.DESCENDING),
	ZULETZT("Zuletzt gesehen", listOf(ItemSortBy.DATE_PLAYED, ItemSortBy.SORT_NAME), SortOrder.DESCENDING),
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

	/** «Weiterschauen»: angefangene Videos, danach die nächsten Folgen begonnener Serien (je Serie nur einmal). */
	suspend fun weiterschauen(): List<BaseItemDto> = io {
		val angefangen = api.itemsApi.getResumeItems(
			limit = 24,
			mediaTypes = setOf(MediaType.VIDEO),
			fields = felder,
			enableUserData = true,
		).content.items
		val naechste = api.tvShowsApi.getNextUp(
			limit = 24,
			fields = felder,
			enableUserData = true,
			enableResumable = false,
		).content.items
		val serien = angefangen.mapNotNull { it.seriesId }.toSet()
		ohneAusgeblendete((angefangen + naechste.filter { it.seriesId !in serien }).distinctBy { it.id }).take(30)
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
	): BaseItemDtoQueryResult = io {
		api.itemsApi.getItems(
			parentId = bibliothek.id,
			includeItemTypes = typen,
			recursive = true,
			sortBy = sortierung.nach,
			sortOrder = listOf(sortierung.reihenfolge),
			filters = if (nurUngesehen) setOf(ItemFilter.IS_UNPLAYED) else emptySet(),
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

	/** «Weil du X geschaut hast»: Ähnliches zum zuletzt Gesehenen der Bibliothek. */
	suspend fun weilDuGeschaut(bib: BaseItemDto, serien: Boolean): Pair<String, List<BaseItemDto>>? = io {
		val zuletzt = zuletztGesehen(bib, serien).firstOrNull() ?: return@io null
		val basis = if (serien) zuletzt.seriesId ?: return@io null else zuletzt.id
		val name = if (serien) zuletzt.seriesName.orEmpty() else zuletzt.name.orEmpty()
		val treffer = api.libraryApi.getSimilarItems(itemId = basis, limit = 20, fields = felder).content.items
		if (treffer.isEmpty()) null else "Weil du «$name» geschaut hast" to treffer
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
			fields = felder + ItemFields.PATH).content.items.filter { it.path?.startsWith("/mnt/") != true }
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
		).content.items
	}

	suspend fun gesehen(id: UUID, ja: Boolean) = io {
		if (ja) api.playStateApi.markPlayedItem(itemId = id) else api.playStateApi.markUnplayedItem(itemId = id)
	}
}
