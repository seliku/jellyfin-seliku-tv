package ch.seliku.handy.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ch.seliku.handy.Einstellungen
import ch.seliku.handy.Sitzung
import ch.seliku.handy.daten.Farben
import ch.seliku.handy.daten.SelikuDaten
import ch.seliku.handy.daten.Sortierung
import ch.seliku.handy.daten.typenFuer
import ch.seliku.handy.seerr.SeerrDaten
import ch.seliku.handy.seerr.SeerrTitel
import ch.seliku.handy.seerr.SeerrVerbindung
import ch.seliku.handy.seerr.SeerrZustand
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CollectionType
import java.util.UUID


/** Liste aller Bibliotheken (Reihenfolge wie die Leiste am TV: Filme, Serien, Live-TV, Musik, Rest). */
@Composable
fun BibliothekenSeite() {
	val nav = LocalNav.current
	var bibs by remember { mutableStateOf<List<BaseItemDto>?>(null) }
	LaunchedEffect(Datenstand.stand) {
		bibs = runCatching { Sitzung.daten.bibliotheken() }.getOrDefault(emptyList())
			.filter { it.collectionType != CollectionType.PLAYLISTS }
			.sortedBy { gruppeFuer(it, emptySet()) }
	}
	Column(Modifier.fillMaxSize().selikuHintergrund()) {
		SeitenKopf("Bibliotheken", null)
		val l = bibs
		if (l == null) Laden() else LazyVerticalGrid(GridCells.Adaptive(160.dp), contentPadding = PaddingValues(16.dp),
			horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
			items(l, key = { it.id }) { b ->
				QuerKachel(b, 200.dp, zeile1 = if (b.imageTags?.containsKey(org.jellyfin.sdk.model.api.ImageType.PRIMARY) == true) "" else b.name.orEmpty(), zeile2 = "", bildUrl = Bilder.item(b.id, "Primary", 480)) { bibliothekOeffnen(nav, b) }
			}
		}
	}
}

/** Reihen der Bibliotheks-Startseite «Empfohlen» (wie `bibliothekReihenLaden` am TV). */
suspend fun bibliothekReihenLaden(c: android.content.Context, d: SelikuDaten, bib: BaseItemDto): List<StartReihe> = coroutineScope {
	val serien = bib.collectionType == CollectionType.TVSHOWS
	val typen = typenFuer(bib)
	fun <T> s(f: suspend () -> T) = async { runCatching { f() }.getOrNull() }
	val weiter = s { d.weiterschauenIn(bib).let { w -> if (Einstellungen.an("weiterschauen_geraet")) w.filter { GeraetVerlauf.enthaelt(c, it) } else w } }
	val neu = s { d.neuIn(bib) }
	val weil = s { d.weilDuGeschaut(bib, serien) }
	val top = s { d.topUngesehen(bib, typen) }
	val zuletzt = s { d.zuletztGesehen(bib, serien) }
	val wieder = s { d.wiederentdecken(bib, typen) }
	val genres = runCatching { d.genres(bib, typen) }.getOrDefault(emptyList()).shuffled().take(4)
	val genreReihen = genres.map { g -> s { StartReihe(g, d.genreReihe(bib, typen, g), StartReihe.Art.ANDERE, 0) } }
	buildList {
		weiter.await()?.let { add(StartReihe("Weiterschauen", it, StartReihe.Art.WEITER, 0)) }
		neu.await()?.let { add(StartReihe("Neu hinzugefügt", it, StartReihe.Art.NEU, 0)) }
		weil.await()?.let { (name, l) -> add(StartReihe("Weil du «$name» geschaut hast", l, StartReihe.Art.NEU, 0)) }
		top.await()?.let { add(StartReihe(if (serien) "Top-Serien, noch nicht gesehen" else "Top-Filme, noch nicht gesehen", it, StartReihe.Art.NEU, 0)) }
		genreReihen.forEach { r -> r.await()?.let { add(it) } }
		zuletzt.await()?.let { add(StartReihe("Zuletzt gesehen", it, StartReihe.Art.NEU, 0)) }
		wieder.await()?.let { add(StartReihe("Wiederentdecken", it, StartReihe.Art.NEU, 0)) }
	}.filter { it.items.isNotEmpty() }
}

/** Bibliothek: Reiter «Empfohlen» (Reihen) und «Bibliothek» (Raster mit Sortierung, Filtern, Genres). */
@Composable
fun BibliothekStartSeite(id: UUID) {
	val nav = LocalNav.current
	val c = LocalContext.current
	var bib by remember { mutableStateOf<BaseItemDto?>(null) }
	var reiter by rememberSaveable { mutableIntStateOf(0) }
	LaunchedEffect(id) { bib = runCatching { Sitzung.daten.bibliotheken().firstOrNull { it.id == id } ?: Sitzung.daten.item(id) }.getOrNull() }
	val b = bib
	Column(Modifier.fillMaxSize().selikuHintergrund()) {
		SeitenKopf(b?.name.orEmpty(), { nav.popBackStack() })
		Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
			Pille("Empfohlen", aktiv = reiter == 0) { reiter = 0 }
			Pille("Bibliothek", aktiv = reiter == 1) { reiter = 1 }
		}
		if (b == null) Laden()
		else if (reiter == 0) BibliothekEmpfohlen(b) else BibliothekRaster(b, null)
	}
}

@Composable
private fun BibliothekEmpfohlen(b: BaseItemDto) {
	val nav = LocalNav.current
	val c = LocalContext.current
	val d = remember { Sitzung.daten }
	var reihen by remember(b.id) { mutableStateOf(Zwischenspeicher.bibReihen[b.id]) }
	var banner by remember(b.id) { mutableStateOf(Zwischenspeicher.bibBanner[b.id].orEmpty()) }
	var fehlend by remember(b.id) { mutableStateOf<List<SeerrTitel>>(emptyList()) }
	val seerrTyp = when (b.collectionType) { CollectionType.MOVIES -> "movie"; CollectionType.TVSHOWS -> "tv"; else -> null }
	LaunchedEffect(b.id, Datenstand.stand) {
		coroutineScope {
			val bn = async { if (banner.isEmpty()) runCatching { bannerLaden(c, d, b, seerrTyp) }.getOrNull() else null }
			val f = async {
				if (seerrTyp != null && SeerrVerbindung.verbunden(c)) runCatching {
					val s = SeerrDaten(c, Sitzung.api!!)
					val l = if (seerrTyp == "movie") s.beliebteFilme(1) + s.beliebteFilme(2) else s.beliebteSerien(1) + s.beliebteSerien(2)
					l.filter { it.zustand != SeerrZustand.ANSEHEN }.distinctBy { it.schluessel }.take(30)
				}.getOrDefault(emptyList()) else emptyList()
			}
			runCatching { bibliothekReihenLaden(c, d, b) }.onSuccess { reihen = it; Zwischenspeicher.bibReihen[b.id] = it }
			bn.await()?.let { banner = it; Zwischenspeicher.bibBanner[b.id] = it }
			fehlend = f.await()
		}
	}
	LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
		if (banner.isNotEmpty()) item { Box(Modifier.padding(top = 8.dp)) { Banner(banner) } }
		val r = reihen
		if (r == null) item { Laden() } else r.forEach { reihe ->
			item(key = reihe.titel) {
				if (reihe.art == StartReihe.Art.WEITER) Reihe(reihe.titel, reihe.items, schluessel = { it.seriesId ?: it.id }) { i ->
					QuerKachel(i, 240.dp) { abspielen(nav, c, i) }
				} else Reihe(reihe.titel, reihe.items, schluessel = { it.id }) { i -> PosterKachel(i) { oeffnen(nav, c, i) } }
			}
		}
		if (fehlend.isNotEmpty()) item(key = "seerr") {
			Reihe(if (seerrTyp == "movie") "Beliebte Filme – noch nicht da" else "Beliebte Serien – noch nicht da", fehlend, schluessel = { it.schluessel }) { t ->
				ch.seliku.handy.seerr.SeerrKachel(t) { nav.navigate(Wege.seerr(t.typ, t.tmdb)) }
			}
		}
	}
}

/** Raster wie am TV: Sortierungen, «Nur ungesehene», «Favoriten», «Zufällig», Genres; lädt beim Rollen nach (60 je Seite). */
@Composable
fun BibliothekRaster(b: BaseItemDto, startGenre: String?) {
	val nav = LocalNav.current
	val c = LocalContext.current
	val scope = rememberCoroutineScope()
	val d = remember { Sitzung.daten }
	val typen = remember(b.id) { typenFuer(b) }
	var sortierung by rememberSaveable { mutableStateOf(Sortierung.NEU) }
	var ungesehen by rememberSaveable { mutableStateOf(false) }
	var favoriten by rememberSaveable { mutableStateOf(false) }
	var genre by rememberSaveable { mutableStateOf(startGenre) }
	var genres by remember { mutableStateOf<List<String>>(emptyList()) }
	val liste = remember { mutableStateListOf<BaseItemDto>() }
	var gesamt by remember { mutableIntStateOf(-1) }
	var laedt by remember { mutableStateOf(false) }
	val raster = rememberLazyGridState()
	LaunchedEffect(b.id) { genres = runCatching { d.genres(b, typen) }.getOrDefault(emptyList()) }
	suspend fun nachladen() {
		if (laedt || (gesamt >= 0 && liste.size >= gesamt)) return
		laedt = true
		runCatching { d.inhalt(b, typen, sortierung, ungesehen, liste.size, 60, genre, favoriten) }.onSuccess { r ->
			val da = liste.map { it.id }.toHashSet()
			liste.addAll(r.items.filter { da.add(it.id) }); gesamt = r.totalRecordCount
		}.onFailure { gesamt = liste.size }
		laedt = false
	}
	LaunchedEffect(sortierung, ungesehen, favoriten, genre, Datenstand.stand) { liste.clear(); gesamt = -1; raster.scrollToItem(0); nachladen() }
	val amEnde by remember { derivedStateOf { (raster.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= liste.size - 18 } }
	LaunchedEffect(amEnde) { if (amEnde) nachladen() }
	Column(Modifier.fillMaxSize()) {
		LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
			items(Sortierung.entries) { s -> Pille(s.titel, aktiv = s == sortierung) { sortierung = s } }
			item { Pille(if (ungesehen) "◐  Nur ungesehene" else "◐  Alle", aktiv = ungesehen) { ungesehen = !ungesehen } }
			item { Pille(if (favoriten) "♥  Favoriten" else "♡  Favoriten", aktiv = favoriten) { favoriten = !favoriten } }
			item { Pille("⤨  Zufällig") { liste.randomOrNull()?.let { i -> scope.launch { if (i.type == BaseItemKind.SERIES) serieAbspielen(nav, c, i.id, zufall = true) else abspielen(nav, c, i, vonVorn = true) } } } }
		}
		if (genres.isNotEmpty()) LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
			items(genres) { g -> Pille(g, aktiv = g == genre) { genre = if (genre == g) null else g } }
		}
		if (gesamt == 0) Hinweis("Nichts gefunden.")
		LazyVerticalGrid(GridCells.Adaptive(108.dp), state = raster, contentPadding = PaddingValues(16.dp),
			horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxSize()) {
			items(liste, key = { it.id }) { i -> PosterKachel(i, 108.dp) { oeffnen(nav, c, i) } }
		}
	}
}

@Composable
fun RasterSeite(id: UUID, genre: String?) {
	val nav = LocalNav.current
	var bib by remember { mutableStateOf<BaseItemDto?>(null) }
	LaunchedEffect(id) { bib = runCatching { Sitzung.daten.item(id) }.getOrNull() }
	Column(Modifier.fillMaxSize().selikuHintergrund()) {
		SeitenKopf(genre ?: bib?.name.orEmpty(), { nav.popBackStack() })
		bib?.let { BibliothekRaster(it, genre) } ?: Laden()
	}
}
