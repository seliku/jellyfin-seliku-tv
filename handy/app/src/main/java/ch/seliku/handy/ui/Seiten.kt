package ch.seliku.handy.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ch.seliku.handy.BuildConfig
import ch.seliku.handy.Einstellungen
import ch.seliku.handy.Sitzung
import ch.seliku.handy.Update
import ch.seliku.handy.daten.Farben
import ch.seliku.handy.daten.verlaufUeberblick
import ch.seliku.handy.daten.VerlaufUeberblick
import ch.seliku.handy.seerr.SeerrDaten
import ch.seliku.handy.seerr.SeerrKachel
import ch.seliku.handy.seerr.SeerrTitel
import ch.seliku.handy.seerr.SeerrVerbindung
import ch.seliku.handy.seerr.SeerrZustand
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jellyfin.sdk.api.client.extensions.displayPreferencesApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import java.util.UUID

// ───────────── Suche ─────────────

private object SuchSpeicher { var text = ""; var treffer: List<BaseItemDto> = emptyList(); var seerr: List<SeerrTitel> = emptyList() }

/**
 * Suche wie am TV: ab 2 Zeichen, 450 ms nach dem letzten Tippen; Jellyfin und Seerr gleichzeitig. Reihen: Filme,
 * Serien, «Noch nicht da – anfragen» (nur Fehlendes aus Seerr), Folgen, Sender, Interpreten, Alben, Titel.
 */
@Composable
fun SuchSeite() {
	val c = LocalContext.current
	val nav = LocalNav.current
	var text by remember { mutableStateOf(SuchSpeicher.text) }
	var treffer by remember { mutableStateOf(SuchSpeicher.treffer) }
	var seerr by remember { mutableStateOf(SuchSpeicher.seerr) }
	var laeuft by remember { mutableStateOf(false) }
	val fokus = remember { FocusRequester() }
	LaunchedEffect(Unit) { if (text.isEmpty()) runCatching { fokus.requestFocus() } }
	LaunchedEffect(text) {
		SuchSpeicher.text = text
		if (text.trim().length < 2) { treffer = emptyList(); seerr = emptyList(); return@LaunchedEffect }
		delay(450)
		laeuft = true
		launch {
			if (SeerrVerbindung.verbunden(c)) seerr = runCatching { SeerrDaten(c, Sitzung.api!!).suche(text.trim()).filter { it.zustand != SeerrZustand.ANSEHEN } }.getOrDefault(emptyList())
			SuchSpeicher.seerr = seerr
		}
		treffer = runCatching { Sitzung.daten.suche(text.trim()) }.getOrDefault(emptyList())
		SuchSpeicher.treffer = treffer
		laeuft = false
	}
	Column(Modifier.fillMaxSize().selikuHintergrund()) {
		OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().statusBarsPadding().padding(16.dp).focusRequester(fokus),
			placeholder = { Text("Filme, Serien, Musik, Sender …") }, singleLine = true,
			leadingIcon = { Icon(Icons.Filled.Search, null) },
			trailingIcon = { if (text.isNotEmpty()) IconButton({ text = "" }) { Icon(Icons.Filled.Close, "Leeren") } },
			keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), shape = RoundedCornerShape(24.dp))
		LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
			fun gruppe(t: BaseItemKind) = treffer.filter { it.type == t }
			if (laeuft && treffer.isEmpty()) item { Hinweis("Suche läuft …") }
			else if (text.trim().length >= 2 && treffer.isEmpty() && seerr.isEmpty() && !laeuft) item { Hinweis("Nichts gefunden.") }
			item { Reihe("Filme", gruppe(BaseItemKind.MOVIE), schluessel = { it.id }) { i -> PosterKachel(i) { oeffnen(nav, c, i) } } }
			item { Reihe("Serien", gruppe(BaseItemKind.SERIES), schluessel = { it.id }) { i -> PosterKachel(i) { oeffnen(nav, c, i) } } }
			item { Reihe("Noch nicht da – anfragen", seerr, schluessel = { it.schluessel }) { t -> SeerrKachel(t) { nav.navigate(Wege.seerr(t.typ, t.tmdb)) } } }
			item { Reihe("Folgen", gruppe(BaseItemKind.EPISODE), schluessel = { it.id }) { i -> QuerKachel(i, 220.dp) { abspielen(nav, c, i) } } }
			item { Reihe("Sender", gruppe(BaseItemKind.TV_CHANNEL), schluessel = { it.id }) { i -> SenderKachel(i, 200.dp) { abspielen(nav, c, i) } } }
			item { Reihe("Interpreten", gruppe(BaseItemKind.MUSIC_ARTIST), schluessel = { it.id }) { i -> PosterKachel(i, 110.dp, rund = true) { oeffnen(nav, c, i) } } }
			item { Reihe("Alben", gruppe(BaseItemKind.MUSIC_ALBUM), schluessel = { it.id }) { i -> PosterKachel(i, 120.dp, quadrat = true) { oeffnen(nav, c, i) } } }
			item { Reihe("Titel", gruppe(BaseItemKind.AUDIO), schluessel = { it.id }) { i -> PosterKachel(i, 120.dp, quadrat = true) { oeffnen(nav, c, i) } } }
		}
	}
}

// ───────────── Mehr ─────────────

/** «Mehr»: was am TV in der Seitenleiste steht (Bibliotheken, Live-TV, Musik, Radio), dazu Mein Seliku und Einstellungen. */
@Composable
fun MehrSeite() {
	val nav = LocalNav.current
	var bibs by remember { mutableStateOf<List<BaseItemDto>>(emptyList()) }
	LaunchedEffect(Unit) { bibs = runCatching { Sitzung.daten.bibliotheken() }.getOrDefault(emptyList()) }
	@Composable fun Zeile(symbol: ImageVector, titel: String, aktion: () -> Unit) {
		Row(Modifier.fillMaxWidth().clickable(onClick = aktion).padding(horizontal = 20.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
			Icon(symbol, null, tint = Farben.Akzent); Spacer(Modifier.width(18.dp)); Text(titel, fontSize = Schrift.Reihe)
		}
	}
	LazyColumn(Modifier.fillMaxSize().selikuHintergrund()) {
		item {
			Row(Modifier.fillMaxWidth().statusBarsPadding().clickable { if (Einstellungen.an("mein_verlauf")) nav.navigate("verlauf") }.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
				Profilbild(56)
				Column(Modifier.padding(start = 14.dp)) {
					Text(Sitzung.benutzerName, fontSize = Schrift.Titel, fontWeight = FontWeight.Bold)
					Text("Mein Seliku ›", color = Farben.TextLeise, fontSize = Schrift.Leise)
				}
			}
		}
		Update.neu?.let { (_, name) -> item {
			val sc = rememberCoroutineScope(); val c = LocalContext.current
			Row(Modifier.fillMaxWidth().clickable { sc.launch { Update.installieren(c) } }.padding(horizontal = 20.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
				Icon(Icons.Filled.SystemUpdate, null, tint = Farben.Akzent); Spacer(Modifier.width(18.dp))
				Text(if (Update.laedt) "Update wird geladen …" else "Update auf $name", fontSize = Schrift.Reihe, color = Farben.Akzent)
			}
		} }
		items(bibs.filter { it.collectionType in setOf(org.jellyfin.sdk.model.api.CollectionType.LIVETV, org.jellyfin.sdk.model.api.CollectionType.MUSIC) }.sortedBy { gruppeFuer(it, emptySet()) }) { b ->
			Zeile(if (b.collectionType == org.jellyfin.sdk.model.api.CollectionType.LIVETV) Icons.Filled.LiveTv else Icons.Filled.LibraryMusic, b.name.orEmpty()) { bibliothekOeffnen(nav, b) }
		}
		item { Zeile(Icons.Filled.Radio, "Radio") { nav.navigate("radio") } }
		item { Zeile(Icons.Filled.Favorite, "Meine Liste") { nav.navigate("favoriten") } }
		item { Zeile(Icons.Filled.History, "Mein Seliku") { nav.navigate("verlauf") } }
		item { Zeile(Icons.Filled.Settings, "Einstellungen") { nav.navigate("einstellungen") } }
		item { Zeile(Icons.AutoMirrored.Filled.Logout, "Abmelden") { Sitzung.abmelden() } }
		item { Text("Jellyfin Seliku Handy ${BuildConfig.VERSION_NAME}", color = Farben.TextLeise, fontSize = Schrift.Klein, modifier = Modifier.padding(20.dp)) }
	}
}

@Composable
fun Profilbild(groesse: Int) {
	val id = Sitzung.benutzerId
	Box(Modifier.size(groesse.dp).clip(CircleShape).background(Farben.AkzentDunkel), contentAlignment = Alignment.Center) {
		Text(Sitzung.benutzerName.take(2).uppercase(), fontWeight = FontWeight.Bold)
		if (id != null) coil3.compose.AsyncImage("${Sitzung.server}/Users/$id/Images/Primary?maxWidth=200", null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
	}
}

// ───────────── Einstellungen ─────────────

@Composable
fun EinstellungenSeite() {
	val nav = LocalNav.current
	val c = LocalContext.current
	LaunchedEffect(Unit) { Einstellungen.holen() }
	LazyColumn(Modifier.fillMaxSize().selikuHintergrund(), contentPadding = PaddingValues(bottom = 24.dp)) {
		item { SeitenKopf("Seliku-Einstellungen", { nav.popBackStack() }) }
		item { Text("☁ = für alle Geräte (über das Konto), 📱 = nur dieses Handy. Antippen wechselt.", fontSize = Schrift.Klein, color = Farben.TextLeise, modifier = Modifier.padding(horizontal = 16.dp)) }
		Einstellungen.schalter.groupBy { it.gruppe }.forEach { (gruppe, liste) ->
			item { ReihenTitel(gruppe) }
			items(liste, key = { it.schluessel }) { s ->
				Row(Modifier.fillMaxWidth().clickable { Einstellungen.setzen(s.schluessel, (!Einstellungen.an(s.schluessel)).toString()); Datenstand.neu() }
					.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
					Column(Modifier.weight(1f)) {
						Text(s.titel, fontWeight = FontWeight.SemiBold)
						Text(s.beschreibung, fontSize = Schrift.Klein, color = Farben.TextLeise)
					}
					Text(if (Einstellungen.alle(s)) "☁" else "📱", Modifier.clickable { Einstellungen.umfangWechseln(s) }.padding(10.dp))
					Switch(Einstellungen.an(s.schluessel), { Einstellungen.setzen(s.schluessel, it.toString()); Datenstand.neu() },
						colors = SwitchDefaults.colors(checkedTrackColor = Farben.Akzent, checkedThumbColor = Farben.Text))
				}
			}
		}
		item { ReihenTitel("Wiedergabe auf diesem Handy") }
		item {
			Auswahl("Nach einer Folge", listOf("true" to "Nächste startet", "warten" to "Wartet auf Tippen", "false" to "Schluss"), Einstellungen.nachDerFolge()) { Einstellungen.setzen("naechste_folge", it) }
			Auswahl("Höchste Bitrate", listOf("0" to "Original", "20000000" to "20 Mbit/s", "10000000" to "10 Mbit/s", "5000000" to "5 Mbit/s", "2000000" to "2 Mbit/s"),
				Einstellungen.text("handy_max_bitrate") ?: "0") { Einstellungen.setzen("handy_max_bitrate", it) }
		}
		item { ReihenTitel("Seerr") }
		item {
			Text(if (SeerrVerbindung.vermittelt.value) "Verbunden über Jellyfin (ohne eigene Anmeldung)" else if (SeerrVerbindung.verbunden(c)) "Verbunden als ${SeerrVerbindung.name(c)}" else "Nicht verbunden",
				modifier = Modifier.padding(horizontal = 16.dp), color = Farben.TextLeise)
		}
	}
}

@Composable
private fun Auswahl(titel: String, werte: List<Pair<String, String>>, aktuell: String, setzen: (String) -> Unit) {
	Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
		Text(titel, fontWeight = FontWeight.SemiBold)
		LazyRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
			items(werte) { (w, t) -> Pille(t, aktiv = w == aktuell) { setzen(w) } }
		}
	}
}

// ───────────── Mein Seliku, Meine Liste ─────────────

private fun dauerText(m: Int) = if (m >= 60) "${m / 60} Std. ${m % 60} Min." else "$m Min."

@Composable
fun VerlaufSeite() {
	val nav = LocalNav.current
	val c = LocalContext.current
	val d = remember { Sitzung.daten }
	var weiter by remember { mutableStateOf<List<BaseItemDto>>(emptyList()) }
	var liste by remember { mutableStateOf<List<BaseItemDto>>(emptyList()) }
	var gefallen by remember { mutableStateOf<List<BaseItemDto>>(emptyList()) }
	var zuletzt by remember { mutableStateOf<List<BaseItemDto>>(emptyList()) }
	var nicht by remember { mutableStateOf<List<BaseItemDto>>(emptyList()) }
	var ueber by remember { mutableStateOf<VerlaufUeberblick?>(null) }
	LaunchedEffect(Datenstand.stand) {
		launch { weiter = runCatching { d.weiterschauen() }.getOrDefault(emptyList()) }
		launch { liste = runCatching { d.favoriten() }.getOrDefault(emptyList()) }
		launch { gefallen = runCatching { d.gefallen() }.getOrDefault(emptyList()) }
		launch { nicht = runCatching { d.nichtGemocht() }.getOrDefault(emptyList()) }
		val v = runCatching { d.verlauf() }.getOrDefault(emptyList())
		zuletzt = v.filter { it.userData?.played == true }.distinctBy { it.seriesId ?: it.id }.take(30)
		ueber = runCatching { verlaufUeberblick(Sitzung.api!!, d, v) }.getOrNull()
	}
	LazyColumn(Modifier.fillMaxSize().selikuHintergrund(), contentPadding = PaddingValues(bottom = 24.dp)) {
		item { SeitenKopf("Mein Seliku", { nav.popBackStack() }) }
		item {
			Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
				Profilbild(76)
				Column(Modifier.padding(start = 16.dp)) {
					Text(Sitzung.benutzerName, fontSize = Schrift.Titel, fontWeight = FontWeight.Bold)
					ueber?.let { u ->
						Text("Diese Woche ${if (u.woche.geschaetzt) "ca. " else ""}${dauerText(u.woche.minuten)} · Diesen Monat ${if (u.monat.geschaetzt) "ca. " else ""}${dauerText(u.monat.minuten)}", fontSize = Schrift.Klein, color = Farben.TextLeise)
						if (u.genres.isNotEmpty()) Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
							u.genres.forEach { g -> Text(g, fontSize = Schrift.Klein, modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(Farben.FlaecheHell).padding(horizontal = 10.dp, vertical = 4.dp)) }
						}
					}
				}
			}
		}
		item { Reihe("Weiterschauen", weiter, schluessel = { it.seriesId ?: it.id }) { i -> QuerKachel(i, 220.dp) { abspielen(nav, c, i) } } }
		item { Reihe("Meine Liste", liste, schluessel = { it.id }) { i -> PosterKachel(i) { oeffnen(nav, c, i) } } }
		item { Reihe("Serien und Filme, die dir gefallen", gefallen, schluessel = { it.id }) { i -> PosterKachel(i) { oeffnen(nav, c, i) } } }
		item { Reihe("Zuletzt gesehen", zuletzt, schluessel = { it.seriesId ?: it.id }) { i -> PosterKachel(i) { oeffnen(nav, c, i) } } }
		item { Reihe("Nicht mehr empfehlen", nicht, schluessel = { it.id }) { i -> PosterKachel(i) { oeffnen(nav, c, i) } } }
	}
}

@Composable
fun FavoritenSeite() {
	val nav = LocalNav.current
	val c = LocalContext.current
	var liste by remember { mutableStateOf<List<BaseItemDto>?>(null) }
	LaunchedEffect(Datenstand.stand) { liste = runCatching { Sitzung.daten.favoriten() }.getOrDefault(emptyList()) }
	Column(Modifier.fillMaxSize().selikuHintergrund()) {
		SeitenKopf("Meine Liste", { nav.popBackStack() })
		val l = liste
		if (l == null) Laden() else if (l.isEmpty()) Hinweis("Noch nichts auf der Liste. Mit ♡ auf einer Detailseite hinzufügen.")
		else LazyVerticalGrid(GridCells.Adaptive(108.dp), contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
			items(l, key = { it.id }) { i -> PosterKachel(i, 108.dp) { oeffnen(nav, c, i) } }
		}
	}
}

// ───────────── Ordner ─────────────

@Composable
fun OrdnerSeite(id: UUID) {
	val nav = LocalNav.current
	val c = LocalContext.current
	var ordner by remember { mutableStateOf<BaseItemDto?>(null) }
	var inhalt by remember { mutableStateOf<List<BaseItemDto>?>(null) }
	LaunchedEffect(id, Datenstand.stand) {
		ordner = runCatching { Sitzung.daten.item(id) }.getOrNull()
		inhalt = runCatching { Sitzung.daten.ordnerInhalt(id) }.getOrDefault(emptyList())
	}
	Column(Modifier.fillMaxSize().selikuHintergrund()) {
		SeitenKopf(ordner?.name.orEmpty(), { nav.popBackStack() })
		val l = inhalt
		if (l == null) Laden() else {
			val audio = l.filter { it.type == BaseItemKind.AUDIO || it.type == BaseItemKind.AUDIO_BOOK }
			LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
				val ordnerListe = l.filter { it.isFolder == true }
				if (ordnerListe.isNotEmpty()) item { Reihe("Ordner", ordnerListe, schluessel = { it.id }) { i -> PosterKachel(i) { oeffnen(nav, c, i) } } }
				// Ordner mit Audiodateien = Hörbuch: mit eigener Stelle (seliku-hoeren) wie am TV
				if (audio.isNotEmpty()) item { ordner?.let { o -> Box(Modifier.padding(16.dp)) { val sc = rememberCoroutineScope(); Pille("▶  Weiterhören / Abspielen", hervorgehoben = true) { sc.launch { ch.seliku.handy.musik.Musik.buchSpielen(c, o) } } } } }
				items(l.filter { it.isFolder != true }, key = { it.id }) { i ->
					if (i in audio) Text(i.name.orEmpty(), Modifier.fillMaxWidth().clickable { ordner?.let { o -> kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch { ch.seliku.handy.musik.Musik.buchSpielen(c, o, audio.indexOf(i)) } } }.padding(horizontal = 16.dp, vertical = 12.dp))
					else Box(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) { QuerKachel(i, 320.dp) { oeffnen(nav, c, i) } }
				}
			}
		}
	}
}
