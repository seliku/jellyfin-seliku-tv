package org.jellyfin.androidtv.seliku

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.lazy.LazyRow
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.koin.compose.koinInject
import timber.log.Timber
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import androidx.compose.ui.res.stringResource
import org.jellyfin.androidtv.R

/** Suche: Eingabefeld oben, Treffer nach Art in Reihen. */
@Composable
fun SuchSeite(vm: SelikuViewModel, oeffnen: (BaseItemDto) -> Unit) {
	val daten = koinInject<SelikuDaten>()
	val abspielen = koinInject<Abspielen>()
	val context = LocalContext.current
	val seite = Seite.Suche
	var text by remember { mutableStateOf(vm.wahl["suche|text"] as? String ?: "") }
	@Suppress("UNCHECKED_CAST")
	var treffer by remember { mutableStateOf(vm.wahl["suche|treffer"] as? List<BaseItemDto> ?: emptyList()) }
	var sucht by remember { mutableStateOf(false) }
	var feldFokus by remember { mutableStateOf(false) }
	val feld = remember { FocusRequester() }

	LaunchedEffect(Unit) { if (text.isEmpty()) runCatching { feld.requestFocus() } }
	// Seerr verbunden: die Suche findet auch, was noch fehlt (gemischt, mit derselben Markierung wie im Katalog)
	val seerr = koinInject<SeerrDaten>()
	val mitSeerr = remember { SeerrVerbindung.verbunden(context) }
	@Suppress("UNCHECKED_CAST")
	var seerrTreffer by remember { mutableStateOf(vm.wahl["suche|seerr"] as? List<SeerrTitel> ?: emptyList()) }
	LaunchedEffect(text) {
		if (!mitSeerr || text.trim().length < 2) { seerrTreffer = emptyList(); return@LaunchedEffect }
		delay(450)
		seerrTreffer = runCatching { seerr.suche(text.trim()) }.onFailure { Timber.w(it, "Seerr-Suche") }.getOrDefault(emptyList())
		vm.wahl["suche|seerr"] = seerrTreffer
	}
	LaunchedEffect(text) {
		vm.wahl["suche|text"] = text
		if (text.trim().length < 2) { treffer = emptyList(); return@LaunchedEffect }
		delay(450)
		sucht = true
		treffer = runCatching { daten.suche(text.trim()) }.onFailure { Timber.w(it, "Suche") }.getOrDefault(emptyList())
		vm.wahl["suche|treffer"] = treffer
		sucht = false
	}

	val gruppen = listOf(
		stringResource(R.string.seliku_movies) to setOf(BaseItemKind.MOVIE),
		stringResource(R.string.seliku_series_plural) to setOf(BaseItemKind.SERIES),
		stringResource(R.string.seliku_episodes) to setOf(BaseItemKind.EPISODE),
		stringResource(R.string.seliku_channels) to setOf(BaseItemKind.TV_CHANNEL),
		stringResource(R.string.seliku_music_artists) to setOf(BaseItemKind.MUSIC_ARTIST),
		stringResource(R.string.seliku_music_albums) to setOf(BaseItemKind.MUSIC_ALBUM),
		stringResource(R.string.seliku_tracks) to setOf(BaseItemKind.AUDIO),
	)

	LazyColumn(state = vm.liste(seite), contentPadding = PaddingValues(bottom = 40.dp), modifier = Modifier.fillMaxSize().background(Farben.Hintergrund)) {
		item {
			Column(Modifier.padding(start = 48.dp, top = 32.dp, end = 48.dp, bottom = 20.dp)) {
				Text(stringResource(R.string.seliku_search), style = Schrift.Titel)
				Spacer(Modifier.height(14.dp))
				Box(
					Modifier.width(620.dp)
						.border(2.dp, if (feldFokus) Farben.Akzent else Farben.FlaecheHell, RoundedCornerShape(10.dp))
						.background(Farben.Flaeche, RoundedCornerShape(10.dp))
						.padding(horizontal = 18.dp, vertical = 14.dp)
				) {
					if (text.isEmpty()) Text(stringResource(R.string.seliku_search_hint), style = Schrift.Leise)
					BasicTextField(
						value = text,
						onValueChange = { text = it },
						singleLine = true,
						textStyle = Schrift.Normal,
						cursorBrush = SolidColor(Farben.Akzent),
						keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
						keyboardActions = KeyboardActions(onSearch = { }),
						modifier = Modifier.fillMaxWidth().focusRequester(feld).onFocusChanged { feldFokus = it.isFocused },
					)
				}
				if (sucht) { Spacer(Modifier.height(8.dp)); Text(stringResource(R.string.seliku_searching), style = Schrift.Leise) }
				else if (text.trim().length >= 2 && treffer.isEmpty() && seerrTreffer.isEmpty()) { Spacer(Modifier.height(8.dp)); Text(stringResource(R.string.seliku_nothing_found), style = Schrift.Leise) }
			}
		}
		// Was schon da ist, steht oben unter Filme/Serien; hier nur, was fehlt oder unterwegs ist
		val fehlend = seerrTreffer.filter { it.zustand != SeerrZustand.ANSEHEN }
		gruppen.take(2).forEach { (titel, typen) ->
			val liste = treffer.filter { it.type in typen }
			if (liste.isNotEmpty()) item(key = titel) {
				Reihe(titel, liste, vm, seite, onClick = { item -> oeffnen(item) })
			}
		}
		if (fehlend.isNotEmpty()) item(key = "seerr") { SeerrReihe(stringResource(R.string.seliku_seerr_missing_request), fehlend, vm, seite) }
		gruppen.drop(2).forEach { (titel, typen) ->
			val liste = treffer.filter { it.type in typen }
			if (liste.isNotEmpty()) item(key = titel) {
				Reihe(titel, liste, vm, seite, onClick = { item ->
					when (item.type) {
						BaseItemKind.EPISODE -> abspielen.video(context, item)
						BaseItemKind.TV_CHANNEL -> abspielen.sender(context, item)
						BaseItemKind.AUDIO -> abspielen.musik(context, listOf(item))
						else -> oeffnen(item)
					}
				})
			}
		}
	}
}

private val UHR = DateTimeFormatter.ofPattern("HH:mm")

/** Senderblock einer Kanalnummer: 1–99 → 0, 100–199 → 1 …; ohne Nummer ganz hinten. */
fun senderBlock(nummer: String?): Int = nummer?.toDoubleOrNull()?.let { (it / 100).toInt() } ?: Int.MAX_VALUE
fun BaseItemDto.senderBlock() = senderBlock(channelNumber ?: number)

/**
 * Live-TV: Senderliste mit laufender Sendung und Fortschritt; Klick schaltet um. Oben der Weg zum Programmführer.
 * Gibt es mehrere Senderblöcke (z. B. je Sprache), stehen darunter Reiter: «Alle» zeigt die Blöcke mit Überschrift,
 * ein Reiter nur seinen Block. Die Wahl bleibt auf dem Gerät; Namen der Blöcke kommen aus dem Konto (sonst «Sender 100–199»).
 */
@Composable
fun LiveTvSeite(vm: SelikuViewModel) {
	val daten = koinInject<SelikuDaten>()
	val abspielen = koinInject<Abspielen>()
	val context = LocalContext.current
	val seite = Seite.LiveTv
	val scope = rememberCoroutineScope()
	@Suppress("UNCHECKED_CAST")
	var sender by remember { mutableStateOf(vm.wahl["livetv|sender"] as? List<BaseItemDto>) }
	@Suppress("UNCHECKED_CAST")
	var namen by remember { mutableStateOf(vm.wahl["livetv|gruppen"] as? Map<Int, String> ?: emptyMap()) }
	val gruppenAn = remember { SelikuEinstellungen.senderGruppen(context) }
	var block by remember { mutableStateOf(SelikuEinstellungen.senderBlock(context)) }

	LaunchedEffect(Unit) {
		if (gruppenAn) runCatching { daten.senderGruppenNamen() }.onSuccess { namen = it; vm.wahl["livetv|gruppen"] = it }
		while (true) {
			runCatching { daten.sender() }.onSuccess { sender = it; vm.wahl["livetv|sender"] = it }.onFailure { Timber.w(it, "Sender laden") }
			delay(60_000)
		}
	}

	val s = sender
	val bloecke = if (gruppenAn && s != null) s.map { it.senderBlock() }.distinct().sorted() else emptyList()
	val mitReitern = bloecke.size > 1
	val gewaehlt = if (mitReitern && block in bloecke) block else -1
	val name = { b: Int ->
		namen[b] ?: if (b == Int.MAX_VALUE) context.getString(R.string.seliku_channel_block_other)
		else context.getString(R.string.seliku_channel_block, maxOf(1, b * 100), b * 100 + 99)
	}
	val waehlen = { b: Int ->
		block = b; SelikuEinstellungen.senderBlock(context, b)
		scope.launch { runCatching { vm.liste(seite).scrollToItem(0) } }
	}

	// Zurück aus der Senderliste springt erst hier hinauf (wie bei Filmen und Serien): auf den gewählten Senderblock,
	// ohne Blöcke auf den Programmführer; erst das nächste Zurück öffnet die Leiste
	val reiter = vm.reiter(seite)
	val anReiter = { aktiv: Boolean -> if (aktiv) Modifier.focusRequester(reiter) else Modifier }
	Column(Modifier.fillMaxSize().background(Farben.Hintergrund)) {
		Column(Modifier.onFocusChanged { vm.reiterFokus = it.hasFocus }) {
			Row(Modifier.padding(start = 48.dp, top = 28.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
				Text(stringResource(R.string.seliku_live_tv), style = Schrift.Titel)
				Spacer(Modifier.width(12.dp))
				Knopf(stringResource(R.string.seliku_guide), symbol = "▦", onClick = { abspielen.programmfuehrer() }, modifier = anReiter(!mitReitern))
				Knopf(stringResource(R.string.seliku_recordings), symbol = "●", onClick = { abspielen.aufnahmen() })
			}
			if (mitReitern) LazyRow(
				contentPadding = PaddingValues(start = 48.dp, end = 48.dp, bottom = 10.dp),
				horizontalArrangement = Arrangement.spacedBy(10.dp),
			) {
				item { Knopf(stringResource(R.string.seliku_all), onClick = { waehlen(-1) }, aktiv = gewaehlt == -1, modifier = anReiter(gewaehlt == -1)) }
				items(bloecke) { b -> Knopf(name(b), onClick = { waehlen(b) }, aktiv = gewaehlt == b, modifier = anReiter(gewaehlt == b)) }
			}
		}
		if (s == null) { Hinweis(stringResource(R.string.seliku_loading)); return@Column }
		LazyColumn(state = vm.liste(seite), contentPadding = PaddingValues(start = 40.dp, end = 48.dp, bottom = 40.dp), modifier = Modifier.fillMaxSize()) {
			if (mitReitern && gewaehlt == -1) {
				bloecke.forEachIndexed { index, b ->
					val imBlock = s.filter { it.senderBlock() == b }
					item(key = "kopf$b") {
						Row(
							Modifier.padding(start = 8.dp, top = if (index == 0) 4.dp else 24.dp, bottom = 8.dp),
							verticalAlignment = Alignment.CenterVertically,
							horizontalArrangement = Arrangement.spacedBy(12.dp),
						) {
							Text(name(b), style = Schrift.Reihe)
							Text(pluralStringResource(R.plurals.seliku_channel_count, imBlock.size, imBlock.size), style = Schrift.Klein)
						}
					}
					items(imBlock, key = { it.id }) { k -> SenderZeile(vm, k) }
				}
			} else {
				items(if (gewaehlt == -1) s else s.filter { it.senderBlock() == gewaehlt }, key = { it.id }) { k -> SenderZeile(vm, k) }
			}
		}
	}
}

/** Eine Zeile der Senderliste: Logo, Bild der Sendung, Name und Nummer, laufende Sendung mit Fortschritt. */
@Composable
private fun SenderZeile(vm: SelikuViewModel, k: BaseItemDto) {
	val bilder = koinInject<Bilder>()
	val abspielen = koinInject<Abspielen>()
	val context = LocalContext.current
	val p = k.currentProgram
	Waehlbar(onClick = { abspielen.sender(context, k) }, vergroessern = false, merken = vm to (Seite.LiveTv.schluessel to k.id.toString()),
		modifier = Modifier.padding(vertical = 3.dp)) { fokussiert ->
		Row(
			Modifier.fillMaxWidth().background(if (fokussiert) Farben.FlaecheHell else Farben.Flaeche).padding(horizontal = 16.dp, vertical = 10.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(18.dp),
		) {
			Bild(bilder.eigenesBild(k, 200), Modifier.width(96.dp).aspectRatio(16f / 9f), ContentScale.Fit, grund = androidx.compose.ui.graphics.Color.Transparent)
			Bild(bilder.sendung(k, 300), Modifier.width(120.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(6.dp)))
			Column(Modifier.width(200.dp)) {
				Text(k.name.orEmpty(), style = Schrift.Normal.copy(fontWeight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
				k.channelNumber?.let { Text(stringResource(R.string.seliku_channel_number, it), style = Schrift.Klein) }
			}
			Column(Modifier.weight(1f)) {
				Text(p?.name ?: stringResource(R.string.seliku_no_program), style = Schrift.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
				if (p?.startDate != null && p.endDate != null) {
					val jetzt = LocalDateTime.now()
					val gesamt = java.time.Duration.between(p.startDate, p.endDate).seconds.coerceAtLeast(1)
					val vorbei = java.time.Duration.between(p.startDate, jetzt).seconds
					Spacer(Modifier.height(4.dp))
					Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
						Text("${p.startDate!!.format(UHR)}–${p.endDate!!.format(UHR)}", style = Schrift.Klein)
						Fortschritt(vorbei.toFloat() / gesamt, Modifier.width(240.dp))
					}
				}
			}
		}
	}
}
