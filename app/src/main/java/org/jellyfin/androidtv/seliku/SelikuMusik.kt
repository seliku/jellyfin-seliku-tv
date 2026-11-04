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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.ItemFilter
import org.jellyfin.sdk.model.api.BaseItemKind
import org.koin.compose.koinInject
import timber.log.Timber

/** Musikbibliothek: Reiter «Interpreten» und «Alben». */
@Composable
fun MusikSeite(vm: SelikuViewModel, seite: Seite.Musik, oeffnen: (BaseItemDto) -> Unit) {
	val daten = koinInject<SelikuDaten>()
	// Reiter: 0 Playlists (mit automatischen Mixen), 1 Interpreten, 2 Alben
	var reiter by remember { mutableStateOf(vm.wahl["${seite.schluessel}|reiter"] as? Int ?: 0) }
	@Suppress("UNCHECKED_CAST")
	var liste by remember(reiter) { mutableStateOf(vm.wahl["${seite.schluessel}|liste|$reiter"] as? List<BaseItemDto>) }

	LaunchedEffect(reiter) {
		vm.wahl["${seite.schluessel}|reiter"] = reiter
		if (reiter == 0) return@LaunchedEffect
		val neu = runCatching { if (reiter == 1) daten.interpreten(seite.ansicht) else daten.alben(seite.ansicht, null) }
			.onFailure { Timber.w(it, "Musik laden") }.getOrNull()
		if (neu != null) { liste = neu; vm.wahl["${seite.schluessel}|liste|$reiter"] = neu }
		if (neu != null && neu.isEmpty() && reiter == 1) reiter = 2
	}

	Column(Modifier.fillMaxSize()) {
		Row(Modifier.padding(start = 48.dp, top = 28.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
			Text(seite.ansicht.name.orEmpty(), style = Schrift.Titel)
			Spacer(Modifier.width(12.dp))
			Knopf("Playlists", onClick = { reiter = 0 }, aktiv = reiter == 0)
			Knopf("Interpreten", onClick = { reiter = 1 }, aktiv = reiter == 1)
			Knopf("Alben", onClick = { reiter = 2 }, aktiv = reiter == 2)
		}
		if (reiter == 0) { PlaylistsReiter(vm, seite); return@Column }
		val l = liste
		if (l == null) { Hinweis("Wird geladen …"); return@Column }
		val schluessel = Seite.Ordner(seite.ansicht.id, "musik-$reiter")
		LazyVerticalGrid(
			state = vm.raster(schluessel),
			columns = GridCells.Adaptive(150.dp),
			contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 12.dp, bottom = 40.dp),
			horizontalArrangement = Arrangement.spacedBy(22.dp),
			verticalArrangement = Arrangement.spacedBy(22.dp),
			modifier = Modifier.fillMaxSize(),
		) {
			items(l, key = { it.id }) { item ->
				QuadratKachel(item, onClick = { oeffnen(item) }, rund = reiter == 1, merken = vm to (schluessel.schluessel to item.id.toString()))
			}
		}
	}
}

/** Ein automatischer Mix: Name, Untertitel und wie er seine Titel holt. */
private class Mix(val name: String, val unter: String, val laden: suspend () -> List<BaseItemDto>)

/** Playlists-Reiter: eigene Playlists, darunter automatische Mixe (je Genre, neu, meistgehört, lange nicht gehört, Zufall). */
@Composable
private fun PlaylistsReiter(vm: SelikuViewModel, seite: Seite.Musik) {
	val daten = koinInject<SelikuDaten>()
	val abspielen = koinInject<Abspielen>()
	val context = LocalContext.current
	val scope = rememberCoroutineScope()
	@Suppress("UNCHECKED_CAST")
	var eigene by remember { mutableStateOf(vm.wahl["musik-playlists"] as? List<BaseItemDto> ?: emptyList()) }
	@Suppress("UNCHECKED_CAST")
	var genres by remember { mutableStateOf(vm.wahl["${seite.schluessel}|genres"] as? List<String> ?: emptyList()) }
	LaunchedEffect(Unit) {
		runCatching { daten.playlists() }.onSuccess { eigene = it; vm.wahl["musik-playlists"] = it }
		runCatching { daten.genres(seite.ansicht, setOf(BaseItemKind.MUSIC_ALBUM, BaseItemKind.AUDIO)) }
			.onSuccess { genres = it.take(8); vm.wahl["${seite.schluessel}|genres"] = genres }
	}
	val bib = seite.ansicht
	val mixe = listOf(
		Mix("Zufallsmix", "Querbeet durch alles") { daten.musikTitel(bib, ItemSortBy.RANDOM, true) },
		Mix("Neu hinzugefügt", "Die neuesten Titel") { daten.musikTitel(bib, ItemSortBy.DATE_CREATED, true) },
		Mix("Meistgehört", "Deine Lieblinge") { daten.musikTitel(bib, ItemSortBy.PLAY_COUNT, true, setOf(ItemFilter.IS_PLAYED)) },
		Mix("Lange nicht gehört", "Wiederentdecken") { daten.musikTitel(bib, ItemSortBy.DATE_PLAYED, false, setOf(ItemFilter.IS_PLAYED)) },
	) + genres.map { g -> Mix("$g-Mix", "Automatisch nach Genre") { daten.genreMix(g) } }
	val unterSeite = Seite.Ordner(seite.ansicht.id, "musik-playlists")
	SenkrechtRollen {
		LazyColumn(state = vm.liste(unterSeite), contentPadding = PaddingValues(top = 8.dp, bottom = 48.dp), modifier = Modifier.fillMaxSize()) {
			if (eigene.isNotEmpty()) item {
				Reihe("Deine Playlists", eigene, vm, unterSeite, onClick = { p ->
					scope.launch { abspielen.musik(context, runCatching { daten.playlistTitel(p.id) }.getOrDefault(emptyList())) }
				}, kachel = { item, klick, merken, fokus -> QuadratKachel(item, klick, merken = merken, onFokus = fokus) })
			}
			item {
				Text("Automatische Mixe", style = Schrift.Reihe, modifier = Modifier.padding(start = 48.dp, top = 6.dp, bottom = 8.dp))
				androidx.compose.foundation.lazy.LazyRow(
					contentPadding = PaddingValues(horizontal = 48.dp, vertical = 8.dp),
					horizontalArrangement = Arrangement.spacedBy(18.dp),
				) {
					itemsIndexed(mixe, key = { _, m -> m.name }) { i, m ->
						MixKachel(m.name, m.unter, i, merken = vm to (unterSeite.schluessel to "mix|" + m.name)) {
							scope.launch { abspielen.musik(context, runCatching { m.laden() }.getOrDefault(emptyList())) }
						}
					}
				}
			}
		}
	}
}

/** Quadratische Mix-Kachel mit warmem Verlauf (je Nummer ein anderer Farbton) und Namen darauf. */
@Composable
private fun MixKachel(name: String, unter: String, nr: Int, merken: Pair<SelikuViewModel, Pair<String, String>>, onClick: () -> Unit) {
	val toene = listOf(
		androidx.compose.ui.graphics.Color(0xFFF08A4B) to androidx.compose.ui.graphics.Color(0xFF8E3B2E),
		androidx.compose.ui.graphics.Color(0xFFE9B44C) to androidx.compose.ui.graphics.Color(0xFF9B4F1F),
		androidx.compose.ui.graphics.Color(0xFFD9605A) to androidx.compose.ui.graphics.Color(0xFF5E2433),
		androidx.compose.ui.graphics.Color(0xFF7FA37A) to androidx.compose.ui.graphics.Color(0xFF2F4A3A),
		androidx.compose.ui.graphics.Color(0xFFB07AA1) to androidx.compose.ui.graphics.Color(0xFF4A2E4F),
		androidx.compose.ui.graphics.Color(0xFF5FA8B8) to androidx.compose.ui.graphics.Color(0xFF243E52),
	)
	val (hell, dunkel) = toene[nr % toene.size]
	Waehlbar(onClick = onClick, merken = merken, modifier = Modifier.width(150.dp)) {
		Box(Modifier.fillMaxWidth().aspectRatio(1f).background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(hell, dunkel)))) {
			Column(Modifier.align(Alignment.BottomStart).padding(12.dp)) {
				Text(name, style = Schrift.Reihe, maxLines = 2, overflow = TextOverflow.Ellipsis)
				Text(unter, style = Schrift.Klein.copy(color = Farben.Text.copy(alpha = 0.8f)), maxLines = 1)
			}
		}
	}
}

/** Interpret: Bild, «Abspielen», «Zufällig», beliebte Titel, Alben. */
@Composable
fun InterpretSeite(vm: SelikuViewModel, seite: Seite.Interpret, oeffnen: (BaseItemDto) -> Unit) {
	val daten = koinInject<SelikuDaten>()
	val bilder = koinInject<Bilder>()
	val abspielen = koinInject<Abspielen>()
	val context = LocalContext.current
	val scope = rememberCoroutineScope()
	var interpret by remember { mutableStateOf<BaseItemDto?>(null) }
	var beliebt by remember { mutableStateOf<List<BaseItemDto>>(emptyList()) }
	var alben by remember { mutableStateOf<List<BaseItemDto>>(emptyList()) }
	var fehltInterpret by remember { mutableStateOf(false) }
	val start = remember { FocusRequester() }

	LaunchedEffect(seite.id) {
		runCatching {
			interpret = daten.item(seite.id)
			if (vm.fokus[seite.schluessel] == null) runCatching { start.requestFocus() }
			alben = daten.alben(null, seite.id)
			beliebt = daten.titelVon(seite.id, beliebteste = true)
		}.onFailure { Timber.w(it, "Interpret laden"); if (istWeg(it)) fehltInterpret = true }
	}

	val i = interpret
	Box(Modifier.fillMaxSize()) {
		Kulisse(bilder.hintergrund(i))
		if (fehltInterpret) { NichtMehrDa(vm); return@Box }
		if (i == null) { Hinweis("Wird geladen …"); return@Box }
		SenkrechtRollen { LazyColumn(state = vm.liste(seite), contentPadding = PaddingValues(bottom = 48.dp), modifier = Modifier.fillMaxSize()) {
			item {
				Row(Modifier.padding(start = 48.dp, top = 40.dp, end = 48.dp), horizontalArrangement = Arrangement.spacedBy(32.dp), verticalAlignment = Alignment.CenterVertically) {
					Bild(bilder.eigenesBild(i, 400), Modifier.width(180.dp).aspectRatio(1f).clip(CircleShape))
					Column(Modifier.weight(1f).padding(end = 200.dp)) {
						Text(i.name.orEmpty(), style = Schrift.Riesig)
						Spacer(Modifier.height(6.dp))
						Text(listOfNotNull(if (alben.isNotEmpty()) "${alben.size} Alben" else null, i.genres?.take(3)?.joinToString(", ")).joinToString("   ·   "), style = Schrift.Leise)
						Spacer(Modifier.height(10.dp))
						Text(i.overview.orEmpty(), style = Schrift.Normal, maxLines = 3, overflow = TextOverflow.Ellipsis)
						Spacer(Modifier.height(18.dp))
						Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
							Knopf("Abspielen", symbol = "▶", hervorgehoben = true, modifier = Modifier.focusRequester(start), onClick = {
								scope.launch { abspielen.musik(context, daten.titelVon(seite.id, beliebteste = false)) }
							})
							Knopf("Zufällig", symbol = "⤨", onClick = {
								scope.launch { abspielen.musik(context, daten.titelVon(seite.id, beliebteste = false), zufall = true) }
							})
						}
					}
				}
				Spacer(Modifier.height(28.dp))
			}
			if (beliebt.isNotEmpty()) {
				item { Text("Beliebte Titel", style = Schrift.Reihe, modifier = Modifier.padding(start = 48.dp, bottom = 8.dp)) }
				itemsIndexed(beliebt, key = { _, it -> "b" + it.id }) { index, t ->
					TitelZeile(t, nummer = index + 1, mitAlbum = true, onClick = { abspielen.musik(context, beliebt, index) })
				}
				item { Spacer(Modifier.height(24.dp)) }
			}
			item { Reihe("Alben", alben, vm, seite, onClick = oeffnen) }
		} }
	}
}

/** Album: Cover, «Abspielen», «Zufällig», Titelliste. Klick auf einen Titel spielt das Album ab diesem Titel. */
@Composable
fun AlbumSeite(vm: SelikuViewModel, seite: Seite.Album, oeffnen: (BaseItemDto) -> Unit) {
	val daten = koinInject<SelikuDaten>()
	val bilder = koinInject<Bilder>()
	val abspielen = koinInject<Abspielen>()
	val context = LocalContext.current
	var album by remember { mutableStateOf<BaseItemDto?>(null) }
	var titel by remember { mutableStateOf<List<BaseItemDto>>(emptyList()) }
	var fehltAlbum by remember { mutableStateOf(false) }
	val start = remember { FocusRequester() }

	LaunchedEffect(seite.id) {
		runCatching {
			album = daten.item(seite.id)
			titel = daten.titel(seite.id)
			if (vm.fokus[seite.schluessel] == null) runCatching { start.requestFocus() }
		}.onFailure { Timber.w(it, "Album laden"); if (istWeg(it)) fehltAlbum = true }
	}

	val a = album
	Box(Modifier.fillMaxSize().background(Farben.Hintergrund)) {
		if (fehltAlbum) { NichtMehrDa(vm); return@Box }
		if (a == null) { Hinweis("Wird geladen …"); return@Box }
		Row(Modifier.fillMaxSize().padding(start = 48.dp, top = 40.dp, end = 48.dp), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
			Column(Modifier.width(300.dp)) {
				Bild(bilder.eigenesBild(a, 500), Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(8.dp)))
				Spacer(Modifier.height(16.dp))
				Text(a.name.orEmpty(), style = Schrift.Titel, maxLines = 2, overflow = TextOverflow.Ellipsis)
				Spacer(Modifier.height(4.dp))
				Text(listOfNotNull(a.albumArtist, a.productionYear?.toString(), "${titel.size} Titel", laufzeit(a.runTimeTicks)).joinToString("  ·  "), style = Schrift.Leise)
				Spacer(Modifier.height(16.dp))
				Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
					Knopf("Abspielen", symbol = "▶", hervorgehoben = true, modifier = Modifier.focusRequester(start), onClick = { abspielen.musik(context, titel) })
					Knopf("Zufällig", symbol = "⤨", onClick = { abspielen.musik(context, titel, zufall = true) })
				}
				val interpretId = a.albumArtists?.firstOrNull()?.id
				if (interpretId != null) {
					Spacer(Modifier.height(10.dp))
					Knopf("Zum Interpreten", symbol = "›", onClick = { vm.zu(Seite.Interpret(interpretId)) })
				}
			}
			LazyColumn(state = vm.liste(seite), contentPadding = PaddingValues(bottom = 48.dp), modifier = Modifier.weight(1f)) {
				itemsIndexed(titel, key = { _, it -> it.id }) { index, t ->
					TitelZeile(t, nummer = t.indexNumber ?: (index + 1), mitAlbum = false, onClick = { abspielen.musik(context, titel, index) },
						merken = vm to (seite.schluessel to t.id.toString()))
				}
			}
		}
	}
}

/** Eine Zeile in einer Titelliste. */
@Composable
fun TitelZeile(
	t: BaseItemDto,
	nummer: Int,
	mitAlbum: Boolean,
	onClick: () -> Unit,
	merken: Pair<SelikuViewModel, Pair<String, String>>? = null,
) {
	Waehlbar(onClick = onClick, vergroessern = false, merken = merken, modifier = Modifier.padding(horizontal = if (mitAlbum) 40.dp else 0.dp, vertical = 2.dp)) { fokussiert ->
		Row(
			Modifier.fillMaxWidth().background(if (fokussiert) Farben.FlaecheHell else Farben.Flaeche.copy(alpha = 0.5f)).padding(horizontal = 16.dp, vertical = 10.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			Text(nummer.toString(), style = Schrift.Leise, modifier = Modifier.width(36.dp))
			Column(Modifier.weight(1f)) {
				Text(t.name.orEmpty(), style = Schrift.Normal.copy(fontWeight = if (fokussiert) FontWeight.Bold else FontWeight.Normal), maxLines = 1, overflow = TextOverflow.Ellipsis)
				if (mitAlbum) Text(listOfNotNull(t.album, t.productionYear?.toString()).joinToString(" · "), style = Schrift.Klein, maxLines = 1)
			}
			Text(laufzeitKurz(t.runTimeTicks), style = Schrift.Leise)
		}
	}
}

fun laufzeitKurz(ticks: Long?): String {
	val s = (ticks ?: 0L) / 10_000_000L
	return if (s <= 0) "" else "%d:%02d".format(s / 60, s % 60)
}
