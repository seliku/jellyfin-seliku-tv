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
import androidx.compose.ui.res.stringResource
import org.jellyfin.androidtv.R

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
	/** An (Vorgabe): Live-TV nach Senderblöcken getrennt (1–99, 100–199 …, z. B. je Sprache) – Reiter auf der Seite, Zappen bleibt im Block. */
	@JvmStatic fun senderGruppen(c: Context) = prefs(c).getBoolean("sender_gruppen", true)
	fun senderGruppen(c: Context, an: Boolean) = prefs(c).edit().putBoolean("sender_gruppen", an).apply()

	/** An (Vorgabe): «Zurück» im Live-TV öffnet den Programmführer, der Sender läuft klein weiter; erst ein zweites «Zurück» beendet. */
	@JvmStatic fun liveZurueckProgramm(c: Context) = prefs(c).getBoolean("live_zurueck_programm", true)
	fun liveZurueckProgramm(c: Context, an: Boolean) = prefs(c).edit().putBoolean("live_zurueck_programm", an).apply()
	/** Zuletzt gewählter Senderblock auf der Live-TV-Seite (-1 = alle), nur auf diesem Gerät. */
	fun senderBlock(c: Context) = prefs(c).getInt("sender_block", -1)
	fun senderBlock(c: Context, block: Int) = prefs(c).edit().putInt("sender_block", block).apply()
	fun zufallKnopf(c: Context, an: Boolean) = prefs(c).edit().putBoolean("zufall_knopf", an).apply()

	/** An (Vorgabe): im Banner läuft nach ein paar Sekunden stumm der Trailer (falls auf dem Server einer liegt). */
	fun trailerImBanner(c: Context) = prefs(c).getBoolean("trailer_banner", true)
	fun trailerImBanner(c: Context, an: Boolean) = prefs(c).edit().putBoolean("trailer_banner", an).apply()

	/** Aus (Vorgabe): die Seitenleiste kommt nur mit Zurück. An: auch mit Links am linken Rand. */
	fun leisteMitLinks(c: Context) = prefs(c).getBoolean("leiste_mit_links", false)
	fun leisteMitLinks(c: Context, an: Boolean) = prefs(c).edit().putBoolean("leiste_mit_links", an).apply()

	/** An (Vorgabe): Filme starten sofort; Untertitel kommen nach, sobald der Server sie aus der Datei gelesen hat. */
	/** An (Vorgabe): Der Fernseher bleibt nur wach, solange etwas läuft (Film, Musik, Podcast, Radio). Aus: wie in Jellyfin – immer, solange die App offen ist. */
	@JvmStatic fun nurBeiWiedergabeWach(c: Context) = prefs(c).getBoolean("nur_bei_wiedergabe_wach", true)
	fun nurBeiWiedergabeWach(c: Context, an: Boolean) = prefs(c).edit().putBoolean("nur_bei_wiedergabe_wach", an).apply()

	/** An (Vorgabe): Reihe «Deine Favoriten» auf der Startseite (nur, wenn es Favoriten gibt). */
	fun favoritenReihe(c: Context) = prefs(c).getBoolean("favoriten_reihe", true)
	fun favoritenReihe(c: Context, an: Boolean) = prefs(c).edit().putBoolean("favoriten_reihe", an).apply()

	/** Startseite: Reihe «Wahre Geschichten» (TMDb-Schlagwörter). */
	fun wahreGeschichten(c: Context) = prefs(c).getBoolean("wahre_geschichten", true)
	fun wahreGeschichten(c: Context, an: Boolean) = prefs(c).edit().putBoolean("wahre_geschichten", an).apply()

	/** Musik-Übersicht: oben «Deine Favoriten» (Playlists, Mixe, Alben, Radiosender). */
	fun musikFavoriten(c: Context) = prefs(c).getBoolean("musik_favoriten", true)
	fun musikFavoriten(c: Context, an: Boolean) = prefs(c).edit().putBoolean("musik_favoriten", an).apply()

	/** An (Vorgabe): Seite «Mein Verlauf» in der Seitenleiste (Gesehenes, Sehzeit, Daumen hoch/runter). */
	fun meinVerlauf(c: Context) = prefs(c).getBoolean("mein_verlauf", true)
	fun meinVerlauf(c: Context, an: Boolean) = prefs(c).edit().putBoolean("mein_verlauf", an).apply()

	/** An (Vorgabe): im Banner auch 1–2 Titel, die es auf dem Server nicht gibt (über Seerr), mit «Anfragen». */
	fun bannerSeerr(c: Context) = prefs(c).getBoolean("banner_seerr", true)
	fun bannerSeerr(c: Context, an: Boolean) = prefs(c).edit().putBoolean("banner_seerr", an).apply()

	/** An (Vorgabe): eigener Player für Musik, Hörbücher und Podcasts; aus = Jellyfins alter Player. */
	fun neuerAudioPlayer(c: Context) = prefs(c).getBoolean("audio_player", true)
	fun neuerAudioPlayer(c: Context, an: Boolean) = prefs(c).edit().putBoolean("audio_player", an).apply()

	/** An (Vorgabe): Musik spielt nach dem letzten Titel mit passenden Titeln weiter (wie Plexamp). */
	fun endlosMusik(c: Context) = prefs(c).getBoolean("endlos_musik", true)
	fun endlosMusik(c: Context, an: Boolean) = prefs(c).edit().putBoolean("endlos_musik", an).apply()

	/** Musik: so viele Sekunden blenden zwei Lieder ineinander (0 = aus). Vorgabe 6. */
	fun ueberblenden(c: Context) = prefs(c).getInt("ueberblenden", 6)
	fun ueberblenden(c: Context, sekunden: Int) = prefs(c).edit().putInt("ueberblenden", sekunden).apply()

	/** An (Vorgabe): Reihe «Weiterhören» (Hörbücher, Podcasts) auf der Startseite. */
	fun hoerenStart(c: Context) = prefs(c).getBoolean("hoeren_start", true)
	fun hoerenStart(c: Context, an: Boolean) = prefs(c).edit().putBoolean("hoeren_start", an).apply()

	/** Eigene Wertung (Sterne) setzen und anzeigen. */
	fun eigeneWertung(c: Context) = prefs(c).getBoolean("eigene_wertung", true)
	fun eigeneWertung(c: Context, an: Boolean) = prefs(c).edit().putBoolean("eigene_wertung", an).apply()

	/** Rotten-Tomatoes-Wertung anzeigen (fehlt sie in Jellyfin, z. B. bei Serien, wird sie bei Seerr nachgeschlagen). */
	fun tomaten(c: Context) = prefs(c).getBoolean("tomaten", true)
	fun tomaten(c: Context, an: Boolean) = prefs(c).edit().putBoolean("tomaten", an).apply()

	/** An (Vorgabe): Gibt es keinen Trailer, zeigen Banner und Kacheln einen Ausschnitt aus dem Film bzw. einer Folge. */
	fun ausschnittOhneTrailer(c: Context) = prefs(c).getBoolean("ausschnitt_ohne_trailer", true)
	fun ausschnittOhneTrailer(c: Context, an: Boolean) = prefs(c).edit().putBoolean("ausschnitt_ohne_trailer", an).apply()

	@JvmStatic fun untertitelNachladen(c: Context) = prefs(c).getBoolean("untertitel_nachladen", true)
	fun untertitelNachladen(c: Context, an: Boolean) = prefs(c).edit().putBoolean("untertitel_nachladen", an).apply()
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
	// Musik wie bei Plexamp: sofort spielen, als Nächstes oder hinten anstellen (die laufende Liste bleibt)
	val musik = item.type in setOf(BaseItemKind.AUDIO, BaseItemKind.MUSIC_ALBUM, BaseItemKind.PLAYLIST, BaseItemKind.MUSIC_ARTIST)
	suspend fun musikTitel(): List<BaseItemDto> = runCatching {
		when (item.type) {
			BaseItemKind.AUDIO -> listOf(item)
			BaseItemKind.MUSIC_ALBUM -> daten.titel(item.id)
			BaseItemKind.PLAYLIST -> daten.playlistTitel(item.id)
			else -> daten.titelVon(item.id, beliebteste = false)
		}
	}.getOrDefault(emptyList())

	data class Punkt(val text: String, val symbol: String, val aktion: () -> Unit)
	val punkte = buildList {
		// Bei Folgen steht «Zur Serie» zuoberst
		item.seriesId?.let { sid -> add(Punkt(stringResource(R.string.seliku_go_to_series), "›") { schliessen(); vm.zu(Seite.Serie(sid)) }) }
		if (musik) {
			add(Punkt(stringResource(R.string.seliku_play), "▶") { scope.launch { abspielen.musik(context, musikTitel()); schliessen() } })
			if (Hoeren.musikLaeuft()) {
				add(Punkt(stringResource(R.string.seliku_audio_play_next), "⤵") { scope.launch {
					Hoeren.einreihen(musikTitel(), gleichDanach = true)
					android.widget.Toast.makeText(context, context.getString(R.string.seliku_audio_queued_next, item.name.orEmpty()), android.widget.Toast.LENGTH_SHORT).show()
					schliessen()
				} })
				add(Punkt(stringResource(R.string.seliku_audio_add_queue), "+") { scope.launch {
					Hoeren.einreihen(musikTitel(), gleichDanach = false)
					android.widget.Toast.makeText(context, context.getString(R.string.seliku_audio_queued_end, item.name.orEmpty()), android.widget.Toast.LENGTH_SHORT).show()
					schliessen()
				} })
			}
			if (item.type == BaseItemKind.AUDIO) item.albumId?.let { a -> add(Punkt(stringResource(R.string.seliku_audio_go_album), "›") { schliessen(); vm.zu(Seite.Album(a)) }) }
			if (item.type != BaseItemKind.MUSIC_ARTIST) (item.albumArtists?.firstOrNull()?.id ?: item.artistItems?.firstOrNull()?.id)?.let { i ->
				add(Punkt(stringResource(R.string.seliku_go_to_artist), "›") { schliessen(); vm.zu(Seite.Interpret(i)) })
			}
		}
		if (spielbar) add(Punkt(if (angefangen) fortsetzenText(item) ?: stringResource(R.string.seliku_play) else stringResource(R.string.seliku_play), "▶") { abspielen.video(context, item); schliessen() })
		if (spielbar && angefangen) add(Punkt(stringResource(R.string.seliku_from_start), "↺") { abspielen.video(context, item, vonVorn = true); schliessen() })
		// Serie bzw. Film aus «Weiterschauen» nehmen (auch «Als Nächstes»-Folgen); kommt beim nächsten Abspielen von selbst zurück
		if (item.type == BaseItemKind.EPISODE || (item.type == BaseItemKind.MOVIE && angefangen)) add(Punkt(stringResource(R.string.seliku_remove_continue_watching), "✕") {
			scope.launch { runCatching { daten.ausWeiterschauen(item) }; schliessen(); geaendert() }
		})
		if (item.type == BaseItemKind.SERIES || item.type == BaseItemKind.SEASON) add(Punkt(stringResource(R.string.seliku_random_episode), "⤨") { abspielen.zufall(context, item); schliessen() })
		if (!musik) add(Punkt(stringResource(if (gesehen) R.string.seliku_mark_as_unwatched else R.string.seliku_mark_as_watched), if (gesehen) "○" else "✓") {
			scope.launch { runCatching { daten.gesehen(item.id, !gesehen) }; schliessen(); geaendert() }
		})
		add(Punkt(stringResource(if (favorit) R.string.seliku_remove_favorite else R.string.seliku_add_favorite), if (favorit) "☆" else "★") {
			scope.launch {
				runCatching { daten.favorit(item.id, !favorit) }
					.onSuccess { favoritMeldung(context, !favorit) }
					.onFailure { android.widget.Toast.makeText(context, context.getString(R.string.seliku_failed, it.message?.take(80).toString()), android.widget.Toast.LENGTH_SHORT).show() }
				schliessen(); geaendert()
			}
		})
		if (SelikuEinstellungen.eigeneWertung(context) && item.type in setOf(BaseItemKind.MOVIE, BaseItemKind.SERIES, BaseItemKind.EPISODE, BaseItemKind.VIDEO))
			add(Punkt(item.eigeneSterne()?.let(::sterneText) ?: stringResource(R.string.seliku_rate), "☆") { schliessen(); vm.wertung = item })
		seiteFuer(item)?.let { s -> add(Punkt(stringResource(R.string.seliku_details), "ⓘ") { schliessen(); vm.zu(s) }) }
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

/** «Wie war's?»: eigene Wertung von 1 bis 5 Sternen setzen oder löschen (bei Jellyfin pro Benutzer gespeichert). */
@Composable
fun WertungAuswahl(vm: SelikuViewModel, item: BaseItemDto, geaendert: () -> Unit) {
	val daten = koinInject<SelikuDaten>()
	val context = LocalContext.current
	val scope = rememberCoroutineScope()
	val erstes = remember { FocusRequester() }
	val jetzt = item.eigeneSterne()
	val schliessen = { vm.wertung = null }
	fun setzen(sterne: Int) {
		scope.launch {
			runCatching { daten.wertung(item.id, sterne) }
				.onSuccess { geaendert() }
				.onFailure { android.widget.Toast.makeText(context, context.getString(R.string.seliku_failed, it.message?.take(80).toString()), android.widget.Toast.LENGTH_SHORT).show() }
			schliessen()
		}
	}
	val stufen = listOf(5 to R.string.seliku_rating_5, 4 to R.string.seliku_rating_4, 3 to R.string.seliku_rating_3, 2 to R.string.seliku_rating_2, 1 to R.string.seliku_rating_1)
	Dialog(onDismissRequest = schliessen) {
		Column(Modifier.width(420.dp).background(Farben.Flaeche, RoundedCornerShape(18.dp)).padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
			Text(stringResource(R.string.seliku_rating_question), style = Schrift.Reihe, maxLines = 1)
			Text(item.hauptzeile(), style = Schrift.Klein, maxLines = 1, overflow = TextOverflow.Ellipsis)
			Spacer(Modifier.height(6.dp))
			stufen.forEach { (sterne, text) ->
				Knopf(sterneText(sterne) + "   " + stringResource(text), onClick = { setzen(sterne) }, aktiv = sterne == jetzt, breit = true,
					modifier = Modifier.fillMaxWidth().then(if (sterne == (jetzt ?: 5)) Modifier.focusRequester(erstes) else Modifier))
			}
			if (jetzt != null) Knopf(stringResource(R.string.seliku_rating_remove), onClick = { setzen(0) }, symbol = "✕", breit = true, modifier = Modifier.fillMaxWidth())
		}
	}
	LaunchedEffect(item.id) { runCatching { erstes.requestFocus() } }
}

/** Kurze Bestätigung, damit man sieht, dass der Favorit gesetzt ist – und wo man ihn findet. */
fun favoritMeldung(context: Context, hinzugefuegt: Boolean) {
	android.widget.Toast.makeText(context, context.getString(if (hinzugefuegt) R.string.seliku_favorite_added else R.string.seliku_favorite_removed), android.widget.Toast.LENGTH_LONG).show()
}
