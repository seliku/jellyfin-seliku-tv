package ch.seliku.handy.musik

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ch.seliku.handy.Sitzung
import ch.seliku.handy.daten.Farben
import ch.seliku.handy.ui.*
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.BaseItemDto
import java.util.UUID

/** Bibliotheksinhalt Hören: Podcasts (Liste der Podcasts + neue Folgen) bzw. Hörbücher (Raster). */
@Composable
fun HoerInhalt(bib: BaseItemDto, podcast: Boolean) {
	val nav = LocalNav.current
	val c = LocalContext.current
	val scope = rememberCoroutineScope()
	var liste by remember(bib.id) { mutableStateOf<List<BaseItemDto>?>(null) }
	var neue by remember(bib.id) { mutableStateOf<List<BaseItemDto>>(emptyList()) }
	var weiter by remember { mutableStateOf<List<BaseItemDto>>(emptyList()) }
	LaunchedEffect(bib.id, Hoeren.version) {
		Hoeren.laden()
		liste = runCatching { Sitzung.daten.ordnerInhalt(bib.id) }.getOrDefault(emptyList())
		if (podcast) neue = runCatching { Hoeren.folgen(bib.id, nurNeue = true, anzahl = 20) }.getOrDefault(emptyList())
		weiter = runCatching { Hoeren.weiterhoeren() }.getOrDefault(emptyList())
	}
	val l = liste ?: run { Laden(); return }
	LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
		item { Reihe("Weiterhören", weiter, schluessel = { it.id }) { i -> HoerKachel(i) { scope.launch { hoerenStarten(c, i) } } } }
		if (podcast) item { Reihe("Neue Folgen", neue, schluessel = { it.id }) { i -> PosterKachel(i, 120.dp, quadrat = true) { scope.launch { Musik.folgeSpielen(c, i) } } } }
		item { ReihenTitel(if (podcast) "Alle Podcasts" else "Alle Hörbücher") }
		items(l.chunked(3)) { zeile ->
			Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
				zeile.forEach { i -> Box(Modifier.weight(1f)) { PosterKachel(i, 110.dp, quadrat = true) { nav.navigate((if (podcast) "podcast/" else "buch/") + i.id) } } }
				repeat(3 - zeile.size) { Spacer(Modifier.weight(1f)) }
			}
		}
	}
}

/** Weiterhören: Buch an der gemerkten Stelle bzw. Folge mit den nächsten ungehörten. */
suspend fun hoerenStarten(c: android.content.Context, i: BaseItemDto) {
	if (i.type == org.jellyfin.sdk.model.api.BaseItemKind.AUDIO && Hoeren.wert("p:${i.id}") != null) Musik.folgeSpielen(c, i)
	else if (Hoeren.wert("b:${i.id}") != null || i.isFolder == true) Musik.buchSpielen(c, i)
	else Musik.folgeSpielen(c, i)
}

@Composable
fun HoerKachel(i: BaseItemDto, onClick: () -> Unit) {
	val stand = Hoeren.wert("p:${i.id}")?.split("|")?.get(0)?.toLongOrNull()
	Column(Modifier.width(130.dp).clickable(onClick = onClick)) {
		Box(Modifier.size(130.dp).clip(RoundedCornerShape(8.dp))) {
			Bild(Bilder.poster(i, 300), Modifier.fillMaxSize())
			val d = i.runTimeTicks?.div(10_000)
			if (stand != null && d != null && d > 0) Fortschrittsbalken((stand.toFloat() / d).coerceIn(0.02f, 1f), Modifier.align(Alignment.BottomCenter))
		}
		Text(i.name.orEmpty(), fontSize = Schrift.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
		Text(i.album ?: i.albumArtist ?: "", fontSize = Schrift.Klein, color = Farben.TextLeise, maxLines = 1)
	}
}

/** Hörbuch: Cover, Weiterhören/Abspielen, Von vorn, gehört markieren, Kapitel. */
@Composable
fun BuchSeite(id: UUID) {
	val nav = LocalNav.current
	val c = LocalContext.current
	val scope = rememberCoroutineScope()
	var buch by remember { mutableStateOf<BaseItemDto?>(null) }
	var dateien by remember { mutableStateOf<List<BaseItemDto>?>(null) }
	LaunchedEffect(id, Hoeren.version) {
		Hoeren.laden(); buch = runCatching { Sitzung.daten.item(id) }.getOrNull()
		dateien = runCatching { Hoeren.buchDateien(id) }.getOrDefault(emptyList())
		// Kein Buch, sondern ein Ordner mit Büchern (z. B. ein Autor): als Ordner zeigen
		if (dateien?.isEmpty() == true) { nav.popBackStack(); nav.navigate("ordner/$id") }
	}
	val b = buch
	val stand = Hoeren.wert("b:$id")?.split("|")
	val fertig = Hoeren.wert("f:$id") != null
	LazyColumn(Modifier.fillMaxSize().selikuHintergrund(), contentPadding = PaddingValues(bottom = 24.dp)) {
		item { SeitenKopf(b?.name.orEmpty(), { nav.popBackStack() }) }
		if (b != null) item {
			Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
				Bild(Bilder.poster(b, 600), Modifier.size(220.dp).clip(RoundedCornerShape(10.dp)))
				val d = dateien.orEmpty()
				if (stand != null && d.isNotEmpty()) {
					val n = d.indexOfFirst { it.id.toString() == stand[0] }.coerceAtLeast(0)
					val gesamt = d.sumOf { (it.runTimeTicks ?: 0) / 10_000 }; val bisher = d.take(n).sumOf { (it.runTimeTicks ?: 0) / 10_000 } + (stand[1].toLongOrNull() ?: 0)
					if (gesamt > 0) {
						LinearProgressIndicator({ (bisher.toFloat() / gesamt).coerceIn(0f, 1f) }, Modifier.padding(top = 14.dp).fillMaxWidth(0.7f), color = Farben.Akzent)
						Text("Kapitel ${n + 1} von ${d.size} · noch ${zeit((gesamt - bisher).coerceAtLeast(0))}", fontSize = Schrift.Klein, color = Farben.TextLeise, modifier = Modifier.padding(top = 6.dp))
					}
				} else if (fertig) Text("✓ Gehört", color = Farben.Akzent, modifier = Modifier.padding(top = 10.dp))
				FlowRow(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
					Pille(if (stand != null) "▶  Weiterhören" else "▶  Abspielen", hervorgehoben = true) { scope.launch { Musik.buchSpielen(c, b) } }
					if (stand != null) Pille("↺  Von vorn") { scope.launch { Hoeren.setzen("b:$id", null); Musik.buchSpielen(c, b, 0) } }
					Pille(if (fertig) "○  Als ungehört" else "✓  Als gehört") {
						scope.launch { Hoeren.setzen("f:$id", if (fertig) null else System.currentTimeMillis().toString()); if (!fertig) Hoeren.setzen("b:$id", null); Hoeren.hochladen() }
					}
				}
			}
		}
		val d = dateien
		if (d == null) item { Laden() } else itemsIndexed(d, key = { _, x -> x.id }) { n, x ->
			val aktiv = stand?.get(0) == x.id.toString()
			Row(Modifier.fillMaxWidth().clickable { scope.launch { b?.let { Musik.buchSpielen(c, it, n) } } }.padding(horizontal = 16.dp, vertical = 10.dp)) {
				Text("${n + 1}", color = if (aktiv) Farben.Akzent else Farben.TextLeise, modifier = Modifier.width(32.dp))
				Text(x.name.orEmpty(), Modifier.weight(1f), color = if (aktiv) Farben.Akzent else Farben.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
				Text(laufzeitKurz(x.runTimeTicks), fontSize = Schrift.Klein, color = Farben.TextLeise)
			}
		}
	}
}

/** Podcast: Folgen neueste zuerst mit Datum, ✓ und Balken; langes Drücken = gehört ↔ ungehört. */
@Composable
fun PodcastSeite(id: UUID) {
	val nav = LocalNav.current
	val c = LocalContext.current
	val scope = rememberCoroutineScope()
	var pod by remember { mutableStateOf<BaseItemDto?>(null) }
	var folgen by remember { mutableStateOf<List<BaseItemDto>?>(null) }
	LaunchedEffect(id, Hoeren.version) {
		Hoeren.laden(); pod = runCatching { Sitzung.daten.item(id) }.getOrNull()
		folgen = runCatching { Hoeren.folgen(id) }.getOrDefault(emptyList())
	}
	val f = folgen
	val angefangen = f?.firstOrNull { Hoeren.wert("p:${it.id}") != null }
	LazyColumn(Modifier.fillMaxSize().selikuHintergrund(), contentPadding = PaddingValues(bottom = 24.dp)) {
		item { SeitenKopf(pod?.name.orEmpty(), { nav.popBackStack() }) }
		pod?.let { p -> item {
			Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
				Bild(Bilder.poster(p, 400), Modifier.size(120.dp).clip(RoundedCornerShape(10.dp)))
				Column(Modifier.padding(start = 16.dp)) {
					Text(p.albumArtist.orEmpty(), color = Farben.TextLeise, fontSize = Schrift.Klein)
					Spacer(Modifier.height(8.dp))
					val erste = angefangen ?: f?.firstOrNull()
					if (erste != null) Pille(if (angefangen != null) "▶  Weiterhören" else "▶  Neueste abspielen", hervorgehoben = true) { scope.launch { Musik.folgeSpielen(c, erste) } }
				}
			}
		} }
		if (f == null) item { Laden() } else items(f, key = { it.id }) { x ->
			val pos = Hoeren.wert("p:${x.id}")?.split("|")?.get(0)?.toLongOrNull()
			val d = (x.runTimeTicks ?: 0) / 10_000
			Column(Modifier.fillMaxWidth().combinedClickableKurz({ scope.launch { Musik.folgeSpielen(c, x) } }) { scope.launch { Hoeren.alsGehoert(x, !Hoeren.gehoert(x)) } }.padding(horizontal = 16.dp, vertical = 10.dp)) {
				Text((x.premiereDate ?: x.dateCreated)?.let { "%02d.%02d.%d".format(it.dayOfMonth, it.monthValue, it.year) }.orEmpty(), fontSize = Schrift.Klein, color = Farben.TextLeise)
				Text((if (Hoeren.gehoert(x)) "✓  " else "") + x.name.orEmpty(), fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis,
					color = if (Hoeren.gehoert(x)) Farben.TextLeise else Farben.Text)
				Text(listOfNotNull(laufzeit(x.runTimeTicks), pos?.let { "noch ${zeit((d - it).coerceAtLeast(0))}" }).joinToString(" · "), fontSize = Schrift.Klein, color = Farben.TextLeise)
				if (pos != null && d > 0) LinearProgressIndicator({ (pos.toFloat() / d).coerceIn(0f, 1f) }, Modifier.padding(top = 4.dp).fillMaxWidth(), color = Farben.Akzent)
			}
		}
	}
}
