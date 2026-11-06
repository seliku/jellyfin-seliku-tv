package org.jellyfin.androidtv.seliku

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.koin.compose.koinInject
import timber.log.Timber
import androidx.compose.ui.res.stringResource
import org.jellyfin.androidtv.R

/** Art der Startseiten-Reihe (die Überschrift ist übersetzt und taugt nicht als Unterscheidung). */
private enum class StartArt { WEITER, TV, NEU, FAVORITEN, ANDERE }

private data class StartReihe(val titel: String, val items: List<BaseItemDto>, val loeschdatum: Map<java.util.UUID, String> = emptyMap(), val art: StartArt = StartArt.ANDERE)

/** Startseite: oben das gewählte Element gross, darunter Weiterschauen, Live-TV und «Neu in …» je Bibliothek. */
@Composable
fun StartSeite(vm: SelikuViewModel, oeffnen: (BaseItemDto) -> Unit) {
	val daten = koinInject<SelikuDaten>()
	val bilder = koinInject<Bilder>()
	val abspielen = koinInject<Abspielen>()
	val context = LocalContext.current
	val seite = Seite.Start

	@Suppress("UNCHECKED_CAST")
	var reihen by remember { mutableStateOf(vm.wahl["start-reihen"] as? List<StartReihe>) }
	var gewaehlt by remember { mutableStateOf<BaseItemDto?>(null) }
	var hero by remember { mutableStateOf<BaseItemDto?>(null) }
	var bannerFokus by remember { mutableStateOf(false) }
	val bannerAnfrage = remember { androidx.compose.ui.focus.FocusRequester() }
	val rollScope = androidx.compose.runtime.rememberCoroutineScope()
	// Beim Start steht die Seite ganz oben (Banner sichtbar), auch wenn der Fokus auf «Weiterschauen» liegt
	LaunchedEffect(reihen != null) {
		if (reihen != null && vm.fokus[seite.schluessel] == null) { kotlinx.coroutines.delay(600); runCatching { vm.liste(seite).scrollToItem(0) } }
	}
	@Suppress("UNCHECKED_CAST")
	var banner by remember { mutableStateOf(vm.wahl["start-banner"] as? List<BaseItemDto> ?: emptyList()) }
	LaunchedEffect(Unit) {
		runCatching { daten.bannerEmpfehlungen() }.onSuccess { if (it.isNotEmpty()) { banner = it; vm.wahl["start-banner"] = it } }
	}

	LaunchedEffect(vm.datenStand) {
		// Immer frisch laden (auch nach Änderungen im Titelmenü, z. B. «Aus Weiterschauen entfernen») (Fortschritt ändert sich nach dem Schauen), alte Reihen bis dahin stehen lassen
		val neu = runCatching {
			val bibs = daten.bibliotheken()
			buildList {
				add(StartReihe(context.getString(R.string.seliku_continue_watching), daten.weiterschauen(), art = StartArt.WEITER))
				if (SelikuEinstellungen.favoritenReihe(context))
					add(StartReihe(context.getString(R.string.seliku_your_favorites), runCatching { daten.favoriten() }.getOrDefault(emptyList()), art = StartArt.FAVORITEN))
				if (bibs.any { it.istLiveTv() }) add(StartReihe(context.getString(R.string.seliku_on_tv_now), daten.sender().filter { it.currentProgram != null }.take(20), art = StartArt.TV))
				// Sonderreihen wie «Diese Filme werden bald gelöscht» (Maintainerr, gespiegelt als Jellyfin-Sammlung)
				runCatching { daten.startseitenSammlungen() }.getOrDefault(emptyList()).forEach { (name, items, datum) -> add(StartReihe(name, items, datum)) }
				bibs.filter { !it.istLiveTv() }.forEach { bib ->
					add(StartReihe(context.getString(R.string.seliku_new_in, bib.name.orEmpty()), runCatching { daten.neuIn(bib) }.getOrDefault(emptyList()), art = StartArt.NEU))
				}
			}.filter { it.items.isNotEmpty() }
		}.onFailure { Timber.w(it, "Startseite laden") }.getOrNull()
		if (neu != null) {
			reihen = neu
			vm.wahl["start-reihen"] = neu
			// Hintergründe für die Anmeldeseite merken (neu hinzugefügte zuerst)
			Kulissen.merken(context, neu.filter { it.art == StartArt.NEU || it.art == StartArt.WEITER }
				.flatMap { r -> r.items.mapNotNull { bilder.hintergrund(it, 1280) } })
		}
	}
	// Hintergrund erst nach kurzem Verweilen wechseln, damit schnelles Durchblättern flüssig bleibt
	LaunchedEffect(gewaehlt) {
		delay(350)
		hero = gewaehlt
	}

	Box(Modifier.fillMaxSize()) {
		Kulisse(hero?.let { if (it.type == BaseItemKind.TV_CHANNEL) bilder.sendung(it, 1280) else bilder.hintergrund(it) })
		val r = reihen
		if (r == null) { Hinweis(stringResource(R.string.seliku_loading)); return@Box }
		if (r.isEmpty()) { Hinweis(stringResource(R.string.seliku_nothing_yet)); return@Box }

		Column(Modifier.fillMaxSize()) {
			BannerRollen(vm.liste(seite), banner.isNotEmpty()) { LazyColumn(
				state = vm.liste(seite),
				contentPadding = PaddingValues(bottom = 40.dp),
				modifier = Modifier.fillMaxSize(),
			) {
				item(key = "banner") {
					Banner(banner, oeffnen = { b -> seiteFuer(b)?.let(vm::zu) }, zeigt = { }, fokus = bannerAnfrage,
						modifier = Modifier.onFocusChanged { bannerFokus = it.hasFocus; if (it.hasFocus) gewaehlt = null })
				}
				items(r, key = { it.titel }) { reihe ->
					val ersteReihe = reihe == r.firstOrNull()
					Reihe(
						beiHoch = if (ersteReihe && banner.isNotEmpty()) ({
							rollScope.launch {
								runCatching { vm.liste(seite).scrollToItem(0) }
								kotlinx.coroutines.delay(60)
								runCatching { bannerAnfrage.requestFocus() }
							}
							true
						}) else null,
						titel = reihe.titel,
						items = reihe.items,
						vm = vm,
						seite = seite,
						onFokus = { gewaehlt = it },
						onClick = { item ->
							when {
								reihe.art == StartArt.TV -> abspielen.sender(context, item)
								reihe.art == StartArt.WEITER -> abspielen.video(context, item)
								item.type == BaseItemKind.EPISODE -> abspielen.video(context, item)
								else -> oeffnen(item)
							}
						},
						kachel = if (reihe.art == StartArt.WEITER) { item, klick, merken, fokus ->
							PosterKachel(item, klick, breite = 120.dp, merken = merken, onFokus = fokus, onMenue = { vm.menue = item }, aufklappen = true)
						} else if (reihe.art == StartArt.TV) { item, klick, merken, fokus ->
							SenderKachel(item, klick, merken = merken, onFokus = fokus)
						} else if (reihe.loeschdatum.isNotEmpty()) { item, klick, merken, fokus ->
							val tag = reihe.loeschdatum[item.id]?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }
							val wann = tag?.let { stringResource(R.string.seliku_deleted_on, it.format(java.time.format.DateTimeFormatter.ofPattern(stringResource(R.string.seliku_date_short_pattern)))) } ?: stringResource(R.string.seliku_deleted_soon)
							QuerKachel(item, klick, merken = merken, onFokus = fokus, zeilen = item.name.orEmpty() to wann)
						} else if (reihe.art == StartArt.FAVORITEN) { item, klick, merken, fokus ->
							PosterKachel(item, klick, breite = 120.dp, merken = merken, onFokus = fokus, onMenue = { vm.menue = item }, aufklappen = true)
						} else if (reihe.art == StartArt.NEU && reihe.items.none { it.type == BaseItemKind.MUSIC_ALBUM || it.type == BaseItemKind.AUDIO }) { item, klick, merken, fokus ->
							PosterKachel(item, klick, breite = 120.dp, merken = merken, onFokus = fokus, onMenue = { vm.menue = item }, aufklappen = true)
						} else if (reihe.items.all { it.type == BaseItemKind.MOVIE || it.type == BaseItemKind.SERIES }) { item, klick, merken, fokus ->
							// Sammlungen (z. B. «Diese Filme werden bald gelöscht») wie die anderen Reihen
							PosterKachel(item, klick, breite = 120.dp, merken = merken, onFokus = fokus, onMenue = { vm.menue = item }, aufklappen = true)
						} else null,
					)
				}
			} }
		}
	}
}
