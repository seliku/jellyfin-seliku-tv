package org.jellyfin.androidtv.seliku

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.koin.compose.koinInject
import timber.log.Timber
import java.util.UUID

/**
 * Ordneransicht für Bibliotheken ohne feste Ordnung (Hörbücher, eigene Videos): oben die Unterordner,
 * darunter die Dateien als Liste. Ein Klick spielt ab dieser Datei alles Folgende im Ordner ab.
 */
@Composable
fun OrdnerSeite(vm: SelikuViewModel, seite: Seite.Ordner, oeffnen: (BaseItemDto) -> Unit) {
	val daten = koinInject<SelikuDaten>()
	val abspielen = koinInject<Abspielen>()
	val context = LocalContext.current
	var inhalt by remember { mutableStateOf<List<BaseItemDto>?>(null) }
	var titel by remember { mutableStateOf(seite.name) }

	LaunchedEffect(seite.id) {
		inhalt = runCatching { daten.ordnerInhalt(seite.id) }.onFailure { Timber.w(it, "Ordner laden") }.getOrDefault(emptyList())
	}

	val alle = inhalt
	Column(Modifier.fillMaxSize().background(Farben.Hintergrund)) {
		Text(titel, style = Schrift.Titel, modifier = Modifier.padding(start = 48.dp, top = 28.dp, bottom = 12.dp))
		if (alle == null) { Hinweis("Wird geladen …"); return@Column }
		if (alle.isEmpty()) { Hinweis("Dieser Ordner ist leer."); return@Column }
		val ordner = alle.filter { it.isFolder == true && it.type != BaseItemKind.MUSIC_ALBUM }
		val dateien = alle.filter { it !in ordner }
		val audio = dateien.filter { it.type == BaseItemKind.AUDIO_BOOK || it.type == BaseItemKind.AUDIO }
		LazyColumn(state = vm.liste(seite), contentPadding = PaddingValues(bottom = 48.dp), modifier = Modifier.fillMaxSize()) {
			if (ordner.isNotEmpty()) item {
				Reihe("Ordner", ordner, vm, seite, onClick = { oeffnen(it) }, kachel = { item, klick, merken, fokus ->
					QuadratKachel(item, klick, merken = merken, onFokus = fokus)
				})
			}
			if (audio.isNotEmpty()) item {
				Row(Modifier.padding(start = 48.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
					Knopf("Alles abspielen", symbol = "▶", hervorgehoben = true, onClick = { abspielen.musik(context, audio) })
				}
			}
			itemsIndexed(dateien, key = { _, it -> it.id }) { index, d ->
				if (d.type == BaseItemKind.AUDIO_BOOK || d.type == BaseItemKind.AUDIO) {
					TitelZeile(d, nummer = index + 1, mitAlbum = false, merken = vm to (seite.schluessel to d.id.toString()),
						onClick = { abspielen.musik(context, audio, audio.indexOf(d).coerceAtLeast(0)) })
				} else {
					Box(Modifier.padding(start = 48.dp, top = 8.dp)) {
						QuerKachel(d, onClick = { abspielen.video(context, d) }, merken = vm to (seite.schluessel to d.id.toString()))
					}
				}
			}
		}
	}
}

/**
 * Bibliotheken verwalten: alle Bibliotheken mit «Öffnen» und «In Leiste und Startseite zeigen».
 * Ausgeblendete (z. B. private Videos) erreicht man nur über diese Seite. Gespeichert wird in den
 * Jellyfin-Benutzereinstellungen, gilt also auf allen Geräten.
 */
@Composable
fun BibliothekenSeite(vm: SelikuViewModel, oeffnenBibliothek: (BaseItemDto) -> Unit, geaendert: () -> Unit) {
	val daten = koinInject<SelikuDaten>()
	val scope = rememberCoroutineScope()
	var alle by remember { mutableStateOf<List<BaseItemDto>?>(null) }
	var versteckt by remember { mutableStateOf<Set<UUID>>(emptySet()) }

	LaunchedEffect(Unit) {
		runCatching {
			alle = daten.alleBibliotheken()
			versteckt = daten.versteckteBibliotheken()
		}.onFailure { Timber.w(it, "Bibliotheken verwalten") }
	}

	Column(Modifier.fillMaxSize().background(Farben.Hintergrund)) {
		Text("Bibliotheken", style = Schrift.Titel, modifier = Modifier.padding(start = 48.dp, top = 28.dp))
		Text("Ausgeblendete Bibliotheken erscheinen nicht in der Leiste und nicht auf der Startseite, sind aber hier erreichbar.",
			style = Schrift.Leise, modifier = Modifier.padding(start = 48.dp, top = 6.dp, end = 200.dp, bottom = 16.dp))
		val l = alle
		if (l == null) { Hinweis("Wird geladen …"); return@Column }
		LazyColumn(state = vm.liste(Seite.Bibliotheken), contentPadding = PaddingValues(start = 40.dp, end = 48.dp, bottom = 40.dp)) {
			items(l, key = { it.id }) { b ->
				val aus = b.id in versteckt
				Row(
					Modifier.fillMaxWidth().padding(vertical = 4.dp).background(Farben.Flaeche, androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
						.padding(horizontal = 16.dp, vertical = 10.dp),
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.spacedBy(14.dp),
				) {
					Column(Modifier.weight(1f)) {
						Text(b.name.orEmpty(), style = Schrift.Normal.copy(fontWeight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
						Text(if (aus) "ausgeblendet" else "sichtbar", style = Schrift.Klein)
					}
					Knopf("Öffnen", symbol = "›", onClick = { oeffnenBibliothek(b) })
					Knopf(if (aus) "Einblenden" else "Ausblenden", symbol = if (aus) "◉" else "○", aktiv = !aus, onClick = {
						val neu = if (aus) versteckt - b.id else versteckt + b.id
						versteckt = neu
						scope.launch {
							runCatching { daten.versteckteSetzen(neu) }.onFailure { Timber.w(it, "Ausblenden speichern") }
							geaendert()
						}
					})
				}
			}
		}
	}
}

/**
 * Seliku-Einstellungen: eigene Punkte der Oberfläche plus der Weg zu den Jellyfin-Einstellungen.
 * Weitere Schalter (Zurück-Verhalten, Seerr, Radio, Bildschirmschoner) kommen hier dazu.
 */
@Composable
fun EinstellungenSeite(vm: SelikuViewModel, jellyfinEinstellungen: () -> Unit) {
	data class Punkt(val titel: String, val text: String, val aktion: () -> Unit)
	val context = androidx.compose.ui.platform.LocalContext.current
	var doppel by remember { mutableStateOf(SelikuEinstellungen.doppelZurueck(context)) }
	val punkte = listOf(
		Punkt("Zurück-Taste: " + (if (doppel) "zweimal drücken beendet die App" else "beendet die App nie"),
			"Aus: Zurück springt nach oben, dann in die Leiste, dann zur Startseite – wie bei Netflix. An: zusätzlich zweimal Zurück auf der Startseite zum Beenden") {
			doppel = !doppel; SelikuEinstellungen.doppelZurueck(context, doppel)
		},
		Punkt("Bibliotheken", "Welche Bibliotheken in der Leiste und auf der Startseite erscheinen; ausgeblendete hier öffnen") { vm.zu(Seite.Bibliotheken) },
		Punkt("Jellyfin-Einstellungen", "Wiedergabe, Untertitel, Live-TV, Konten – die Einstellungen der Jellyfin-App") { jellyfinEinstellungen() },
	)
	Column(Modifier.fillMaxSize().background(Farben.Hintergrund)) {
		Text("Einstellungen", style = Schrift.Titel, modifier = Modifier.padding(start = 48.dp, top = 28.dp, bottom = 16.dp))
		LazyColumn(state = vm.liste(Seite.Einstellungen), contentPadding = PaddingValues(start = 40.dp, end = 48.dp, bottom = 40.dp)) {
			items(punkte, key = { it.titel }) { p ->
				Waehlbar(onClick = p.aktion, vergroessern = false, modifier = Modifier.padding(vertical = 4.dp),
					merken = vm to (Seite.Einstellungen.schluessel to p.titel)) { fokussiert ->
					Row(
						Modifier.fillMaxWidth().background(if (fokussiert) Farben.FlaecheHell else Farben.Flaeche).padding(horizontal = 20.dp, vertical = 14.dp),
						verticalAlignment = Alignment.CenterVertically,
					) {
						Column(Modifier.weight(1f)) {
							Text(p.titel, style = Schrift.Normal.copy(fontWeight = FontWeight.Bold))
							Text(p.text, style = Schrift.Klein, maxLines = 2)
						}
						Text("›", style = Schrift.Titel.copy(color = Farben.TextLeise))
					}
				}
			}
		}
	}
}
