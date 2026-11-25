package ch.seliku.handy.wiedergabe

import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import ch.seliku.handy.Sitzung
import ch.seliku.handy.musik.Musik
import org.jellyfin.sdk.api.client.extensions.libraryApi
import java.util.UUID

/** Titelmusik auf Serienseiten wie am TV: leise (35 %), ohne Audio-Fokus, nie wenn Musik oder Radio läuft. */
@OptIn(UnstableApi::class)
@Composable
fun TitelMusik(serie: UUID) {
	val c = LocalContext.current
	val player = remember {
		ExoPlayer.Builder(c).setMediaSourceFactory(DefaultMediaSourceFactory(Quellen.datenquelle()))
			.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), false)
			.build().apply { volume = 0.35f }
	}
	LaunchedEffect(serie) {
		if (Musik.laeuft) return@LaunchedEffect
		val lied = runCatching { Sitzung.api!!.libraryApi.getThemeSongs(itemId = serie, inheritFromParent = true).content.items.firstOrNull() }.getOrNull() ?: return@LaunchedEffect
		player.setMediaItem(MediaItem.fromUri("${Sitzung.server}/Audio/${lied.id}/stream?static=true"))
		player.prepare(); player.play()
	}
	DisposableEffect(Unit) { onDispose { player.release() } }
}
