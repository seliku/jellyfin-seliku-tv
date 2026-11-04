package org.jellyfin.androidtv.seliku

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.fragment.compose.content
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import androidx.lifecycle.repeatOnLifecycle
import org.jellyfin.androidtv.auth.repository.SessionRepository
import org.jellyfin.androidtv.auth.repository.UserRepository
import org.jellyfin.androidtv.R
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

private data class Eintrag(val symbol: Int, val titel: String, val seite: Seite?, val hervor: Boolean = false, val aktion: (() -> Unit)? = null)

/** Seite und Symbol je Bibliotheksart. */
fun seiteFuerBibliothek(bib: BaseItemDto): Seite = when (bib.collectionType?.serialName) {
	"livetv" -> Seite.LiveTv
	"music" -> Seite.Musik(bib)
	"movies", "tvshows", "boxsets", "musicvideos" -> Seite.Bibliothek(bib)
	else -> Seite.Ordner(bib.id, bib.name.orEmpty())
}

private fun symbolFuer(bib: BaseItemDto): Int = when (bib.collectionType?.serialName) {
	"movies" -> R.drawable.ic_movie
	"tvshows" -> R.drawable.ic_tv
	"livetv" -> R.drawable.ic_tv_guide
	"music" -> if (bib.name.orEmpty().contains("podcast", true)) R.drawable.ic_microphone else R.drawable.ic_music_album
	"books" -> R.drawable.seliku_buch
	"homevideos", "photos" -> R.drawable.ic_camera
	"playlists" -> R.drawable.ic_mix
	"boxsets" -> R.drawable.ic_grid
	else -> R.drawable.ic_folder
}

@Composable
private fun Oberflaeche(vm: SelikuViewModel) {
	val daten = koinInject<SelikuDaten>()
	val context = LocalContext.current
	var bibliotheken by remember { mutableStateOf<List<BaseItemDto>>(emptyList()) }
	var leisteOffen by remember { mutableStateOf(false) }
	val leiste = remember { FocusRequester() }
	val inhalt = remember { FocusRequester() }

	var neuLaden by remember { mutableStateOf(0) }
	LaunchedEffect(neuLaden) {
		bibliotheken = runCatching { daten.bibliotheken() }.onFailure { Timber.w(it, "Bibliotheken") }.getOrDefault(emptyList())
		vm.wahl.remove("start-reihen")
	}

	// Vorladen: Startseite zuerst, danach mit kurzer Verzögerung die anderen Bibliotheken im Hintergrund,
	// damit beim Wechsel kein «Wird geladen» kommt (die Seiten lesen dieselben Zwischenspeicher-Schlüssel)
	LaunchedEffect(bibliotheken) {
		if (bibliotheken.isEmpty()) return@LaunchedEffect
		kotlinx.coroutines.delay(4_000)
		bibliotheken.forEach { bib ->
			runCatching {
				when (val ziel = seiteFuerBibliothek(bib)) {
					is Seite.Bibliothek -> {
						val k = ziel.schluessel
						if (vm.wahl["$k|start-reihen"] == null) vm.wahl["$k|start-reihen"] = bibliothekReihenLaden(daten, bib)
						if (vm.wahl["$k|banner"] == null) vm.wahl["$k|banner"] = daten.bannerEmpfehlungen(bib)
						if (vm.wahl["$k|items|${Sortierung.NEU}|false|null"] == null)
							vm.wahl["$k|items|${Sortierung.NEU}|false|null"] = daten.inhalt(bib, typenFuer(bib), Sortierung.NEU, false, 0, 60).items
					}
					is Seite.Musik -> {
						if (vm.wahl["musik-playlists"] == null) vm.wahl["musik-playlists"] = daten.playlists()
						if (vm.wahl["${ziel.schluessel}|liste|1"] == null) vm.wahl["${ziel.schluessel}|liste|1"] = daten.interpreten(bib)
					}
					Seite.LiveTv -> if (vm.wahl["livetv|sender"] == null) vm.wahl["livetv|sender"] = daten.sender()
					else -> Unit
				}
			}.onFailure { Timber.w(it, "Vorladen %s", bib.name) }
			kotlinx.coroutines.delay(500)
		}
	}

	// Selbst-Update: beim Start und danach alle 6 Stunden nachsehen, ob es eine neuere Version gibt
	val scope = androidx.compose.runtime.rememberCoroutineScope()
	var update by remember { mutableStateOf<SelikuUpdate.Version?>(null) }
	var updateText by remember { mutableStateOf<String?>(null) }
	// Android TV lässt die App im Hintergrund weiterlaufen: deshalb bei jedem Zurückkommen in die App nachsehen, nicht nur beim Start
	val lebenszyklus = androidx.lifecycle.compose.LocalLifecycleOwner.current
	LaunchedEffect(lebenszyklus) {
		lebenszyklus.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
			while (true) {
				SelikuUpdate.pruefen()?.let { update = it }
				kotlinx.coroutines.delay(6 * 60 * 60 * 1000L)
			}
		}
	}

	var updateEintrag: Eintrag? = null
	var einstellungenEintrag: Eintrag? = null
	val eintraege = buildList {
		add(Eintrag(R.drawable.ic_house, "Startseite", Seite.Start))
		// Feste Reihenfolge wie gewohnt: Filme, Serien, Live-TV, Musik, dann alles andere
		val rang = { b: BaseItemDto -> when (b.collectionType?.serialName) { "movies" -> 0; "tvshows" -> 1; "livetv" -> 2; "music" -> 3; else -> 4 } }
		bibliotheken.filter { it.collectionType?.serialName != "playlists" }.sortedBy(rang).forEach { bib -> add(Eintrag(symbolFuer(bib), bib.name.orEmpty(), seiteFuerBibliothek(bib))) }
		updateEintrag = update?.let { v ->
			(Eintrag(R.drawable.seliku_update, updateText ?: "Update ${v.name}", null, hervor = true) {
				when {
					updateText != null && updateText!!.startsWith("Lädt") -> Unit
					!SelikuUpdate.darfInstallieren(context) -> {
						updateText = "Erlauben, dann nochmals"
						SelikuUpdate.erlaubnisOeffnen(context)
					}
					else -> scope.launch {
						updateText = "Lädt …"
						val r = SelikuUpdate.installieren(context, v) { anteil -> updateText = "Lädt … ${(anteil * 100).toInt()} %" }
						updateText = if (r.isSuccess) "Installieren bestätigen" else "Update fehlgeschlagen"
					}
				}
			})
		}
		einstellungenEintrag = Eintrag(R.drawable.ic_settings, "Einstellungen", Seite.Einstellungen)
	}

	// Beim Start und nach jedem Seitenwechsel steht der Fokus im Inhalt, nicht in der Seitenleiste
	var inhaltHatFokus by remember { mutableStateOf(false) }
	var tasteGedrueckt by remember { mutableStateOf(false) }
	LaunchedEffect(vm.aktuell.schluessel) {
		// Hat die Seite eine gemerkte Kachel, holt sie den Fokus selbst; sonst (oder wenn das nicht klappt) die erste Kachel
		// Auf langsamen Geräten (Chromecast) kommen die Daten spät; bis dahin landet Android den Fokus sonst in der Leiste.
		// Solange niemand eine Taste gedrückt hat, holen wir ihn in den Inhalt (erster Titel unter «Weiterschauen»).
		val gemerkt = vm.fokus[vm.aktuell.schluessel] != null
		tasteGedrueckt = false
		repeat(60) { i ->
			kotlinx.coroutines.delay(150)
			if (inhaltHatFokus || tasteGedrueckt) return@LaunchedEffect
			// Erste Kachel der Seite (auf der Startseite: erster Titel unter «Weiterschauen», nicht der Banner)
			val erste = vm.ersteAnfrage(vm.aktuell.schluessel)
			if ((!gemerkt || i >= 5) && erste != null && runCatching { erste.requestFocus() }.getOrDefault(false) && inhaltHatFokus) return@LaunchedEffect
			if ((!gemerkt || i >= 5) && i >= 8 && runCatching { inhalt.requestFocus() }.getOrDefault(false) && inhaltHatFokus) return@LaunchedEffect
		}
	}

	// Zurück wie bei Netflix: Unterseite → eine Seite zurück; im Inhalt erst zur ersten Kachel oben, dann in die Leiste;
	// in der Leiste zur Startseite. Beenden nur, wenn in den Einstellungen «zweimal Zurück» an ist.
	var letztesZurueck by remember { mutableStateOf(0L) }
	BackHandler(enabled = true) {
		val seite = vm.aktuell
		when {
			vm.zurueck() -> Unit
			!leisteOffen && vm.fokus[seite.schluessel] != null && vm.fokus[seite.schluessel] != vm.ersterSchluessel[seite.schluessel] -> scope.launch {
				runCatching { vm.listen[seite.schluessel]?.scrollToItem(0) }
				runCatching { vm.raster[seite.schluessel]?.scrollToItem(0) }
				kotlinx.coroutines.delay(80)
				if (vm.ersteAnfrage(seite.schluessel)?.let { runCatching { it.requestFocus() }.getOrDefault(false) } != true) runCatching { leiste.requestFocus() }
			}
			!leisteOffen -> runCatching { leiste.requestFocus() }
			seite != Seite.Start -> { vm.wurzel(Seite.Start); runCatching { inhalt.requestFocus() } }
			SelikuEinstellungen.doppelZurueck(context) -> {
				val jetzt = System.currentTimeMillis()
				if (jetzt - letztesZurueck < 2000) (context as? android.app.Activity)?.finish()
				else { letztesZurueck = jetzt; android.widget.Toast.makeText(context, "Nochmals Zurück zum Beenden", android.widget.Toast.LENGTH_SHORT).show() }
			}
			else -> runCatching { inhalt.requestFocus() }
		}
	}


	Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Farben.HintergrundOben, Farben.Hintergrund), end = androidx.compose.ui.geometry.Offset(1400f, 1100f))).onPreviewKeyEvent { tasteGedrueckt = true; false }) {
		Box(Modifier.fillMaxSize()) {
			// Inhalt beginnt neben der schmalen Leiste; die offene Leiste legt sich als Insel darüber, ohne den Inhalt zu verschieben
			Box(Modifier.fillMaxSize().padding(start = 76.dp).focusRequester(inhalt).onFocusChanged { inhaltHatFokus = it.hasFocus }.focusRestorer().focusGroup()) {
				val seite = vm.aktuell
				val oeffnen: (BaseItemDto) -> Unit = { item -> seiteFuer(item)?.let(vm::zu) }
				key(seite.schluessel) { SeitlichRollen(rand = 60.dp) {
					when (seite) {
						Seite.Start -> StartSeite(vm, oeffnen)
						Seite.Suche -> SuchSeite(vm, oeffnen)
						Seite.LiveTv -> LiveTvSeite(vm)
						is Seite.Bibliothek -> BibliothekRahmen(vm, seite, oeffnen)
						is Seite.Musik -> MusikSeite(vm, seite, oeffnen)
						is Seite.Serie -> SerienSeite(vm, seite, oeffnen)
						is Seite.Titel -> TitelSeite(vm, seite, oeffnen)
						is Seite.Interpret -> InterpretSeite(vm, seite, oeffnen)
						is Seite.Album -> AlbumSeite(vm, seite, oeffnen)
						is Seite.Ordner -> OrdnerSeite(vm, seite, oeffnen)
						Seite.Einstellungen -> EinstellungenSeite(vm) { context.startActivity(ActivityDestinations.userPreferences(context)) }
						Seite.Bibliotheken -> BibliothekenSeite(vm, oeffnenBibliothek = { vm.zu(seiteFuerBibliothek(it)) }, geaendert = { neuLaden++ })
					}
				} }
			}
			if (leisteOffen) Box(Modifier.fillMaxSize().background(Color(0x99000000)))
			Seitenleiste(
				eintraege = eintraege,
				oben = listOfNotNull(einstellungenEintrag, updateEintrag),
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
		}
		MiniPlayer(Modifier.align(Alignment.BottomEnd).padding(end = 32.dp, bottom = 24.dp))
		vm.menue?.let { item -> TitelMenue(vm, item) { vm.wahl.keys.removeAll { it.endsWith("start-reihen") || it == "start-reihen" }; neuLaden++ } }
	}
}

@Composable
private fun Seitenleiste(
	eintraege: List<Eintrag>,
	oben: List<Eintrag>,
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
	val benutzerAlt by remember { userRepository.currentUser.filterNotNull() }.collectAsState(null)
	val daten = koinInject<SelikuDaten>()
	var benutzerNeu by remember { mutableStateOf<org.jellyfin.sdk.model.api.UserDto?>(null) }
	LaunchedEffect(Unit) { benutzerNeu = runCatching { daten.benutzer() }.getOrNull() }
	val benutzer = benutzerNeu ?: benutzerAlt
	// Beim Betreten der Seitenleiste steht der Fokus auf der aktiven Seite, nicht oben beim Profil
	val aktivAnfrage = remember { FocusRequester() }
	// Von der ersten Bibliothek nach oben landet man immer zuerst bei der Suche (ganz links)
	val sucheAnfrage = remember { FocusRequester() }

	Column(
		modifier
			.fillMaxHeight()
			.then(
				if (offen) Modifier.padding(start = 10.dp, top = 14.dp, bottom = 14.dp).width(260.dp)
					.shadow(24.dp, RoundedCornerShape(22.dp)).clip(RoundedCornerShape(22.dp)).background(Farben.Flaeche)
				else Modifier.width(76.dp).background(Farben.Hintergrund)
			)
			.focusRestorer(aktivAnfrage)
			.focusGroup()
			// Viele Bibliotheken passen nicht auf den Bildschirm: die Leiste rollt mit dem Fokus mit
			.verticalScroll(androidx.compose.foundation.rememberScrollState())
			.padding(vertical = 24.dp, horizontal = 12.dp),
		verticalArrangement = Arrangement.spacedBy(4.dp),
	) {
		// Oben eine Symbolreihe: Suche, Einstellungen, Update (falls vorhanden), Profilbild. Zugeklappt nur die Suche.
		Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = if (offen) Arrangement.spacedBy(6.dp) else Arrangement.Center) {
			val sucheAktiv = aktiv.schluessel == Seite.Suche.schluessel
			// Zugeklappt steht oben nur das Profilbild; aufgeklappt die Symbolreihe
			if (!offen) Box(Modifier.size(44.dp).padding(3.dp)) {
				Avatar(benutzer?.primaryImage?.getUrl(api), benutzer?.name, Modifier.fillMaxSize())
			}
			if (offen) {
				SymbolKnopf(R.drawable.ic_search, "Suche", aktiv = sucheAktiv, modifier = Modifier.focusRequester(sucheAnfrage)) { waehlen(Eintrag(R.drawable.ic_search, "Suche", Seite.Suche)) }
				oben.forEach { e -> SymbolKnopf(e.symbol, e.titel, hervor = e.hervor) { waehlen(e) } }
				Spacer(Modifier.weight(1f))
				Waehlbar(onClick = {
					mediaManager.clearAudioQueue()
					sessionRepository.destroyCurrentSession()
					context.startActivity(ActivityDestinations.startup(context))
				}, vergroessern = false, shape = CircleShape) { fokussiert ->
					Box(Modifier.size(44.dp).background(if (fokussiert) Farben.Text else Color.Transparent).padding(3.dp)) {
						Avatar(benutzer?.primaryImage?.getUrl(api), benutzer?.name, Modifier.fillMaxSize())
					}
				}
			}
		}
		// Was die Symbole gerade melden (z. B. Update-Fortschritt), steht klein darunter
		oben.firstOrNull { it.hervor }?.let { if (offen) Text(it.titel, style = Schrift.Klein.copy(color = Farben.Akzent), maxLines = 1, modifier = Modifier.padding(start = 8.dp, top = 4.dp)) }
		Spacer(Modifier.height(12.dp))
		eintraege.forEachIndexed { index, e ->
			val istAktiv = e.seite != null && e.seite.schluessel == aktiv.schluessel
			Waehlbar(onClick = { waehlen(e) }, vergroessern = false, shape = RoundedCornerShape(10.dp),
				modifier = (if (istAktiv) Modifier.focusRequester(aktivAnfrage) else Modifier)
					.then(if (index == 0) Modifier.focusProperties { up = sucheAnfrage } else Modifier)) { fokussiert ->
				Row(
					Modifier.fillMaxWidth()
						.background(when { fokussiert -> Farben.Text; istAktiv -> Farben.FlaecheHell; else -> Color.Transparent })
						.padding(horizontal = if (offen) 14.dp else 0.dp, vertical = 10.dp),
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = if (offen) Arrangement.Start else Arrangement.Center,
				) {
					val farbe = when { fokussiert -> Color.Black; istAktiv || e.hervor -> Farben.Akzent; else -> Farben.TextLeise }
					androidx.compose.foundation.Image(
						painter = androidx.compose.ui.res.painterResource(e.symbol),
						contentDescription = e.titel,
						colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(farbe),
						modifier = Modifier.size(24.dp),
					)
					if (offen) {
						Spacer(Modifier.width(14.dp))
						Text(e.titel, style = Schrift.Normal.copy(color = if (fokussiert) Color.Black else if (e.hervor) Farben.Akzent else Farben.Text, fontWeight = if (istAktiv) FontWeight.Bold else FontWeight.Normal),
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

@Composable
private fun SymbolKnopf(symbol: Int, titel: String, aktiv: Boolean = false, hervor: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
	Waehlbar(onClick = onClick, vergroessern = false, shape = CircleShape, modifier = modifier) { fokussiert ->
		Box(Modifier.size(44.dp).background(when { fokussiert -> Farben.Text; aktiv -> Farben.FlaecheHell; else -> Color.Transparent }),
			contentAlignment = Alignment.Center) {
			androidx.compose.foundation.Image(
				painter = androidx.compose.ui.res.painterResource(symbol),
				contentDescription = titel,
				colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(when { fokussiert -> Color.Black; aktiv || hervor -> Farben.Akzent; else -> Farben.TextLeise }),
				modifier = Modifier.size(24.dp),
			)
		}
	}
}

/** Profilbild; fehlt eines, ein farbiger Kreis mit Initialen (es gibt also immer ein Bild). */
@Composable
fun Avatar(url: String?, name: String?, modifier: Modifier = Modifier) {
	Box(modifier.clip(CircleShape).background(Farben.AkzentDunkel), contentAlignment = Alignment.Center) {
		Text(initialen(name), style = Schrift.Normal.copy(color = Farben.Text, fontWeight = FontWeight.Bold))
		if (url != null) coil3.compose.AsyncImage(model = url, contentDescription = name, imageLoader = koinInject<coil3.ImageLoader>(),
			contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.fillMaxSize())
	}
}
