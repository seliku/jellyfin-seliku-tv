package org.jellyfin.androidtv.seliku

import android.view.TextureView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.hlsSegmentApi
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemSortBy
import org.koin.compose.koinInject
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Lokale Trailer (von Trailarr in den Ordner «Trailers» geladen). YouTube-Trailer aus den Metadaten spielt der Player
 * nicht direkt ab – deshalb liegen sie auf dem Server.
 */
object Trailer {
	private val zwischenspeicher = ConcurrentHashMap<UUID, String>()
	// «Kein Trailer» nur 15 Minuten merken: Trailarr lädt laufend nach, und der Fernseher hält die App tagelang am Leben
	private val keiner = ConcurrentHashMap<UUID, Long>()

	/**
	 * Adresse des ersten lokalen Trailers (bei Folgen: der Serie). Gibt es keinen und ist [ausschnitt] an,
	 * ein Ausschnitt aus dem Film bzw. einer Folge (siehe [ausschnittAdresse]), sonst null.
	 */
	suspend fun adresse(api: ApiClient, item: BaseItemDto, ausschnitt: Boolean = false): String? {
		val id = if (item.type == BaseItemKind.EPISODE || item.type == BaseItemKind.SEASON) item.seriesId ?: item.id else item.id
		zwischenspeicher[id]?.let { return it }
		keiner[id]?.let {
			if (System.currentTimeMillis() - it < 15 * 60_000) return if (ausschnitt) ausschnittAdresse(api, item, id) else null
			keiner.remove(id)
		}
		val t = runCatching { withContext(Dispatchers.IO) { api.userLibraryApi.getLocalTrailers(itemId = id).content.firstOrNull() } }
			.onFailure { Timber.d(it, "Trailer suchen") }.getOrNull()
		val url = t?.let { tr ->
			// Seliku: Kann das Gerät den Codec nicht in Hardware (z. B. AV1 auf dem Chromecast), ruckelt der Trailer.
			// Dann wandelt der Server ihn in h264 um (S 08.11.).
			val codec = (tr.mediaStreams ?: tr.mediaSources?.firstOrNull()?.mediaStreams)
				?.firstOrNull { it.type == org.jellyfin.sdk.model.api.MediaStreamType.VIDEO }?.codec
			if (codec == null || hardwareKann(codec)) "${api.baseUrl?.trimEnd('/')}/Videos/${tr.id}/stream?static=true"
			else {
				val q = tr.id.toString().replace("-", "")
				"${api.baseUrl?.trimEnd('/')}/Videos/$q/main.m3u8?MediaSourceId=$q&DeviceId=${api.deviceInfo.id}" +
					"&PlaySessionId=seliku-trailer-${UUID.randomUUID().toString().take(8)}&VideoCodec=h264&AudioCodec=aac&MaxWidth=1920" +
					"&VideoBitrate=6000000&AudioBitrate=128000&MaxAudioChannels=2&SegmentContainer=ts&BreakOnNonKeyFrames=true"
			}
		}
		if (url != null) { zwischenspeicher[id] = url; return url }
		keiner[id] = System.currentTimeMillis()
		return if (ausschnitt) ausschnittAdresse(api, item, id) else null
	}

	private val hardware = ConcurrentHashMap<String, Boolean>()

	/** Hat das Gerät für diesen Jellyfin-Codec einen Hardware-Decoder? Unbekannte Codecs gelten als machbar. */
	private fun hardwareKann(codec: String): Boolean {
		val mime = when (codec.lowercase()) {
			"av1" -> "video/av01"; "hevc", "h265" -> "video/hevc"; "vp9" -> "video/x-vnd.on2.vp9"
			"vp8" -> "video/x-vnd.on2.vp8"; "h264", "avc" -> "video/avc"; else -> return true
		}
		return hardware.getOrPut(mime) {
			runCatching {
				android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
					!info.isEncoder && info.supportedTypes.any { it.equals(mime, true) } &&
						(android.os.Build.VERSION.SDK_INT < 29 || info.isHardwareAccelerated)
				}
			}.getOrDefault(true)
		}
	}

	private val ausschnitte = ConcurrentHashMap<UUID, String>()

	/**
	 * Ohne Trailer: 90 s aus dem Film selbst (bei Serien aus einer zufälligen Folge), ab einem Viertel der Laufzeit –
	 * nach dem Vorspann, weit vor dem Ende. Der Server wandelt in 720p/h264 um, das läuft auf jedem Gerät und auch bei
	 * langsamem Internet. Der Startpunkt steht als SelikuAbMs in der Adresse (der Server ignoriert ihn).
	 */
	private suspend fun ausschnittAdresse(api: ApiClient, item: BaseItemDto, id: UUID): String? {
		ausschnitte[id]?.let { return it }
		val quelle = runCatching {
			withContext(Dispatchers.IO) {
				when (item.type) {
					BaseItemKind.MOVIE, BaseItemKind.EPISODE -> item.takeIf { it.runTimeTicks != null } ?: api.userLibraryApi.getItem(itemId = item.id).content
					BaseItemKind.SERIES, BaseItemKind.SEASON -> api.itemsApi.getItems(parentId = id, recursive = true, includeItemTypes = listOf(BaseItemKind.EPISODE),
						isMissing = false, sortBy = listOf(ItemSortBy.RANDOM), limit = 1).content.items.firstOrNull()
					else -> null
				}
			}
		}.onFailure { Timber.d(it, "Ausschnitt suchen") }.getOrNull() ?: return null
		val laufzeitMs = (quelle.runTimeTicks ?: return null) / 10_000
		if (laufzeitMs < 5 * 60_000) return null
		val q = quelle.id.toString().replace("-", "")
		val url = "${api.baseUrl?.trimEnd('/')}/Videos/$q/main.m3u8?MediaSourceId=$q&DeviceId=${api.deviceInfo.id}" +
			"&PlaySessionId=seliku-ausschnitt-${UUID.randomUUID().toString().take(8)}&VideoCodec=h264&AudioCodec=aac&MaxWidth=1280" +
			"&VideoBitrate=3000000&AudioBitrate=128000&MaxAudioChannels=2&SegmentContainer=ts&BreakOnNonKeyFrames=true&SelikuAbMs=${laufzeitMs / 4}"
		ausschnitte[id] = url
		return url
	}

	/** Der erste lokale Trailer als Eintrag (zum Abspielen im normalen Player), sonst null. */
	suspend fun eintrag(api: ApiClient, item: BaseItemDto): BaseItemDto? {
		val id = if (item.type == BaseItemKind.EPISODE || item.type == BaseItemKind.SEASON) item.seriesId ?: item.id else item.id
		return runCatching { withContext(Dispatchers.IO) { api.userLibraryApi.getLocalTrailers(itemId = id).content.firstOrNull() } }.getOrNull()
	}

	/** Zufällige Trailer aus Filmen und Serien (für den Bildschirm bei Musik). */
	suspend fun zufaellige(api: ApiClient, anzahl: Int = 20): List<String> = zufaelligeMitTitel(api, anzahl).map { it.second }

	/** Wie [zufaellige], mit dem Namen des Films bzw. der Serie (Name to Adresse). */
	suspend fun zufaelligeMitTitel(api: ApiClient, anzahl: Int = 20): List<Pair<String, String>> {
		val kandidaten = runCatching {
			withContext(Dispatchers.IO) {
				api.itemsApi.getItems(recursive = true, includeItemTypes = listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES),
					hasTrailer = true, sortBy = listOf(ItemSortBy.RANDOM), limit = 80).content.items
			}
		}.getOrDefault(emptyList())
		// Jellyfin liefert die Trailer-Anzahl in Listen nicht mit: pro Titel nachfragen, aber gleichzeitig
		return kotlinx.coroutines.coroutineScope {
			kandidaten.map { k -> async { adresse(api, k)?.let { k.name.orEmpty() to it } } }.mapNotNull { it.await() }.take(anzahl)
		}
	}
}

/** Es läuft nie mehr als ein Trailer: der zuletzt gestartete gewinnt, der vorige hört auf ([TrailerVideo] onWeg). */
object TrailerWaechter {
	val aktiv = androidx.compose.runtime.mutableStateOf<Any?>(null)
}

/**
 * Spielt einen Trailer in einer Fläche ab. [onBereit] kommt, sobald wirklich etwas zu sehen ist (zum Überblenden),
 * [onEnde] am Schluss oder bei einem Fehler, [onWeg], wenn ein anderer Trailer übernimmt (Vorgabe: wie [onEnde]).
 */
@Composable
fun TrailerVideo(url: String, modifier: Modifier = Modifier, mitTon: Boolean = false, onBereit: () -> Unit = {}, onEnde: () -> Unit = {}, onWeg: (() -> Unit)? = null) {
	val ich = remember(url) { Any() }
	val weg by rememberUpdatedState(onWeg ?: onEnde)
	// Ton nur, wenn gerade keine Musik und kein Radio läuft – die sollen nicht unterbrochen werden
	val mediaManager = koinInject<org.jellyfin.androidtv.ui.playback.MediaManager>()
	val ton = mitTon && !mediaManager.isPlayingAudio && !RadioSpieler.spielt.value
	val context = LocalContext.current
	val api = koinInject<ApiClient>()
	val bereit by rememberUpdatedState(onBereit)
	val ende by rememberUpdatedState(onEnde)
	// TextureView verzerrt das Bild auf die Fläche; deshalb wie ein Hintergrundbild zuschneiden (mittig, füllend)
	val ansicht = remember { arrayOfNulls<TextureView>(1) }
	fun zuschneiden(breite: Int, hoehe: Int) {
		val v = ansicht[0] ?: return
		if (breite <= 0 || hoehe <= 0 || v.width == 0 || v.height == 0) return
		val sx = v.width.toFloat(); val sy = v.height.toFloat()
		val faktor = maxOf(sx / breite, sy / hoehe)
		val m = android.graphics.Matrix()
		m.setScale(breite * faktor / sx, hoehe * faktor / sy, sx / 2f, sy / 2f)
		v.setTransform(m)
	}
	val spieler = remember(url) {
		// Jellyfin nimmt nur noch den Authorization-Kopf an (kein api_key in der Adresse)
		val quelle = DefaultHttpDataSource.Factory()
			.setDefaultRequestProperties(mapOf("Authorization" to "MediaBrowser Token=\"${api.accessToken.orEmpty()}\""))
		ExoPlayer.Builder(context.applicationContext).setMediaSourceFactory(DefaultMediaSourceFactory(quelle)).build().apply {
			// Kein Audio-Fokus anfordern: sonst hielte Android laufende Musik an
			setAudioAttributes(androidx.media3.common.AudioAttributes.Builder().setUsage(androidx.media3.common.C.USAGE_MEDIA)
				.setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MOVIE).build(), false)
			volume = if (ton) 1f else 0f
			// Ausschnitt statt Trailer: ab SelikuAbMs 90 Sekunden lang
			val abMs = android.net.Uri.parse(url).getQueryParameter("SelikuAbMs")?.toLongOrNull()
			setMediaItem(if (abMs == null) MediaItem.fromUri(url) else MediaItem.Builder().setUri(url)
				.setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setStartPositionMs(abMs).setEndPositionMs(abMs + 90_000).build()).build())
			addListener(object : Player.Listener {
				// Trailer beginnen meist mit schwarzen Bildern: erst nach gut einer Sekunde Laufzeit überblenden,
				// sonst wird das Standbild für einen Moment schwarz
				override fun onRenderedFirstFrame() { android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ if (isPlaying) bereit() }, 1_200) }
				override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) { zuschneiden(videoSize.width, videoSize.height) }
				override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_ENDED) ende() }
				override fun onPlayerError(error: androidx.media3.common.PlaybackException) { Timber.d(error, "Trailer"); ende() }
			})
			prepare(); playWhenReady = true
		}
	}
	androidx.compose.runtime.LaunchedEffect(ich) {
		TrailerWaechter.aktiv.value = ich
		androidx.compose.runtime.snapshotFlow { TrailerWaechter.aktiv.value }.collect { if (it !== ich) { spieler.volume = 0f; spieler.pause(); weg() } }
	}
	// Geht die App in den Hintergrund oder startet der Player, sofort still sein
	val lebenszyklus = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
	DisposableEffect(spieler, lebenszyklus) {
		val beobachter = androidx.lifecycle.LifecycleEventObserver { _, e ->
			if (e == androidx.lifecycle.Lifecycle.Event.ON_PAUSE) { spieler.volume = 0f; spieler.pause(); weg() }
		}
		lebenszyklus.addObserver(beobachter)
		onDispose { lebenszyklus.removeObserver(beobachter) }
	}
	DisposableEffect(spieler) {
		onDispose {
			if (TrailerWaechter.aktiv.value === ich) TrailerWaechter.aktiv.value = null
			spieler.release()
			// Beim Ausschnitt die Umwandlung auf dem Server beenden, sonst läuft sie noch eine Weile weiter
			val adresse = android.net.Uri.parse(url)
			val sitzung = adresse.getQueryParameter("PlaySessionId")
			if (sitzung != null && adresse.getQueryParameter("SelikuAbMs") != null) kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
				runCatching { api.hlsSegmentApi.stopEncodingProcess(deviceId = adresse.getQueryParameter("DeviceId").orEmpty(), playSessionId = sitzung) }
					.onFailure { Timber.d(it, "Ausschnitt beenden") }
			}
		}
	}
	AndroidView(factory = {
		TextureView(it).also { v ->
			ansicht[0] = v
			spieler.setVideoTextureView(v)
			v.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> spieler.videoSize.let { g -> zuschneiden(g.width, g.height) } }
		}
	}, modifier = modifier)
}

/**
 * Titelmusik einer Serie (theme.mp3 im Serienordner), leise beim Öffnen der Serienseite – wie bei Plex.
 * Nicht, wenn Musik/Radio läuft; hört auf, sobald man die Seite verlässt oder etwas abspielt.
 */
@Composable
fun TitelMusik(itemId: UUID) {
	val context = LocalContext.current
	val api = koinInject<ApiClient>()
	val mediaManager = koinInject<org.jellyfin.androidtv.ui.playback.MediaManager>()
	if (!SelikuEinstellungen.titelmusik(context) || mediaManager.isPlayingAudio || RadioSpieler.spielt.value) return
	var url by androidx.compose.runtime.remember(itemId) { androidx.compose.runtime.mutableStateOf<String?>(null) }
	androidx.compose.runtime.LaunchedEffect(itemId) {
		url = runCatching {
			withContext(Dispatchers.IO) { api.libraryApi.getThemeSongs(itemId = itemId, inheritFromParent = true).content.items.firstOrNull() }
		}.getOrNull()?.let { "${api.baseUrl?.trimEnd('/')}/Audio/${it.id}/stream?static=true" }
	}
	val u = url ?: return
	val lebenszyklus = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
	DisposableEffect(u, lebenszyklus) {
		val quelle = DefaultHttpDataSource.Factory()
			.setDefaultRequestProperties(mapOf("Authorization" to "MediaBrowser Token=\"${api.accessToken.orEmpty()}\""))
		val p = ExoPlayer.Builder(context.applicationContext).setMediaSourceFactory(DefaultMediaSourceFactory(quelle)).build().apply {
			setAudioAttributes(androidx.media3.common.AudioAttributes.Builder().setUsage(androidx.media3.common.C.USAGE_MEDIA)
				.setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MUSIC).build(), false)
			volume = 0.35f
			setMediaItem(MediaItem.fromUri(u)); prepare(); playWhenReady = true
		}
		// Startet man eine Folge (Player-Fenster), sofort aufhören
		val beobachter = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_PAUSE) p.stop() }
		lebenszyklus.addObserver(beobachter)
		onDispose { lebenszyklus.removeObserver(beobachter); p.release() }
	}
}
