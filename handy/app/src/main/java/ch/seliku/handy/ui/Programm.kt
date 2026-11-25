package ch.seliku.handy.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import ch.seliku.handy.Einstellungen
import ch.seliku.handy.Sitzung
import ch.seliku.handy.daten.Farben
import kotlinx.coroutines.launch
import org.jellyfin.sdk.api.client.extensions.liveTvApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemSortBy
import java.time.LocalDateTime
import okhttp3.MediaType.Companion.toMediaType
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private const val DP_JE_MIN = 4

/** Programmführer: Sender nach Nummer (gewählter Sprachblock), 12 Stunden ab jetzt; laufende Sendung antippen schaltet ein. */
@Composable
fun ProgrammSeite() {
	val nav = LocalNav.current
	val c = LocalContext.current
	val scope = rememberCoroutineScope()
	val prefs = remember { c.getSharedPreferences("seliku", 0) }
	val startUtc = remember { LocalDateTime.now(ZoneOffset.UTC).withSecond(0).withNano(0).let { it.withMinute(if (it.minute < 30) 0 else 30) } }
	val endeUtc = remember { startUtc.plusHours(12) }
	var sender by remember { mutableStateOf<List<BaseItemDto>?>(null) }
	var sendungen by remember { mutableStateOf<Map<java.util.UUID, List<BaseItemDto>>>(emptyMap()) }
	var info by remember { mutableStateOf<Pair<BaseItemDto, BaseItemDto>?>(null) }
	val waagrecht = rememberScrollState()
	val dichte = LocalDensity.current
	LaunchedEffect(Unit) {
		val block = prefs.getInt("sender_block", -1)
		val alle = runCatching { Sitzung.daten.sender() }.getOrDefault(emptyList())
		val s = if (Einstellungen.an("sender_gruppen") && block >= 0) alle.filter { senderBlock(it) == block } else alle
		sender = s
		sendungen = runCatching {
			Sitzung.api!!.liveTvApi.getLiveTvPrograms(channelIds = s.map { it.id }, minEndDate = startUtc, maxStartDate = endeUtc, sortBy = listOf(ItemSortBy.START_DATE), enableImages = false, userId = Sitzung.benutzerId)
				.content.items.groupBy { it.channelId!! }
		}.getOrDefault(emptyMap())
		val jetztMin = java.time.Duration.between(startUtc, LocalDateTime.now(ZoneOffset.UTC)).toMinutes()
		waagrecht.scrollTo(with(dichte) { ((jetztMin - 15).coerceAtLeast(0) * DP_JE_MIN).toInt().dp.roundToPx() })
	}
	val uhr = DateTimeFormatter.ofPattern("HH:mm")
	val breite = (java.time.Duration.between(startUtc, endeUtc).toMinutes() * DP_JE_MIN).toInt().dp
	Column(Modifier.fillMaxSize().selikuHintergrund()) {
		SeitenKopf("Programmführer", { nav.popBackStack() })
		val s = sender ?: run { Laden(); return@Column }
		Row {
			Spacer(Modifier.width(84.dp))
			Box(Modifier.horizontalScroll(waagrecht).width(breite).height(24.dp)) {
				for (i in 0 until 24) Text(ortszeit(startUtc.plusMinutes(i * 30L))?.format(uhr).orEmpty(), Modifier.offset(x = (i * 30 * DP_JE_MIN + 4).dp), fontSize = Schrift.Klein, color = Farben.TextLeise)
			}
		}
		LazyColumn(Modifier.fillMaxSize()) {
			items(s, key = { it.id }) { sd ->
				Row(Modifier.height(60.dp)) {
					Box(Modifier.width(84.dp).fillMaxHeight().background(Farben.Flaeche).clickable { abspielen(nav, c, sd) }.padding(6.dp), contentAlignment = Alignment.Center) {
						if (sd.imageTags?.get(ImageType.PRIMARY) != null) AsyncImage(Bilder.item(sd.id, "Primary", 160), sd.name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
						else Text(sd.name.orEmpty(), fontSize = Schrift.Klein, maxLines = 2)
					}
					Box(Modifier.horizontalScroll(waagrecht).width(breite).fillMaxHeight()) {
						val jetzt = LocalDateTime.now(ZoneOffset.UTC)
						sendungen[sd.id].orEmpty().forEach { p ->
							val a = (p.startDate ?: startUtc).let { if (it.isBefore(startUtc)) startUtc else it }; val b = (p.endDate ?: endeUtc).let { if (it.isAfter(endeUtc)) endeUtc else it }
							val x = java.time.Duration.between(startUtc, a).toMinutes() * DP_JE_MIN
							val w = (java.time.Duration.between(a, b).toMinutes() * DP_JE_MIN - 2).coerceAtLeast(4)
							val laeuft = (p.startDate ?: jetzt) <= jetzt && (p.endDate ?: jetzt) > jetzt
							Column(Modifier.offset(x = x.toInt().dp).width(w.toInt().dp).fillMaxHeight().padding(vertical = 3.dp).clip(RoundedCornerShape(6.dp))
								.background(if (laeuft) Color(0xFF4A3226) else Farben.FlaecheHell).clickable { if (laeuft) abspielen(nav, c, sd) else info = p to sd }.padding(horizontal = 6.dp, vertical = 4.dp)) {
								Text(p.name.orEmpty(), fontSize = Schrift.Klein, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
								Text("${ortszeit(p.startDate)?.format(uhr)}–${ortszeit(p.endDate)?.format(uhr)}", fontSize = Schrift.Klein, color = Farben.TextLeise, maxLines = 1)
							}
						}
					}
				}
			}
		}
	}
	info?.let { (p, sd) ->
		AlertDialog(onDismissRequest = { info = null }, title = { Text(p.name.orEmpty()) },
			text = { Column { Text("${sd.name} · ${ortszeit(p.startDate)?.format(uhr)}–${ortszeit(p.endDate)?.format(uhr)}", color = Farben.TextLeise); p.overview?.let { Text(it, Modifier.padding(top = 8.dp)) } } },
			confirmButton = {
				TextButton({
					scope.launch {
						runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
							// Vorgaben des Servers holen und unverändert als Aufnahme anlegen (wie Jellyfins eigene Apps)
							val kopf = "MediaBrowser Token=\"${Sitzung.token}\""
							val http = ch.seliku.handy.wiedergabe.Quellen.http
							val t = http.newCall(okhttp3.Request.Builder().url("${Sitzung.server}/LiveTv/Timers/Defaults?programId=${p.id}").header("Authorization", kopf).build()).execute().use { it.body!!.string() }
							http.newCall(okhttp3.Request.Builder().url("${Sitzung.server}/LiveTv/Timers").header("Authorization", kopf)
								.post(okhttp3.RequestBody.create("application/json".toMediaType(), t)).build()).execute().use { if (!it.isSuccessful) error("${it.code}") }
						} }.onSuccess { Toast.makeText(c, "Aufnahme geplant", Toast.LENGTH_SHORT).show() }.onFailure { Toast.makeText(c, "Aufnahme nicht möglich", Toast.LENGTH_SHORT).show() }
						info = null
					}
				}) { Text("●  Aufnehmen", color = Farben.Akzent) }
			}, dismissButton = { TextButton({ info = null }) { Text("Schliessen") } })
	}
}
