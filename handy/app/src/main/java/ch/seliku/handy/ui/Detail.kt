package ch.seliku.handy.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import ch.seliku.handy.Einstellungen
import ch.seliku.handy.Sitzung
import ch.seliku.handy.daten.Farben
import ch.seliku.handy.seerr.SeerrDaten
import ch.seliku.handy.seerr.SeerrKachel
import ch.seliku.handy.seerr.SeerrTitel
import ch.seliku.handy.seerr.SeerrVerbindung
import ch.seliku.handy.seerr.SeerrZustand
import ch.seliku.handy.wiedergabe.TitelMusik
import kotlinx.coroutines.launch
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.BaseItemPerson
import org.jellyfin.sdk.model.api.PersonKind
import java.util.UUID

/**
 * Detailseite für Filme und Serien (am TV `TitelSeite`/`SerienSeite`). Staffeln führen zur Serie, Folgen spielen
 * direkt (es gibt keine eigene Folgen-Seite). Oben das Hintergrundbild, darunter Kopf, Knöpfe, Folgen, Besetzung,
 * «Ähnliche Titel» gemischt mit fehlenden aus Seerr.
 */
@Composable
fun DetailSeite(id: UUID) {
	val nav = LocalNav.current
	val c = LocalContext.current
	val scope = rememberCoroutineScope()
	val d = remember { Sitzung.daten }
	var item by remember { mutableStateOf<BaseItemDto?>(null) }
	var nichtDa by remember { mutableStateOf(false) }
	var naechste by remember { mutableStateOf<BaseItemDto?>(null) }
	var staffeln by remember { mutableStateOf<List<BaseItemDto>>(emptyList()) }
	var staffel by rememberSaveable { mutableStateOf<String?>(null) }
	var folgen by remember { mutableStateOf<List<BaseItemDto>?>(null) }
	var aehnlich by remember { mutableStateOf<List<BaseItemDto>>(emptyList()) }
	var fehlend by remember { mutableStateOf<List<SeerrTitel>>(emptyList()) }
	var trailer by remember { mutableStateOf<BaseItemDto?>(null) }
	var textOffen by remember { mutableStateOf(false) }
	var sterne by remember { mutableStateOf(false) }

	LaunchedEffect(id, Datenstand.stand) {
		val i = runCatching { d.item(id) }.getOrElse { nichtDa = true; return@LaunchedEffect }
		// Folge oder Staffel: zur Serie
		if (i.type == BaseItemKind.SEASON || i.type == BaseItemKind.EPISODE) { i.seriesId?.let { nav.popBackStack(); nav.navigate(Wege.detail(it)) }; return@LaunchedEffect }
		item = i
		if (i.type == BaseItemKind.SERIES) {
			naechste = runCatching { d.naechsteFolge(i.id) }.getOrNull()
			staffeln = runCatching { d.staffeln(i.id) }.getOrDefault(emptyList())
			if (staffel == null) staffel = (staffeln.firstOrNull { it.id == naechste?.seasonId } ?: staffeln.firstOrNull())?.id?.toString()
		}
		launch { aehnlich = runCatching { d.aehnlich(i.id) }.getOrDefault(emptyList()) }
		launch { trailer = runCatching { Sitzung.api!!.userLibraryApi.getLocalTrailers(itemId = i.id).content.firstOrNull() }.getOrNull() }
		launch {
			val tmdb = i.providerIds?.entries?.firstOrNull { it.key.equals("Tmdb", true) }?.value?.toIntOrNull()
			if (tmdb != null && SeerrVerbindung.verbunden(c)) {
				val typ = if (i.type == BaseItemKind.SERIES) "tv" else "movie"
				val s = SeerrDaten(c, Sitzung.api!!)
				fehlend = runCatching {
					(1..2).flatMap { p -> s.blatt("/$typ/$tmdb/recommendations?language=de", typ, p).first }
						.filter { it.zustand != SeerrZustand.ANSEHEN }.distinctBy { it.schluessel }
				}.getOrDefault(emptyList())
			}
		}
	}
	LaunchedEffect(staffel, Datenstand.stand) {
		val i = item ?: return@LaunchedEffect
		val s = staffel?.let { UUID.fromString(it) } ?: return@LaunchedEffect
		folgen = runCatching { d.folgen(i.id, s) }.getOrNull()
	}

	if (nichtDa) { Column(Modifier.fillMaxSize().selikuHintergrund()) { SeitenKopf("", { nav.popBackStack() }); Hinweis("Nicht mehr vorhanden") }; return }
	val i = item ?: run { Box(Modifier.fillMaxSize().selikuHintergrund()) { Laden(Modifier.align(Alignment.Center)) }; return }
	val serie = i.type == BaseItemKind.SERIES
	if (serie && Einstellungen.an("titelmusik")) TitelMusik(i.id)

	LazyColumn(Modifier.fillMaxSize().background(Farben.Hintergrund), contentPadding = PaddingValues(bottom = 32.dp)) {
		// Kopf: Hintergrundbild mit Verlauf, Poster, Logo/Name, Metazeile
		item(key = "kopf") {
			Box(Modifier.fillMaxWidth().height(300.dp)) {
				Bild(Bilder.hintergrund(i, 1280) ?: Bilder.poster(i, 800), Modifier.fillMaxSize())
				Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color(0x88120D0B), 0.3f to Color.Transparent, 1f to Farben.Hintergrund)))
				IconButton({ nav.popBackStack() }, Modifier.statusBarsPadding().padding(4.dp)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück") }
				Row(Modifier.align(Alignment.BottomStart).padding(horizontal = 16.dp), verticalAlignment = Alignment.Bottom) {
					Bild(Bilder.poster(i, 300), Modifier.width(100.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp)))
					Column(Modifier.padding(start = 14.dp).weight(1f)) {
						val logo = Bilder.logo(i)
						if (logo != null) AsyncImage(logo, i.name, Modifier.height(56.dp).widthIn(max = 240.dp), contentScale = ContentScale.Fit, alignment = Alignment.CenterStart)
						else Text(i.name.orEmpty(), fontSize = Schrift.Titel, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
					}
				}
			}
		}
		item(key = "info") {
			Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
				Text(metazeile(i), fontSize = Schrift.Klein, color = Farben.TextLeise)
				if (serie) naechste?.let { n -> Text("Als Nächstes: S${n.parentIndexNumber ?: 0} · F${n.indexNumber ?: 0} ${n.name.orEmpty()}", fontSize = Schrift.Leise, modifier = Modifier.padding(top = 4.dp)) }
				val regie = i.people?.filter { it.type == PersonKind.DIRECTOR }?.take(2)?.mapNotNull { it.name }.orEmpty()
				if (!serie && regie.isNotEmpty()) Text("Regie: ${regie.joinToString(", ")}", fontSize = Schrift.Leise, color = Farben.TextLeise, modifier = Modifier.padding(top = 4.dp))
				i.taglines?.firstOrNull()?.let { Text(it, color = Farben.Akzent, fontSize = Schrift.Leise, modifier = Modifier.padding(top = 6.dp)) }
				// Knöpfe
				val pos = i.userData?.playbackPositionTicks ?: 0L
				FlowRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
					if (serie) {
						val n = naechste
						val text = when {
							n != null && (n.userData?.playbackPositionTicks ?: 0) > 0 -> "▶  Fortsetzen ab ${zeit(n.userData!!.playbackPositionTicks / 10_000)}"
							n != null -> "▶  Abspielen S${n.parentIndexNumber ?: 0} F${n.indexNumber ?: 0}"
							else -> "▶  Abspielen"
						}
						Pille(text, hervorgehoben = true) { scope.launch { if (n != null) abspielen(nav, c, n) else serieAbspielen(nav, c, i.id, zufall = true) } }
						if (Einstellungen.an("zufall_knopf")) Pille("⤨  Zufällig") { scope.launch { serieAbspielen(nav, c, i.id, zufall = true) } }
					} else {
						Pille(if (pos > 0 && i.userData?.played != true) "▶  Fortsetzen ab ${zeit(pos / 10_000)}" else "▶  Abspielen", hervorgehoben = true) { abspielen(nav, c, i) }
						if (pos > 0) Pille("↺  Von vorn") { abspielen(nav, c, i, vonVorn = true) }
					}
					Pille("📺") { if (serie) scope.launch { (naechste ?: runCatching { d.alleFolgen(i.id).firstOrNull() }.getOrNull())?.let { Fernsteuerung.titel = it } } else Fernsteuerung.titel = i }
					trailer?.let { t -> Pille("▸  Trailer") { nav.navigate(Wege.spielen(t.id, 0)) } }
					val gesehen = i.userData?.played == true
					Pille(if (gesehen) "✓  Gesehen" else "○  Ungesehen", aktiv = gesehen) { scope.launch { runCatching { d.gesehen(i.id, !gesehen) }; Datenstand.neu() } }
					val fav = i.userData?.isFavorite == true
					Pille(if (fav) "♥" else "♡", aktiv = fav) { scope.launch { runCatching { d.favorit(i.id, !fav) }; Datenstand.neu() } }
					if (Einstellungen.an("eigene_wertung")) {
						val s = i.userData?.rating?.let { Math.round(it / 2).toInt() }?.takeIf { it in 1..5 }
						Pille(if (s != null) "★ $s" else "☆  Bewerten", aktiv = s != null) { sterne = true }
					}
					val menue = LocalTitelMenue.current
					Pille("⋮") { menue(i) }
				}
				val text = i.overview.orEmpty().takeUnless { it.startsWith("http") }.orEmpty()
				if (text.isNotBlank()) Text(text, fontSize = Schrift.Normal, color = Farben.Text.copy(alpha = 0.9f), maxLines = if (textOffen) Int.MAX_VALUE else 4,
					overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 14.dp).animateContentSize().clickable { textOffen = !textOffen })
			}
		}
		if (serie) {
			if (staffeln.size > 1) item(key = "staffeln") {
				LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
					items(staffeln, key = { it.id }) { s ->
						val name = s.name.orEmpty().replace("Season Unknown", "Folgen").replace(Regex("^Season (\\d+)$"), "Staffel $1")
						Pille(name, aktiv = s.id.toString() == staffel) { staffel = s.id.toString() }
					}
				}
			}
			val f = folgen
			if (f == null) item { Laden() }
			else items(f, key = { "f" + it.id }) { e -> FolgenZeile(e) { abspielen(nav, c, e) } }
		}
		val besetzung = i.people?.filter { it.type == PersonKind.ACTOR }?.take(30).orEmpty()
		if (besetzung.isNotEmpty()) item(key = "besetzung") {
			Reihe("Besetzung", besetzung, schluessel = { it.id }) { p -> PersonKachel(p) { personOeffnen(nav, c, p) } }
		}
		if (aehnlich.isNotEmpty() || fehlend.isNotEmpty()) item(key = "aehnlich") {
			// Gemischt: eigen, fehlend, eigen … (fehlende tragen «+ Anfragen»)
			val gemischt = buildList<Any> {
				val a = aehnlich.iterator(); val b = fehlend.iterator()
				while (a.hasNext() || b.hasNext()) { if (a.hasNext()) add(a.next()); if (b.hasNext()) add(b.next()) }
			}
			Reihe(if (serie) "Ähnliche Serien" else "Ähnliche Titel", gemischt) { x ->
				when (x) {
					is BaseItemDto -> PosterKachel(x) { oeffnen(nav, c, x) }
					is SeerrTitel -> SeerrKachel(x, anfragenZeichen = true) { nav.navigate(Wege.seerr(x.typ, x.tmdb)) }
				}
			}
		}
	}
	if (sterne) WertungAuswahl(i, { sterne = false }) { Datenstand.neu() }
}

/** Folge als Zeile: Bild links, Nummer und Titel, Laufzeit, Beschreibung. Antippen spielt ab. */
@Composable
fun FolgenZeile(e: BaseItemDto, onClick: () -> Unit) {
	val menue = LocalTitelMenue.current
	Row(Modifier.fillMaxWidth().combinedClickableKurz(onClick) { menue(e) }.padding(horizontal = 16.dp, vertical = 8.dp)) {
		Box(Modifier.width(150.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(6.dp))) {
			Bild(Bilder.quer(e, 400), Modifier.fillMaxSize())
			if (e.userData?.played == true) Abzeichen(e, Modifier.align(Alignment.TopEnd))
			fortschritt(e)?.let { Fortschrittsbalken(it, Modifier.align(Alignment.BottomCenter)) }
		}
		Column(Modifier.padding(start = 12.dp).weight(1f)) {
			Text(folgenTitel(e), fontSize = Schrift.Normal, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
			Text(listOfNotNull(laufzeit(e.runTimeTicks), if (e.userData?.played == true) "gesehen" else null).joinToString(" · "), fontSize = Schrift.Klein, color = Farben.TextLeise)
			e.overview?.let { Text(it, fontSize = Schrift.Klein, color = Farben.TextLeise, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp)) }
		}
	}
}

/** «6. Titel», doppelte Nummer aus dem Dateinamen entfernt (wie `folgenTitel` am TV). */
fun folgenTitel(e: BaseItemDto): String {
	val n = e.indexNumber
	val name = e.name.orEmpty().replace(Regex("^(S\\d+E\\d+|\\d+x\\d+|\\d+)[ .\\-:]+", RegexOption.IGNORE_CASE), "")
	return if (n != null) "$n. $name" else name
}

@Composable
fun PersonKachel(p: BaseItemPerson, onClick: () -> Unit) {
	Column(Modifier.width(92.dp).clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
		Bild(p.primaryImageTag?.let { Bilder.item(p.id, "Primary", 200) }, Modifier.size(80.dp).clip(CircleShape))
		Text(p.name.orEmpty(), fontSize = Schrift.Klein, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
		Text(p.role.orEmpty(), fontSize = Schrift.Klein, color = Farben.TextLeise, maxLines = 1, overflow = TextOverflow.Ellipsis)
	}
}

/** Person → TMDb-Nummer holen → Filmografie über Seerr (wie am TV). */
fun personOeffnen(nav: androidx.navigation.NavHostController, c: android.content.Context, p: BaseItemPerson) {
	kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
		val tmdb = runCatching { Sitzung.daten.item(p.id).providerIds?.entries?.firstOrNull { it.key.equals("Tmdb", true) }?.value?.toIntOrNull() }.getOrNull()
		if (tmdb != null && SeerrVerbindung.verbunden(c)) nav.navigate("seerrliste/person/$tmdb?titel=" + android.net.Uri.encode(p.name.orEmpty()))
		else android.widget.Toast.makeText(c, "Keine Filmografie verfügbar", android.widget.Toast.LENGTH_SHORT).show()
	}
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
fun Modifier.combinedClickableKurz(onClick: () -> Unit, onLong: () -> Unit) = this.then(Modifier.combinedClickable(onClick = onClick, onLongClick = onLong))
