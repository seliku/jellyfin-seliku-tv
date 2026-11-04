package org.jellyfin.androidtv.seliku

import android.content.Context
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import org.jellyfin.androidtv.ui.navigation.Destinations
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.androidtv.ui.playback.MediaManager
import org.jellyfin.androidtv.ui.playback.PlaybackLauncher
import org.jellyfin.androidtv.util.PlaybackHelper
import org.jellyfin.androidtv.util.apiclient.JellyfinImage
import org.jellyfin.androidtv.util.apiclient.Response
import org.jellyfin.androidtv.util.apiclient.getUrl
import org.jellyfin.androidtv.util.apiclient.itemBackdropImages
import org.jellyfin.androidtv.util.apiclient.itemImages
import org.jellyfin.androidtv.util.apiclient.parentBackdropImages
import org.jellyfin.androidtv.util.apiclient.parentImages
import org.jellyfin.androidtv.util.apiclient.seriesPrimaryImage
import org.jellyfin.androidtv.util.apiclient.seriesThumbImage
import org.jellyfin.androidtv.util.apiclient.albumPrimaryImage
import org.jellyfin.androidtv.util.apiclient.channelPrimaryImage
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CollectionType
import org.jellyfin.sdk.model.api.ImageType
import java.util.UUID

/** Seiten der Seliku-Oberfläche. Alles andere (Player, Programmführer, Einstellungen) bleibt bei der App. */
sealed interface Seite {
	val schluessel: String

	data object Start : Seite { override val schluessel = "start" }
	data object Suche : Seite { override val schluessel = "suche" }
	data object LiveTv : Seite { override val schluessel = "livetv" }
	data class Bibliothek(val ansicht: BaseItemDto) : Seite { override val schluessel = "bib-${ansicht.id}" }
	data class Musik(val ansicht: BaseItemDto) : Seite { override val schluessel = "musik-${ansicht.id}" }
	data class Serie(val id: UUID) : Seite { override val schluessel = "serie-$id" }
	data class Titel(val id: UUID) : Seite { override val schluessel = "titel-$id" }
	data class Interpret(val id: UUID) : Seite { override val schluessel = "interpret-$id" }
	data class Album(val id: UUID) : Seite { override val schluessel = "album-$id" }
	data class Ordner(val id: UUID, val name: String) : Seite { override val schluessel = "ordner-$id" }
	data object Bibliotheken : Seite { override val schluessel = "bibliotheken" }
	data object Einstellungen : Seite { override val schluessel = "einstellungen" }
	data object Entdecken : Seite { override val schluessel = "entdecken" }
	data class SeerrDetail(val typ: String, val tmdb: Int) : Seite { override val schluessel = "seerr-$typ-$tmdb" }
	data object SeerrVerbinden : Seite { override val schluessel = "seerr-verbinden" }
}

/** Seitenstapel und gemerkte Scroll-/Fokuspositionen; überlebt den Abstecher in den Player. */
class SelikuViewModel : ViewModel() {
	val stapel = mutableStateListOf<Seite>(Seite.Start)
	val listen = mutableMapOf<String, LazyListState>()
	val raster = mutableMapOf<String, LazyGridState>()
	val fokus = mutableMapOf<String, String>()
	val wahl = mutableMapOf<String, Any>()

	/** Titel, zu dem gerade das Titelmenü (langes Drücken) offen ist. */
	var menue by androidx.compose.runtime.mutableStateOf<BaseItemDto?>(null)

	/** Erste Kachel je Seite: «Zurück» springt dorthin, bevor die Leiste aufgeht. */
	/** Seiten, auf denen die gemerkte Kachel den Fokus schon zurückgeholt hat (einmal pro Besuch, nicht bei jedem Auftauchen). */
	val wiederhergestellt = mutableSetOf<String>()
	/** Reiterleiste oben (Musik, Filme, Serien): Zurück springt erst dorthin, erst das nächste Zurück öffnet die Leiste. */
	val reiterAnfragen = mutableMapOf<String, androidx.compose.ui.focus.FocusRequester>()
	fun reiter(seite: Seite) = reiterAnfragen.getOrPut(seite.schluessel) { androidx.compose.ui.focus.FocusRequester() }
	var reiterFokus = false
	val ersterSchluessel = mutableMapOf<String, String>()
	private val ersteAnfragen = mutableMapOf<String, androidx.compose.ui.focus.FocusRequester>()
	fun ersteAnfrageFuer(seite: String, kachel: String): androidx.compose.ui.focus.FocusRequester {
		val erster = ersterSchluessel.getOrPut(seite) { kachel }
		return if (erster == kachel) ersteAnfragen.getOrPut(seite) { androidx.compose.ui.focus.FocusRequester() } else androidx.compose.ui.focus.FocusRequester()
	}
	fun ersteAnfrage(seite: String) = ersteAnfragen[seite]

	val aktuell get() = stapel.last()

	fun zu(seite: Seite) {
		if (stapel.last() != seite) stapel.add(seite)
	}

	fun wurzel(seite: Seite) {
		stapel.clear()
		stapel.add(seite)
	}

	fun zurueck(): Boolean {
		if (stapel.size <= 1) return false
		val weg = stapel.removeAt(stapel.lastIndex)
		if (weg !in stapel) {
			listen.remove(weg.schluessel); raster.remove(weg.schluessel); fokus.remove(weg.schluessel)
			ersterSchluessel.remove(weg.schluessel); ersteAnfragen.remove(weg.schluessel)
		}
		return true
	}

	fun liste(seite: Seite) = listen.getOrPut(seite.schluessel) { LazyListState() }
	fun raster(seite: Seite) = raster.getOrPut(seite.schluessel) { LazyGridState() }
}

fun BaseItemDto.istMusik() = collectionType == CollectionType.MUSIC
fun BaseItemDto.istLiveTv() = collectionType == CollectionType.LIVETV
fun BaseItemDto.istFilmeOderSerien() = collectionType == CollectionType.MOVIES || collectionType == CollectionType.TVSHOWS ||
	collectionType == CollectionType.HOMEVIDEOS || collectionType == CollectionType.MUSICVIDEOS || collectionType == null

/** Wohin ein Klick auf ein Element führt – wie man es von gängigen Mediencentern kennt. */
fun seiteFuer(item: BaseItemDto): Seite? = when (item.type) {
	BaseItemKind.SERIES -> Seite.Serie(item.id)
	BaseItemKind.SEASON -> item.seriesId?.let { Seite.Serie(it) }
	BaseItemKind.MOVIE, BaseItemKind.VIDEO, BaseItemKind.MUSIC_VIDEO, BaseItemKind.TRAILER -> Seite.Titel(item.id)
	BaseItemKind.MUSIC_ARTIST -> Seite.Interpret(item.id)
	BaseItemKind.MUSIC_ALBUM -> Seite.Album(item.id)
	BaseItemKind.FOLDER, BaseItemKind.COLLECTION_FOLDER, BaseItemKind.BOX_SET -> Seite.Ordner(item.id, item.name.orEmpty())
	else -> null
}

/** Bilder: kleine Grössen anfordern, der Chromecast ist sparsam ausgestattet. */
class Bilder(private val api: ApiClient) {
	private fun url(bild: JellyfinImage?, breite: Int) = bild?.getUrl(api, maxWidth = breite)

	fun poster(item: BaseItemDto, breite: Int = 300): String? = url(
		when (item.type) {
			BaseItemKind.EPISODE, BaseItemKind.SEASON -> item.seriesPrimaryImage ?: item.itemImages[ImageType.PRIMARY]
			BaseItemKind.AUDIO -> item.albumPrimaryImage ?: item.itemImages[ImageType.PRIMARY]
			BaseItemKind.TV_CHANNEL -> item.itemImages[ImageType.PRIMARY]
			BaseItemKind.PROGRAM -> item.channelPrimaryImage ?: item.itemImages[ImageType.PRIMARY]
			else -> item.itemImages[ImageType.PRIMARY]
		}, breite
	)

	fun eigenesBild(item: BaseItemDto, breite: Int = 300): String? = url(item.itemImages[ImageType.PRIMARY], breite)

	/** Bild der gerade laufenden Sendung eines Senders (aus dem Fernsehprogramm), falls vorhanden. */
	fun sendung(sender: BaseItemDto, breite: Int = 480): String? = sender.currentProgram?.let {
		url(it.itemImages[ImageType.THUMB] ?: it.itemImages[ImageType.PRIMARY] ?: it.itemBackdropImages.firstOrNull(), breite)
	}

	/** Querformat: Folgenbild, sonst Thumb oder Hintergrund der Serie/des Films. */
	fun quer(item: BaseItemDto, breite: Int = 480): String? = url(
		when (item.type) {
			BaseItemKind.EPISODE -> item.itemImages[ImageType.PRIMARY] ?: item.parentImages[ImageType.THUMB] ?: item.seriesThumbImage
				?: item.parentBackdropImages.firstOrNull()
			else -> item.itemImages[ImageType.THUMB] ?: item.itemBackdropImages.firstOrNull() ?: item.itemImages[ImageType.PRIMARY]
		}, breite
	)

	fun hintergrund(item: BaseItemDto?, breite: Int = 1280): String? = item?.let {
		url(it.itemBackdropImages.firstOrNull() ?: it.parentBackdropImages.firstOrNull(), breite)
	}

	fun logo(item: BaseItemDto?, breite: Int = 600): String? = item?.let {
		url(it.itemImages[ImageType.LOGO] ?: it.parentImages[ImageType.LOGO], breite)
	}
}

/** Abspielen über die vorhandene Wiedergabe der App. */
class Abspielen(
	private val playbackHelper: PlaybackHelper,
	private val playbackLauncher: PlaybackLauncher,
	private val mediaManager: MediaManager,
	private val navigation: NavigationRepository,
) {
	/** Startet ein Video dort, wo man aufgehört hat (vonVorn = false), mit den folgenden Folgen in der Warteschlange. */
	fun video(context: Context, item: BaseItemDto, vonVorn: Boolean = false) {
		RadioSpieler.stoppen()
		val ticks = item.userData?.playbackPositionTicks ?: 0L
		val start = if (vonVorn || ticks <= 0L) 0 else ((ticks / 10_000L) - 5_000L).coerceAtLeast(0L).toInt()
		playbackHelper.getItemsToPlay(context, item, false, false, object : Response<List<BaseItemDto>>() {
			override fun onResponse(response: List<BaseItemDto>) {
				playbackLauncher.launch(context, response, start)
			}
		})
	}

	fun zufall(context: Context, item: BaseItemDto) {
		playbackHelper.getItemsToPlay(context, item, false, true, object : Response<List<BaseItemDto>>() {
			override fun onResponse(response: List<BaseItemDto>) {
				playbackLauncher.launch(context, response, 0, shuffle = true)
			}
		})
	}

	fun sender(context: Context, sender: BaseItemDto) {
		playbackHelper.getItemsToPlay(context, sender, false, false, object : Response<List<BaseItemDto>>() {
			override fun onResponse(response: List<BaseItemDto>) {
				playbackLauncher.launch(context, response)
			}
		})
	}

	/** Musik: Liste ab Titel [ab] abspielen. Die Wiedergabe läuft im Hintergrund weiter, die Seite bleibt offen. */
	fun musik(context: Context, titel: List<BaseItemDto>, ab: Int = 0, zufall: Boolean = false) {
		if (titel.isEmpty()) return
		RadioSpieler.stoppen()
		mediaManager.playNow(context, titel, ab, zufall)
	}

	fun jetztLaeuft() = navigation.navigate(Destinations.nowPlaying)
	fun programmfuehrer() = navigation.navigate(Destinations.liveTvGuide)
	fun aufnahmen() = navigation.navigate(Destinations.liveTvRecordings)
}
