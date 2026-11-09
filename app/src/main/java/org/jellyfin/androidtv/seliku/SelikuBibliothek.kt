package org.jellyfin.androidtv.seliku

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CollectionType
import org.koin.compose.koinInject
import timber.log.Timber
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import org.jellyfin.androidtv.R

private const val SEITENGROESSE = 60

/** Bibliothek (Filme oder Serien): Raster mit Sortierung und Filter «Ungesehen», lädt beim Scrollen nach. */
@Composable
fun BibliothekSeite(vm: SelikuViewModel, seite: Seite.Bibliothek, oeffnen: (BaseItemDto) -> Unit, mitKopf: Boolean = true) {
	val daten = koinInject<SelikuDaten>()
	val abspielen = koinInject<Abspielen>()
	val context = LocalContext.current
	val scope = rememberCoroutineScope()
	val bib = seite.ansicht
	val typen = when (bib.collectionType) {
		CollectionType.MOVIES -> setOf(BaseItemKind.MOVIE)
		CollectionType.TVSHOWS -> setOf(BaseItemKind.SERIES)
		CollectionType.BOXSETS -> setOf(BaseItemKind.BOX_SET)
		else -> setOf(BaseItemKind.MOVIE, BaseItemKind.SERIES, BaseItemKind.VIDEO)
	}

	var sortierung by remember { mutableStateOf(vm.wahl["${seite.schluessel}|sort"] as? Sortierung ?: Sortierung.NEU) }
	var ungesehen by remember { mutableStateOf(vm.wahl["${seite.schluessel}|ungesehen"] as? Boolean ?: false) }
	var genre by remember { mutableStateOf(vm.wahl["${seite.schluessel}|genre"] as? String) }
	var favoriten by remember { mutableStateOf(vm.wahl["${seite.schluessel}|favoriten"] as? Boolean ?: false) }
	var genres by remember { mutableStateOf<List<String>>(emptyList()) }
	LaunchedEffect(bib.id) { genres = runCatching { daten.genres(bib, typen) }.getOrDefault(emptyList()) }
	@Suppress("UNCHECKED_CAST")
	val items = remember(sortierung, ungesehen, genre, favoriten) {
		mutableStateListOf<BaseItemDto>().apply { addAll(vm.wahl["${seite.schluessel}|items|$sortierung|$ungesehen|$genre|$favoriten"] as? List<BaseItemDto> ?: emptyList()) }
	}
	var gesamt by remember(sortierung, ungesehen, genre, favoriten) { mutableStateOf<Int?>(null) }
	var laedt by remember(sortierung, ungesehen, genre, favoriten) { mutableStateOf(false) }

	fun nachladen() {
		if (laedt || (gesamt != null && items.size >= gesamt!!)) return
		laedt = true
		scope.launch {
			runCatching { daten.inhalt(bib, typen, sortierung, ungesehen, items.size, SEITENGROESSE, genre, favoriten) }
				.onSuccess { r ->
					items.addAll(r.items.filter { n -> items.none { it.id == n.id } })
					gesamt = r.totalRecordCount
					vm.wahl["${seite.schluessel}|items|$sortierung|$ungesehen|$genre|$favoriten"] = items.toList()
				}
				.onFailure { Timber.w(it, "Bibliothek laden") }
			laedt = false
		}
	}

	val raster = vm.raster(seite)
	// Beim ersten Öffnen steht der Fokus auf dem ersten Titel, nicht auf der Sortierung
	val erstes = remember { androidx.compose.ui.focus.FocusRequester() }
	LaunchedEffect(items.isNotEmpty()) {
		if (items.isNotEmpty() && vm.fokus[seite.schluessel] == null) { kotlinx.coroutines.delay(100); runCatching { erstes.requestFocus() } }
	}
	LaunchedEffect(sortierung, ungesehen, genre, favoriten) { if (items.isEmpty()) nachladen() }
	LaunchedEffect(vm.geloescht.size) { if (vm.geloescht.isNotEmpty()) items.removeAll { it.id in vm.geloescht } }
	LaunchedEffect(raster, sortierung, ungesehen, genre, favoriten) {
		snapshotFlow { raster.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
			.distinctUntilChanged()
			.collect { letzter -> if (letzter >= items.size - 18) nachladen() }
	}

	Column(Modifier.fillMaxSize()) {
		if (mitKopf) Row(
			Modifier.fillMaxWidth().padding(start = 48.dp, top = 28.dp, end = 48.dp, bottom = 8.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			Text(bib.name.orEmpty(), style = Schrift.Titel)
			Spacer(Modifier.width(16.dp))
			Text(gesamt?.let { pluralStringResource(R.plurals.seliku_title_count, it, it) }.orEmpty(), style = Schrift.Leise)
		}
		LazyRow(
			contentPadding = PaddingValues(horizontal = 48.dp, vertical = 6.dp),
			horizontalArrangement = Arrangement.spacedBy(10.dp),
		) {
			items(Sortierung.entries) { s ->
				Knopf(stringResource(s.titel), onClick = {
					if (sortierung != s) { sortierung = s; vm.wahl["${seite.schluessel}|sort"] = s; scope.launch { raster.scrollToItem(0) } }
				}, aktiv = s == sortierung)
			}
			item {
				Knopf(stringResource(if (ungesehen) R.string.seliku_unwatched_only else R.string.seliku_all), onClick = {
					ungesehen = !ungesehen; vm.wahl["${seite.schluessel}|ungesehen"] = ungesehen; scope.launch { raster.scrollToItem(0) }
				}, aktiv = ungesehen, symbol = "◐")
			}
			// Nur Favoriten (Herz auf der Detailseite oder im Titelmenü)
			item {
				Knopf(stringResource(R.string.seliku_favorites), onClick = {
					favoriten = !favoriten; vm.wahl["${seite.schluessel}|favoriten"] = favoriten; scope.launch { raster.scrollToItem(0) }
				}, aktiv = favoriten, symbol = if (favoriten) "♥" else "♡")
			}
			item {
				Knopf(stringResource(R.string.seliku_shuffle), onClick = {
					items.randomOrNull()?.let { if (it.type == BaseItemKind.SERIES) abspielen.zufall(context, it) else abspielen.video(context, it, vonVorn = true) }
				}, symbol = "⤨")
			}
		}
		// Genres: wirken zusätzlich zu Sortierung und «Nur ungesehene»; nochmals drücken hebt die Wahl auf
		if (genres.isNotEmpty()) LazyRow(
			contentPadding = PaddingValues(horizontal = 48.dp, vertical = 6.dp),
			horizontalArrangement = Arrangement.spacedBy(10.dp),
		) {
			items(genres) { g ->
				Knopf(g, onClick = {
					genre = if (genre == g) null else g
					vm.wahl["${seite.schluessel}|genre"] = genre ?: ""
					if (genre == null) vm.wahl.remove("${seite.schluessel}|genre")
					scope.launch { raster.scrollToItem(0) }
				}, aktiv = g == genre)
			}
		}
		Box(Modifier.fillMaxSize()) {
			if (items.isEmpty()) Hinweis(stringResource(if (laedt || gesamt == null) R.string.seliku_loading else R.string.seliku_no_titles))
			LazyVerticalGrid(
				state = raster,
				columns = GridCells.Adaptive(118.dp),
				contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 12.dp, bottom = 40.dp),
				horizontalArrangement = Arrangement.spacedBy(20.dp),
				verticalArrangement = Arrangement.spacedBy(22.dp),
				modifier = Modifier.fillMaxSize(),
			) {
				itemsIndexed(items, key = { _, it -> it.id }) { index, item ->
					PosterKachel(
						item = item,
						breite = 118.dp,
						erster = if (index == 0) erstes else null,
						onClick = { oeffnen(item) },
						merken = vm to (seite.schluessel to item.id.toString()),
						onMenue = { vm.menue = item },
					)
				}
			}
		}
	}
}
