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
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.res.stringResource
import org.jellyfin.androidtv.R

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
	// Hörbücher: angefangene Bücher (in der Bibliothek selbst als Reihe «Weiterhören»), Fortschritt auf den Kacheln
	val version by Hoeren.version.collectAsState()
	var weiter by remember { mutableStateOf<List<BaseItemDto>>(emptyList()) }
	var istHoerbuecher by remember { mutableStateOf(false) }
	LaunchedEffect(seite.id, version) {
		istHoerbuecher = runCatching { daten.alleBibliotheken().any { it.id == seite.id && it.collectionType == org.jellyfin.sdk.model.api.CollectionType.BOOKS } }.getOrDefault(false)
		weiter = runCatching { Hoeren.weiterhoeren().filter { it.isFolder == true } }.getOrDefault(emptyList())
	}

	val alle = inhalt
	if (alle != null && Hoeren.istBuch(alle) && SelikuEinstellungen.neuerAudioPlayer(context)) { BuchSeite(vm, seite); return }
	Column(Modifier.fillMaxSize().background(Farben.Hintergrund)) {
		Row(Modifier.padding(start = 48.dp, top = 28.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
			Text(titel, style = Schrift.Titel)
			LaeuftKnopf()
		}
		if (alle == null) { Hinweis(stringResource(R.string.seliku_loading)); return@Column }
		if (alle.isEmpty()) { Hinweis(stringResource(R.string.seliku_folder_empty)); return@Column }
		val ordner = alle.filter { it.isFolder == true && it.type != BaseItemKind.MUSIC_ALBUM }
		val dateien = alle.filter { it !in ordner }
		val audio = dateien.filter { it.type == BaseItemKind.AUDIO_BOOK || it.type == BaseItemKind.AUDIO }
		LazyColumn(state = vm.liste(seite), contentPadding = PaddingValues(bottom = 48.dp), modifier = Modifier.fillMaxSize()) {
			if (istHoerbuecher && weiter.isNotEmpty()) item {
				Reihe(stringResource(R.string.seliku_audio_continue), weiter, vm, seite, onClick = { abspielen.hoeren(context, it) }, kachel = { item, klick, merken, fokus ->
					HoerKachel(item, klick, merken, fokus, onMenue = { oeffnen(item) })
				})
			}
			if (ordner.isNotEmpty()) item {
				Reihe(stringResource(R.string.seliku_folders), ordner, vm, seite, onClick = { oeffnen(it) }, kachel = { item, klick, merken, fokus ->
					if (Hoeren.fortschritt(item.id) != null && weiter.any { it.id == item.id }) HoerKachel(item, klick, merken, fokus)
					else QuadratKachel(item, klick, merken = merken, onFokus = fokus)
				})
			}
			if (audio.isNotEmpty()) item {
				Row(Modifier.padding(start = 48.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
					Knopf(stringResource(R.string.seliku_play_all), symbol = "▶", hervorgehoben = true, onClick = { abspielen.musik(context, audio) })
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
		Text(stringResource(R.string.seliku_libraries), style = Schrift.Titel, modifier = Modifier.padding(start = 48.dp, top = 28.dp))
		Text(stringResource(R.string.seliku_libraries_intro),
			style = Schrift.Leise, modifier = Modifier.padding(start = 48.dp, top = 6.dp, end = 200.dp, bottom = 16.dp))
		val l = alle
		if (l == null) { Hinweis(stringResource(R.string.seliku_loading)); return@Column }
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
						Text(stringResource(if (aus) R.string.seliku_hidden else R.string.seliku_visible), style = Schrift.Klein)
					}
					Knopf(stringResource(R.string.seliku_open), symbol = "›", onClick = { oeffnenBibliothek(b) })
					Knopf(stringResource(if (aus) R.string.seliku_show else R.string.seliku_hide), symbol = if (aus) "◉" else "○", aktiv = !aus, onClick = {
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
	// Beim Öffnen Werte vom Server holen; hat sich etwas geändert, liest «stand» alles neu
	var stand by remember { mutableStateOf(0) }
	LaunchedEffect(Unit) { if (SelikuSync.holen(context)) stand++ }
	// Umfang (☁/📺) pro Einstellung; «umfangStand» zeichnet die Zeichen nach dem Umschalten neu
	var umfangStand by remember { mutableStateOf(0) }
	val gesetzt = { schluessel: String -> SelikuSync.geaendert(context, schluessel) }
	var doppel by remember(stand) { mutableStateOf(SelikuEinstellungen.doppelZurueck(context)) }
	var schlank by remember(stand) { mutableStateOf(SelikuEinstellungen.schlankerPlayer(context)) }
	var links by remember(stand) { mutableStateOf(SelikuEinstellungen.leisteMitLinks(context)) }
	var favoritenReihe by remember(stand) { mutableStateOf(SelikuEinstellungen.favoritenReihe(context)) }
	var wahre by remember(stand) { mutableStateOf(SelikuEinstellungen.wahreGeschichten(context)) }
	var wertung by remember(stand) { mutableStateOf(SelikuEinstellungen.eigeneWertung(context)) }
	var verlauf by remember(stand) { mutableStateOf(SelikuEinstellungen.meinVerlauf(context)) }
	var bannerSeerr by remember(stand) { mutableStateOf(SelikuEinstellungen.bannerSeerr(context)) }
	var tomaten by remember(stand) { mutableStateOf(SelikuEinstellungen.tomaten(context)) }
	var musikFav by remember(stand) { mutableStateOf(SelikuEinstellungen.musikFavoriten(context)) }
	var audioPlayer by remember(stand) { mutableStateOf(SelikuEinstellungen.neuerAudioPlayer(context)) }
	var endlos by remember(stand) { mutableStateOf(SelikuEinstellungen.endlosMusik(context)) }
	var hoerenStart by remember(stand) { mutableStateOf(SelikuEinstellungen.hoerenStart(context)) }
	var trailerBanner by remember(stand) { mutableStateOf(SelikuEinstellungen.trailerImBanner(context)) }
	var zufall by remember(stand) { mutableStateOf(SelikuEinstellungen.zufallKnopf(context)) }
	var senderGruppen by remember(stand) { mutableStateOf(SelikuEinstellungen.senderGruppen(context)) }
	var netflix by remember(stand) { mutableStateOf(SelikuEinstellungen.netflixKacheln(context)) }
	var musik by remember(stand) { mutableStateOf(SelikuEinstellungen.titelmusik(context)) }
	var nachladen by remember(stand) { mutableStateOf(SelikuEinstellungen.untertitelNachladen(context)) }
	var ausschnitt by remember(stand) { mutableStateOf(SelikuEinstellungen.ausschnittOhneTrailer(context)) }
	var wach by remember(stand) { mutableStateOf(SelikuEinstellungen.nurBeiWiedergabeWach(context)) }
	val userPrefs = remember { org.koin.java.KoinJavaComponent.get<org.jellyfin.androidtv.preference.UserPreferences>(org.jellyfin.androidtv.preference.UserPreferences::class.java) }
	var bitrate by remember(stand) { mutableStateOf(userPrefs[org.jellyfin.androidtv.preference.UserPreferences.maxBitrate]) }
	var naechste by remember(stand) { mutableStateOf(userPrefs[org.jellyfin.androidtv.preference.UserPreferences.mediaQueuingEnabled]) }
	val bitrateStufen = listOf("100", "40", "20", "10", "5")
	val seerr = when {
		SeerrVerbindung.vermittelt.value -> stringResource(R.string.seliku_seerr_via_jellyfin)
		SeerrVerbindung.verbunden(context) -> SeerrVerbindung.name(context).let { if (it.isBlank()) stringResource(R.string.seliku_seerr_state_connected) else stringResource(R.string.seliku_seerr_state_connected_as, it) }
		else -> stringResource(R.string.seliku_seerr_state_not_connected)
	}

	val eintraege = listOf(
		EinstellungsEintrag.Gruppe(stringResource(R.string.seliku_set_group_home)),
		EinstellungsEintrag.Schalter("kacheln", umfang = "netflix_kacheln", stringResource(R.string.seliku_set_tiles), stringResource(R.string.seliku_set_tiles_desc), netflix) {
			netflix = !netflix; SelikuEinstellungen.netflixKacheln(context, netflix); gesetzt("netflix_kacheln")
		},
		EinstellungsEintrag.Schalter("favoriten", umfang = "favoriten_reihe", stringResource(R.string.seliku_set_favorites_row), stringResource(R.string.seliku_set_favorites_row_desc), favoritenReihe) {
			favoritenReihe = !favoritenReihe; SelikuEinstellungen.favoritenReihe(context, favoritenReihe); gesetzt("favoriten_reihe"); vm.datenStand++
		},
		EinstellungsEintrag.Schalter("wahre", umfang = "wahre_geschichten", stringResource(R.string.seliku_set_true_stories), stringResource(R.string.seliku_set_true_stories_desc), wahre) {
			wahre = !wahre; SelikuEinstellungen.wahreGeschichten(context, wahre); gesetzt("wahre_geschichten"); vm.datenStand++
		},
		EinstellungsEintrag.Schalter("bannerseerr", umfang = "banner_seerr", stringResource(R.string.seliku_set_banner_seerr), stringResource(R.string.seliku_set_banner_seerr_desc), bannerSeerr) {
			bannerSeerr = !bannerSeerr; SelikuEinstellungen.bannerSeerr(context, bannerSeerr); gesetzt("banner_seerr"); vm.datenStand++
		},
		EinstellungsEintrag.Schalter("verlauf", umfang = "mein_verlauf", stringResource(R.string.seliku_set_history), stringResource(R.string.seliku_set_history_desc), verlauf) {
			verlauf = !verlauf; SelikuEinstellungen.meinVerlauf(context, verlauf); gesetzt("mein_verlauf"); vm.datenStand++
		},
		EinstellungsEintrag.Schalter("hoerenstart", umfang = "hoeren_start", stringResource(R.string.seliku_set_listen_home), stringResource(R.string.seliku_set_listen_home_desc), hoerenStart) {
			hoerenStart = !hoerenStart; SelikuEinstellungen.hoerenStart(context, hoerenStart); gesetzt("hoeren_start"); vm.datenStand++
		},
		EinstellungsEintrag.Schalter("wertung", umfang = "eigene_wertung", stringResource(R.string.seliku_set_own_rating), stringResource(R.string.seliku_set_own_rating_desc), wertung) {
			wertung = !wertung; SelikuEinstellungen.eigeneWertung(context, wertung); gesetzt("eigene_wertung")
		},
		EinstellungsEintrag.Schalter("musikfav", umfang = "musik_favoriten", stringResource(R.string.seliku_set_music_favorites), stringResource(R.string.seliku_set_music_favorites_desc), musikFav) {
			musikFav = !musikFav; SelikuEinstellungen.musikFavoriten(context, musikFav); gesetzt("musik_favoriten")
		},
		EinstellungsEintrag.Schalter("tomaten", umfang = "tomaten", stringResource(R.string.seliku_set_tomatoes), stringResource(R.string.seliku_set_tomatoes_desc), tomaten) {
			tomaten = !tomaten; SelikuEinstellungen.tomaten(context, tomaten); gesetzt("tomaten")
		},
		EinstellungsEintrag.Schalter("trailer", umfang = "trailer_banner", stringResource(R.string.seliku_set_trailer), stringResource(R.string.seliku_set_trailer_desc), trailerBanner) {
			trailerBanner = !trailerBanner; SelikuEinstellungen.trailerImBanner(context, trailerBanner); gesetzt("trailer_banner")
		},
		EinstellungsEintrag.Schalter("ausschnitt", umfang = "ausschnitt_ohne_trailer", stringResource(R.string.seliku_set_clip), stringResource(R.string.seliku_set_clip_desc), ausschnitt) {
			ausschnitt = !ausschnitt; SelikuEinstellungen.ausschnittOhneTrailer(context, ausschnitt); gesetzt("ausschnitt_ohne_trailer")
		},
		EinstellungsEintrag.Schalter("leiste", umfang = "leiste_mit_links", stringResource(R.string.seliku_set_sidebar_left), stringResource(R.string.seliku_set_sidebar_left_desc), links) {
			links = !links; SelikuEinstellungen.leisteMitLinks(context, links); gesetzt("leiste_mit_links")
		},
		EinstellungsEintrag.Schalter("zurueck", umfang = "doppel_zurueck", stringResource(R.string.seliku_set_double_back), stringResource(R.string.seliku_set_double_back_desc), doppel) {
			doppel = !doppel; SelikuEinstellungen.doppelZurueck(context, doppel); gesetzt("doppel_zurueck")
		},
		EinstellungsEintrag.Schalter("sendergruppen", umfang = "sender_gruppen", stringResource(R.string.seliku_set_channel_groups), stringResource(R.string.seliku_set_channel_groups_desc), senderGruppen) {
			senderGruppen = !senderGruppen; SelikuEinstellungen.senderGruppen(context, senderGruppen); gesetzt("sender_gruppen")
		},

		EinstellungsEintrag.Gruppe(stringResource(R.string.seliku_set_group_playback)),
		EinstellungsEintrag.Schalter("player", umfang = "schlanker_player", stringResource(R.string.seliku_set_slim_player), stringResource(R.string.seliku_set_slim_player_desc), schlank) {
			schlank = !schlank; SelikuEinstellungen.schlankerPlayer(context, schlank); gesetzt("schlanker_player")
		},
		EinstellungsEintrag.Auswahl("bitrate", umfang = "max_bitrate", stringResource(R.string.seliku_set_bitrate), stringResource(R.string.seliku_set_bitrate_desc), stringResource(R.string.seliku_set_bitrate_value, bitrate)) {
			bitrate = bitrateStufen[(bitrateStufen.indexOf(bitrate) + 1) % bitrateStufen.size]
			userPrefs[org.jellyfin.androidtv.preference.UserPreferences.maxBitrate] = bitrate; gesetzt("max_bitrate")
		},
		// Jellyfins «Nächstes automatisch abspielen» (pref_enable_tv_queuing): aus = nur die gewählte Folge kommt in die Warteschlange
		EinstellungsEintrag.Schalter("folge", umfang = "naechste_folge", stringResource(R.string.seliku_set_autoplay_next), stringResource(R.string.seliku_set_autoplay_next_desc), naechste) {
			naechste = !naechste; userPrefs[org.jellyfin.androidtv.preference.UserPreferences.mediaQueuingEnabled] = naechste; gesetzt("naechste_folge")
		},
		EinstellungsEintrag.Schalter("audioplayer", umfang = "audio_player", stringResource(R.string.seliku_set_audio_player), stringResource(R.string.seliku_set_audio_player_desc), audioPlayer) {
			audioPlayer = !audioPlayer; SelikuEinstellungen.neuerAudioPlayer(context, audioPlayer); gesetzt("audio_player")
		},
		EinstellungsEintrag.Schalter("endlos", umfang = "endlos_musik", stringResource(R.string.seliku_set_endless), stringResource(R.string.seliku_set_endless_desc), endlos) {
			endlos = !endlos; SelikuEinstellungen.endlosMusik(context, endlos); gesetzt("endlos_musik")
		},
		EinstellungsEintrag.Schalter("wach", umfang = "nur_bei_wiedergabe_wach", stringResource(R.string.seliku_set_keep_awake), stringResource(R.string.seliku_set_keep_awake_desc), wach) {
			wach = !wach; SelikuEinstellungen.nurBeiWiedergabeWach(context, wach); gesetzt("nur_bei_wiedergabe_wach")
		},
		EinstellungsEintrag.Schalter("untertitel", umfang = "untertitel_nachladen", stringResource(R.string.seliku_set_subtitles_later), stringResource(R.string.seliku_set_subtitles_later_desc), nachladen) {
			nachladen = !nachladen; SelikuEinstellungen.untertitelNachladen(context, nachladen); gesetzt("untertitel_nachladen")
		},

		EinstellungsEintrag.Gruppe(stringResource(R.string.seliku_set_group_series)),
		EinstellungsEintrag.Schalter("titelmusik", umfang = "titelmusik", stringResource(R.string.seliku_set_theme_music), stringResource(R.string.seliku_set_theme_music_desc), musik) {
			musik = !musik; SelikuEinstellungen.titelmusik(context, musik); gesetzt("titelmusik")
		},
		EinstellungsEintrag.Schalter("zufall", umfang = "zufall_knopf", stringResource(R.string.seliku_set_shuffle_button), stringResource(R.string.seliku_set_shuffle_button_desc), zufall) {
			zufall = !zufall; SelikuEinstellungen.zufallKnopf(context, zufall); gesetzt("zufall_knopf")
		},

		EinstellungsEintrag.Gruppe(stringResource(R.string.seliku_set_group_content)),
		EinstellungsEintrag.Verweis("bibliotheken", stringResource(R.string.seliku_libraries), stringResource(R.string.seliku_set_libraries_desc)) { vm.zu(Seite.Bibliotheken) },
		EinstellungsEintrag.Verweis("seerr", "Seerr", stringResource(R.string.seliku_set_seerr_desc), seerr) {
			if (SeerrVerbindung.vermittelt.value) vm.wurzel(Seite.Entdecken) else vm.zu(Seite.SeerrVerbinden)
		},

		EinstellungsEintrag.Gruppe(stringResource(R.string.seliku_set_group_more)),
		EinstellungsEintrag.Verweis("jellyfin", stringResource(R.string.seliku_set_jellyfin), stringResource(R.string.seliku_set_jellyfin_desc)) { jellyfinEinstellungen() },
	)

	Column(Modifier.fillMaxSize().background(Farben.Hintergrund)) {
		Text(stringResource(R.string.seliku_settings), style = Schrift.Titel, modifier = Modifier.padding(start = 48.dp, top = 28.dp))
		Text(stringResource(R.string.seliku_set_scope_hint), style = Schrift.Leise, modifier = Modifier.padding(start = 48.dp, top = 4.dp, bottom = 8.dp))
		LazyColumn(state = vm.liste(Seite.Einstellungen), contentPadding = PaddingValues(start = 40.dp, end = 48.dp, bottom = 40.dp)) {
			items(eintraege, key = { it.id }) { e ->
				when (e) {
					is EinstellungsEintrag.Gruppe -> Text(e.titel, style = Schrift.Reihe.copy(color = Farben.Akzent),
						modifier = Modifier.padding(start = 8.dp, top = 22.dp, bottom = 6.dp))
					else -> Waehlbar(onClick = e.aktion, vergroessern = false, modifier = Modifier.padding(vertical = 3.dp),
						merken = vm to (Seite.Einstellungen.schluessel to e.id),
						// Menütaste bzw. OK lang: für alle Geräte ↔ nur dieses Gerät; wird abgeglichen, gleich den Wert hochladen
						onMenue = e.umfang?.let { k -> fun() {
							if (SelikuUmfang.umschalten(context, k)) SelikuSync.speichern(context)
							umfangStand++
						} }) { fokussiert ->
						Row(
							Modifier.fillMaxWidth().background(if (fokussiert) Farben.FlaecheHell else Farben.Flaeche).padding(horizontal = 20.dp, vertical = 12.dp),
							verticalAlignment = Alignment.CenterVertically,
						) {
							Column(Modifier.weight(1f)) {
								Text(e.titel, style = Schrift.Normal.copy(fontWeight = FontWeight.Bold))
								Text(e.text, style = Schrift.Klein, maxLines = 1, overflow = TextOverflow.Ellipsis)
							}
							Spacer(Modifier.width(16.dp))
							e.umfang?.let { k ->
								val alle = remember(umfangStand, k) { SelikuUmfang.alle(context, k) }
								Text(if (alle) "☁" else "📺", style = Schrift.Normal.copy(color = Farben.TextLeise))
								Spacer(Modifier.width(14.dp))
							}
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
				Text(stringResource(R.string.seliku_version, org.jellyfin.androidtv.BuildConfig.VERSION_NAME), style = Schrift.Klein,
					modifier = Modifier.padding(start = 8.dp, top = 24.dp))
			}
		}
	}
}

/** [umfang]: Schlüssel für den Abgleich (☁/📺, siehe [SelikuSync]); null = nicht abgleichbar. */
private sealed class EinstellungsEintrag(val id: String, val titel: String, val text: String, val aktion: () -> Unit, val umfang: String? = null) {
	class Gruppe(titel: String) : EinstellungsEintrag("gruppe:$titel", titel, "", {})
	class Schalter(id: String, umfang: String?, titel: String, text: String, val an: Boolean, aktion: () -> Unit) : EinstellungsEintrag(id, titel, text, aktion, umfang)
	class Auswahl(id: String, umfang: String?, titel: String, text: String, val wert: String, aktion: () -> Unit) : EinstellungsEintrag(id, titel, text, aktion, umfang)
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
