package ch.seliku.handy.musik

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import ch.seliku.handy.Einstellungen
import ch.seliku.handy.Sitzung
import ch.seliku.handy.daten.Farben
import ch.seliku.handy.ui.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.CollectionType
import org.jellyfin.sdk.model.api.ItemFilter
import org.jellyfin.sdk.model.api.ItemSortBy
import java.util.UUID

/** Automatische Mixe wie am TV (Schlüssel sprachunabhängig, Favoriten im Konto «seliku-musik»). */
data class Mix(val schluessel: String, val name: String)
private val grundMixe = listOf(Mix("zufall", "Zufallsmix"), Mix("neu", "Neu hinzugefügt"), Mix("meist", "Meistgehört"), Mix("lange", "Lange nicht gehört"))
private val mixFarben = listOf(0xFFFFB347 to 0xFFFF6B4A, 0xFFF08A4B to 0xFFA9552C, 0xFFE76F51 to 0xFF8E3B2E, 0xFFF4A261 to 0xFFB5651D, 0xFFE9C46A to 0xFFC7782F, 0xFFD1495B to 0xFF7A2D3A)

suspend fun mixTitel(bib: BaseItemDto, m: Mix): List<BaseItemDto> {
	val d = Sitzung.daten
	return when (m.schluessel) {
		"zufall" -> d.musikTitel(bib, ItemSortBy.RANDOM, true)
		"neu" -> d.musikTitel(bib, ItemSortBy.DATE_CREATED, true)
		"meist" -> d.musikTitel(bib, ItemSortBy.PLAY_COUNT, true, setOf(ItemFilter.IS_PLAYED))
		"lange" -> d.musikTitel(bib, ItemSortBy.DATE_PLAYED, false, setOf(ItemFilter.IS_PLAYED))
		else -> d.genreMix(m.schluessel.removePrefix("genre:"))
	}
}

@Composable
fun MixKachel(m: Mix, nr: Int, fav: Boolean, onClick: () -> Unit) {
	val (a, b) = mixFarben[nr % mixFarben.size]
	Box(Modifier.size(130.dp).clip(RoundedCornerShape(10.dp)).background(Brush.linearGradient(listOf(Color(a), Color(b)))).clickable(onClick = onClick).padding(12.dp)) {
		Text(m.name, color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.align(Alignment.BottomStart))
		if (fav) Text("♥", color = Color.Black, modifier = Modifier.align(Alignment.TopEnd))
	}
}

/** Musik: Reiter Übersicht · Playlists · Interpreten · Alben · Radio (Playlists gehören in die Musik, S 04.11.). */
@Composable
fun MusikSeite() {
	val nav = LocalNav.current
	var bib by remember { mutableStateOf<BaseItemDto?>(null) }
	var reiter by rememberSaveable { mutableIntStateOf(0) }
	var hoerBibs by remember { mutableStateOf<List<Pair<BaseItemDto, Boolean>>>(emptyList()) }
	LaunchedEffect(Unit) {
		hoerBibs = runCatching { Hoeren.bibliotheken().let { (p, b) -> p.map { it to true } + b.map { it to false } } }.getOrDefault(emptyList())
		bib = runCatching { Sitzung.daten.bibliotheken().filter { it.collectionType == CollectionType.MUSIC }.let { l -> l.firstOrNull { !it.name.orEmpty().contains("podcast", true) } ?: l.firstOrNull() } }.getOrNull()
	}
	Column(Modifier.fillMaxSize().selikuHintergrund()) {
		SeitenKopf(bib?.name ?: "Musik", { nav.popBackStack() })
		LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
			itemsIndexed(listOf("Übersicht", "Playlists", "Interpreten", "Alben", "Radio") + hoerBibs.map { it.first.name.orEmpty() }) { i, t -> Pille(t, aktiv = reiter == i) { reiter = i } }
		}
		val b = bib ?: run { Laden(); return@Column }
		when (reiter) {
			0 -> MusikUebersicht(b)
			1 -> MusikPlaylists(b)
			2 -> MusikRaster(b, interpreten = true)
			3 -> MusikRaster(b, interpreten = false)
			4 -> RadioInhalt()
			else -> hoerBibs.getOrNull(reiter - 5)?.let { (hb, pod) -> HoerInhalt(hb, pod) }
		}
	}
}

@Composable
private fun MusikUebersicht(b: BaseItemDto) {
	val nav = LocalNav.current
	val c = LocalContext.current
	val scope = rememberCoroutineScope()
	val d = remember { Sitzung.daten }
	var fav by remember { mutableStateOf<List<BaseItemDto>>(emptyList()) }
	var zuletzt by remember { mutableStateOf<List<BaseItemDto>>(emptyList()) }
	var neu by remember { mutableStateOf<List<BaseItemDto>>(emptyList()) }
	var playlists by remember { mutableStateOf<List<BaseItemDto>>(emptyList()) }
	var mixFav by remember { mutableStateOf<Set<String>>(emptySet()) }
	var radio by remember { mutableStateOf<List<RadioSender>>(emptyList()) }
	LaunchedEffect(b.id, Datenstand.stand) {
		launch { if (Einstellungen.an("musik_favoriten")) fav = runCatching { d.musikFavoriten(b) }.getOrDefault(emptyList()) }
		launch { zuletzt = runCatching { d.musikTitel(b, ItemSortBy.DATE_PLAYED, true, setOf(ItemFilter.IS_PLAYED), 30) }.getOrDefault(emptyList()) }
		launch { neu = runCatching { d.neuIn(b) }.getOrDefault(emptyList()) }
		launch { playlists = runCatching { d.playlists() }.getOrDefault(emptyList()) }
		launch { mixFav = runCatching { d.mixFavoriten() }.getOrDefault(emptySet()) }
		launch { val r = RadioDaten(Sitzung.api!!); radio = runCatching { r.favoriten().ifEmpty { r.beliebtIn("CH", 20) } }.getOrDefault(emptyList()) }
	}
	LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
		item { Reihe("Deine Favoriten", fav, schluessel = { it.id }) { i -> PosterKachel(i, 130.dp, quadrat = true, rund = i.type == org.jellyfin.sdk.model.api.BaseItemKind.MUSIC_ARTIST) { oeffnen(nav, c, i) } } }
		item { Reihe("Zuletzt gehört", zuletzt, schluessel = { it.id }) { i -> PosterKachel(i, 130.dp, quadrat = true) { Musik.spielen(c, zuletzt, zuletzt.indexOf(i)) } } }
		item { Reihe(if (radio.isNotEmpty()) "Radio" else "", radio, schluessel = { it.id }) { s -> RadioKachel(s) { Musik.radio(c, s) } } }
		item { Reihe("Neu hinzugefügt", neu, schluessel = { it.id }) { i -> PosterKachel(i, 130.dp, quadrat = true) { oeffnen(nav, c, i) } } }
		item {
			Reihe("Automatische Mixe", grundMixe.withIndex().toList(), schluessel = { it.value.schluessel }) { (n, m) ->
				MixKachel(m, n, m.schluessel in mixFav) { scope.launch { val t = runCatching { mixTitel(b, m) }.getOrDefault(emptyList()); if (t.isNotEmpty()) Musik.spielen(c, t, 0) } }
			}
		}
		item { Reihe("Deine Playlists", playlists.take(20), schluessel = { it.id }) { i -> PosterKachel(i, 130.dp, quadrat = true) { oeffnen(nav, c, i) } } }
	}
}

@Composable
private fun MusikPlaylists(b: BaseItemDto) {
	val nav = LocalNav.current
	val c = LocalContext.current
	val scope = rememberCoroutineScope()
	var eigene by remember { mutableStateOf<List<BaseItemDto>>(emptyList()) }
	var genres by remember { mutableStateOf<List<String>>(emptyList()) }
	LaunchedEffect(b.id) {
		eigene = runCatching { Sitzung.daten.playlists() }.getOrDefault(emptyList())
		genres = runCatching { Sitzung.daten.genres(b, setOf(org.jellyfin.sdk.model.api.BaseItemKind.MUSIC_ALBUM, org.jellyfin.sdk.model.api.BaseItemKind.AUDIO)) }.getOrDefault(emptyList())
	}
	val alle: List<Any> = (eigene + grundMixe + genres.map { Mix("genre:$it", it) }).sortedBy { (it as? BaseItemDto)?.name ?: (it as Mix).name }
	LazyVerticalGrid(GridCells.Adaptive(130.dp), contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
		items(alle.size) { n ->
			when (val x = alle[n]) {
				is BaseItemDto -> PosterKachel(x, 130.dp, quadrat = true) { oeffnen(nav, c, x) }
				is Mix -> MixKachel(x, n, false) { scope.launch { val t = runCatching { mixTitel(b, x) }.getOrDefault(emptyList()); if (t.isNotEmpty()) Musik.spielen(c, t, 0) } }
			}
		}
	}
}

@Composable
private fun MusikRaster(b: BaseItemDto, interpreten: Boolean) {
	val nav = LocalNav.current
	val c = LocalContext.current
	var liste by remember(interpreten) { mutableStateOf<List<BaseItemDto>?>(null) }
	LaunchedEffect(b.id, interpreten) {
		liste = runCatching { if (interpreten) Sitzung.daten.interpreten(b) else Sitzung.daten.alben(b, null) }.getOrDefault(emptyList())
	}
	val l = liste ?: run { Laden(); return }
	LazyVerticalGrid(GridCells.Adaptive(110.dp), contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
		items(l, key = { it.id }) { i -> PosterKachel(i, 110.dp, quadrat = !interpreten, rund = interpreten) { oeffnen(nav, c, i) } }
	}
}

/** Album oder Playlist: Antippen eines Titels spielt das Ganze ab diesem Titel. */
@Composable
fun AlbumSeite(id: UUID) {
	val nav = LocalNav.current
	val c = LocalContext.current
	val scope = rememberCoroutineScope()
	var album by remember { mutableStateOf<BaseItemDto?>(null) }
	var titel by remember { mutableStateOf<List<BaseItemDto>?>(null) }
	LaunchedEffect(id) {
		val a = runCatching { Sitzung.daten.item(id) }.getOrNull(); album = a
		titel = a?.let { runCatching { Musik.titelVon(it) }.getOrDefault(emptyList()) }
	}
	val a = album
	LazyColumn(Modifier.fillMaxSize().selikuHintergrund(), contentPadding = PaddingValues(bottom = 24.dp)) {
		item { SeitenKopf(a?.name.orEmpty(), { nav.popBackStack() }) }
		if (a != null) item {
			Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
				Bild(Bilder.poster(a, 600), Modifier.size(220.dp).clip(RoundedCornerShape(10.dp)))
				Text(a.name.orEmpty(), fontSize = Schrift.Titel, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
				Text(unterzeile(a), color = Farben.TextLeise)
				Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
					Pille("▶  Abspielen", hervorgehoben = true) { titel?.let { Musik.spielen(c, it, 0) } }
					Pille("⤨  Zufällig") { titel?.let { Musik.spielen(c, it, 0, zufall = true) } }
					val fav = a.userData?.isFavorite == true
					Pille(if (fav) "♥" else "♡", aktiv = fav) { scope.launch { runCatching { Sitzung.daten.favorit(a.id, !fav) }; album = runCatching { Sitzung.daten.item(id) }.getOrNull() } }
				}
			}
		}
		val t = titel
		if (t == null) item { Laden() }
		else itemsIndexed(t, key = { _, x -> x.id }) { n, x -> TitelZeile(x, n + 1) { Musik.spielen(c, t, n) } }
	}
}

@Composable
fun TitelZeile(x: BaseItemDto, nr: Int, onClick: () -> Unit) {
	val menue = LocalTitelMenue.current
	val aktiv = Musik.aktuelleIdState == x.id.toString()
	Row(Modifier.fillMaxWidth().combinedClickableKurz(onClick) { menue(x) }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
		Text(if (aktiv) "▶" else "${x.indexNumber ?: nr}", color = if (aktiv) Farben.Akzent else Farben.TextLeise, modifier = Modifier.width(32.dp))
		Column(Modifier.weight(1f)) {
			Text(x.name.orEmpty(), color = if (aktiv) Farben.Akzent else Farben.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
			Text(x.artists.orEmpty().joinToString(", "), fontSize = Schrift.Klein, color = Farben.TextLeise, maxLines = 1, overflow = TextOverflow.Ellipsis)
		}
		Text(laufzeitKurz(x.runTimeTicks), fontSize = Schrift.Klein, color = Farben.TextLeise)
	}
}

fun laufzeitKurz(t: Long?): String = t?.let { zeit(it / 10_000) }.orEmpty()

/** Interpret: Beliebt (10), Alben, Abspielen/Zufällig mit allen Titeln (Gastauftritte zählen mit). */
@Composable
fun InterpretSeite(id: UUID) {
	val nav = LocalNav.current
	val c = LocalContext.current
	var k by remember { mutableStateOf<BaseItemDto?>(null) }
	var beliebt by remember { mutableStateOf<List<BaseItemDto>>(emptyList()) }
	var alben by remember { mutableStateOf<List<BaseItemDto>>(emptyList()) }
	LaunchedEffect(id) {
		k = runCatching { Sitzung.daten.item(id) }.getOrNull()
		launch { beliebt = runCatching { Sitzung.daten.titelVon(id, beliebteste = true).take(10) }.getOrDefault(emptyList()) }
		launch { alben = runCatching { Sitzung.daten.alben(null, id) }.getOrDefault(emptyList()) }
	}
	LazyColumn(Modifier.fillMaxSize().selikuHintergrund(), contentPadding = PaddingValues(bottom = 24.dp)) {
		item { SeitenKopf(k?.name.orEmpty(), { nav.popBackStack() }) }
		k?.let { a -> item {
			Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
				Bild(Bilder.poster(a, 600), Modifier.size(180.dp).clip(androidx.compose.foundation.shape.CircleShape))
				Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
					val scope = rememberCoroutineScope()
					Pille("▶  Abspielen", hervorgehoben = true) { scope.launch { Musik.spielenItem(c, a) } }
					Pille("⤨  Zufällig") { scope.launch { Musik.spielenItem(c, a, zufall = true) } }
				}
			}
		} }
		if (beliebt.isNotEmpty()) { item { ReihenTitel("Beliebt") }; itemsIndexed(beliebt, key = { _, x -> "b" + x.id }) { n, x -> TitelZeile(x, n + 1) { Musik.spielen(c, beliebt, n) } } }
		item { Reihe("Alben", alben, schluessel = { it.id }) { i -> PosterKachel(i, 130.dp, quadrat = true) { oeffnen(nav, c, i) } } }
	}
}

// ───────────── Radio ─────────────

@Composable
fun RadioKachel(s: RadioSender, onClick: () -> Unit) {
	Column(Modifier.width(110.dp).clickable(onClick = onClick)) {
		Box(Modifier.size(110.dp).clip(RoundedCornerShape(10.dp)).background(Farben.FlaecheHell), contentAlignment = Alignment.Center) {
			if (s.logo != null) AsyncImage(s.logo, s.name, Modifier.fillMaxSize().padding(10.dp), contentScale = ContentScale.Fit)
			else Text(s.name.take(2), fontWeight = FontWeight.Bold)
		}
		Text(s.name, fontSize = Schrift.Klein, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
	}
}

@Composable
fun RadioSeite() {
	val nav = LocalNav.current
	Column(Modifier.fillMaxSize().selikuHintergrund()) { SeitenKopf("Radio", { nav.popBackStack() }); RadioInhalt() }
}

/** Radio wie am TV: Favoriten (im Konto), Beliebt in der Schweiz, Genres, Suche. Langes Drücken = Favorit. */
@Composable
fun RadioInhalt() {
	val c = LocalContext.current
	val scope = rememberCoroutineScope()
	val r = remember { RadioDaten(Sitzung.api!!) }
	var fav by remember { mutableStateOf<List<RadioSender>>(emptyList()) }
	var ch by remember { mutableStateOf<List<RadioSender>>(emptyList()) }
	var genres by remember { mutableStateOf<Map<String, List<RadioSender>>>(emptyMap()) }
	var suche by remember { mutableStateOf("") }
	var treffer by remember { mutableStateOf<List<RadioSender>>(emptyList()) }
	LaunchedEffect(Unit) {
		fav = runCatching { r.favoriten() }.getOrDefault(emptyList())
		ch = runCatching { r.beliebtIn("CH") }.getOrDefault(emptyList())
		genres = listOf("pop", "rock", "jazz", "classical", "news", "electronic").associateWith { g -> runCatching { r.nachGenre(g, 20) }.getOrDefault(emptyList()) }
	}
	LaunchedEffect(suche) { if (suche.length >= 2) { delay(450); treffer = runCatching { r.suche(suche) }.getOrDefault(emptyList()) } else treffer = emptyList() }
	fun favWechseln(s: RadioSender) = scope.launch {
		val neu = if (fav.any { it.id == s.id }) fav.filter { it.id != s.id } else fav + s
		runCatching { r.favoritenSetzen(neu) }.onSuccess { fav = neu }
		android.widget.Toast.makeText(c, if (neu.size > fav.size || neu.any { it.id == s.id }) "Zu Radio-Favoriten" else "Aus Radio-Favoriten entfernt", android.widget.Toast.LENGTH_SHORT).show()
	}
	LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
		item {
			OutlinedTextField(suche, { suche = it }, Modifier.fillMaxWidth().padding(16.dp), placeholder = { Text("Sender suchen") }, singleLine = true,
				leadingIcon = { Icon(Icons.Filled.Search, null) }, shape = RoundedCornerShape(24.dp), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search))
		}
		@Composable fun Senderreihe(t: String, l: List<RadioSender>) = Reihe(t, l, schluessel = { it.id }) { s ->
			Box(Modifier.combinedClickableKurz({ Musik.radio(c, s) }) { favWechseln(s) }) { RadioKachel(s) { Musik.radio(c, s) } }
		}
		item { Senderreihe("Treffer", treffer) }
		item { Senderreihe("Deine Favoriten", fav) }
		item { Senderreihe("Beliebt in der Schweiz", ch) }
		genres.forEach { (g, l) -> item(key = g) { Senderreihe(g.replaceFirstChar { it.uppercase() }, l) } }
	}
}
