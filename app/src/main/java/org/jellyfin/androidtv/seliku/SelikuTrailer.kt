package org.jellyfin.androidtv.seliku

import android.view.TextureView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
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
	private val keiner = ConcurrentHashMap.newKeySet<UUID>()

	/** Adresse des ersten lokalen Trailers (bei Folgen: der Serie), sonst null. */
	suspend fun adresse(api: ApiClient, item: BaseItemDto): String? {
		val id = if (item.type == BaseItemKind.EPISODE || item.type == BaseItemKind.SEASON) item.seriesId ?: item.id else item.id
		zwischenspeicher[id]?.let { return it }
		if (id in keiner) return null
		val t = runCatching { withContext(Dispatchers.IO) { api.userLibraryApi.getLocalTrailers(itemId = id).content.firstOrNull() } }
			.onFailure { Timber.d(it, "Trailer suchen") }.getOrNull()
		val url = t?.let { "${api.baseUrl?.trimEnd('/')}/Videos/${it.id}/stream?static=true" }
		if (url != null) zwischenspeicher[id] = url else keiner += id
		return url
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
fun TrailerVideo(url: String, modifier: Modifier = Modifier, onBereit: () -> Unit = {}, onEnde: () -> Unit = {}) {
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
			volume = 0f
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

