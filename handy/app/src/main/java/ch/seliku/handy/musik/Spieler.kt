package ch.seliku.handy.musik

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import ch.seliku.handy.daten.Farben
import ch.seliku.handy.ui.LocalNav
import ch.seliku.handy.ui.Schrift
import ch.seliku.handy.ui.zeit
import kotlinx.coroutines.delay

/** Mini-Player über der unteren Leiste (wie Plexamp); Antippen öffnet «Läuft gerade». */
@Composable
fun MiniSpieler() {
	if (!Musik.laeuft) return
	val nav = LocalNav.current
	val t = Musik.titel
	LaunchedEffect(Musik.spielt) { while (Musik.spielt) { Musik.auffrischen(); delay(1000) } }
	Column(Modifier.fillMaxWidth().background(Farben.FlaecheHell).clickable { nav.navigate("laeuft") }) {
		if (!Musik.istRadio && Musik.dauer > 0) Box(Modifier.fillMaxWidth().height(2.dp)) {
			Box(Modifier.fillMaxWidth((Musik.position.toFloat() / Musik.dauer).coerceIn(0f, 1f)).fillMaxHeight().background(Farben.Akzent))
		}
		Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
			AsyncImage(t?.artworkUri?.toString(), null, Modifier.size(44.dp).clip(RoundedCornerShape(6.dp)).background(Farben.Flaeche), contentScale = ContentScale.Crop)
			Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
				Text(t?.title?.toString().orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
				Text(t?.artist?.toString().orEmpty(), fontSize = Schrift.Klein, color = Farben.TextLeise, maxLines = 1, overflow = TextOverflow.Ellipsis)
			}
			IconButton({ Musik.spielenPause() }) { Icon(if (Musik.spielt) Icons.Filled.Pause else Icons.Filled.PlayArrow, "Pause") }
			if (Musik.istRadio) IconButton({ Musik.stoppen() }) { Icon(Icons.Filled.Stop, "Stopp") }
			else IconButton({ Musik.weiter() }) { Icon(Icons.Filled.SkipNext, "Weiter") }
		}
	}
}

/** «Läuft gerade»: Cover unscharf im Hintergrund (wie Plexamp), Zeitleiste, Knöpfe, «Als Nächstes». */
@Composable
fun LaeuftSeite() {
	val nav = LocalNav.current
	val t = Musik.titel
	LaunchedEffect(Unit) { while (true) { Musik.auffrischen(); delay(500) } }
	LaunchedEffect(Musik.laeuft) { if (!Musik.laeuft) { delay(3000); if (!Musik.laeuft) nav.popBackStack() } }
	Box(Modifier.fillMaxSize().background(Color.Black)) {
		AsyncImage(t?.artworkUri?.toString(), null, Modifier.fillMaxSize().blur(48.dp), contentScale = ContentScale.Crop, alpha = 0.45f)
		LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(bottom = 24.dp)) {
			item {
				Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
					IconButton({ nav.popBackStack() }) { Icon(Icons.Filled.KeyboardArrowDown, "Schliessen") }
					Spacer(Modifier.weight(1f))
					if (Musik.endlosAktiv) Text("∞ Endlos-Mix", color = Farben.TextLeise, fontSize = Schrift.Klein, modifier = Modifier.padding(end = 16.dp))
				}
			}
			item {
				Column(Modifier.fillMaxWidth().padding(horizontal = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
					AsyncImage(t?.artworkUri?.toString(), null, Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp)).background(Farben.Flaeche), contentScale = ContentScale.Crop)
					Spacer(Modifier.height(20.dp))
					Text(t?.albumTitle?.toString().orEmpty(), fontSize = Schrift.Klein, color = Farben.TextLeise, maxLines = 1)
					Text(t?.title?.toString().orEmpty(), fontSize = Schrift.Titel, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
					Text(t?.artist?.toString().orEmpty(), color = Farben.TextLeise, maxLines = 1)
					if (!Musik.istRadio) {
						Slider(value = if (Musik.dauer > 0) Musik.position.toFloat() / Musik.dauer else 0f, onValueChange = { Musik.springen((it * Musik.dauer).toLong()) },
							colors = SliderDefaults.colors(thumbColor = Farben.Akzent, activeTrackColor = Farben.Akzent), modifier = Modifier.padding(top = 12.dp))
						Row(Modifier.fillMaxWidth()) {
							Text(zeit(Musik.position), fontSize = Schrift.Klein, color = Farben.TextLeise); Spacer(Modifier.weight(1f))
							Text("-" + zeit((Musik.dauer - Musik.position).coerceAtLeast(0)), fontSize = Schrift.Klein, color = Farben.TextLeise)
						}
					}
					Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
						if (!Musik.istRadio) IconButton({ Musik.zufallWechseln() }) { Icon(Icons.Filled.Shuffle, "Zufall", tint = if (Musik.zufall) Farben.Akzent else Farben.Text) }
						if (!Musik.istRadio) IconButton({ Musik.zurueck() }, Modifier.size(56.dp)) { Icon(Icons.Filled.SkipPrevious, "Zurück", Modifier.size(36.dp)) }
						FilledIconButton({ Musik.spielenPause() }, Modifier.size(72.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = Farben.Akzent)) {
							Icon(if (Musik.spielt) Icons.Filled.Pause else Icons.Filled.PlayArrow, "Pause", Modifier.size(40.dp), tint = Color.Black)
						}
						if (!Musik.istRadio) IconButton({ Musik.weiter() }, Modifier.size(56.dp)) { Icon(Icons.Filled.SkipNext, "Weiter", Modifier.size(36.dp)) }
						if (!Musik.istRadio) IconButton({ Musik.wiederholenWechseln() }) { Icon(Icons.Filled.RepeatOne, "Wiederholen", tint = if (Musik.wiederholen) Farben.Akzent else Farben.Text) }
						if (Musik.istRadio) IconButton({ Musik.stoppen() }) { Icon(Icons.Filled.Stop, "Stopp") }
					}
					// Hören: Sprünge, Tempo, Schlaf-Timer, gehört (wie am TV)
					Musik.hoerArt?.let { art ->
						val buch = art.startsWith("b:")
						Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
							TextButton({ Musik.springenUm(if (buch) -30_000 else -10_000) }) { Text(if (buch) "−30 s" else "−10 s") }
							TextButton({ Musik.springenUm(30_000) }) { Text("+30 s") }
							TextButton({ Musik.tempoWeiter() }) { Text("%.2f×".format(Musik.tempo).replace(".00", "").replace(".", ","), color = Farben.Akzent) }
							val rest = ((Musik.schlafBis - System.currentTimeMillis()) / 60_000).coerceAtLeast(0)
							TextButton({
								val stufen = listOf(0L, 15, 30, 45, 60)
								val n = stufen.indexOfFirst { it >= rest + 1 }.let { if (Musik.schlafBis == 0L) 1 else it }.coerceAtLeast(0)
								val m = stufen.getOrElse(n) { 0L }
								Musik.schlafBis = if (m == 0L) 0L else System.currentTimeMillis() + m * 60_000
							}) { Text(if (Musik.schlafBis == 0L) "☾ Schlaf" else "☾ ${rest + 1} min") }
						}
					}
					if (!Musik.istRadio) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
						t?.extras?.getString("album")?.let { a -> TextButton({ nav.popBackStack(); nav.navigate("album/$a") }) { Text("Zum Album", color = Farben.Akzent) } }
						t?.extras?.getString("interpret")?.let { a -> TextButton({ nav.popBackStack(); nav.navigate("interpret/$a") }) { Text("Zum Interpreten", color = Farben.Akzent) } }
					}
				}
			}
			if (!Musik.istRadio && Musik.warteschlange.size > Musik.index + 1) {
				item { Text("Als Nächstes", fontSize = Schrift.Reihe, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(16.dp)) }
				val ab = Musik.index + 1
				itemsIndexed(Musik.warteschlange.drop(ab).take(40)) { n, m ->
					Row(Modifier.fillMaxWidth().clickable { Musik.zu(ab + n) }.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
						AsyncImage(m.artworkUri?.toString(), null, Modifier.size(40.dp).clip(RoundedCornerShape(4.dp)), contentScale = ContentScale.Crop)
						Column(Modifier.padding(start = 12.dp)) {
							Text(m.title?.toString().orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis)
							Text(m.artist?.toString().orEmpty(), fontSize = Schrift.Klein, color = Farben.TextLeise, maxLines = 1)
						}
					}
				}
			}
		}
	}
}
