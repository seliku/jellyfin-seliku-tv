package org.jellyfin.androidtv.seliku

import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.artistsApi
import org.jellyfin.sdk.api.client.extensions.genresApi
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

/** Farben der Seliku-Oberfläche: eigene Gestaltung, dunkel, Akzent Petrol. */
object Farben {
	val Hintergrund = Color(0xFF0E1116)
	val Flaeche = Color(0xFF1A1F27)
	val FlaecheHell = Color(0xFF2A313C)
	val Akzent = Color(0xFF4FB3BF)
	val AkzentDunkel = Color(0xFF2E7F89)
	val Text = Color(0xFFF2F4F7)
	val TextLeise = Color(0xFFA3ACB9)
	val Schatten = Color(0xCC0E1116)
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
		(angefangen + naechste.filter { it.seriesId !in serien }).distinctBy { it.id }.take(30)
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
