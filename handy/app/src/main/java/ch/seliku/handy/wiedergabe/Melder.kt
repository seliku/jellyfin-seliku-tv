package ch.seliku.handy.wiedergabe

import ch.seliku.handy.Sitzung
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.jellyfin.sdk.api.client.extensions.hlsSegmentApi
import org.jellyfin.sdk.api.client.extensions.playStateApi
import org.jellyfin.sdk.model.api.PlayMethod
import org.jellyfin.sdk.model.api.PlaybackOrder
import org.jellyfin.sdk.model.api.PlaybackProgressInfo
import org.jellyfin.sdk.model.api.PlaybackStartInfo
import org.jellyfin.sdk.model.api.PlaybackStopInfo
import org.jellyfin.sdk.model.api.RepeatMode
import java.util.UUID

/** Meldet Start, Fortschritt und Ende an Jellyfin (davon leben Weiterschauen, «gesehen» und WatchState). */
class Melder(private val itemId: UUID, private val quelle: Quelle) {
	// Eigener Bereich: das Ende muss auch ankommen, wenn der Bildschirm schon weg ist
	private val bereich = CoroutineScope(SupervisorJob() + Dispatchers.IO)

	fun start(posMs: Long, pause: Boolean) = bereich.launch {
		runCatching {
			Sitzung.api?.playStateApi?.reportPlaybackStart(PlaybackStartInfo(
				itemId = itemId, positionTicks = posMs * 10_000, canSeek = true, isPaused = pause, isMuted = false,
				playSessionId = quelle.playSessionId, mediaSourceId = quelle.mediaSourceId, playMethod = quelle.methode,
				repeatMode = RepeatMode.REPEAT_NONE, playbackOrder = PlaybackOrder.DEFAULT,
			))
		}
	}

	fun fortschritt(posMs: Long, pause: Boolean) = bereich.launch {
		runCatching {
			Sitzung.api?.playStateApi?.reportPlaybackProgress(PlaybackProgressInfo(
				itemId = itemId, positionTicks = posMs * 10_000, canSeek = true, isPaused = pause, isMuted = false,
				playSessionId = quelle.playSessionId, mediaSourceId = quelle.mediaSourceId, playMethod = quelle.methode,
				repeatMode = RepeatMode.REPEAT_NONE, playbackOrder = PlaybackOrder.DEFAULT,
			))
		}
	}

	fun ende(posMs: Long) = bereich.launch {
		runCatching {
			Sitzung.api?.playStateApi?.reportPlaybackStopped(PlaybackStopInfo(
				itemId = itemId, positionTicks = posMs * 10_000, playSessionId = quelle.playSessionId,
				mediaSourceId = quelle.mediaSourceId, failed = false,
			))
		}
		// Umgewandelte Wiedergabe: Umwandlung auf dem Server beenden
		if (quelle.methode == PlayMethod.TRANSCODE) runCatching {
			Sitzung.api?.hlsSegmentApi?.stopEncodingProcess(deviceId = Sitzung.geraeteId, playSessionId = quelle.playSessionId ?: "")
		}
	}
}
