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
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.ApiClient
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

	/** Adresse des ersten lokalen Trailers (bei Folgen: der Serie), sonst null. */
	suspend fun adresse(api: ApiClient, item: BaseItemDto): String? {
		val id = if (item.type == BaseItemKind.EPISODE || item.type == BaseItemKind.SEASON) item.seriesId ?: item.id else item.id
		zwischenspeicher[id]?.let { return it }
		keiner[id]?.let { if (System.currentTimeMillis() - it < 15 * 60_000) return null else keiner.remove(id) }
		val t = runCatching { withContext(Dispatchers.IO) { api.userLibraryApi.getLocalTrailers(itemId = id).content.firstOrNull() } }
			.onFailure { Timber.d(it, "Trailer suchen") }.getOrNull()
		val url = t?.let { "${api.baseUrl?.trimEnd('/')}/Videos/${it.id}/stream?static=true" }
		if (url != null) zwischenspeicher[id] = url else keiner[id] = System.currentTimeMillis()
		return url
	}

	/** Der erste lokale Trailer als Eintrag (zum Abspielen im normalen Player), sonst null. */
	suspend fun eintrag(api: ApiClient, item: BaseItemDto): BaseItemDto? {
		val id = if (item.type == BaseItemKind.EPISODE || item.type == BaseItemKind.SEASON) item.seriesId ?: item.id else item.id
		return runCatching { withContext(Dispatchers.IO) { api.userLibraryApi.getLocalTrailers(itemId = id).content.firstOrNull() } }.getOrNull()
	}

	/** Zufällige Trailer aus Filmen und Serien (für den Bildschirm bei Musik). */
	suspend fun zufaellige(api: ApiClient, anzahl: Int = 20): List<String> {
		val kandidaten = runCatching {
			withContext(Dispatchers.IO) {
				api.itemsApi.getItems(recursive = true, includeItemTypes = listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES),
					hasTrailer = true, sortBy = listOf(ItemSortBy.RANDOM), limit = 80).content.items
			}
		}.getOrDefault(emptyList())
		// Jellyfin liefert die Trailer-Anzahl in Listen nicht mit: pro Titel nachfragen, aber gleichzeitig
		return kotlinx.coroutines.coroutineScope {
			kandidaten.map { k -> async { adresse(api, k) } }.mapNotNull { it.await() }.take(anzahl)
		}
	}
}

/**
 * Spielt einen Trailer stumm in einer Fläche ab. [onBereit] kommt mit dem ersten Bild (zum Überblenden),
 * [onEnde] am Schluss oder bei einem Fehler.
 */
@Composable
fun TrailerVideo(url: String, modifier: Modifier = Modifier, mitTon: Boolean = false, onBereit: () -> Unit = {}, onEnde: () -> Unit = {}) {
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
			setMediaItem(MediaItem.fromUri(url))
			addListener(object : Player.Listener {
				override fun onRenderedFirstFrame() { bereit() }
				override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) { zuschneiden(videoSize.width, videoSize.height) }
				override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_ENDED) ende() }
				override fun onPlayerError(error: androidx.media3.common.PlaybackException) { Timber.d(error, "Trailer"); ende() }
			})
			prepare(); playWhenReady = true
		}
	}
	DisposableEffect(spieler) { onDispose { spieler.release() } }
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
