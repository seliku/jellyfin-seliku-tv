package ch.seliku.handy.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import ch.seliku.handy.Einstellungen
import ch.seliku.handy.Sitzung
import ch.seliku.handy.daten.Farben
import kotlinx.coroutines.delay
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.ImageType
import java.time.format.DateTimeFormatter
import java.util.UUID

fun senderNummer(s: BaseItemDto): Int? = (s.channelNumber ?: s.number)?.substringBefore('.')?.trim()?.toIntOrNull()
fun senderBlock(s: BaseItemDto): Int = ch.seliku.handy.daten.senderBlock(s.channelNumber ?: s.number)

/**
 * Live-TV wie am TV: Sender nach Nummer, in Sprachblöcken (1–99, 100–199 …, Namen aus dem Konto «seliku-livetv»),
 * Reiter «Alle» + je Block; der gewählte Reiter gilt nur auf diesem Gerät. Antippen schaltet sofort ein. Alle 60 s neu.
 */
@Composable
fun LiveTvSeite() {
	val nav = LocalNav.current
	val c = LocalContext.current
	val d = remember { Sitzung.daten }
	val prefs = remember { c.getSharedPreferences("seliku", 0) }
	var sender by remember { mutableStateOf<List<BaseItemDto>?>(null) }
	var danach by remember { mutableStateOf<Map<UUID, BaseItemDto>>(emptyMap()) }
	var namen by remember { mutableStateOf<Map<Int, String>>(emptyMap()) }
	var block by remember { mutableIntStateOf(prefs.getInt("sender_block", -1)) }
	val liste = rememberLazyListState()
	LaunchedEffect(Unit) {
		namen = runCatching { d.senderGruppenNamen() }.getOrDefault(emptyMap())
		while (true) {
			val s = runCatching { d.sender() }.getOrNull()
			if (s != null) { sender = s; danach = runCatching { d.naechsteSendungen(s) }.getOrDefault(emptyMap()) }
			delay(60_000)
		}
	}
	val alle = sender
	val gruppiert = Einstellungen.an("sender_gruppen")
	val bloecke = alle?.map { senderBlock(it) }?.distinct()?.sorted().orEmpty()
	fun blockName(b: Int) = if (b == Int.MAX_VALUE) "Weitere Sender" else namen[b] ?: (if (b == 0) "Sender 1–99" else "Sender ${b * 100}–${b * 100 + 99}")
	LaunchedEffect(block) { liste.scrollToItem(0) }
	Column(Modifier.fillMaxSize().selikuHintergrund()) {
		SeitenKopf("Live-TV", { nav.popBackStack() }) { TextButton({ nav.navigate("programm") }) { Text("▦ Programm", color = Farben.Akzent) } }
		if (gruppiert && bloecke.size > 1) LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
			item { Pille("Alle", aktiv = block == -1) { block = -1; prefs.edit().putInt("sender_block", -1).apply() } }
			items(bloecke) { b -> Pille(blockName(b), aktiv = block == b) { block = b; prefs.edit().putInt("sender_block", b).apply() } }
		}
		if (alle == null) Laden()
		else LazyColumn(state = liste, contentPadding = PaddingValues(bottom = 24.dp)) {
			val zeigen = if (!gruppiert || bloecke.size <= 1) listOf(-2 to alle)
				else if (block == -1) bloecke.map { b -> b to alle.filter { senderBlock(it) == b } }
				else listOf(block to alle.filter { senderBlock(it) == block })
			zeigen.forEach { (b, l) ->
				if (b != -2 && block == -1) item(key = "k$b") { ReihenTitel("${blockName(b)} · ${l.size} Sender") }
				items(l, key = { it.id }) { s -> SenderZeile(s, danach[s.id]) { abspielen(nav, c, s) } }
			}
		}
	}
}

private val UHR = DateTimeFormatter.ofPattern("HH:mm")

@Composable
private fun SenderZeile(s: BaseItemDto, danach: BaseItemDto?, onClick: () -> Unit) {
	Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
		Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
			if (s.imageTags?.get(ImageType.PRIMARY) != null) AsyncImage(Bilder.item(s.id, "Primary", 160), s.name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
			else Text(senderNummer(s)?.toString().orEmpty(), fontWeight = FontWeight.Bold)
		}
		Box(Modifier.padding(start = 10.dp).width(110.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(6.dp))) {
			Bild(sendungsBild(s), Modifier.fillMaxSize())
			sendungsAnteil(s)?.let { Fortschrittsbalken(it, Modifier.align(Alignment.BottomCenter)) }
		}
		Column(Modifier.padding(start = 12.dp).weight(1f)) {
			Text("${s.name.orEmpty()}  ·  Kanal ${s.channelNumber ?: s.number ?: "–"}", fontSize = Schrift.Klein, color = Farben.TextLeise, maxLines = 1)
			val p = s.currentProgram
			Text(p?.name ?: "Kein Programm", fontSize = Schrift.Normal, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
			if (p?.startDate != null && p.endDate != null) Text("${ortszeit(p.startDate)?.format(UHR)}–${ortszeit(p.endDate)?.format(UHR)}", fontSize = Schrift.Klein, color = Farben.TextLeise)
			danach?.let { n -> Text("Danach ${ortszeit(n.startDate)?.format(UHR).orEmpty()} · ${n.name.orEmpty()}", fontSize = Schrift.Klein, color = Farben.TextLeise, maxLines = 1, overflow = TextOverflow.Ellipsis) }
		}
	}
}
