package org.jellyfin.androidtv.seliku

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.jellyfin.playback.core.PlaybackManager
import org.jellyfin.playback.core.mediastream.mediaStream
import org.jellyfin.playback.core.model.PlayState
import org.jellyfin.playback.core.model.RepeatMode
import org.jellyfin.playback.core.queue.queue
import org.jellyfin.playback.jellyfin.queue.baseItem
import org.jellyfin.sdk.model.api.BaseItemDto
import org.koin.java.KoinJavaComponent.get
import timber.log.Timber
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Überblenden zwischen zwei Liedern (wie Plexamp, Spotify): Kurz vor Schluss spielt ein zweiter, kleiner Player das Ende
 * des laufenden Liedes weiter und wird leiser, während der eigentliche Player schon das nächste Lied lauter werden lässt.
 * Nur bei Musik; nicht innerhalb eines Albums, wenn das nächste Lied direkt folgt (Live-Alben, DJ-Mixe laufen nahtlos).
 */
@OptIn(UnstableApi::class)
object Ueberblenden {
	private val pm by lazy { get<PlaybackManager>(PlaybackManager::class.java) }
	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
	private var laeuft: Job? = null
	private var fuer: java.util.UUID? = null

	/** Wird jede Sekunde mit dem laufenden Musiktitel aufgerufen. */
	fun pruefen(context: Context, item: BaseItemDto, pos: Long, dauer: Long) {
		val sekunden = SelikuEinstellungen.ueberblenden(context)
		if (sekunden <= 0 || laeuft?.isActive == true || fuer == item.id) return
		val d = sekunden * 1000L
		// Zu kurze Titel nicht, und erst in den letzten Sekunden vorbereiten
		if (dauer < 3 * d || dauer - pos > d + 8_000L || dauer - pos < d) return
		if (pm.state.playState.value != PlayState.PLAYING || pm.state.repeatMode.value != RepeatMode.NONE) return
		val entry = pm.queue.entry.value ?: return
		val url = entry.mediaStream?.url ?: return
		fuer = item.id
		laeuft = scope.launch {
			val naechster = runCatching { pm.queue.peekNext() }.getOrNull()?.baseItem ?: return@launch
			// Album am Stück: nahtlos, nicht überblenden
			if (naechster.albumId != null && naechster.albumId == item.albumId &&
				(naechster.parentIndexNumber ?: 1) == (item.parentIndexNumber ?: 1) && naechster.indexNumber == (item.indexNumber ?: -2) + 1) return@launch
			blenden(context, item, url, dauer, d)
		}
	}

	private suspend fun blenden(context: Context, item: BaseItemDto, url: String, dauer: Long, d: Long) {
		val quelle = DefaultMediaSourceFactory(DefaultDataSource.Factory(context, get<HttpDataSource.Factory>(HttpDataSource.Factory::class.java)))
		val hilfe = ExoPlayer.Builder(context).setMediaSourceFactory(quelle).build()
		try {
			// Den zweiten Player schon an die Stelle setzen, an der das Überblenden beginnt
			hilfe.volume = 1f
			hilfe.setMediaItem(MediaItem.fromUri(url), dauer - d)
			hilfe.playWhenReady = false
			hilfe.prepare()
			val bereit = withTimeoutOrNull(6_000) { while (hilfe.playbackState != Player.STATE_READY) delay(50) }
			Timber.i("Überblenden: " + "bereit=${bereit != null}")
			if (bereit == null) { Timber.w("Überblenden: zweiter Player nicht bereit"); return }
			// Warten, bis das Lied an der Stelle ist (abgebrochen, wenn man weiterschaltet, pausiert oder spult)
			while (true) {
				if (Hoeren.laufend()?.id != item.id || pm.state.playState.value != PlayState.PLAYING) return
				val pos = Hoeren.position()
				if (pos < dauer - d - 9_000L) return
				if (pos >= dauer - d) break
				delay(40)
			}
			// Übergabe: der zweite Player spielt das Ende, der Hauptplayer springt leise zum nächsten Lied
			Timber.i("Überblenden: " + "Start bei ${Hoeren.position()} von $dauer")
			hilfe.seekTo(Hoeren.position())
			hilfe.play()
			pm.state.setPlayerVolume(0f)
			pm.queue.next()
			val start = System.currentTimeMillis()
			// Beim Laden des nächsten Liedes meldet der Hauptplayer kurz «Pause»: erst zählt, was nach dem Anlaufen kommt
			var angelaufen = false
			while (true) {
				if (pm.state.playState.value == PlayState.PLAYING && Hoeren.laufend()?.id != item.id && Hoeren.position() in 300L..(d + 5_000L)) angelaufen = true
				val t = ((System.currentTimeMillis() - start).toFloat() / d).coerceIn(0f, 1f)
				// Gleiche Leistung: Kosinus/Sinus statt geradlinig, sonst entsteht in der Mitte ein Loch
				hilfe.volume = cos(t * PI / 2).toFloat()
				pm.state.setPlayerVolume(sin(t * PI / 2).toFloat())
				if (t >= 1f) { Timber.i("Überblenden: " + "fertig, Hilfe spielt=${hilfe.isPlaying}"); break }
				// Pausiert man mitten drin, endet das alte Lied sofort
				if (angelaufen && pm.state.playState.value == PlayState.PAUSED) { Timber.i("Überblenden: " + "abgebrochen: Pause nach ${System.currentTimeMillis() - start} ms"); break }
				delay(40)
			}
		} catch (e: Exception) {
			Timber.w(e, "Überblenden")
		} finally {
			pm.state.setPlayerVolume(1f)
			hilfe.release()
		}
	}
}
