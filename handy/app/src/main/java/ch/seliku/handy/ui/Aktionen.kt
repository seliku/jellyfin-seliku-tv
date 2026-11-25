package ch.seliku.handy.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import ch.seliku.handy.Einstellungen
import ch.seliku.handy.Sitzung
import ch.seliku.handy.daten.Farben
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CollectionType
import java.util.UUID

/** Zähler wie `datenStand` am TV: nach Änderungen (Menü, Favorit, Einstellung) laden offene Seiten neu. */
object Datenstand { var stand by mutableIntStateOf(0); fun neu() { stand++ } }

val LocalTitelMenue = staticCompositionLocalOf<(BaseItemDto) -> Unit> { {} }

/** Nur auf diesem Gerät: was hier angefangen wurde (für «Weiterschauen nur von diesem Gerät»), max. 400. */
object GeraetVerlauf {
	private fun p(c: Context) = c.getSharedPreferences("seliku_geraet_verlauf", Context.MODE_PRIVATE)
	fun merken(c: Context, i: BaseItemDto) {
		val k = (i.seriesId ?: i.id).toString()
		val alt = p(c).getString("ids", "").orEmpty().split(",").filter { it.isNotBlank() && it != k }
		p(c).edit().putString("ids", (listOf(k) + alt).take(400).joinToString(",")).apply()
	}
	fun enthaelt(c: Context, i: BaseItemDto) = (i.seriesId ?: i.id).toString() in p(c).getString("ids", "").orEmpty().split(",")
}

private val hintergrund = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/** Wohin ein Antippen führt (wie `seiteFuer` am TV): Folgen, Lieder und Sender spielen direkt. */
fun oeffnen(nav: NavHostController, c: Context, i: BaseItemDto) {
	when (i.type) {
		BaseItemKind.SERIES, BaseItemKind.MOVIE, BaseItemKind.VIDEO, BaseItemKind.MUSIC_VIDEO, BaseItemKind.TRAILER -> nav.navigate(Wege.detail(i.id))
		BaseItemKind.SEASON -> nav.navigate(Wege.detail(i.seriesId ?: i.id))
		BaseItemKind.MUSIC_ARTIST -> nav.navigate("interpret/${i.id}")
		BaseItemKind.MUSIC_ALBUM, BaseItemKind.PLAYLIST -> nav.navigate("album/${i.id}")
		BaseItemKind.FOLDER, BaseItemKind.COLLECTION_FOLDER, BaseItemKind.BOX_SET -> nav.navigate(Wege.ordner(i.id))
		BaseItemKind.AUDIO, BaseItemKind.AUDIO_BOOK -> ch.seliku.handy.musik.Musik.spielen(c, listOf(i), 0)
		else -> abspielen(nav, c, i)
	}
}

/** Video abspielen wie `Abspielen.video`: Ausblendung aufheben, Gerät merken, 5 s vor der gemerkten Stelle. */
fun abspielen(nav: NavHostController, c: Context, i: BaseItemDto, vonVorn: Boolean = false) {
	ch.seliku.handy.musik.Musik.radioStoppen()
	GeraetVerlauf.merken(c, i)
	hintergrund.launch { runCatching { Sitzung.daten.wiederAufnehmen(i) } }
	val pos = if (vonVorn) 0L else ((i.userData?.playbackPositionTicks ?: 0L) / 10_000 - 5_000).coerceAtLeast(0)
	nav.navigate(Wege.spielen(i.id, pos))
}

/** Serie abspielen: nächste Folge (Jellyfin NextUp bzw. zuletzt geschaute), sonst die erste. */
suspend fun serieAbspielen(nav: NavHostController, c: Context, serie: UUID, zufall: Boolean = false) {
	val d = Sitzung.daten
	val folge = if (zufall) d.alleFolgen(serie).randomOrNull() else d.naechsteFolge(serie) ?: d.alleFolgen(serie).firstOrNull()
	folge?.let { abspielen(nav, c, it, vonVorn = zufall) }
}

private fun toast(c: Context, t: String) = Toast.makeText(c, t, Toast.LENGTH_SHORT).show()

/** Titelmenü (langes Drücken), Punkte und Reihenfolge wie am Fernseher. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TitelMenue(item: BaseItemDto, nav: NavHostController, schliessen: () -> Unit) {
	val c = LocalContext.current
	val scope = rememberCoroutineScope()
	var voll by remember { mutableStateOf(item) }
	var sterne by remember { mutableStateOf(false) }
	var loeschenFragen by remember { mutableStateOf(false) }
	LaunchedEffect(item.id) { runCatching { Sitzung.api!!.userLibraryApi.getItem(itemId = item.id).content }.onSuccess { voll = it } }
	val i = voll
	fun fertig() { Datenstand.neu(); schliessen() }
	ModalBottomSheet(onDismissRequest = schliessen, containerColor = Farben.Flaeche) {
		Column(Modifier.padding(bottom = 24.dp)) {
			Text(hauptzeile(i), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 20.dp))
			if (i.type == BaseItemKind.EPISODE) Text(unterzeile(i), color = Farben.TextLeise, modifier = Modifier.padding(horizontal = 20.dp))
			Spacer(Modifier.height(8.dp))
			@Composable fun Punkt(t: String, a: () -> Unit) = Text(t, Modifier.fillMaxWidth().clickable { a() }.padding(horizontal = 20.dp, vertical = 14.dp))
			val video = i.type in setOf(BaseItemKind.MOVIE, BaseItemKind.EPISODE, BaseItemKind.VIDEO, BaseItemKind.MUSIC_VIDEO)
			val musik = i.type in setOf(BaseItemKind.AUDIO, BaseItemKind.MUSIC_ALBUM, BaseItemKind.PLAYLIST, BaseItemKind.MUSIC_ARTIST)
			val pos = i.userData?.playbackPositionTicks ?: 0L
			if (i.seriesId != null) Punkt("›  Zur Serie") { schliessen(); nav.navigate(Wege.detail(i.seriesId!!)) }
			if (musik) {
				Punkt("▶  Abspielen") { schliessen(); scope.launch { ch.seliku.handy.musik.Musik.spielenItem(c, i) } }
				if (ch.seliku.handy.musik.Musik.laeuft) {
					Punkt("⤵  Als Nächstes") { scope.launch { ch.seliku.handy.musik.Musik.einreihen(c, i, gleich = true); toast(c, "Kommt als Nächstes") }; schliessen() }
					Punkt("+  Hinten anstellen") { scope.launch { ch.seliku.handy.musik.Musik.einreihen(c, i, gleich = false); toast(c, "Hinten angestellt") }; schliessen() }
				}
				if (i.type == BaseItemKind.AUDIO && i.albumId != null) Punkt("›  Zum Album") { schliessen(); nav.navigate("album/${i.albumId}") }
				val interpret = i.albumArtists?.firstOrNull()?.id ?: i.artistItems?.firstOrNull()?.id
				if (interpret != null && i.type != BaseItemKind.MUSIC_ARTIST) Punkt("›  Zum Interpreten") { schliessen(); nav.navigate("interpret/$interpret") }
			}
			if (video) Punkt(if (pos > 0 && i.userData?.played != true) "▶  Fortsetzen ab ${zeit(pos / 10_000)}" else "▶  Abspielen") { schliessen(); abspielen(nav, c, i) }
			if (video) Punkt("📺  Auf anderem Gerät abspielen") { schliessen(); Fernsteuerung.titel = i }
			if (video && pos > 0) Punkt("↺  Von vorn") { schliessen(); abspielen(nav, c, i, vonVorn = true) }
			if (i.type == BaseItemKind.EPISODE || (i.type == BaseItemKind.MOVIE && pos > 0))
				Punkt("✕  Aus Weiterschauen entfernen") { scope.launch { runCatching { Sitzung.daten.ausWeiterschauen(i) }; fertig() } }
			if (i.type == BaseItemKind.SERIES || i.type == BaseItemKind.SEASON)
				Punkt("⤨  Zufällige Folge") { schliessen(); scope.launch { serieAbspielen(nav, c, i.seriesId ?: i.id, zufall = true) } }
			if (!musik) {
				val gesehen = i.userData?.played == true
				Punkt(if (gesehen) "○  Als ungesehen markieren" else "✓  Als gesehen markieren") { scope.launch { runCatching { Sitzung.daten.gesehen(i.id, !gesehen) }; fertig() } }
			}
			val fav = i.userData?.isFavorite == true
			Punkt(if (fav) "☆  Aus Favoriten" else "★  Zu Favoriten") {
				scope.launch { runCatching { Sitzung.daten.favorit(i.id, !fav) }; if (!fav) toast(c, "Zu Favoriten hinzugefügt – auf der Startseite unter «Deine Favoriten»"); fertig() }
			}
			if (Einstellungen.an("eigene_wertung") && i.type in setOf(BaseItemKind.MOVIE, BaseItemKind.SERIES, BaseItemKind.EPISODE, BaseItemKind.VIDEO)) {
				val s = i.userData?.rating?.let { Math.round(it / 2).toInt() }?.takeIf { it in 1..5 }
				Punkt(if (s != null) "☆  Bewerten (" + "★".repeat(s) + ")" else "☆  Bewerten") { sterne = true }
			}
			if (i.type in setOf(BaseItemKind.MOVIE, BaseItemKind.SERIES, BaseItemKind.SEASON, BaseItemKind.MUSIC_ALBUM, BaseItemKind.MUSIC_ARTIST, BaseItemKind.BOX_SET))
				Punkt("ⓘ  Details") { schliessen(); oeffnen(nav, c, i) }
			if (Einstellungen.an("loeschen") && i.canDelete == true && i.type !in setOf(BaseItemKind.FOLDER, BaseItemKind.COLLECTION_FOLDER, BaseItemKind.TV_CHANNEL))
				Punkt("⌫  Löschen") { loeschenFragen = true }
		}
	}
	if (sterne) WertungAuswahl(i, { sterne = false }) { fertig() }
	if (loeschenFragen) AlertDialog(
		onDismissRequest = { loeschenFragen = false },
		title = { Text("«${hauptzeile(i)}» löschen?") },
		text = { Text(if (i.type == BaseItemKind.SERIES) "Alle Folgen werden vom Server gelöscht. Das lässt sich nicht rückgängig machen." else "Die Datei wird vom Server gelöscht. Das lässt sich nicht rückgängig machen.") },
		confirmButton = { TextButton({ scope.launch { runCatching { Sitzung.api!!.libraryApi.deleteItem(itemId = i.id) }.onSuccess { toast(c, "Gelöscht") }.onFailure { toast(c, "Löschen fehlgeschlagen") }; loeschenFragen = false; fertig() } }) { Text("Löschen", color = MaterialTheme.colorScheme.error) } },
		dismissButton = { TextButton({ loeschenFragen = false }) { Text("Abbrechen") } },
	)
}

/** Sterne-Auswahl «Wie war's?» (Jellyfin speichert 2–10 je Benutzer). */
@Composable
fun WertungAuswahl(i: BaseItemDto, schliessen: () -> Unit, geaendert: () -> Unit) {
	val scope = rememberCoroutineScope()
	val namen = listOf(5 to "Super", 4 to "Gut", 3 to "Okay", 2 to "Mässig", 1 to "Kacke")
	AlertDialog(onDismissRequest = schliessen, title = { Text("Wie war's?") }, confirmButton = {}, dismissButton = { TextButton(schliessen) { Text("Abbrechen") } }, text = {
		Column {
			namen.forEach { (n, t) ->
				Text("★".repeat(n) + "☆".repeat(5 - n) + "   " + t, Modifier.fillMaxWidth().clickable {
					scope.launch { runCatching { Sitzung.daten.wertung(i.id, n) }; schliessen(); geaendert() }
				}.padding(vertical = 10.dp), color = Farben.Text)
			}
			Text("✕  Wertung entfernen", Modifier.fillMaxWidth().clickable { scope.launch { runCatching { Sitzung.daten.wertung(i.id, 0) }; schliessen(); geaendert() } }.padding(vertical = 10.dp), color = Farben.TextLeise)
		}
	})
}

fun zeit(ms: Long): String {
	val s = ms / 1000
	return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/** Ziel einer Bibliothek (wie `seiteFuerBibliothek`). */
fun bibliothekOeffnen(nav: NavHostController, b: BaseItemDto) = when (b.collectionType) {
	CollectionType.LIVETV -> nav.navigate("livetv")
	CollectionType.MUSIC -> nav.navigate("musik")
	CollectionType.MOVIES, CollectionType.TVSHOWS, CollectionType.BOXSETS, CollectionType.MUSICVIDEOS -> nav.navigate(Wege.bibliothek(b.id))
	else -> nav.navigate(Wege.ordner(b.id))
}
