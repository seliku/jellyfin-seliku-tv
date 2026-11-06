@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

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
import kotlinx.coroutines.delay
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.onKeyEvent
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
import androidx.compose.ui.res.stringResource

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
	// Die Leiste öffnet nur, wenn man sie holt (Zurück, bzw. Links falls eingestellt). Landet der Fokus sonst dort –
	// etwa weil ein Knopf verschwindet, auf dem er lag –, geht er gleich in den Inhalt zurück, statt die Insel aufzuklappen.
	var leisteGewollt by remember { mutableStateOf(false) }
	// Steht der Fokus in der Symbolreihe oben (Suche, Einstellungen, Update, Profil), bewegt Rechts sich dort weiter
	var obenHatFokus by remember { mutableStateOf(false) }
	val fokusManager = androidx.compose.ui.platform.LocalFocusManager.current
	// Radio läuft und niemand drückt: nach 20 s dunkel bzw. Diashow (Wahl wie im Musikplayer); der Fernseher bleibt an
	var letzteTaste by remember { mutableStateOf(System.currentTimeMillis()) }
	var radioEbene by remember { mutableStateOf(false) }
	var schluckeLoslassen by remember { mutableStateOf(false) }
	val radioSpielt by RadioSpieler.spielt.collectAsState()
	val bildschirmModus = SelikuMusikBildschirm.aktuell(context)
	// Musik aus Jellyfin zählt wie Radio. Ohne das schaltet Google TV nach ein paar Minuten ohne Tastendruck den Bildschirm
	// und per HDMI-CEC den Fernseher aus – die Musik ist dann weg. Video hält den Bildschirm selbst wach.
	val wiedergabe = koinInject<PlaybackManager>()
	val musikZustand by wiedergabe.state.playState.collectAsState()
	val tonSpielt = radioSpielt || musikZustand == PlayState.PLAYING
	LaunchedEffect(letzteTaste, tonSpielt, bildschirmModus) {
		radioEbene = false
		if (!tonSpielt || bildschirmModus == MusikBildschirm.NORMAL) return@LaunchedEffect
		delay(SelikuMusikBildschirm.WARTEZEIT_MS)
		radioEbene = true
	}
	val ansicht = androidx.compose.ui.platform.LocalView.current
	DisposableEffect(tonSpielt) { ansicht.keepScreenOn = tonSpielt; onDispose { ansicht.keepScreenOn = false } }
	org.jellyfin.androidtv.ui.ScreensaverLock(enabled = tonSpielt)
	val leisteAnteil by androidx.compose.animation.core.animateFloatAsState(if (leisteOffen) 1f else 0f,
		androidx.compose.animation.core.tween(220, easing = androidx.compose.animation.core.FastOutSlowInEasing), label = "leiste")
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
						if (vm.wahl["$k|start-reihen"] == null) vm.wahl["$k|start-reihen"] = bibliothekReihenLaden(context, daten, bib)
						if (vm.wahl["$k|banner"] == null) vm.wahl["$k|banner"] = daten.bannerEmpfehlungen(bib)
						if (vm.wahl["$k|items|${Sortierung.NEU}|false|null"] == null)
							vm.wahl["$k|items|${Sortierung.NEU}|false|null"] = daten.inhalt(bib, typenFuer(bib), Sortierung.NEU, false, 0, 60).items
					}
					is Seite.Musik -> {
						if (vm.wahl["musik-playlists"] == null) vm.wahl["musik-playlists"] = daten.playlists()
						if (vm.wahl["${ziel.schluessel}|liste2|2"] == null) vm.wahl["${ziel.schluessel}|liste2|2"] = daten.interpreten(bib)
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

	// Einstellungen «für alle Geräte» vom Jellyfin-Konto holen (nach Start bzw. Anmeldung); ohne Netz bleibt es bei den Werten hier
	LaunchedEffect(Unit) { SelikuSync.holen(context) }

	// Bietet der Jellyfin-Server Seerr an (Vermittler), ist man ohne eigene Anmeldung verbunden
	val seerrDaten = koinInject<SeerrDaten>()
	LaunchedEffect(Unit) {
		// Gleich nach dem Start ist die Anmeldung manchmal noch nicht fertig: ein paarmal nachfragen
		repeat(4) { runCatching { seerrDaten.vermittlerPruefen() }; if (SeerrVerbindung.vermittelt.value) return@LaunchedEffect; delay(15_000) }
	}
	val seerrDa = SeerrVerbindung.vermittelt.value || SeerrVerbindung.verbunden(context)
	var updateEintrag: Eintrag? = null
	var einstellungenEintrag: Eintrag? = null
	val eintraege = buildList {
		add(Eintrag(R.drawable.ic_house, stringResource(R.string.seliku_home), Seite.Start))
		// Seerr verbunden: «Entdecken» gleich nach der Startseite
		if (seerrDa) add(Eintrag(R.drawable.seliku_entdecken, stringResource(R.string.seliku_discover), Seite.Entdecken))
		// Feste Reihenfolge wie gewohnt: Filme, Serien, Live-TV, Musik, dann alles andere
		val rang = { b: BaseItemDto -> when (b.collectionType?.serialName) { "movies" -> 0; "tvshows" -> 1; "livetv" -> 2; "music" -> 3; else -> 4 } }
		bibliotheken.filter { it.collectionType?.serialName != "playlists" }.sortedBy(rang).forEach { bib -> add(Eintrag(symbolFuer(bib), bib.name.orEmpty(), seiteFuerBibliothek(bib))) }
		val updateTitel = update?.let { stringResource(R.string.seliku_update_version, it.name) }
		updateEintrag = update?.let { v ->
			(Eintrag(R.drawable.seliku_update, updateText ?: updateTitel.orEmpty(), null, hervor = true) {
				when {
					updateText != null && updateText!!.startsWith(context.getString(R.string.seliku_update_downloading)) -> Unit
					!SelikuUpdate.darfInstallieren(context) -> {
						updateText = context.getString(R.string.seliku_update_allow)
						SelikuUpdate.erlaubnisOeffnen(context)
					}
					else -> scope.launch {
						updateText = context.getString(R.string.seliku_update_downloading)
						val r = SelikuUpdate.installieren(context, v) { anteil -> updateText = context.getString(R.string.seliku_update_progress, (anteil * 100).toInt()) }
						updateText = context.getString(if (r.isSuccess) R.string.seliku_update_confirm else R.string.seliku_update_failed)
					}
				}
			})
		}
		einstellungenEintrag = Eintrag(R.drawable.ic_settings, stringResource(R.string.seliku_settings), Seite.Einstellungen)
	}

	// Beim Start und nach jedem Seitenwechsel steht der Fokus im Inhalt, nicht in der Seitenleiste
	var inhaltHatFokus by remember { mutableStateOf(false) }
	// Aus der Leiste zurück genau dorthin, wo man im Inhalt war (die Gruppe allein landete oft beim Banner-Knopf)
	// Danach prüfen, ob der Fokus wirklich im Inhalt ist: ist die gemerkte Kachel verschwunden (z. B. «Aus Weiterschauen
	// entfernen»), meldet requestFocus trotzdem Erfolg – dann die Gruppe bzw. das nächstbeste Ziel im Inhalt nehmen.
	val zurueckInDenInhalt: () -> Unit = {
		runCatching { FokusSpur.imInhalt?.requestFocus() }
		scope.launch {
			kotlinx.coroutines.delay(60)
			if (!inhaltHatFokus) runCatching { inhalt.requestFocus() }
			kotlinx.coroutines.delay(60)
			if (!inhaltHatFokus) runCatching { fokusManager.moveFocus(FocusDirection.Right) }
		}
	}
	var tasteGedrueckt by remember { mutableStateOf(false) }
	LaunchedEffect(vm.aktuell.schluessel) {
		// Hat die Seite eine gemerkte Kachel, holt sie den Fokus selbst; sonst (oder wenn das nicht klappt) die erste Kachel
		// Auf langsamen Geräten (Chromecast) kommen die Daten spät; bis dahin landet Android den Fokus sonst in der Leiste.
		// Solange niemand eine Taste gedrückt hat, holen wir ihn in den Inhalt (erster Titel unter «Weiterschauen»).
		val gemerkt = vm.fokus[vm.aktuell.schluessel] != null
		tasteGedrueckt = false
		repeat(200) { i ->
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
			// Insel offen: Zurück schliesst sie, man bleibt, wo man war (nicht zur Startseite springen)
			leisteOffen && !(seite == Seite.Start && SelikuEinstellungen.doppelZurueck(context)) -> zurueckInDenInhalt()
			!leisteOffen && vm.zurueck() -> Unit
			// Seiten mit Reitern oben (Musik, Filme, Serien): erst auf die Reiter, dann erst die Leiste
			!leisteOffen && !vm.reiterFokus && vm.reiterAnfragen[seite.schluessel]?.let { runCatching { it.requestFocus() }.isSuccess } == true -> Unit
			// Sonst öffnet Zurück direkt die Leiste (S 04.11.: auf der Startseite erst nach oben springen war nicht intuitiv)
			!leisteOffen -> { leisteGewollt = true; runCatching { leiste.requestFocus() } }
			SelikuEinstellungen.doppelZurueck(context) -> {
				val jetzt = System.currentTimeMillis()
				if (jetzt - letztesZurueck < 2000) (context as? android.app.Activity)?.finish()
				else { letztesZurueck = jetzt; android.widget.Toast.makeText(context, context.getString(R.string.seliku_back_again_to_exit), android.widget.Toast.LENGTH_SHORT).show() }
			}
			else -> zurueckInDenInhalt()
		}
	}


	Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Farben.HintergrundOben, Farben.Hintergrund), end = androidx.compose.ui.geometry.Offset(1400f, 1100f))).onPreviewKeyEvent { e ->
			tasteGedrueckt = true; letzteTaste = System.currentTimeMillis()
			// Steht die Radio-Ebene (dunkel/Diashow), holt der Druck nur die Oberfläche zurück – samt Loslassen
			if (radioEbene && e.type == KeyEventType.KeyDown) { radioEbene = false; schluckeLoslassen = true; true }
			else if (schluckeLoslassen) { if (e.type == KeyEventType.KeyUp) schluckeLoslassen = false; true }
			else false
		}
		// Links/oben am Rand will niemand weiter: sonst reicht Compose die Taste an die Android-Ansicht dahinter weiter
		// und der Fokus verschwindet in einem unsichtbaren Element
		.onKeyEvent { e ->
			val richtung = when (e.key) { Key.DirectionLeft -> FocusDirection.Left; Key.DirectionUp -> FocusDirection.Up; else -> null }
			if (richtung != null && e.type == KeyEventType.KeyDown) {
				if (richtung == FocusDirection.Left && SelikuEinstellungen.leisteMitLinks(context)) leisteGewollt = true
				val vorher = FokusSpur.anfrage; val alt = FokusSpur.lage()
				fokusManager.moveFocus(richtung)
				if (!leisteOffen) leisteGewollt = false
				val neu = FokusSpur.lage()
				// Nur annehmen, wenn das neue Ziel wirklich links bzw. oberhalb liegt; sonst bleibt der Fokus, wo er war
				// Ziel ohne Fläche ist auch falsch (dort sähe man den Fokus nicht). Geprüft wird die echte Lage, nicht die sichtbare:
				// eine Reihe, die gerade aus dem Bild gerollt ist, ist ein gültiges Ziel (sonst kam man nach 4 Reihen nicht mehr hoch).
				val falsch = FokusSpur.anfrage !== vorher && alt != null && neu != null && (neu.width <= 0f || neu.height <= 0f || when (richtung) {
					FocusDirection.Left -> neu.center.x >= alt.left
					else -> neu.center.y >= alt.top
				})
				if (falsch) runCatching { vorher?.requestFocus() }
				true
			} else false
		}) {
		Box(Modifier.fillMaxSize()) {
			// Inhalt beginnt neben der schmalen Leiste; die offene Leiste legt sich als Insel darüber, ohne den Inhalt zu verschieben
			Box(Modifier.fillMaxSize().padding(start = 76.dp).focusRequester(inhalt).onFocusChanged { inhaltHatFokus = it.hasFocus }
				// Links/oben am Rand nicht in die Leiste springen (passierte zu oft aus Versehen); die Leiste kommt mit Zurück
				.focusProperties { exit = { richtung -> if ((richtung == FocusDirection.Left && !SelikuEinstellungen.leisteMitLinks(context)) || richtung == FocusDirection.Up) FocusRequester.Cancel else FocusRequester.Default } }
				.focusRestorer().focusGroup()) {
				val seite = vm.aktuell
				val oeffnen: (BaseItemDto) -> Unit = { item -> seiteFuer(item)?.let(vm::zu) }
				// Neue Seite bzw. wieder sichtbar: die gemerkte Kachel darf den Fokus einmal zurückholen
				key(seite.schluessel) { androidx.compose.runtime.remember { vm.wiederhergestellt.remove(seite.schluessel); 0 }
 androidx.compose.runtime.CompositionLocalProvider(LocalImInhalt provides true, LocalTasteGedrueckt provides tasteGedrueckt) { SeitlichRollen(rand = 60.dp) {
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
						Seite.Entdecken -> EntdeckenSeite(vm)
						is Seite.SeerrDetail -> SeerrDetailSeite(vm, seite, oeffnen)
						is Seite.SeerrListe -> SeerrListenSeite(vm, seite)
						Seite.SeerrVerbinden -> SeerrVerbindenSeite(vm)
						Seite.Bibliotheken -> BibliothekenSeite(vm, oeffnenBibliothek = { vm.zu(seiteFuerBibliothek(it)) }, geaendert = { neuLaden++ })
					}
				} } }
			}
			Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f * leisteAnteil)))
			Seitenleiste(
				eintraege = eintraege,
				oben = listOfNotNull(einstellungenEintrag, updateEintrag),
				obenFokus = { obenHatFokus = it },
				aktiv = vm.stapel.first(),
				offen = leisteOffen,
				anteil = leisteAnteil,
				// Rechts aus der Leiste: zurück genau dorthin, wo man im Inhalt war (nicht zum nächstbesten Knopf daneben)
				modifier = Modifier.focusRequester(leiste).onFocusChanged {
					when {
						!it.hasFocus -> { leisteOffen = false; leisteGewollt = false }
						leisteGewollt -> leisteOffen = true
						else -> scope.launch { zurueckInDenInhalt() }
					}
				}.onPreviewKeyEvent { e ->
					// Erst innerhalb der Leiste weiter (Symbolreihe oben: Suche → Einstellungen → Update → Profil);
					// geht es dort nicht weiter, zurück an die Stelle im Inhalt, wo man war
					if (e.key == Key.DirectionRight && e.type == KeyEventType.KeyDown && !obenHatFokus) { zurueckInDenInhalt(); true } else false
				},
				waehlen = { e ->
					if (e.seite != null) {
						vm.wurzel(e.seite)
						runCatching { inhalt.requestFocus() }
					} else e.aktion?.invoke()
				},
			)
		}
		androidx.compose.animation.AnimatedVisibility(radioEbene, enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(1500)),
			exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(400))) { SelikuMusikBildschirm.Ebene(bildschirmModus) }
		vm.menue?.let { item -> TitelMenue(vm, item) {
			vm.wahl.keys.removeAll { it.endsWith("start-reihen") || it == "start-reihen" }; neuLaden++; vm.datenStand++
			// Ist die Kachel verschwunden, auf der man stand, nach dem Neuladen wieder in den Inhalt
			scope.launch { kotlinx.coroutines.delay(900); if (!inhaltHatFokus) zurueckInDenInhalt() }
		} }
	}
}

@Composable
private fun Seitenleiste(
	eintraege: List<Eintrag>,
	oben: List<Eintrag>,
	aktiv: Seite,
	offen: Boolean,
	anteil: Float,
	modifier: Modifier,
	obenFokus: (Boolean) -> Unit = {},
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
	val sucheTitel = stringResource(R.string.seliku_search)

	Column(
		modifier
			.fillMaxHeight()
			// Gleiche Modifier-Kette in beiden Zuständen, nur andere Werte: eine andere Kette baut die Fokusknoten neu
			// auf, und der Fokus, der gerade in die Leiste gesprungen ist, fällt ins Leere
			// Weich aufziehen (wie bei Disney): Breite, Rand und Farbe folgen einem Anteil 0..1, kein Schatten (der kostet auf TV-Chips jedes Bild)
			.padding(start = 10.dp * anteil, top = 14.dp * anteil, bottom = 14.dp * anteil)
			.width(76.dp + 184.dp * anteil)
			.clip(RoundedCornerShape(22.dp * anteil))
			.background(androidx.compose.ui.graphics.lerp(Farben.Hintergrund, Farben.Flaeche, anteil))
			.focusRestorer(aktivAnfrage)
			.focusGroup()
			// Viele Bibliotheken passen nicht auf den Bildschirm: die Leiste rollt mit dem Fokus mit
			.verticalScroll(androidx.compose.foundation.rememberScrollState())
			.padding(vertical = 16.dp, horizontal = 12.dp),
		verticalArrangement = Arrangement.spacedBy(4.dp),
	) {
		// Oben eine Symbolreihe: Suche, Einstellungen, Update (falls vorhanden), Profilbild. Zugeklappt nur die Suche.
		Row(Modifier.fillMaxWidth().onFocusChanged { obenFokus(it.hasFocus) }, verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = if (offen) Arrangement.spacedBy(6.dp) else Arrangement.Center) {
			val sucheAktiv = aktiv.schluessel == Seite.Suche.schluessel
			// Erster Platz oben: zugeklappt das Profilbild, aufgeklappt die Suche. Derselbe Baustein in beiden Zuständen –
			// wechselt hier die Struktur, verliert die Leiste beim Aufklappen den Fokus (Fokus war danach nirgends).
			Waehlbar(onClick = { if (offen) waehlen(Eintrag(R.drawable.ic_search, sucheTitel, Seite.Suche)) }, vergroessern = false, shape = CircleShape,
				modifier = Modifier.focusRequester(sucheAnfrage).focusProperties { canFocus = offen }) { fokussiert ->
				Box(Modifier.size(44.dp).background(when { fokussiert -> Farben.Text; offen && sucheAktiv -> Farben.FlaecheHell; else -> Color.Transparent }),
					contentAlignment = Alignment.Center) {
					if (offen) androidx.compose.foundation.Image(
						painter = androidx.compose.ui.res.painterResource(R.drawable.ic_search), contentDescription = sucheTitel,
						colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(when { fokussiert -> Color.Black; sucheAktiv -> Farben.Akzent; else -> Farben.TextLeise }),
						modifier = Modifier.size(24.dp),
					) else Avatar(benutzer?.primaryImage?.getUrl(api), benutzer?.name, Modifier.fillMaxSize().padding(3.dp))
				}
			}
			if (offen) {
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
						// 14 dp Rand: zugeklappt steht das Symbol so genau mittig, aufgeklappt bleibt es an derselben Stelle
						.padding(horizontal = 14.dp, vertical = 7.dp),
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.Start,
				) {
					val farbe = when { fokussiert -> Color.Black; istAktiv || e.hervor -> Farben.Akzent; else -> Farben.TextLeise }
					androidx.compose.foundation.Image(
						painter = androidx.compose.ui.res.painterResource(e.symbol),
						contentDescription = e.titel,
						colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(farbe),
						modifier = Modifier.size(24.dp),
					)
					if (anteil > 0f) {
						Spacer(Modifier.width(14.dp))
						Text(e.titel, modifier = Modifier.graphicsLayer { alpha = anteil }, style = Schrift.Normal.copy(color = if (fokussiert) Color.Black else if (e.hervor) Farben.Akzent else Farben.Text, fontWeight = if (istAktiv) FontWeight.Bold else FontWeight.Normal),
							maxLines = 1, overflow = TextOverflow.Ellipsis)
					}
				}
			}
		}
		// Was gerade läuft, unten in der Leiste: erreichbar mit Zurück, verdeckt keine Kacheln
		// Zugeklappt nicht: dort reicht der Platz nicht, die schmale Leiste würde sonst rollen und das Profilbild abschneiden
		if (anteil > 0f) {
			Spacer(Modifier.height(12.dp))
			LaeuftEintrag(offen, anteil)
		}
	}
}

/** Was gerade läuft (Musik oder Radio), als Eintrag in der Seitenleiste; zugeklappt nur das Cover. OK öffnet die Wiedergabe bzw. hält das Radio an. */
@Composable
private fun LaeuftEintrag(offen: Boolean, anteil: Float) {
	val modifier = Modifier
	// Läuft ein Radiosender, zeigt der Mini-Player ihn; OK hält an bzw. spielt weiter
	val radio by RadioSpieler.laeuft.collectAsState()
	val radioSpielt by RadioSpieler.spielt.collectAsState()
	radio?.let { r ->
		Waehlbar(onClick = { RadioSpieler.umschalten() }, onMenue = { RadioSpieler.stoppen() }, modifier = modifier, vergroessern = false, shape = RoundedCornerShape(14.dp)) { fokussiert ->
			Row(
				Modifier.fillMaxWidth().background(if (fokussiert) Farben.Text else Farben.FlaecheHell).padding(horizontal = 4.dp, vertical = 4.dp),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(12.dp),
			) {
				Bild(r.logo, Modifier.size(44.dp).clip(RoundedCornerShape(6.dp)), androidx.compose.ui.layout.ContentScale.Fit, ersatz = "♫")
				if (offen) Column(Modifier.weight(1f).graphicsLayer { alpha = anteil }) {
					Text(r.name, style = Schrift.Normal.copy(fontWeight = FontWeight.Bold, color = if (fokussiert) Color.Black else Farben.Text), maxLines = 1, overflow = TextOverflow.Ellipsis)
					Text(stringResource(if (radioSpielt) R.string.seliku_radio_playing else R.string.seliku_radio_paused), style = Schrift.Klein, maxLines = 1)
				}
				if (offen) Text(if (radioSpielt) "❚❚" else "▶", style = Schrift.Titel.copy(color = Farben.Akzent))
			}
		}
		return
	}
	val playbackManager = koinInject<PlaybackManager>()
	val abspielen = koinInject<Abspielen>()
	val bilder = koinInject<Bilder>()
	val eintrag by rememberQueueEntry(playbackManager)
	val item = eintrag?.run { baseItemFlow.collectAsState(baseItem) }?.value ?: return
	val zustand by playbackManager.state.playState.collectAsState()

	Waehlbar(onClick = { abspielen.jetztLaeuft() }, modifier = modifier, vergroessern = false, shape = RoundedCornerShape(14.dp)) { fokussiert ->
		Row(
			Modifier.fillMaxWidth().background(if (fokussiert) Farben.Text else Farben.FlaecheHell).padding(horizontal = 4.dp, vertical = 4.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(12.dp),
		) {
			Bild(bilder.poster(item, 120), Modifier.size(44.dp).clip(RoundedCornerShape(6.dp)))
			Column(Modifier.weight(1f)) {
				Text(item.name.orEmpty(), style = Schrift.Normal.copy(fontWeight = FontWeight.Bold, color = if (fokussiert) Color.Black else Farben.Text), maxLines = 1, overflow = TextOverflow.Ellipsis)
				Text(item.unterzeile(), style = Schrift.Klein, maxLines = 1, overflow = TextOverflow.Ellipsis)
			}
			if (offen) if (zustand == PlayState.PLAYING) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
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
