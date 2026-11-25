package ch.seliku.handy.musik

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import ch.seliku.handy.Einstellungen
import ch.seliku.handy.MainActivity
import ch.seliku.handy.Sitzung
import ch.seliku.handy.wiedergabe.Quellen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jellyfin.sdk.api.client.extensions.instantMixApi
import org.jellyfin.sdk.api.client.extensions.playStateApi
import org.jellyfin.sdk.model.api.PlayMethod
import org.jellyfin.sdk.model.api.PlaybackOrder
import org.jellyfin.sdk.model.api.PlaybackProgressInfo
import org.jellyfin.sdk.model.api.PlaybackStartInfo
import org.jellyfin.sdk.model.api.PlaybackStopInfo
import org.jellyfin.sdk.model.api.RepeatMode
import java.util.UUID

/**
 * Musik und Radio im Hintergrund (Benachrichtigung, Sperrbildschirm, Kopfhörer-Tasten). Meldet Jellyfin-Titel an
 * den Server (Start/Fortschritt/Ende) und hängt wie am TV den Endlos-Mix an, wenn die Warteschlange ausläuft.
 */
class MusikDienst : MediaSessionService() {
	private var session: MediaSession? = null
	private lateinit var player: ExoPlayer
	private val bereich = CoroutineScope(SupervisorJob() + Dispatchers.Main)
	private var gemeldet: Pair<UUID, Long>? = null
	private var mixLaeuft = false

	@OptIn(UnstableApi::class)
	override fun onCreate() {
		super.onCreate()
		player = ExoPlayer.Builder(this)
			.setRenderersFactory(DefaultRenderersFactory(this).setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON))
			.setMediaSourceFactory(DefaultMediaSourceFactory(Quellen.datenquelle()))
			.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
			.setHandleAudioBecomingNoisy(true)
			.build()
		val oeffnen = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).putExtra("laeuft", true),
			PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
		session = MediaSession.Builder(this, player).setSessionActivity(oeffnen).build()
		player.addListener(object : Player.Listener {
			override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { melden(); endlos() }
			override fun onIsPlayingChanged(isPlaying: Boolean) {
				fortschritt(!isPlaying)
				if (!isPlaying) hoerArt()?.let { merken(it); bereich.launch { Hoeren.hochladen() } }
			}
			override fun onPlaybackStateChanged(state: Int) {
				// Hörbuch fertig, wenn die letzte Datei endet
				val art = hoerArt()
				if (state == Player.STATE_ENDED && art?.startsWith("b:") == true) {
					Hoeren.setzen(art, null); Hoeren.setzen("f:" + art.drop(2), System.currentTimeMillis().toString()); bereich.launch { Hoeren.hochladen() }
				}
			}
		})
		bereich.launch { while (true) { delay(10_000); if (player.isPlaying) fortschritt(false); if (hoerArt() == null) endlos() } }
		// Hören: Stelle alle 5 s merken, alle 30 s hochladen; Schlaf-Timer
		bereich.launch {
			var n = 0
			while (true) {
				delay(1000); n++
				if (Musik.schlafBis in 1..System.currentTimeMillis()) { Musik.schlafBis = 0; player.pause() }
				val art = hoerArt() ?: continue
				if (!player.isPlaying) continue
				if (n % 5 == 0) merken(art)
				if (n % 30 == 0) Hoeren.hochladen()
			}
		}
	}

	private fun hoerArt(): String? = player.currentMediaItem?.mediaMetadata?.extras?.getString("hoer")

	/** Stelle merken; Podcast-Folge ab 95 % bzw. 45 s vor Schluss als gehört (wie Pocket Casts). */
	private fun merken(art: String) {
		val id = jellyfinId(player.currentMediaItem) ?: return
		val pos = player.currentPosition; val d = player.duration; val jetzt = System.currentTimeMillis()
		if (art.startsWith("b:")) Hoeren.setzen(art, "$id|$pos|$jetzt")
		else if (d > 0 && (pos >= d * 0.95 || d - pos < 45_000)) {
			if (Hoeren.wert("p:$id") != null) { Hoeren.setzen("p:$id", null); bereich.launch(Dispatchers.IO) { runCatching { Sitzung.api!!.playStateApi.markPlayedItem(itemId = id) } } }
		} else Hoeren.setzen("p:$id", "$pos|$jetzt")
	}

	private fun jellyfinId(m: MediaItem?): UUID? = m?.mediaId?.takeIf { !it.startsWith("radio:") }?.let { runCatching { UUID.fromString(it) }.getOrNull() }

	private fun melden() {
		val api = Sitzung.api ?: return
		val alt = gemeldet
		val neu = jellyfinId(player.currentMediaItem)
		bereich.launch(Dispatchers.IO) {
			if (alt != null) runCatching { api.playStateApi.reportPlaybackStopped(PlaybackStopInfo(itemId = alt.first, positionTicks = alt.second * 10_000, failed = false)) }
			if (neu != null) runCatching { api.playStateApi.reportPlaybackStart(PlaybackStartInfo(itemId = neu, canSeek = true, isPaused = false, isMuted = false,
				playMethod = PlayMethod.DIRECT_PLAY, repeatMode = RepeatMode.REPEAT_NONE, playbackOrder = PlaybackOrder.DEFAULT, positionTicks = 0)) }
		}
		gemeldet = neu?.let { it to 0L }
	}

	private fun fortschritt(pause: Boolean) {
		val id = jellyfinId(player.currentMediaItem) ?: return
		val pos = player.currentPosition
		gemeldet = id to pos
		val api = Sitzung.api ?: return
		bereich.launch(Dispatchers.IO) {
			runCatching { api.playStateApi.reportPlaybackProgress(PlaybackProgressInfo(itemId = id, positionTicks = pos * 10_000, canSeek = true, isPaused = pause,
				isMuted = false, playMethod = PlayMethod.DIRECT_PLAY, repeatMode = RepeatMode.REPEAT_NONE, playbackOrder = PlaybackOrder.DEFAULT)) }
		}
	}

	/** Endlos-Mix wie Plexamp: in den letzten 20 s ohne Nachfolger passende Titel anhängen (höchstens 25, ohne Gespieltes). */
	private fun endlos() {
		if (!Einstellungen.an("endlos_musik") || mixLaeuft || player.repeatMode != Player.REPEAT_MODE_OFF) return
		if (player.currentMediaItemIndex < player.mediaItemCount - 1) return
		val id = jellyfinId(player.currentMediaItem) ?: return
		val rest = player.duration - player.currentPosition
		if (player.duration > 0 && rest > 20_000) return
		mixLaeuft = true
		bereich.launch {
			val da = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }.toSet()
			val neu = runCatching { kotlinx.coroutines.withContext(Dispatchers.IO) { Sitzung.api!!.instantMixApi.getInstantMixFromItem(itemId = id, limit = 50).content.items } }
				.getOrDefault(emptyList()).filter { it.type == org.jellyfin.sdk.model.api.BaseItemKind.AUDIO && it.id.toString() !in da }.take(25)
			if (neu.isNotEmpty()) { Musik.endlosAktiv = true; player.addMediaItems(neu.map { Musik.mediaItem(it) }) }
			mixLaeuft = false
		}
	}

	override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

	override fun onTaskRemoved(rootIntent: Intent?) {
		if (!player.playWhenReady || player.mediaItemCount == 0) stopSelf()
	}

	override fun onDestroy() {
		CoroutineScope(Dispatchers.IO).launch { Hoeren.hochladen() }
		gemeldet?.let { (id, pos) -> Sitzung.api?.let { api -> CoroutineScope(Dispatchers.IO).launch { runCatching { api.playStateApi.reportPlaybackStopped(PlaybackStopInfo(itemId = id, positionTicks = pos * 10_000, failed = false)) } } } }
		session?.run { player.release(); release() }
		session = null
		super.onDestroy()
	}
}
