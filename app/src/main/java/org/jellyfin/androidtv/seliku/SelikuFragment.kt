package org.jellyfin.androidtv.seliku

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.fragment.compose.content
import kotlinx.coroutines.flow.filterNotNull
import org.jellyfin.androidtv.auth.repository.SessionRepository
import org.jellyfin.androidtv.auth.repository.UserRepository
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.composable.rememberQueueEntry
import org.jellyfin.androidtv.ui.navigation.ActivityDestinations
import org.jellyfin.androidtv.ui.playback.MediaManager
import org.jellyfin.androidtv.util.apiclient.getUrl
import org.jellyfin.androidtv.util.apiclient.primaryImage
import org.jellyfin.playback.core.PlaybackManager
import org.jellyfin.playback.jellyfin.queue.baseItem
import org.jellyfin.playback.jellyfin.queue.baseItemFlow
import org.jellyfin.playback.core.model.PlayState
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.api.BaseItemDto
import org.koin.compose.koinInject
import timber.log.Timber

/** Hauptbildschirm der Seliku-Oberfläche: Seitenleiste links, Inhalt rechts, Mini-Player unten. */
class SelikuFragment : Fragment() {
	private val vm by viewModels<SelikuViewModel>()

	override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?) = content {
		JellyfinTheme {
			Oberflaeche(vm)
		}
	}
}

private data class Eintrag(val symbol: String, val titel: String, val seite: Seite?, val aktion: (() -> Unit)? = null)

@Composable
private fun Oberflaeche(vm: SelikuViewModel) {
	val daten = koinInject<SelikuDaten>()
	val context = LocalContext.current
	var bibliotheken by remember { mutableStateOf<List<BaseItemDto>>(emptyList()) }
	var leisteOffen by remember { mutableStateOf(false) }
	val leiste = remember { FocusRequester() }
	val inhalt = remember { FocusRequester() }

	LaunchedEffect(Unit) {
		bibliotheken = runCatching { daten.bibliotheken() }.onFailure { Timber.w(it, "Bibliotheken") }.getOrDefault(emptyList())
	}

	val eintraege = buildList {
		add(Eintrag("⌂", "Startseite", Seite.Start))
		add(Eintrag("⌕", "Suche", Seite.Suche))
		// Feste Reihenfolge wie gewohnt: Filme, Serien, Live-TV, Musik, dann alles andere
		val rang = { b: BaseItemDto -> when (b.collectionType?.serialName) { "movies" -> 0; "tvshows" -> 1; "livetv" -> 2; "music" -> 3; else -> 4 } }
		bibliotheken.sortedBy(rang).forEach { bib ->
			when {
				bib.istLiveTv() -> add(Eintrag("◉", bib.name ?: "Live-TV", Seite.LiveTv))
				bib.istMusik() -> add(Eintrag("♪", bib.name ?: "Musik", Seite.Musik(bib)))
				else -> add(Eintrag(if (bib.collectionType?.serialName == "tvshows") "▤" else "▶", bib.name.orEmpty(), Seite.Bibliothek(bib)))
			}
		}
		add(Eintrag("⚙", "Einstellungen", null) {
			context.startActivity(ActivityDestinations.userPreferences(context))
		})
	}

	// Beim Start und nach jedem Seitenwechsel steht der Fokus im Inhalt, nicht in der Seitenleiste
	var inhaltHatFokus by remember { mutableStateOf(false) }
	LaunchedEffect(vm.aktuell.schluessel) {
		// Hat die Seite eine gemerkte Kachel, holt sie den Fokus selbst; sonst (oder wenn das nicht klappt) die erste Kachel
		val gemerkt = vm.fokus[vm.aktuell.schluessel] != null
		repeat(12) { i ->
			kotlinx.coroutines.delay(150)
			if (inhaltHatFokus) return@LaunchedEffect
			if ((!gemerkt || i >= 5) && runCatching { inhalt.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
		}
	}

	// Zurück: erst eine Seite zurück, auf einer Hauptseite in die Seitenleiste, aus der Seitenleiste beendet die App.
	BackHandler(enabled = vm.stapel.size > 1 || !leisteOffen) {
		if (!vm.zurueck()) runCatching { leiste.requestFocus() }
	}

	Box(Modifier.fillMaxSize().background(Farben.Hintergrund)) {
		Row(Modifier.fillMaxSize()) {
			Seitenleiste(
				eintraege = eintraege,
				aktiv = vm.stapel.first(),
				offen = leisteOffen,
				modifier = Modifier.focusRequester(leiste).onFocusChanged { leisteOffen = it.hasFocus },
				waehlen = { e ->
					if (e.seite != null) {
						vm.wurzel(e.seite)
						runCatching { inhalt.requestFocus() }
					} else e.aktion?.invoke()
				},
			)
			Box(Modifier.fillMaxSize().focusRequester(inhalt).onFocusChanged { inhaltHatFokus = it.hasFocus }.focusRestorer().focusGroup()) {
				val seite = vm.aktuell
				val oeffnen: (BaseItemDto) -> Unit = { item -> seiteFuer(item)?.let(vm::zu) }
				key(seite.schluessel) { SeitlichRollen(rand = 60.dp) {
					when (seite) {
						Seite.Start -> StartSeite(vm, oeffnen)
						Seite.Suche -> SuchSeite(vm, oeffnen)
						Seite.LiveTv -> LiveTvSeite(vm)
						is Seite.Bibliothek -> BibliothekSeite(vm, seite, oeffnen)
						is Seite.Musik -> MusikSeite(vm, seite, oeffnen)
						is Seite.Serie -> SerienSeite(vm, seite, oeffnen)
						is Seite.Titel -> TitelSeite(vm, seite, oeffnen)
						is Seite.Interpret -> InterpretSeite(vm, seite, oeffnen)
						is Seite.Album -> AlbumSeite(vm, seite, oeffnen)
					}
				} }
			}
		}
		MiniPlayer(Modifier.align(Alignment.BottomEnd).padding(end = 32.dp, bottom = 24.dp))
	}
}

@Composable
private fun Seitenleiste(
	eintraege: List<Eintrag>,
	aktiv: Seite,
	offen: Boolean,
	modifier: Modifier,
	waehlen: (Eintrag) -> Unit,
) {
	val api = koinInject<ApiClient>()
	val userRepository = koinInject<UserRepository>()
	val sessionRepository = koinInject<SessionRepository>()
	val mediaManager = koinInject<MediaManager>()
	val context = LocalContext.current
	val benutzer by remember { userRepository.currentUser.filterNotNull() }.collectAsState(null)
	// Beim Betreten der Seitenleiste steht der Fokus auf der aktiven Seite, nicht oben beim Profil
	val aktivAnfrage = remember { FocusRequester() }

	Column(
		modifier
			.fillMaxHeight()
			.width(if (offen) 250.dp else 76.dp)
			.animateContentSize()
			.background(if (offen) Farben.Flaeche else Farben.Hintergrund)
			.focusRestorer(aktivAnfrage)
			.focusGroup()
			.padding(vertical = 28.dp, horizontal = 12.dp),
		verticalArrangement = Arrangement.spacedBy(6.dp),
	) {
		// Benutzer oben: wechselt das Profil
		Waehlbar(onClick = {
			mediaManager.clearAudioQueue()
			sessionRepository.destroyCurrentSession()
			context.startActivity(ActivityDestinations.startup(context))
		}, vergroessern = false, shape = RoundedCornerShape(26.dp)) { fokussiert ->
			Row(Modifier.fillMaxWidth().background(if (fokussiert) Farben.FlaecheHell else Color.Transparent).padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
				Bild(benutzer?.primaryImage?.getUrl(api), Modifier.size(40.dp).clip(CircleShape))
				if (offen) {
					Spacer(Modifier.width(12.dp))
					Column {
						Text(benutzer?.name.orEmpty(), style = Schrift.Normal.copy(fontWeight = FontWeight.Bold), maxLines = 1)
						Text("Profil wechseln", style = Schrift.Klein)
					}
				}
			}
		}
		Spacer(Modifier.height(18.dp))
		eintraege.forEach { e ->
			val istAktiv = e.seite != null && e.seite.schluessel == aktiv.schluessel
			Waehlbar(onClick = { waehlen(e) }, vergroessern = false, shape = RoundedCornerShape(10.dp),
				modifier = if (istAktiv) Modifier.focusRequester(aktivAnfrage) else Modifier) { fokussiert ->
				Row(
					Modifier.fillMaxWidth()
						.background(when { fokussiert -> Farben.Text; istAktiv -> Farben.FlaecheHell; else -> Color.Transparent })
						.padding(horizontal = 14.dp, vertical = 11.dp),
					verticalAlignment = Alignment.CenterVertically,
				) {
					val farbe = when { fokussiert -> Color.Black; istAktiv -> Farben.Akzent; else -> Farben.TextLeise }
					Text(e.symbol, style = Schrift.Titel.copy(color = farbe, fontWeight = FontWeight.Normal), modifier = Modifier.width(26.dp))
					if (offen) {
						Spacer(Modifier.width(14.dp))
						Text(e.titel, style = Schrift.Normal.copy(color = if (fokussiert) Color.Black else Farben.Text, fontWeight = if (istAktiv) FontWeight.Bold else FontWeight.Normal),
							maxLines = 1, overflow = TextOverflow.Ellipsis)
					}
				}
			}
		}
	}
}

/** Mini-Player: erscheint, solange Musik läuft; Klick öffnet die Wiedergabe, Musik läuft beim Herumklicken weiter. */
@Composable
private fun MiniPlayer(modifier: Modifier) {
	val playbackManager = koinInject<PlaybackManager>()
	val abspielen = koinInject<Abspielen>()
	val bilder = koinInject<Bilder>()
	val eintrag by rememberQueueEntry(playbackManager)
	val item = eintrag?.run { baseItemFlow.collectAsState(baseItem) }?.value ?: return
	val zustand by playbackManager.state.playState.collectAsState()

	Waehlbar(onClick = { abspielen.jetztLaeuft() }, modifier = modifier, vergroessern = false, shape = RoundedCornerShape(14.dp)) { fokussiert ->
		Row(
			Modifier.width(360.dp).background(if (fokussiert) Farben.FlaecheHell else Farben.Flaeche.copy(alpha = 0.95f)).padding(10.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(12.dp),
		) {
			Bild(bilder.poster(item, 120), Modifier.size(52.dp).clip(RoundedCornerShape(6.dp)))
			Column(Modifier.weight(1f)) {
				Text(item.name.orEmpty(), style = Schrift.Normal.copy(fontWeight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
				Text(item.unterzeile(), style = Schrift.Klein, maxLines = 1, overflow = TextOverflow.Ellipsis)
			}
			if (zustand == PlayState.PLAYING) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
				repeat(2) { Box(Modifier.size(width = 5.dp, height = 18.dp).background(Farben.Akzent, RoundedCornerShape(1.dp))) }
			} else Text("▶", style = Schrift.Titel.copy(color = Farben.Akzent))
		}
	}
}
