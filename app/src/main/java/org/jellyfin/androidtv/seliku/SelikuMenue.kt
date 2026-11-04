package org.jellyfin.androidtv.seliku

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.koin.compose.koinInject

/** Eigene Schalter der Seliku-Oberfläche, auf dem Gerät gespeichert. */
object SelikuEinstellungen {
	private const val DATEI = "seliku"
	private fun prefs(c: Context) = c.getSharedPreferences(DATEI, Context.MODE_PRIVATE)

	/** Aus (Vorgabe): Zurück beendet die App nie – wie bei Netflix. An: zweimal Zurück auf der Startseite beendet. */
	fun doppelZurueck(c: Context) = prefs(c).getBoolean("doppel_zurueck", false)
	fun doppelZurueck(c: Context, an: Boolean) = prefs(c).edit().putBoolean("doppel_zurueck", an).apply()

	/** An (Vorgabe): im Player tun OK/Links/Rechts nur das Gedrückte (Pause, Spulen, Zappen) – ohne Bedienleiste; die kommt mit «hoch». */
	@JvmStatic fun schlankerPlayer(c: Context) = prefs(c).getBoolean("schlanker_player", true)
	fun schlankerPlayer(c: Context, an: Boolean) = prefs(c).edit().putBoolean("schlanker_player", an).apply()

	/** An (Vorgabe): gewählte Kachel in Reihen zieht sich auf (Hintergrund, Infos, später Trailer) – wie bei Netflix. */
	fun netflixKacheln(c: Context) = prefs(c).getBoolean("netflix_kacheln", true)
	fun netflixKacheln(c: Context, an: Boolean) = prefs(c).edit().putBoolean("netflix_kacheln", an).apply()

	/** An (Vorgabe): leise Titelmusik beim Öffnen einer Serie (wie Plex). */
	fun titelmusik(c: Context) = prefs(c).getBoolean("titelmusik", true)
	fun titelmusik(c: Context, an: Boolean) = prefs(c).edit().putBoolean("titelmusik", an).apply()

	/** Aus (Vorgabe): Knopf «Zufällig» auf Serienseiten (sonst im Menü «⋯ Mehr»). */
	fun zufallKnopf(c: Context) = prefs(c).getBoolean("zufall_knopf", false)
	fun zufallKnopf(c: Context, an: Boolean) = prefs(c).edit().putBoolean("zufall_knopf", an).apply()

	/** An (Vorgabe): im Banner läuft nach ein paar Sekunden stumm der Trailer (falls auf dem Server einer liegt). */
	fun trailerImBanner(c: Context) = prefs(c).getBoolean("trailer_banner", true)
	fun trailerImBanner(c: Context, an: Boolean) = prefs(c).edit().putBoolean("trailer_banner", an).apply()

	/** Aus (Vorgabe): die Seitenleiste kommt nur mit Zurück. An: auch mit Links am linken Rand. */
	fun leisteMitLinks(c: Context) = prefs(c).getBoolean("leiste_mit_links", false)
	fun leisteMitLinks(c: Context, an: Boolean) = prefs(c).edit().putBoolean("leiste_mit_links", an).apply()
}

/**
 * Titelmenü nach langem Drücken: Abspielen, Von vorn, Gesehen/Ungesehen, Favorit, Zur Serie, Details.
 * [geaendert] lädt die Seite nach einer Änderung neu.
 */
@Composable
fun TitelMenue(vm: SelikuViewModel, item: BaseItemDto, geaendert: () -> Unit) {
	val daten = koinInject<SelikuDaten>()
	val abspielen = koinInject<Abspielen>()
	val context = LocalContext.current
	val scope = rememberCoroutineScope()
	val erstes = remember { FocusRequester() }
	val schliessen = { vm.menue = null }
	val gesehen = item.userData?.played == true
	val favorit = item.userData?.isFavorite == true
	val angefangen = (item.userData?.playbackPositionTicks ?: 0L) > 0L
	val spielbar = item.type in setOf(BaseItemKind.MOVIE, BaseItemKind.EPISODE, BaseItemKind.VIDEO, BaseItemKind.MUSIC_VIDEO)

	data class Punkt(val text: String, val symbol: String, val aktion: () -> Unit)
	val punkte = buildList {
		// Bei Folgen steht «Zur Serie» zuoberst
		item.seriesId?.let { sid -> add(Punkt("Zur Serie", "›") { schliessen(); vm.zu(Seite.Serie(sid)) }) }
		if (spielbar) add(Punkt(if (angefangen) fortsetzenText(item) ?: "Abspielen" else "Abspielen", "▶") { abspielen.video(context, item); schliessen() })
		if (spielbar && angefangen) add(Punkt("Von vorn", "↺") { abspielen.video(context, item, vonVorn = true); schliessen() })
		// Serie bzw. Film aus «Weiterschauen» nehmen (auch «Als Nächstes»-Folgen); kommt beim nächsten Abspielen von selbst zurück
		if (item.type == BaseItemKind.EPISODE || (item.type == BaseItemKind.MOVIE && angefangen)) add(Punkt("Aus Weiterschauen entfernen", "✕") {
			scope.launch { runCatching { daten.ausWeiterschauen(item) }; schliessen(); geaendert() }
		})
		if (item.type == BaseItemKind.SERIES || item.type == BaseItemKind.SEASON) add(Punkt("Zufällige Folge", "⤨") { abspielen.zufall(context, item); schliessen() })
		add(Punkt(if (gesehen) "Als ungesehen markieren" else "Als gesehen markieren", if (gesehen) "○" else "✓") {
			scope.launch { runCatching { daten.gesehen(item.id, !gesehen) }; schliessen(); geaendert() }
		})
		add(Punkt(if (favorit) "Aus Favoriten entfernen" else "Zu Favoriten", if (favorit) "☆" else "★") {
			scope.launch { runCatching { daten.favorit(item.id, !favorit) }; schliessen(); geaendert() }
		})
		seiteFuer(item)?.let { s -> add(Punkt("Details", "ⓘ") { schliessen(); vm.zu(s) }) }
	}

	// Das Menü geht auf, während OK noch gedrückt ist. Das Loslassen dieser Taste darf nicht den ersten Punkt
	// auslösen: bis zum ersten neuen Tastendruck im Menü wird ein «OK losgelassen» verschluckt.
	var bereit by remember { mutableStateOf(false) }
	Dialog(onDismissRequest = schliessen) {
		Column(
			Modifier.width(420.dp).background(Farben.Flaeche, RoundedCornerShape(18.dp)).padding(20.dp)
				.onPreviewKeyEvent { e ->
					// Solange OK noch vom langen Druck gehalten wird, schickt die Fernbedienung Wiederholungen (repeatCount > 0)
					// und am Ende das Loslassen – beides verschlucken. Erst ein neuer Druck (repeatCount 0) gilt.
					val ok = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
					val wiederholung = e.nativeKeyEvent.repeatCount > 0
					when {
						bereit -> false
						e.type == KeyEventType.KeyDown && !wiederholung -> { bereit = true; false }
						ok -> true
						else -> false
					}
				},
			verticalArrangement = Arrangement.spacedBy(8.dp),
		) {
			Text(item.hauptzeile(), style = Schrift.Reihe, maxLines = 1, overflow = TextOverflow.Ellipsis)
			if (item.type == BaseItemKind.EPISODE) Text(item.unterzeile(), style = Schrift.Klein, maxLines = 1)
			Spacer(Modifier.height(6.dp))
			punkte.forEachIndexed { i, p ->
				Knopf(p.text, onClick = p.aktion, symbol = p.symbol, breit = true,
					modifier = Modifier.fillMaxWidth().then(if (i == 0) Modifier.focusRequester(erstes) else Modifier))
			}
		}
	}
	LaunchedEffect(item.id) { runCatching { erstes.requestFocus() } }
}
