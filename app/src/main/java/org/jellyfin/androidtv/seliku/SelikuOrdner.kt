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
 * Seliku-Einstellungen, in Gruppen: Schalter (An/Aus), Auswahl (OK wechselt den Wert) und Verweise auf Unterseiten.
 * Neue Punkte in die passende Gruppe; die id bleibt fest, damit der Fokus beim Umschalten stehen bleibt.
 */
@Composable
fun EinstellungenSeite(vm: SelikuViewModel, jellyfinEinstellungen: () -> Unit) {
	val context = androidx.compose.ui.platform.LocalContext.current
	var doppel by remember { mutableStateOf(SelikuEinstellungen.doppelZurueck(context)) }
	var schlank by remember { mutableStateOf(SelikuEinstellungen.schlankerPlayer(context)) }
	var links by remember { mutableStateOf(SelikuEinstellungen.leisteMitLinks(context)) }
	var trailerBanner by remember { mutableStateOf(SelikuEinstellungen.trailerImBanner(context)) }
	var zufall by remember { mutableStateOf(SelikuEinstellungen.zufallKnopf(context)) }
	var netflix by remember { mutableStateOf(SelikuEinstellungen.netflixKacheln(context)) }
	var musik by remember { mutableStateOf(SelikuEinstellungen.titelmusik(context)) }
	var nachladen by remember { mutableStateOf(SelikuEinstellungen.untertitelNachladen(context)) }
	val userPrefs = remember { org.koin.java.KoinJavaComponent.get<org.jellyfin.androidtv.preference.UserPreferences>(org.jellyfin.androidtv.preference.UserPreferences::class.java) }
	var bitrate by remember { mutableStateOf(userPrefs[org.jellyfin.androidtv.preference.UserPreferences.maxBitrate]) }
	val bitrateStufen = listOf("100", "40", "20", "10", "5")
	val seerr = when {
		SeerrVerbindung.vermittelt.value -> "über Jellyfin"
		SeerrVerbindung.verbunden(context) -> "verbunden" + SeerrVerbindung.name(context).let { if (it.isBlank()) "" else " als $it" }
		else -> "nicht verbunden"
	}

	val eintraege = listOf(
		EinstellungsEintrag.Gruppe("Startseite & Navigation"),
		EinstellungsEintrag.Schalter("kacheln", "Kacheln aufklappen", "Gewählte Kachel zeigt nach kurzem Verweilen Bild und Infos", netflix) {
			netflix = !netflix; SelikuEinstellungen.netflixKacheln(context, netflix)
		},
		EinstellungsEintrag.Schalter("trailer", "Trailer im Banner und in Kacheln", "Läuft nach ein paar Sekunden, stumm wenn Musik läuft", trailerBanner) {
			trailerBanner = !trailerBanner; SelikuEinstellungen.trailerImBanner(context, trailerBanner)
		},
		EinstellungsEintrag.Schalter("leiste", "Seitenleiste auch mit Links öffnen", "Sonst geht die Leiste nur mit Zurück auf", links) {
			links = !links; SelikuEinstellungen.leisteMitLinks(context, links)
		},
		EinstellungsEintrag.Schalter("zurueck", "Zweimal Zurück beendet die App", "Aus: Zurück beendet die App nie – wie bei Netflix", doppel) {
			doppel = !doppel; SelikuEinstellungen.doppelZurueck(context, doppel)
		},

		EinstellungsEintrag.Gruppe("Wiedergabe"),
		EinstellungsEintrag.Schalter("player", "Schlanke Player-Bedienung", "OK pausiert, Links/Rechts spult, Bedienleiste mit «hoch»", schlank) {
			schlank = !schlank; SelikuEinstellungen.schlankerPlayer(context, schlank)
		},
		EinstellungsEintrag.Auswahl("bitrate", "Max. Streaming-Bitrate", "Tiefer bei langsamem Internet – weniger Unterbrüche. Nur dieses Gerät", "$bitrate Mbit/s") {
			bitrate = bitrateStufen[(bitrateStufen.indexOf(bitrate) + 1) % bitrateStufen.size]
			userPrefs[org.jellyfin.androidtv.preference.UserPreferences.maxBitrate] = bitrate
		},
		EinstellungsEintrag.Schalter("untertitel", "Untertitel nachladen", "Filme starten sofort, Untertitel kommen nach", nachladen) {
			nachladen = !nachladen; SelikuEinstellungen.untertitelNachladen(context, nachladen)
		},

		EinstellungsEintrag.Gruppe("Serien"),
		EinstellungsEintrag.Schalter("titelmusik", "Titelmusik", "Läuft leise beim Öffnen einer Serie – wie bei Plex", musik) {
			musik = !musik; SelikuEinstellungen.titelmusik(context, musik)
		},
		EinstellungsEintrag.Schalter("zufall", "Knopf «Zufällig»", "Zufällige Folge direkt auf der Serienseite, sonst im Menü «⋯»", zufall) {
			zufall = !zufall; SelikuEinstellungen.zufallKnopf(context, zufall)
		},

		EinstellungsEintrag.Gruppe("Inhalte & Dienste"),
		EinstellungsEintrag.Verweis("bibliotheken", "Bibliotheken", "Welche in der Leiste und auf der Startseite erscheinen") { vm.zu(Seite.Bibliotheken) },
		EinstellungsEintrag.Verweis("seerr", "Seerr", "Fehlende Filme und Serien entdecken und anfragen", seerr) {
			if (SeerrVerbindung.vermittelt.value) vm.wurzel(Seite.Entdecken) else vm.zu(Seite.SeerrVerbinden)
		},

		EinstellungsEintrag.Gruppe("Weiteres"),
		EinstellungsEintrag.Verweis("jellyfin", "Jellyfin-Einstellungen", "Untertitel-Aussehen, Live-TV, Konten und mehr") { jellyfinEinstellungen() },
	)

	Column(Modifier.fillMaxSize().background(Farben.Hintergrund)) {
		Text("Einstellungen", style = Schrift.Titel, modifier = Modifier.padding(start = 48.dp, top = 28.dp, bottom = 8.dp))
		LazyColumn(state = vm.liste(Seite.Einstellungen), contentPadding = PaddingValues(start = 40.dp, end = 48.dp, bottom = 40.dp)) {
			items(eintraege, key = { it.id }) { e ->
				when (e) {
					is EinstellungsEintrag.Gruppe -> Text(e.titel, style = Schrift.Reihe.copy(color = Farben.Akzent),
						modifier = Modifier.padding(start = 8.dp, top = 22.dp, bottom = 6.dp))
					else -> Waehlbar(onClick = e.aktion, vergroessern = false, modifier = Modifier.padding(vertical = 3.dp),
						merken = vm to (Seite.Einstellungen.schluessel to e.id)) { fokussiert ->
						Row(
							Modifier.fillMaxWidth().background(if (fokussiert) Farben.FlaecheHell else Farben.Flaeche).padding(horizontal = 20.dp, vertical = 12.dp),
							verticalAlignment = Alignment.CenterVertically,
						) {
							Column(Modifier.weight(1f)) {
								Text(e.titel, style = Schrift.Normal.copy(fontWeight = FontWeight.Bold))
								Text(e.text, style = Schrift.Klein, maxLines = 1, overflow = TextOverflow.Ellipsis)
							}
							Spacer(Modifier.width(16.dp))
							when (e) {
								is EinstellungsEintrag.Schalter -> AnAusSchalter(e.an)
								is EinstellungsEintrag.Auswahl -> Text(e.wert + "  ‹›", style = Schrift.Normal.copy(color = Farben.Akzent, fontWeight = FontWeight.Bold))
								is EinstellungsEintrag.Verweis -> Text((e.wert?.let { "$it  " } ?: "") + "›", style = Schrift.Normal.copy(color = Farben.TextLeise))
								else -> Unit
							}
						}
					}
				}
			}
			item(key = "version") {
				Text("Version ${org.jellyfin.androidtv.BuildConfig.VERSION_NAME}", style = Schrift.Klein,
					modifier = Modifier.padding(start = 8.dp, top = 24.dp))
			}
		}
	}
}

private sealed class EinstellungsEintrag(val id: String, val titel: String, val text: String, val aktion: () -> Unit) {
	class Gruppe(titel: String) : EinstellungsEintrag("gruppe:$titel", titel, "", {})
	class Schalter(id: String, titel: String, text: String, val an: Boolean, aktion: () -> Unit) : EinstellungsEintrag(id, titel, text, aktion)
	class Auswahl(id: String, titel: String, text: String, val wert: String, aktion: () -> Unit) : EinstellungsEintrag(id, titel, text, aktion)
	class Verweis(id: String, titel: String, text: String, val wert: String? = null, aktion: () -> Unit) : EinstellungsEintrag(id, titel, text, aktion)
}

/** An/Aus-Schalter wie in Android: Spur mit Knopf, an = Akzentfarbe, Knopf rechts. */
@Composable
private fun AnAusSchalter(an: Boolean) {
	val lage by androidx.compose.animation.core.animateDpAsState(if (an) 22.dp else 2.dp, label = "schalter")
	Box(Modifier.width(46.dp).height(26.dp)
		.background(if (an) Farben.Akzent else Farben.TextLeise.copy(alpha = 0.35f), androidx.compose.foundation.shape.RoundedCornerShape(13.dp))) {
		Box(Modifier.padding(start = lage, top = 3.dp).width(20.dp).height(20.dp)
			.background(if (an) Farben.Text else Farben.TextLeise, androidx.compose.foundation.shape.CircleShape))
	}
}
