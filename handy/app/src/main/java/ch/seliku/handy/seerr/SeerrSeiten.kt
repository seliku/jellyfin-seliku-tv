package ch.seliku.handy.seerr

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import ch.seliku.handy.Sitzung
import ch.seliku.handy.daten.Farben
import ch.seliku.handy.ui.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val GRUEN = Color(0xFF5BC07A)

@Composable
fun SeerrAbzeichen(z: SeerrZustand, modifier: Modifier = Modifier) {
	val t = z.abzeichen ?: return
	val farbe = when (z) { SeerrZustand.ANSEHEN -> Farben.Akzent; SeerrZustand.GLEICH -> GRUEN; else -> Color(0xCC14100E) }
	Text(t, color = if (z == SeerrZustand.ANSEHEN || z == SeerrZustand.GLEICH) Color.Black else Farben.Text, fontSize = 10.sp, fontWeight = FontWeight.Bold,
		modifier = modifier.padding(5.dp).clip(RoundedCornerShape(5.dp)).background(farbe).padding(horizontal = 6.dp, vertical = 2.dp))
}

/** Kachel eines Seerr-Titels (Poster), mit Abzeichen; in gemischten Reihen «+ Anfragen». */
@Composable
fun SeerrKachel(t: SeerrTitel, breite: Dp = 112.dp, anfragenZeichen: Boolean = false, onClick: () -> Unit) {
	Column(Modifier.width(breite).clickable(onClick = onClick)) {
		Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp))) {
			Bild(t.poster, Modifier.fillMaxSize())
			if (anfragenZeichen && t.zustand == SeerrZustand.ANFRAGEN)
				Text("+ Anfragen", color = Color.Black, fontSize = 10.sp, fontWeight = FontWeight.Bold,
					modifier = Modifier.align(Alignment.TopEnd).padding(5.dp).clip(RoundedCornerShape(5.dp)).background(Farben.Akzent).padding(horizontal = 6.dp, vertical = 2.dp))
			else SeerrAbzeichen(t.zustand, Modifier.align(Alignment.TopEnd))
		}
		Text(t.titel, fontSize = Schrift.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
		Text(listOf(t.jahr, if (t.typ == "movie") "Film" else "Serie").filter { it.isNotBlank() }.joinToString(" · "), fontSize = Schrift.Klein, color = Farben.TextLeise, maxLines = 1)
	}
}

@Composable
fun RubrikKachel(r: SeerrRubrik, daten: SeerrDaten, onClick: () -> Unit) {
	var logo by remember(r.schluessel) { mutableStateOf(SeerrLogos.bekannt[r.schluessel]) }
	LaunchedEffect(r.schluessel) { if (r.istFirma && logo == null) logo = runCatching { daten.logo(r) }.getOrNull() ?: "" }
	Box(Modifier.width(180.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)).background(Farben.FlaecheHell).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
		if (r.istFirma) {
			val l = logo
			if (!l.isNullOrBlank()) AsyncImage(l, r.name, Modifier.fillMaxSize().padding(22.dp), contentScale = ContentScale.Fit)
			else Text(SeerrLogos.namen[r.schluessel] ?: r.name, fontWeight = FontWeight.Bold)
		} else {
			if (r.bild != null) AsyncImage(r.bild, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
			Box(Modifier.fillMaxSize().background(Color(0x77000000)))
			Text(r.name, fontWeight = FontWeight.Bold, fontSize = Schrift.Reihe)
		}
	}
}

/** Liste mit Nachladen beim Rollen (Reihe). */
@Composable
fun SeerrBlaetternReihe(titel: String, q: SeerrBlaettern, daten: SeerrDaten) {
	val nav = LocalNav.current
	val zustand = rememberLazyListState()
	val amEnde by remember { derivedStateOf { (zustand.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= q.liste.size - 6 } }
	LaunchedEffect(amEnde, q.liste.size) { if (amEnde && q.liste.isNotEmpty()) q.mehr(daten) }
	if (q.liste.isEmpty()) return
	Column {
		ReihenTitel(titel)
		LazyRow(state = zustand, contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
			items(q.liste, key = { it.schluessel }) { t -> SeerrKachel(t) { nav.navigate(Wege.seerr(t.typ, t.tmdb)) } }
		}
	}
}

private object EntdeckenSpeicher { var reihen: List<EntdeckenReihe>? = null }

/** Entdecken: zehn Reihen in fester Folge wie am TV; nur wenn Seerr verbunden ist. */
@Composable
fun EntdeckenSeite() {
	val c = LocalContext.current
	val nav = LocalNav.current
	val daten = remember { SeerrDaten(c, Sitzung.api!!) }
	var reihen by remember { mutableStateOf(EntdeckenSpeicher.reihen) }
	var fehler by remember { mutableStateOf<String?>(null) }
	var anfragen by remember { mutableStateOf<List<SeerrTitel>>(emptyList()) }
	LaunchedEffect(Unit) {
		if (reihen == null) {
			val r = listOf(
				EntdeckenReihe.Titel("Im Trend", SeerrBlaettern("/discover/trending", null)),
				EntdeckenReihe.Titel("Beliebte Filme", SeerrBlaettern("/discover/movies", "movie")),
				EntdeckenReihe.Rubriken("Filmgenres", runCatching { daten.genres("movie").map { it.copy(art = "film-genre") } }.getOrDefault(emptyList())),
				EntdeckenReihe.Titel("Bald im Kino", SeerrBlaettern("/discover/movies/upcoming", "movie")),
				EntdeckenReihe.Rubriken("Studios", SeerrStudios),
				EntdeckenReihe.Titel("Beliebte Serien", SeerrBlaettern("/discover/tv", "tv")),
				EntdeckenReihe.Rubriken("Seriengenres", runCatching { daten.genres("tv").map { it.copy(art = "serie-genre") } }.getOrDefault(emptyList())),
				EntdeckenReihe.Titel("Neue Serien demnächst", SeerrBlaettern("/discover/tv/upcoming", "tv")),
				EntdeckenReihe.Rubriken("Sender und Streamingdienste", SeerrSender),
			)
			try {
				coroutineScope { r.filterIsInstance<EntdeckenReihe.Titel>().map { t -> async { runCatching { t.quelle.erste(daten) }.exceptionOrNull()?.let { e -> if (t.titel in setOf("Im Trend", "Beliebte Filme", "Beliebte Serien")) throw e } } }.forEach { it.await() } }
				reihen = r; EntdeckenSpeicher.reihen = r
			} catch (e: Exception) {
				fehler = if ((e as? SeerrFehler)?.code in setOf(401, 403)) "Die Anmeldung bei Seerr ist abgelaufen – unter Einstellungen neu verbinden." else "Seerr ist gerade nicht erreichbar."
			}
		}
		anfragen = runCatching { daten.meineAnfragen().filter { it.zustand != SeerrZustand.ANSEHEN } }.getOrDefault(emptyList())
	}
	LazyColumn(Modifier.fillMaxSize().selikuHintergrund(), contentPadding = PaddingValues(bottom = 24.dp)) {
		item { SeitenKopf("Entdecken", null) }
		fehler?.let { item { Hinweis(it) } }
		val r = reihen
		if (r == null && fehler == null) item { Laden() }
		r?.forEach { reihe ->
			item(key = reihe.titel) {
				when (reihe) {
					is EntdeckenReihe.Titel -> SeerrBlaetternReihe(reihe.titel, reihe.quelle, daten)
					is EntdeckenReihe.Rubriken -> Reihe(reihe.titel, reihe.liste, schluessel = { it.schluessel }) { rb ->
						RubrikKachel(rb, daten) { nav.navigate("seerrliste/${rb.art}/${rb.id}?titel=" + Uri.encode(SeerrLogos.namen[rb.schluessel] ?: rb.name)) }
					}
				}
			}
		}
		if (anfragen.isNotEmpty()) item(key = "anfragen") {
			Reihe("Deine Anfragen", anfragen, schluessel = { it.schluessel }) { t -> SeerrKachel(t) { nav.navigate(Wege.seerr(t.typ, t.tmdb)) } }
		}
	}
}

/** Raster einer Rubrik (Genre, Studio, Sender) oder Filmografie einer Person. */
@Composable
fun SeerrListenSeite(art: String, id: Int, titel: String) {
	val c = LocalContext.current
	val nav = LocalNav.current
	val daten = remember { SeerrDaten(c, Sitzung.api!!) }
	val q = remember(art, id) { quelleFuer(art, id) }
	val raster = rememberLazyGridState()
	LaunchedEffect(q) { runCatching { q.erste(daten) } }
	val amEnde by remember { derivedStateOf { (raster.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= q.liste.size - 12 } }
	LaunchedEffect(amEnde, q.liste.size) { if (amEnde && q.liste.isNotEmpty()) q.mehr(daten) }
	Column(Modifier.fillMaxSize().selikuHintergrund()) {
		SeitenKopf(titel, { nav.popBackStack() })
		if (!q.begonnen) Laden()
		LazyVerticalGrid(GridCells.Adaptive(108.dp), state = raster, contentPadding = PaddingValues(16.dp),
			horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
			items(q.liste, key = { it.schluessel }) { t -> SeerrKachel(t, 108.dp) { nav.navigate(Wege.seerr(t.typ, t.tmdb)) } }
		}
	}
}

/** Seerr-Detail mit Anfrage-Knopf und Zustandslogik wie am TV (wenig Status, keine Restzeit). */
@Composable
fun SeerrDetailSeite(typ: String, tmdb: Int) {
	val c = LocalContext.current
	val nav = LocalNav.current
	val scope = rememberCoroutineScope()
	val daten = remember { SeerrDaten(c, Sitzung.api!!) }
	var t by remember { mutableStateOf<SeerrTitel?>(null) }
	var mehr by remember { mutableStateOf<SeerrMehr?>(null) }
	var tomaten by remember { mutableStateOf<Int?>(null) }
	var meldung by remember { mutableStateOf<String?>(null) }
	var laeuft by remember { mutableStateOf(false) }
	var stand by remember { mutableIntStateOf(0) }
	var textOffen by remember { mutableStateOf(false) }
	var empfohlen by remember { mutableStateOf<List<SeerrTitel>>(emptyList()) }
	var aehnlich by remember { mutableStateOf<List<SeerrTitel>>(emptyList()) }
	LaunchedEffect(stand) {
		runCatching { daten.detail(typ, tmdb) }.onSuccess { (a, b) ->
			// Schon da und in Jellyfin gefunden: gleich die eigene Seite (Zurück führt dorthin, woher man kam)
			if (stand == 0 && a.zustand == SeerrZustand.ANSEHEN) {
				val j = runCatching { daten.inJellyfin(a) }.getOrNull()
				if (j != null) { nav.popBackStack(); nav.navigate(Wege.detail(j.id)); return@LaunchedEffect }
			}
			t = a; mehr = b
		}.onFailure { meldung = "Seerr ist gerade nicht erreichbar." }
		if (stand == 0) {
			launch { tomaten = runCatching { daten.rottenTomatoes(typ, tmdb) }.getOrNull() }
			launch { empfohlen = runCatching { daten.blatt("/$typ/$tmdb/recommendations?language=de", typ, 1).first }.getOrDefault(emptyList()) }
			launch { aehnlich = runCatching { daten.blatt("/$typ/$tmdb/similar?language=de", typ, 1).first }.getOrDefault(emptyList()) }
		}
	}
	// Automatisch nachsehen, solange es läuft (nicht bei «Noch nicht verfügbar»)
	LaunchedEffect(t?.zustand) {
		if (t?.zustand in setOf(SeerrZustand.ANGEFRAGT, SeerrZustand.WIRD_GESUCHT, SeerrZustand.GLEICH)) { delay(30_000); stand++ }
	}
	val x = t
	LazyColumn(Modifier.fillMaxSize().background(Farben.Hintergrund), contentPadding = PaddingValues(bottom = 32.dp)) {
		item {
			Box(Modifier.fillMaxWidth().height(280.dp)) {
				Bild(x?.hintergrund, Modifier.fillMaxSize())
				Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color(0x88120D0B), 0.3f to Color.Transparent, 1f to Farben.Hintergrund)))
				IconButton({ nav.popBackStack() }, Modifier.statusBarsPadding().padding(4.dp)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück") }
				if (x != null) Row(Modifier.align(Alignment.BottomStart).padding(horizontal = 16.dp), verticalAlignment = Alignment.Bottom) {
					Bild(x.poster, Modifier.width(100.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp)))
					Text(x.titel, fontSize = Schrift.Titel, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 14.dp).weight(1f), maxLines = 3, overflow = TextOverflow.Ellipsis)
				}
			}
		}
		if (x == null) { item { if (meldung != null) Hinweis(meldung!!) else Laden() }; return@LazyColumn }
		item {
			Column(Modifier.padding(16.dp)) {
				val m = mehr
				Text(listOfNotNull(x.jahr.ifBlank { null }, if (typ == "movie") "Film" else "Serie",
					m?.laufzeit?.takeIf { typ == "movie" }?.let { if (it >= 60) "${it / 60} Std. ${it % 60} Min." else "$it Min." },
					m?.staffeln?.let { "$it Staffeln" }, m?.folgen?.let { "$it Folgen" }).joinToString("  ·  "), fontSize = Schrift.Klein, color = Farben.TextLeise)
				if (m != null && m.genres.isNotEmpty()) Text(m.genres.joinToString(", "), fontSize = Schrift.Klein, color = Farben.TextLeise)
				val bew = listOfNotNull(m?.bewertung?.let { "★ %.1f (TMDB)".format(it) }, tomaten?.let { "Rotten Tomatoes $it %" })
				if (bew.isNotEmpty()) Text(bew.joinToString("  ·  "), fontSize = Schrift.Klein, color = Farben.TextLeise)
				Spacer(Modifier.height(12.dp))
				FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
					when {
						laeuft -> Pille("Wird angefragt …") {}
						x.zustand == SeerrZustand.ANSEHEN -> Pille("▶  Ansehen", hervorgehoben = true) {
							scope.launch { val j = runCatching { daten.inJellyfin(x) }.getOrNull(); if (j != null) nav.navigate(Wege.detail(j.id)) else meldung = "In Jellyfin noch nicht gefunden – die Bibliothek liest es gleich ein." }
						}
						x.zustand == SeerrZustand.GLEICH -> Pille("↓  Gleich verfügbar") { stand++ }
						x.zustand == SeerrZustand.NOCH_NICHT -> Pille("…  Noch nicht verfügbar") { stand++ }
						x.zustand == SeerrZustand.WIRD_GESUCHT -> Pille("⌕  Wird gesucht") { stand++ }
						x.zustand == SeerrZustand.ANGEFRAGT -> Pille("⏳  Angefragt – wartet auf Freigabe") { stand++ }
						typ == "tv" && m?.ohneTvdb == true -> Pille("–  Nicht anfragbar") { meldung = "Diese Serie fehlt bei TheTVDB, deshalb kann Sonarr sie nicht laden. Eine Anfrage würde verworfen." }
						else -> Pille("+  Anfragen", hervorgehoben = true) {
							laeuft = true; meldung = null
							scope.launch {
								runCatching { daten.anfragen(x) }.onFailure { e ->
									meldung = when ((e as? SeerrFehler)?.code) {
										202 -> "Seerr hat die Anfrage nicht angenommen. Ein Admin kann den Eintrag in Seerr zurücksetzen («Daten löschen»)."
										403 -> "Dafür fehlt in Seerr die Berechtigung."
										else -> "Fehlgeschlagen: ${e.message}"
									}
								}
								laeuft = false; stand++
							}
						}
					}
					if (x.anfrageVonMir && x.zustand in setOf(SeerrZustand.ANGEFRAGT, SeerrZustand.WIRD_GESUCHT, SeerrZustand.NOCH_NICHT))
						Pille("✕  Anfrage zurücknehmen") { scope.launch { runCatching { daten.zuruecknehmen(x) }.onFailure { meldung = "Zurücknehmen fehlgeschlagen" }; stand++ } }
					m?.youtube?.let { k -> Pille("▸  Trailer") { c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$k"))) } }
				}
				if (x.zustand == SeerrZustand.NOCH_NICHT) Text("Bisher hat sich keine passende Quelle gefunden. Die Anfrage bleibt bestehen und wird weiter gesucht.", fontSize = Schrift.Klein, color = Farben.TextLeise, modifier = Modifier.padding(top = 8.dp))
				meldung?.let { Text(it, color = Farben.Akzent, fontSize = Schrift.Leise, modifier = Modifier.padding(top = 8.dp)) }
				if (x.text.isNotBlank()) Text(x.text, maxLines = if (textOffen) Int.MAX_VALUE else 5, overflow = TextOverflow.Ellipsis, fontSize = Schrift.Normal,
					modifier = Modifier.padding(top = 12.dp).clickable { textOffen = !textOffen })
				m?.macher?.let { Text((if (typ == "movie") "Regie: " else "Idee: ") + it, fontSize = Schrift.Klein, color = Farben.TextLeise, modifier = Modifier.padding(top = 8.dp)) }
				m?.firmen?.let { Text(it, fontSize = Schrift.Klein, color = Farben.TextLeise) }
			}
		}
		mehr?.besetzung?.takeIf { it.isNotEmpty() }?.let { b ->
			item { Reihe("Besetzung", b, schluessel = { it.id }) { p ->
				Column(Modifier.width(92.dp).clickable { nav.navigate("seerrliste/person/${p.id}?titel=" + Uri.encode(p.name)) }, horizontalAlignment = Alignment.CenterHorizontally) {
					Bild(p.bild, Modifier.size(80.dp).clip(CircleShape))
					Text(p.name, fontSize = Schrift.Klein, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
					Text(p.rolle, fontSize = Schrift.Klein, color = Farben.TextLeise, maxLines = 1, overflow = TextOverflow.Ellipsis)
				}
			} }
		}
		if (empfohlen.isNotEmpty()) item { Reihe("Empfehlungen", empfohlen, schluessel = { it.schluessel }) { e -> SeerrKachel(e) { nav.navigate(Wege.seerr(e.typ, e.tmdb)) } } }
		if (aehnlich.isNotEmpty()) item { Reihe("Ähnliche Titel", aehnlich, schluessel = { it.schluessel }) { e -> SeerrKachel(e) { nav.navigate(Wege.seerr(e.typ, e.tmdb)) } } }
	}
}
