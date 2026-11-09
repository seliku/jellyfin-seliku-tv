package org.jellyfin.androidtv.seliku

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.fragment.compose.content
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.ScreensaverLock
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.androidtv.ui.playback.AudioNowPlayingFragment
import org.jellyfin.playback.core.PlaybackManager
import org.jellyfin.playback.core.model.PlayState
import org.jellyfin.playback.core.model.PlaybackOrder
import org.jellyfin.playback.core.model.RepeatMode
import org.jellyfin.playback.core.queue.queue
import org.jellyfin.playback.jellyfin.queue.baseItem
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.MediaType
import org.koin.compose.koinInject

/**
 * «Läuft gerade» für Musik, Hörbücher und Podcasts. Ist der neue Player abgeschaltet, zeigt die Seite Jellyfins alten.
 */
class SelikuSpielerFragment : Fragment() {
	override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
		if (!SelikuEinstellungen.neuerAudioPlayer(requireContext())) {
			val rahmen = FrameLayout(requireContext()).apply { id = View.generateViewId() }
			if (savedInstanceState == null) childFragmentManager.beginTransaction().replace(rahmen.id, AudioNowPlayingFragment()).commit()
			return rahmen
		}
		return content { JellyfinTheme { Spieler() } }
	}
}

@Composable
private fun Spieler() {
	val pm = koinInject<PlaybackManager>()
	val bilder = koinInject<Bilder>()
	val navigation = koinInject<NavigationRepository>()
	val daten = koinInject<SelikuDaten>()
	val context = LocalContext.current
	val scope = rememberCoroutineScope()
	val entry by pm.queue.entry.collectAsState()
	val item = entry?.baseItem?.takeIf { it.mediaType == MediaType.AUDIO }
	val zustand by pm.state.playState.collectAsState()
	val tempo by pm.state.speed.collectAsState()
	val reihenfolge by pm.state.playbackOrder.collectAsState()
	val wiederholen by pm.state.repeatMode.collectAsState()
	val schlaf by Hoeren.schlaf.collectAsState()
	var pos by remember { mutableStateOf(0L) }
	var dauer by remember { mutableStateOf(0L) }
	var uhr by remember { mutableStateOf("") }
	LaunchedEffect(Unit) {
		while (true) {
			pos = Hoeren.position(); dauer = Hoeren.dauer()
			uhr = java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
			delay(300)
		}
	}
	// Nichts mehr in der Warteschlange: zurück, woher man kam
	LaunchedEffect(item == null) {
		if (item == null) { delay(1_500); if (Hoeren.laufend() == null) navigation.goBack() }
	}

	// Ruhebildschirm wie beim alten Player: nach 20 s ohne Taste dunkel bzw. Diashow; die erste Taste weckt nur auf
	var modus by remember { mutableStateOf(SelikuMusikBildschirm.aktuell(context)) }
	var letzteTaste by remember { mutableStateOf(System.currentTimeMillis()) }
	var ruht by remember { mutableStateOf(false) }
	var schlucken by remember { mutableStateOf(false) }
	LaunchedEffect(modus, letzteTaste) {
		ruht = false
		if (modus == MusikBildschirm.NORMAL) return@LaunchedEffect
		delay(SelikuMusikBildschirm.WARTEZEIT_MS)
		ruht = true
	}
	val view = LocalView.current
	DisposableEffect(Unit) { view.keepScreenOn = true; onDispose { view.keepScreenOn = false } }
	// Tasten am Fenster mitlesen (wie beim alten Player): läuft im Ruhebildschirm ein Trailer, hat dessen Ansicht den Fokus
	// und Compose sieht die Taste nie. Die erste Taste weckt nur auf; Medientasten wirken je nach Art (Kapitel, 30 s …).
	DisposableEffect(view) {
		var c = view.context
		while (c is android.content.ContextWrapper && c !is android.app.Activity) c = c.baseContext
		val fenster = (c as? android.app.Activity)?.window ?: return@DisposableEffect onDispose {}
		val alt = fenster.callback
		val neu = object : android.view.Window.Callback by alt {
			override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
				letzteTaste = System.currentTimeMillis()
				if (ruht && event.action == android.view.KeyEvent.ACTION_DOWN) { schlucken = true; ruht = false; return true }
				if (schlucken) { if (event.action == android.view.KeyEvent.ACTION_UP) schlucken = false; return true }
				if (Hoeren.taste(event)) return true
				return alt.dispatchKeyEvent(event)
			}
		}
		fenster.callback = neu
		onDispose { if (fenster.callback === neu) fenster.callback = alt }
	}
	ScreensaverLock(enabled = true)

	// Beim Öffnen steht der Fokus auf Abspielen/Pause (die Seite ist anfangs noch nicht ganz aufgebaut: ein paarmal versuchen)
	val start = remember { FocusRequester() }
	var startFokus by remember { mutableStateOf(false) }
	LaunchedEffect(item != null) {
		var versuche = 0
		while (item != null && !startFokus && versuche++ < 15) { delay(200); runCatching { start.requestFocus() } }
	}

	val art = Hoeren.art(item)
	val (zurueckS, vorS) = Hoeren.sprung(art)
	val buch by produceState<Buch?>(null, item?.id, art) { value = if (art == HoerArt.HOERBUCH && item != null) Hoeren.buchVon(item) else null }
	var favorit by remember(item?.id) { mutableStateOf(item?.userData?.isFavorite == true) }

	Box(
		Modifier.fillMaxSize().background(Farben.Hintergrund)
	) {
		if (item == null) return@Box
		// Hintergrund: das Cover gross und unscharf, abgedunkelt (wie Plexamp)
		val cover = if (art == HoerArt.HOERBUCH) buch?.let { bilder.eigenesBild(it.ordner, 600) } else bilder.poster(item, 600)
		Bild(cover, Modifier.fillMaxSize().blur(48.dp).graphicsLayer { alpha = 0.45f }, grund = Farben.Hintergrund)
		Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0x88000000), Color(0xDD14100E)))))

		Column(Modifier.fillMaxSize().padding(start = 64.dp, end = 64.dp, top = 48.dp)) {
			Row(verticalAlignment = Alignment.CenterVertically) {
				Bild(cover, Modifier.size(240.dp).clip(RoundedCornerShape(10.dp)), ersatz = if (art == HoerArt.MUSIK) "♫" else "◉")
				Spacer(Modifier.width(40.dp))
				Column(Modifier.weight(1f).padding(top = 24.dp)) {
					val ober = when (art) {
						HoerArt.HOERBUCH -> buch?.ordner?.name ?: item.album
						HoerArt.PODCAST -> item.album
						HoerArt.MUSIK -> listOfNotNull(item.album, item.productionYear?.toString()).joinToString(" · ")
					}
					if (!ober.isNullOrBlank()) Text(ober, style = Schrift.Leise, maxLines = 1, overflow = TextOverflow.Ellipsis)
					Spacer(Modifier.height(4.dp))
					Text(item.name.orEmpty(), style = Schrift.Riesig, maxLines = 2, overflow = TextOverflow.Ellipsis)
					Spacer(Modifier.height(6.dp))
					val unter = when (art) {
						HoerArt.MUSIK -> (item.artists?.joinToString(", ") ?: item.albumArtist).orEmpty()
						HoerArt.PODCAST -> item.premiereDate?.let { datumLang(it.toLocalDate()) }.orEmpty()
						HoerArt.HOERBUCH -> buch?.let { b ->
							val nr = b.index(item.id)
							if (nr < 0) "" else stringResource(R.string.seliku_audio_chapter_of, nr + 1, b.dateien.size)
						}.orEmpty()
					}
					if (unter.isNotBlank()) Text(unter, style = Schrift.Reihe.copy(color = Farben.TextLeise), maxLines = 1, overflow = TextOverflow.Ellipsis)
					Spacer(Modifier.height(22.dp))
					// Fortschritt: Titel bzw. Kapitel; beim Hörbuch darunter das ganze Buch
					Fortschritt(if (dauer > 0) pos.toFloat() / dauer else 0f, Modifier.clip(RoundedCornerShape(2.dp)))
					Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
						Text(zeit(pos), style = Schrift.Klein)
						Spacer(Modifier.weight(1f))
						Text(if (dauer > 0) "-" + zeit(dauer - pos) else "", style = Schrift.Klein)
					}
					buch?.let { b ->
						val nr = b.index(item.id)
						if (nr >= 0) {
							val gesamt = b.gesamtMs.coerceAtLeast(1)
							val bis = b.bisMs(nr, pos)
							Text(stringResource(R.string.seliku_audio_book_progress, (bis * 100 / gesamt).toInt(), dauerText(((gesamt - bis) / tempo).toLong())),
								style = Schrift.Klein.copy(color = Farben.Akzent), modifier = Modifier.padding(top = 2.dp))
						}
					}
					Spacer(Modifier.height(20.dp))
					// Steuerung: links die Wiedergabe, rechts das, was zur Art passt
					Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
						val spielt = zustand == PlayState.PLAYING
						if (art == HoerArt.MUSIK) {
							SymbolKnopf(R.drawable.ic_previous, stringResource(R.string.seliku_audio_previous), onClick = Hoeren::zurueck)
						} else {
							if (art == HoerArt.HOERBUCH) SymbolKnopf(R.drawable.ic_previous, stringResource(R.string.seliku_audio_previous_chapter), onClick = Hoeren::zurueck)
							SymbolKnopf(R.drawable.ic_rewind, stringResource(R.string.seliku_audio_back_seconds, zurueckS), text = zurueckS.toString(), onClick = { Hoeren.springen(-zurueckS) })
						}
						SymbolKnopf(if (spielt) R.drawable.ic_pause else R.drawable.ic_play, stringResource(if (spielt) R.string.seliku_audio_pause else R.string.seliku_audio_play),
							groesse = 58.dp, hervor = true, modifier = Modifier.focusRequester(start).onFocusChanged { if (it.hasFocus) startFokus = true }, onClick = Hoeren::spielenPause)
						if (art != HoerArt.MUSIK) SymbolKnopf(R.drawable.ic_fast_forward, stringResource(R.string.seliku_audio_forward_seconds, vorS), text = vorS.toString(), onClick = { Hoeren.springen(vorS) })
						SymbolKnopf(R.drawable.ic_next, stringResource(when (art) {
							HoerArt.MUSIK -> R.string.seliku_audio_next
							HoerArt.HOERBUCH -> R.string.seliku_audio_next_chapter
							HoerArt.PODCAST -> R.string.seliku_audio_next_episode
						}), onClick = Hoeren::vor)
						Spacer(Modifier.width(14.dp))
						when (art) {
							HoerArt.MUSIK -> {
								SymbolKnopf(R.drawable.ic_shuffle, stringResource(R.string.seliku_audio_shuffle), aktiv = reihenfolge != PlaybackOrder.DEFAULT, onClick = {
									pm.state.setPlaybackOrder(if (reihenfolge == PlaybackOrder.DEFAULT) PlaybackOrder.SHUFFLE else PlaybackOrder.DEFAULT)
								})
								SymbolKnopf(R.drawable.ic_loop, stringResource(R.string.seliku_audio_repeat), aktiv = wiederholen != RepeatMode.NONE, onClick = {
									pm.state.setRepeatMode(if (wiederholen == RepeatMode.NONE) RepeatMode.REPEAT_ENTRY_INFINITE else RepeatMode.NONE)
								})
								SymbolKnopf(R.drawable.ic_heart, stringResource(if (favorit) R.string.seliku_tip_favorite_remove else R.string.seliku_tip_favorite_add), aktiv = favorit, onClick = {
									favorit = !favorit
									scope.launch { runCatching { daten.favorit(item.id, favorit) } }
								})
							}
							else -> {
								SymbolKnopf(R.drawable.ic_playback_speed, stringResource(R.string.seliku_audio_speed), text = tempoText(tempo), aktiv = tempo != 1f, onClick = Hoeren::tempoWeiter)
								SymbolKnopf(R.drawable.seliku_mond, schlafText(schlaf, art), text = schlaf?.let { if (it.endeVon != null) "⏹" else restMinuten(it).toString() },
									aktiv = schlaf != null, onClick = Hoeren::schlafWeiter)
								if (art == HoerArt.PODCAST) SymbolKnopf(R.drawable.ic_check, stringResource(R.string.seliku_audio_mark_heard), onClick = {
									scope.launch { Hoeren.folgeGehoert(item.id, true); Hoeren.vor() }
								})
							}
						}
						SymbolKnopf(R.drawable.seliku_bildschirm, stringResource(R.string.seliku_screen) + ": " + stringResource(modus.titel), aktiv = modus != MusikBildschirm.NORMAL, onClick = {
							modus = SelikuMusikBildschirm.weiter(context)
						})
					}
				}
			}
			Spacer(Modifier.height(30.dp))
			Unten(item, art, buch)
		}

		Column(Modifier.align(Alignment.TopEnd).padding(top = 28.dp, end = 48.dp), horizontalAlignment = Alignment.End) {
			Text(uhr, style = Schrift.Titel.copy(color = Farben.TextLeise))
			if (art == HoerArt.MUSIK && SelikuEinstellungen.endlosMusik(context))
				Text("∞ " + stringResource(R.string.seliku_audio_endless_on), style = Schrift.Klein, modifier = Modifier.padding(top = 4.dp))
		}

		AnimatedVisibility(ruht, enter = fadeIn(tween(1500)), exit = fadeOut(tween(400))) {
			SelikuMusikBildschirm.Ebene(modus)
		}
	}
}

/** Unten: Kapitel (Hörbuch), weitere Folgen (Podcast) oder «Als Nächstes» (Musik); OK springt dorthin. */
@Composable
private fun Unten(item: BaseItemDto, art: HoerArt, buch: Buch?) {
	val pm = koinInject<PlaybackManager>()
	val scope = rememberCoroutineScope()
	@Suppress("UNUSED_VARIABLE") val index by pm.queue.entryIndex.collectAsState()
	val liste = if (art == HoerArt.HOERBUCH && buch != null && Hoeren.liste.none { it.id == buch.dateien.first().id }) buch.dateien else Hoeren.liste
	val jetzt = liste.indexOfFirst { it.id == item.id }.coerceAtLeast(0)
	val zeigen = if (art == HoerArt.MUSIK) liste.drop(jetzt + 1).take(40) else liste
	if (zeigen.isEmpty()) return
	val titel = stringResource(when (art) {
		HoerArt.HOERBUCH -> R.string.seliku_audio_chapters
		HoerArt.PODCAST -> R.string.seliku_audio_more_episodes
		HoerArt.MUSIK -> R.string.seliku_audio_up_next
	})
	Text(titel, style = Schrift.Reihe, modifier = Modifier.padding(bottom = 8.dp))
	val rolle = rememberLazyListState()
	LaunchedEffect(jetzt, art) { if (art != HoerArt.MUSIK) runCatching { rolle.scrollToItem((jetzt - 1).coerceAtLeast(0)) } }
	LazyRow(state = rolle, horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(end = 64.dp, top = 6.dp, bottom = 6.dp)) {
		itemsIndexed(zeigen, key = { _, t -> t.id }) { n, t ->
			val nr = if (art == HoerArt.MUSIK) jetzt + 1 + n else n
			val laeuft = t.id == item.id
			val vorbei = art != HoerArt.MUSIK && nr < jetzt
			Waehlbar(onClick = {
				if (liste === Hoeren.liste) Hoeren.springeZu(nr)
				else buch?.let { b -> scope.launch { Hoeren.buchSpielen(b.id, nr) } }
			}, vergroessern = false) { fokussiert ->
				Column(Modifier.width(200.dp).height(64.dp)
					.background(if (fokussiert) Farben.FlaecheHell else Farben.Flaeche.copy(alpha = 0.8f))
					.padding(horizontal = 12.dp, vertical = 8.dp)) {
					// Was gerade läuft: ▶ und Akzentfarbe (der Rahmen gehört dem Fokus)
					Text((if (laeuft) "▶ " else "") + (if (art == HoerArt.MUSIK) "" else "${nr + 1}  ") + t.name.orEmpty(),
						style = Schrift.Normal.copy(fontWeight = if (laeuft) FontWeight.Bold else FontWeight.Normal, color = when { laeuft -> Farben.Akzent; vorbei -> Farben.TextLeise; else -> Farben.Text }),
						maxLines = 1, overflow = TextOverflow.Ellipsis)
					Spacer(Modifier.weight(1f))
					Text(listOfNotNull(
						if (art == HoerArt.MUSIK) (t.artists?.firstOrNull() ?: t.albumArtist) else null,
						if (art == HoerArt.PODCAST) t.premiereDate?.let { datumKurz(it.toLocalDate()) } else null,
						laufzeitKurz(t.runTimeTicks).ifBlank { null },
						if (vorbei || (art == HoerArt.PODCAST && Hoeren.folgeGehoert(t))) "✓" else null,
					).joinToString("  ·  "), style = Schrift.Klein, maxLines = 1, overflow = TextOverflow.Ellipsis)
				}
			}
		}
	}
}

private fun zeit(ms: Long): String {
	val s = (ms / 1000).coerceAtLeast(0)
	return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

@Composable
fun dauerText(ms: Long): String {
	val min = (ms / 60_000L).toInt().coerceAtLeast(1)
	return if (min >= 60) stringResource(R.string.seliku_runtime_hours, min / 60, min % 60) else stringResource(R.string.seliku_runtime_minutes, min)
}

private fun tempoText(t: Float) = String.format(java.util.Locale.GERMAN, "%.2f", t).trimEnd('0').let { if (it.endsWith(',')) it + "0" else it } + "×"

private fun restMinuten(t: SchlafTimer) = ((t.bis ?: 0L) - System.currentTimeMillis()).coerceAtLeast(0L).let { ((it + 59_999L) / 60_000L).toInt() }

@Composable
private fun schlafText(t: SchlafTimer?, art: HoerArt): String = when {
	t == null -> stringResource(R.string.seliku_audio_sleep_off)
	t.endeVon != null -> stringResource(if (art == HoerArt.HOERBUCH) R.string.seliku_audio_sleep_end_chapter else R.string.seliku_audio_sleep_end_episode)
	else -> stringResource(R.string.seliku_audio_sleep_in, restMinuten(t))
}

fun datumLang(d: java.time.LocalDate): String = d.format(java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.LONG))
fun datumKurz(d: java.time.LocalDate): String = d.format(java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM))
