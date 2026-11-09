package org.jellyfin.androidtv.seliku

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.koin.compose.koinInject
import timber.log.Timber

/** Quadratische Kachel für Hörbücher und Folgen: Balken, wie weit man ist, darunter «Kapitel 3 von 20 · noch 4 Std.». */
@Composable
fun HoerKachel(
	item: BaseItemDto,
	onClick: () -> Unit,
	merken: Pair<SelikuViewModel, Pair<String, String>>? = null,
	onFokus: () -> Unit = {},
	onMenue: (() -> Unit)? = null,
) {
	val bilder = koinInject<Bilder>()
	val f = Hoeren.fortschritt(item.id)
	Column(Modifier.width(150.dp)) {
		Waehlbar(onClick = onClick, merken = merken, onFokus = onFokus, onMenue = onMenue) {
			Box {
				Bild(bilder.poster(item, 300), Modifier.fillMaxWidth().aspectRatio(1f), ersatz = "◉")
				// Angefangenes: Balken statt Haken (Jellyfin hakt Folgen schon beim Start ab)
				if (f == null) Abzeichen(item, Modifier.align(Alignment.TopEnd))
				if (f != null) Fortschritt(f.anteil, Modifier.align(Alignment.BottomStart))
			}
		}
		Spacer(Modifier.height(6.dp))
		Text(item.name.orEmpty(), style = Schrift.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
		val unter = when {
			f != null && f.kapitelVon > 0 -> stringResource(R.string.seliku_audio_left, dauerText(f.restMs))
			f != null -> stringResource(R.string.seliku_audio_left, dauerText(f.restMs))
			item.type == BaseItemKind.AUDIO -> listOfNotNull(item.album, item.premiereDate?.let { datumKurz(it.toLocalDate()) }).joinToString(" · ")
			else -> ""
		}
		Text(unter, style = Schrift.Klein, maxLines = 1, overflow = TextOverflow.Ellipsis)
	}
}

/** Zeile in einer Kapitel- oder Folgenliste: links Nummer bzw. Datum, Haken für Gehörtes, Balken für Angefangenes. */
@Composable
fun HoerZeile(
	links: String,
	linksBreite: androidx.compose.ui.unit.Dp,
	titel: String,
	rechts: String,
	gehoert: Boolean,
	aktuell: Boolean,
	anteil: Float?,
	onClick: () -> Unit,
	onMenue: (() -> Unit)? = null,
	merken: Pair<SelikuViewModel, Pair<String, String>>? = null,
) {
	Waehlbar(onClick = onClick, onMenue = onMenue, vergroessern = false, merken = merken, modifier = Modifier.padding(vertical = 2.dp)) { fokussiert ->
		Column(Modifier.fillMaxWidth().background(if (fokussiert) Farben.FlaecheHell else Farben.Flaeche.copy(alpha = 0.5f))) {
			Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
				Text(links, style = Schrift.Leise, modifier = Modifier.width(linksBreite), maxLines = 1)
				Text(titel, style = Schrift.Normal.copy(
					fontWeight = if (fokussiert || aktuell) FontWeight.Bold else FontWeight.Normal,
					color = if (gehoert && !fokussiert) Farben.TextLeise else if (aktuell) Farben.Akzent else Farben.Text,
				), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
				Spacer(Modifier.width(12.dp))
				Text(rechts, style = Schrift.Leise, maxLines = 1)
				Text(if (gehoert) "  ✓" else if (aktuell) "  ▶" else "", style = Schrift.Normal.copy(color = Farben.Akzent, fontWeight = FontWeight.Bold))
			}
			if (anteil != null && !gehoert) Fortschritt(anteil)
		}
	}
}

/** Ein Hörbuch: Cover, Fortschritt, «Weiterhören», «Von vorn», «Als gehört markieren» und die Kapitel. */
@Composable
fun BuchSeite(vm: SelikuViewModel, seite: Seite.Ordner) {
	val bilder = koinInject<Bilder>()
	val abspielen = koinInject<Abspielen>()
	val context = LocalContext.current
	val scope = rememberCoroutineScope()
	val version by Hoeren.version.collectAsState()
	var buch by remember { mutableStateOf<Buch?>(null) }
	var stand by remember { mutableStateOf<HoerStand?>(null) }
	var fertig by remember { mutableStateOf(false) }
	val start = remember { FocusRequester() }
	LaunchedEffect(seite.id, version) {
		buch = Hoeren.buch(seite.id)
		stand = Hoeren.buchStand(seite.id)
		fertig = Hoeren.buchFertig(seite.id)
	}
	LaunchedEffect(buch != null) { if (buch != null && vm.fokus[seite.schluessel] == null) runCatching { start.requestFocus() } }
	val b = buch
	if (b == null) { Hinweis(stringResource(R.string.seliku_loading)); return }
	val nr = stand?.datei?.let { b.index(it) }?.takeIf { it >= 0 }
	val spielen = { ab: Int? -> scope.launch { if (Hoeren.buchSpielen(b.id, ab)) abspielen.jetztLaeuft() }; Unit }
	Row(Modifier.fillMaxSize().background(Farben.Hintergrund).padding(start = 48.dp, top = 40.dp, end = 48.dp), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
		Column(Modifier.width(300.dp)) {
			Bild(bilder.eigenesBild(b.ordner, 500), Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(8.dp)), ersatz = "◉")
			Spacer(Modifier.height(16.dp))
			Text(b.ordner.name.orEmpty(), style = Schrift.Titel, maxLines = 2, overflow = TextOverflow.Ellipsis)
			Spacer(Modifier.height(4.dp))
			Text(listOf(stringResource(R.string.seliku_audio_chapter_count, b.dateien.size), dauerText(b.gesamtMs)).joinToString("  ·  "), style = Schrift.Leise)
			Spacer(Modifier.height(12.dp))
			if (nr != null) {
				val gesamt = b.gesamtMs.coerceAtLeast(1)
				val bis = b.bisMs(nr, stand!!.ms)
				Fortschritt(bis.toFloat() / gesamt, Modifier.clip(RoundedCornerShape(2.dp)))
				Text(stringResource(R.string.seliku_audio_chapter_of, nr + 1, b.dateien.size) + " · " + stringResource(R.string.seliku_audio_left, dauerText(gesamt - bis)),
					style = Schrift.Klein.copy(color = Farben.Akzent), modifier = Modifier.padding(top = 4.dp))
			} else if (fertig) Text("✓ " + stringResource(R.string.seliku_audio_heard), style = Schrift.Klein.copy(color = Farben.Akzent))
			Spacer(Modifier.height(16.dp))
			Knopf(stringResource(if (nr != null) R.string.seliku_audio_continue else R.string.seliku_play), symbol = "▶", hervorgehoben = true, breit = true,
				modifier = Modifier.focusRequester(start), onClick = { spielen(null) })
			Spacer(Modifier.height(10.dp))
			Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
				if (nr != null || fertig) Knopf(stringResource(R.string.seliku_audio_from_start), symbol = "↺", onClick = { spielen(0) })
				Knopf(stringResource(if (fertig) R.string.seliku_audio_mark_unheard else R.string.seliku_audio_mark_book_heard), symbol = if (fertig) "○" else "✓", onClick = {
					scope.launch { runCatching { Hoeren.buchGehoert(b.id, !fertig) }.onFailure { Timber.w(it, "Buch markieren") } }
				})
			}
		}
		LazyColumn(state = vm.liste(seite), contentPadding = PaddingValues(bottom = 48.dp), modifier = Modifier.weight(1f)) {
			itemsIndexed(b.dateien, key = { _, d -> d.id }) { i, d ->
				HoerZeile(
					links = (i + 1).toString(),
					linksBreite = 52.dp,
					titel = d.name.orEmpty(),
					rechts = laufzeitKurz(d.runTimeTicks),
					gehoert = fertig || (nr != null && i < nr),
					aktuell = i == nr,
					anteil = if (i == nr) stand!!.ms.toFloat() / dauerMs(d).coerceAtLeast(1) else null,
					onClick = { spielen(i) },
					merken = vm to (seite.schluessel to d.id.toString()),
				)
			}
		}
	}
}

/** Podcast-Bibliothek: Weiterhören, neue Folgen, alle Podcasts. */
@Composable
fun PodcastSeite(vm: SelikuViewModel, seite: Seite.Musik, oeffnen: (BaseItemDto) -> Unit, nebenan: List<BaseItemDto>) {
	val daten = koinInject<SelikuDaten>()
	val abspielen = koinInject<Abspielen>()
	val context = LocalContext.current
	val version by Hoeren.version.collectAsState()
	val k = "${seite.schluessel}|podcast"
	@Suppress("UNCHECKED_CAST")
	var weiter by remember { mutableStateOf(vm.wahl["$k|weiter"] as? List<BaseItemDto> ?: emptyList()) }
	@Suppress("UNCHECKED_CAST")
	var neu by remember { mutableStateOf(vm.wahl["$k|neu"] as? List<BaseItemDto> ?: emptyList()) }
	@Suppress("UNCHECKED_CAST")
	var alle by remember { mutableStateOf(vm.wahl["$k|alle"] as? List<BaseItemDto>) }
	LaunchedEffect(vm.datenStand, version) {
		Hoeren.podcastsLaden()
		runCatching { Hoeren.weiterhoeren().filter { it.type == BaseItemKind.AUDIO && it.albumId?.let(Hoeren::istPodcastAlbum) == true } }
			.onSuccess { weiter = it; vm.wahl["$k|weiter"] = it }
		runCatching { Hoeren.neueFolgen(seite.ansicht.id) }.onSuccess { neu = it; vm.wahl["$k|neu"] = it }
		runCatching { daten.alben(seite.ansicht, null) }.onSuccess { alle = it; vm.wahl["$k|alle"] = it }.onFailure { Timber.w(it, "Podcasts laden") }
	}
	Column(Modifier.fillMaxSize()) {
		Row(Modifier.padding(start = 48.dp, end = 48.dp, top = 28.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
			Text(seite.ansicht.name.orEmpty(), style = Schrift.Titel)
			Spacer(Modifier.width(12.dp))
			LaeuftKnopf()
			nebenan.filter { it.id != seite.ansicht.id }.forEach { bib -> Knopf(bib.name.orEmpty(), onClick = { vm.zu(seiteFuerBibliothek(bib)) }) }
		}
		val a = alle
		if (a == null) { Hinweis(stringResource(R.string.seliku_loading)); return@Column }
		val unter = Seite.Ordner(seite.ansicht.id, "podcasts")
		SenkrechtRollen {
			LazyColumn(state = vm.liste(unter), contentPadding = PaddingValues(top = 8.dp, bottom = 48.dp), modifier = Modifier.fillMaxSize()) {
				if (weiter.isNotEmpty()) item(key = "weiter") {
					Reihe(stringResource(R.string.seliku_audio_continue), weiter, vm, unter, onClick = { abspielen.hoeren(context, it) },
						kachel = { item, klick, merken, fokus -> HoerKachel(item, klick, merken, fokus) })
				}
				if (neu.isNotEmpty()) item(key = "neu") {
					Reihe(stringResource(R.string.seliku_audio_new_episodes), neu, vm, unter, onClick = { abspielen.hoeren(context, it) },
						kachel = { item, klick, merken, fokus -> HoerKachel(item, klick, merken, fokus) })
				}
				item(key = "alle") {
					Reihe(stringResource(R.string.seliku_audio_all_podcasts), a, vm, unter, onClick = oeffnen,
						kachel = { item, klick, merken, fokus -> QuadratKachel(item, klick, merken = merken, onFokus = fokus) })
				}
			}
		}
	}
}

/** Ein Podcast: Folgen neueste zuerst, mit Datum, Haken für Gehörtes und Balken für Angefangenes. Langer Druck: gehört/ungehört. */
@Composable
fun PodcastAlbumSeite(vm: SelikuViewModel, seite: Seite.Album) {
	val daten = koinInject<SelikuDaten>()
	val bilder = koinInject<Bilder>()
	val abspielen = koinInject<Abspielen>()
	val context = LocalContext.current
	val scope = rememberCoroutineScope()
	val version by Hoeren.version.collectAsState()
	var album by remember { mutableStateOf<BaseItemDto?>(null) }
	var folgen by remember { mutableStateOf<List<BaseItemDto>>(emptyList()) }
	var staende by remember { mutableStateOf<Map<java.util.UUID, HoerStand>>(emptyMap()) }
	val start = remember { FocusRequester() }
	LaunchedEffect(seite.id, version) {
		runCatching {
			album = daten.item(seite.id)
			folgen = Hoeren.podcastFolgen(seite.id)
			staende = folgen.mapNotNull { f -> Hoeren.folgeStand(f.id)?.let { f.id to it } }.toMap()
		}.onFailure { Timber.w(it, "Podcast laden") }
	}
	LaunchedEffect(album != null) { if (album != null && vm.fokus[seite.schluessel] == null) runCatching { start.requestFocus() } }
	val a = album
	if (a == null) { Hinweis(stringResource(R.string.seliku_loading)); return }
	val ungehoert = folgen.count { it.id in staende || it.userData?.played != true }
	// Weiterhören: zuletzt angefangene Folge; sonst die neueste ungehörte
	val angefangen = folgen.filter { it.id in staende }.maxByOrNull { staende[it.id]!!.zeit }
	val naechste = angefangen ?: folgen.firstOrNull { it.userData?.played != true }
	Row(Modifier.fillMaxSize().background(Farben.Hintergrund).padding(start = 48.dp, top = 40.dp, end = 48.dp), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
		Column(Modifier.width(300.dp)) {
			Bild(bilder.eigenesBild(a, 500), Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(8.dp)), ersatz = "◉")
			Spacer(Modifier.height(16.dp))
			Text(a.name.orEmpty(), style = Schrift.Titel, maxLines = 2, overflow = TextOverflow.Ellipsis)
			Spacer(Modifier.height(4.dp))
			Text(listOf(
				stringResource(R.string.seliku_audio_episode_count, folgen.size),
				stringResource(R.string.seliku_audio_new_count, ungehoert),
			).joinToString("  ·  "), style = Schrift.Leise)
			Spacer(Modifier.height(16.dp))
			if (naechste != null) Knopf(
				if (angefangen != null) stringResource(R.string.seliku_audio_continue) else stringResource(R.string.seliku_audio_play_newest),
				symbol = "▶", hervorgehoben = true, breit = true, modifier = Modifier.focusRequester(start),
				onClick = { scope.launch { Hoeren.folgeSpielen(naechste, folgen); abspielen.jetztLaeuft() } })
		}
		LazyColumn(state = vm.liste(seite), contentPadding = PaddingValues(bottom = 48.dp), modifier = Modifier.weight(1f)) {
			itemsIndexed(folgen, key = { _, f -> f.id }) { _, f ->
				val s = staende[f.id]
				val gehoert = f.userData?.played == true && s == null
				HoerZeile(
					links = f.premiereDate?.let { datumKurz(it.toLocalDate()) } ?: "",
					linksBreite = 120.dp,
					titel = f.name.orEmpty(),
					rechts = if (s != null && !gehoert) stringResource(R.string.seliku_audio_left, dauerText(dauerMs(f) - s.ms)) else laufzeit(f.runTimeTicks).orEmpty(),
					gehoert = gehoert,
					aktuell = false,
					anteil = s?.let { it.ms.toFloat() / dauerMs(f).coerceAtLeast(1) },
					onClick = { scope.launch { Hoeren.folgeSpielen(f, folgen); abspielen.jetztLaeuft() } },
					onMenue = { scope.launch { Hoeren.folgeGehoert(f.id, !gehoert) } },
					merken = vm to (seite.schluessel to f.id.toString()),
				)
			}
		}
	}
}
