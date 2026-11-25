package ch.seliku.handy.wiedergabe

import android.app.Activity
import android.content.pm.ActivityInfo
import android.view.ViewGroup
import android.view.WindowManager
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import ch.seliku.handy.Einstellungen
import ch.seliku.handy.Sitzung
import ch.seliku.handy.daten.Farben
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jellyfin.sdk.api.client.extensions.mediaSegmentsApi
import org.jellyfin.sdk.api.client.extensions.tvShowsApi
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.MediaSegmentDto
import org.jellyfin.sdk.model.api.MediaSegmentType
import org.jellyfin.sdk.model.api.MediaStreamType
import java.util.UUID

/**
 * Videowiedergabe fürs Handy: quer, ganzer Bildschirm. Wie auf dem Fernseher: Vorspann/Rückblick überspringen
 * (Knopf oder automatisch, Einstellung), beim Abspann die nächste Folge mit Countdown, Tonspur vom Server
 * (Standard bzw. je Serie gemerkt), Fortschritt an Jellyfin, Glas-Uhr unten links (abschaltbar).
 */
@OptIn(UnstableApi::class)
@Composable
fun VideoSpieler(startId: UUID, startMs: Long?, schliessen: () -> Unit) {
	val context = LocalContext.current
	val activity = context as Activity
	val scope = rememberCoroutineScope()
	var itemId by remember { mutableStateOf(startId) }
	var startPos by remember { mutableStateOf(startMs) }
	var item by remember { mutableStateOf<BaseItemDto?>(null) }
	var fehler by remember { mutableStateOf<String?>(null) }
	var segmente by remember { mutableStateOf<List<MediaSegmentDto>>(emptyList()) }
	var naechste by remember { mutableStateOf<BaseItemDto?>(null) }
	var pos by remember { mutableLongStateOf(0L) }
	var dauer by remember { mutableLongStateOf(0L) }
	var countdown by remember { mutableStateOf<Int?>(null) }
	var abspannWeg by remember { mutableStateOf(false) }
	var steuerungSichtbar by remember { mutableStateOf(true) }
	var umwandeln by remember { mutableStateOf(false) }
	var tonGewaehlt by remember { mutableStateOf<UUID?>(null) }

	val player = remember {
		ExoPlayer.Builder(context)
			// Ton, den das Handy nicht kann (AC3, DTS, TrueHD), dekodiert FFmpeg
			.setRenderersFactory(DefaultRenderersFactory(context).setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER))
			.setMediaSourceFactory(DefaultMediaSourceFactory(Quellen.datenquelle()))
			.setAudioAttributes(androidx.media3.common.AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
			.setSeekBackIncrementMs(10_000).setSeekForwardIncrementMs(30_000)
			.build()
	}
	var melder by remember { mutableStateOf<Melder?>(null) }
	var quelle by remember { mutableStateOf<Quelle?>(null) }

	// Quer, ganzer Bildschirm, Bildschirm bleibt an
	DisposableEffect(Unit) {
		val vorher = activity.requestedOrientation
		activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
		activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
		val ctl = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
		ctl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
		ctl.hide(WindowInsetsCompat.Type.systemBars())
		onDispose {
			melder?.ende(player.currentPosition)
			player.release()
			activity.requestedOrientation = vorher
			activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
			ctl.show(WindowInsetsCompat.Type.systemBars())
		}
	}

	// Titel laden und starten (auch beim Wechsel zur nächsten Folge)
	LaunchedEffect(itemId, umwandeln) {
		fehler = null; countdown = null; abspannWeg = false; naechste = null; segmente = emptyList()
		val api = Sitzung.api ?: return@LaunchedEffect
		val i = runCatching { api.userLibraryApi.getItem(itemId = itemId).content }.getOrElse { fehler = "Titel nicht gefunden"; return@LaunchedEffect }
		item = i
		val start = startPos ?: (i.userData?.playbackPositionTicks ?: 0L) / 10_000
		val q = runCatching { Quellen.video(i, start, umwandeln, Einstellungen.maxBitrate()) }.getOrElse { fehler = "Wiedergabe nicht möglich"; return@LaunchedEffect }
		quelle?.let { melder?.ende(player.currentPosition) }
		quelle = q
		player.setMediaItem(q.mediaItem, start)
		player.prepare(); player.playWhenReady = true
		melder = Melder(i.id, q).also { it.start(start, false) }
		segmente = runCatching { api.mediaSegmentsApi.getItemSegments(itemId = i.id).content.items }.getOrDefault(emptyList())
		if (i.type == BaseItemKind.EPISODE && i.seriesId != null) naechste = runCatching {
			api.tvShowsApi.getEpisodes(seriesId = i.seriesId!!, startItemId = i.id, limit = 2, enableUserData = true).content.items
				.firstOrNull { it.id != i.id }
		}.getOrNull()
	}

	// Ereignisse: Fehler → einmal umgewandelt versuchen; Ende → nächste Folge oder schliessen; Pause melden
	DisposableEffect(player) {
		val l = object : Player.Listener {
			override fun onPlayerError(error: PlaybackException) {
				if (!umwandeln) { startPos = player.currentPosition; umwandeln = true } else fehler = "Wiedergabe fehlgeschlagen (${error.errorCodeName})"
			}
			override fun onPlaybackStateChanged(state: Int) {
				if (state == Player.STATE_ENDED) {
					val n = naechste
					if (n != null && Einstellungen.naechsteAutomatisch()) { startPos = 0L; itemId = n.id } else schliessen()
				}
			}
			override fun onIsPlayingChanged(isPlaying: Boolean) { melder?.fortschritt(player.currentPosition, !isPlaying) }
			override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
				val i = item ?: return
				if (tracks.groups.none { it.type == C.TRACK_TYPE_AUDIO }) return
				if (tonGewaehlt != i.id) { tonGewaehlt = i.id; tonspurWaehlen(player, quelle, i); return }
				// Von Hand gewählte Sprache gilt für diese Serie (wie am Fernseher, nur bei Wahl von Hand)
				val sprache = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_AUDIO && it.isSelected }?.getTrackFormat(0)?.language
				i.seriesId?.let { s -> if (sprache != null) Einstellungen.tonspracheMerken(s, sprache) }
			}
		}
		player.addListener(l)
		onDispose { player.removeListener(l) }
	}

	// Takt: Position, Fortschritt alle 10 s, Segmente
	LaunchedEffect(player) {
		var n = 0
		while (true) {
			delay(500)
			pos = player.currentPosition; dauer = player.duration.coerceAtLeast(0)
			if (++n % 20 == 0 && player.isPlaying) melder?.fortschritt(pos, false)
			val seg = segmente.firstOrNull { pos >= it.startTicks / 10_000 && pos < it.endTicks / 10_000 - 500 }
			if (seg != null && (seg.type == MediaSegmentType.INTRO || seg.type == MediaSegmentType.RECAP) && Einstellungen.introAutomatisch()) {
				player.seekTo(seg.endTicks / 10_000)
			}
			// Abspann: nächste Folge mit Countdown (10 s), ausser man hat «Abspann ansehen» gewählt
			val imAbspann = seg?.type == MediaSegmentType.OUTRO || (dauer > 0 && segmente.none { it.type == MediaSegmentType.OUTRO } && dauer - pos < 20_000)
			if (imAbspann && naechste != null && !abspannWeg && Einstellungen.naechsteAutomatisch()) {
				val c = countdown
				if (c == null) countdown = 10 else if (n % 2 == 0) {
					if (c <= 1) { countdown = null; melder?.ende(player.currentPosition); melder = null; quelle = null; startPos = 0L; itemId = naechste!!.id } else countdown = c - 1
				}
			} else if (!imAbspann) countdown = null
		}
	}

	Box(Modifier.fillMaxSize().background(Color.Black)) {
		AndroidView(
			factory = { c ->
				PlayerView(c).apply {
					layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
					this.player = player
					setShowNextButton(false); setShowPreviousButton(false)
					setShowSubtitleButton(true)
					controllerShowTimeoutMs = 3500
					setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v -> steuerungSichtbar = v == android.view.View.VISIBLE })
				}
			},
			modifier = Modifier.fillMaxSize(),
		)
		item?.let { i ->
			AnimatedVisibility(steuerungSichtbar, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopStart)) {
				Column(Modifier.padding(start = 24.dp, top = 16.dp)) {
					Text(if (i.type == BaseItemKind.EPISODE) i.seriesName.orEmpty() else i.name.orEmpty(), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
					if (i.type == BaseItemKind.EPISODE) Text("S${i.parentIndexNumber ?: 0}:F${i.indexNumber ?: 0} · ${i.name.orEmpty()}", color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp)
				}
			}
		}
		if (player.playbackState == Player.STATE_BUFFERING && pos == 0L) CircularProgressIndicator(Modifier.align(Alignment.Center), color = Farben.Akzent)
		fehler?.let { Text(it, color = Color.White, modifier = Modifier.align(Alignment.Center)) }

		// Überspringen-Knopf (Vorspann, Rückblick, Vorschau) – unten rechts wie beim Fernseher
		val seg = segmente.firstOrNull { pos >= it.startTicks / 10_000 && pos < it.endTicks / 10_000 - 1000 }
		val knopf = when (seg?.type) {
			MediaSegmentType.INTRO -> "Vorspann überspringen"
			MediaSegmentType.RECAP -> "Rückblick überspringen"
			MediaSegmentType.PREVIEW -> "Vorschau überspringen"
			MediaSegmentType.COMMERCIAL -> "Werbung überspringen"
			MediaSegmentType.OUTRO -> if (naechste == null) "Abspann überspringen" else null
			else -> null
		}
		Box(Modifier.align(Alignment.BottomEnd).padding(end = 24.dp, bottom = 96.dp)) {
			val c = countdown
			if (c != null && naechste != null) {
				Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
					Button({ abspannWeg = true; countdown = null }, colors = ButtonDefaults.buttonColors(containerColor = Color.Black.copy(alpha = 0.6f), contentColor = Color.White)) { Text("Abspann ansehen") }
					Button({ countdown = null; melder?.ende(player.currentPosition); melder = null; quelle = null; startPos = 0L; itemId = naechste!!.id },
						shape = RoundedCornerShape(8.dp)) { Text("Nächste Folge in $c") }
				}
			} else if (knopf != null && seg != null) {
				Button({ player.seekTo(if (seg.type == MediaSegmentType.OUTRO) player.duration else seg.endTicks / 10_000) }, shape = RoundedCornerShape(8.dp)) { Text(knopf) }
			}
		}
		if (Einstellungen.uhrImFilm() && !steuerungSichtbar) GlasUhr(Modifier.align(Alignment.BottomStart).padding(start = 20.dp, bottom = 18.dp))
	}
}

/** Tonspur wie der Server sie vorschlägt (Benutzer-Sprache) bzw. die je Serie von Hand gewählte (TV-Logik). */
@OptIn(UnstableApi::class)
private fun tonspurWaehlen(player: ExoPlayer, quelle: Quelle?, item: BaseItemDto?) {
	val ms = quelle?.info ?: return
	if (quelle.methode != org.jellyfin.sdk.model.api.PlayMethod.DIRECT_PLAY) return
	val audio = ms.mediaStreams.orEmpty().filter { it.type == MediaStreamType.AUDIO && !it.isExternal }
	val gemerkt = item?.seriesId?.let { Einstellungen.tonspracheFuer(it) }
	val ziel = gemerkt?.let { s -> audio.indexOfFirst { it.language.equals(s, true) }.takeIf { it >= 0 } }
		?: audio.indexOfFirst { it.index == ms.defaultAudioStreamIndex }.takeIf { it >= 0 } ?: return
	val gruppen = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
	if (ziel >= gruppen.size) return
	val g = gruppen[ziel]
	if (g.isSelected) return
	player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
		.setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, 0)).build()
}
