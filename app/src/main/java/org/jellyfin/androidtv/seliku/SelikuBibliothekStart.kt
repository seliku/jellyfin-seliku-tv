package org.jellyfin.androidtv.seliku

import androidx.compose.ui.focus.focusRequester

import androidx.compose.ui.focus.onFocusChanged

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CollectionType
import org.koin.compose.koinInject
import timber.log.Timber
import androidx.compose.ui.res.stringResource
import org.jellyfin.androidtv.R

/** Eine Reihe auf einer Bibliotheks-Startseite. */
data class BibReihe(val titel: String, val items: List<BaseItemDto>, val weiterschauen: Boolean = false)

/** Typen, die eine Bibliothek in Raster und Reihen zeigt. */
fun typenFuer(bib: BaseItemDto): Set<BaseItemKind> = when (bib.collectionType) {
	CollectionType.MOVIES -> setOf(BaseItemKind.MOVIE)
	CollectionType.TVSHOWS -> setOf(BaseItemKind.SERIES)
	CollectionType.BOXSETS -> setOf(BaseItemKind.BOX_SET)
	else -> setOf(BaseItemKind.MOVIE, BaseItemKind.SERIES, BaseItemKind.VIDEO)
}

/** Lädt die Reihen der Bibliotheks-Startseite (auch fürs Vorladen im Hintergrund). */
suspend fun bibliothekReihenLaden(context: android.content.Context, daten: SelikuDaten, bib: BaseItemDto): List<BibReihe> = coroutineScope {
	val serien = bib.collectionType == CollectionType.TVSHOWS
	val typen = typenFuer(bib)
	val weiter = async { runCatching { daten.weiterschauenIn(bib) }.getOrDefault(emptyList()) }
	val naechstes = async { if (serien) runCatching { daten.alsNaechstesIn(bib) }.getOrDefault(emptyList()) else emptyList() }
	val neu = async { runCatching { daten.neuIn(bib) }.getOrDefault(emptyList()) }
	val weil = async { runCatching { daten.weilDuGeschaut(bib, serien) }.getOrNull() }
	val top = async { runCatching { daten.topUngesehen(bib, typen) }.getOrDefault(emptyList()) }
	val zuletzt = async { runCatching { daten.zuletztGesehen(bib, serien) }.getOrDefault(emptyList()) }
	val wieder = async { runCatching { daten.wiederentdecken(bib, typen) }.getOrDefault(emptyList()) }
	val genres = runCatching { daten.genres(bib, typen) }.getOrDefault(emptyList()).shuffled().take(4)
	val genreReihen = genres.map { g -> async { BibReihe(g, runCatching { daten.genreReihe(bib, typen, g) }.getOrDefault(emptyList())) } }
	buildList {
		val w = weiter.await()
		val n = naechstes.await().filter { e -> w.none { it.seriesId == e.seriesId } }
		add(BibReihe(context.getString(R.string.seliku_continue_watching), w + n, weiterschauen = true))
		add(BibReihe(context.getString(R.string.seliku_recently_added), neu.await()))
		weil.await()?.let { (name, l) -> add(BibReihe(context.getString(R.string.seliku_because_you_watched, name), l)) }
		add(BibReihe(context.getString(if (serien) R.string.seliku_top_series_unwatched else R.string.seliku_top_movies_unwatched), top.await()))
		genreReihen.forEach { add(it.await()) }
		add(BibReihe(context.getString(R.string.seliku_recently_watched), zuletzt.await()))
		add(BibReihe(context.getString(R.string.seliku_rediscover), wieder.await()))
	}.filter { it.items.isNotEmpty() }
}

/**
 * Filme/Serien: Reiter «Empfohlen» (Startseite mit Reihen wie bei den Bezahldiensten) und «Bibliothek» (Raster mit
 * Sortierung und Genre-Filter). Empfohlen ist die Vorgabe.
 */
@Composable
fun BibliothekRahmen(vm: SelikuViewModel, seite: Seite.Bibliothek, oeffnen: (BaseItemDto) -> Unit) {
	var reiter by remember { mutableStateOf(vm.wahl["${seite.schluessel}|reiter"] as? Int ?: 0) }
	val bilder = koinInject<Bilder>()
	var hero by remember { mutableStateOf<BaseItemDto?>(null) }
	// Das Hintergrundbild liegt hinter der ganzen Seite, die Reiterleiste oben ist durchsichtig darauf
	Box(Modifier.fillMaxSize()) {
	if (reiter == 0) Kulisse(bilder.hintergrund(hero, 1280))
	Column(Modifier.fillMaxSize()) {
		Row(Modifier.padding(start = 48.dp, top = 24.dp, bottom = 4.dp).onFocusChanged { vm.reiterFokus = it.hasFocus }, verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(12.dp)) {
			Text(seite.ansicht.name.orEmpty(), style = Schrift.Titel)
			Spacer(Modifier.width(12.dp))
			Knopf(stringResource(R.string.seliku_recommended), onClick = { reiter = 0; vm.wahl["${seite.schluessel}|reiter"] = 0 }, aktiv = reiter == 0, modifier = if (reiter == 0) Modifier.focusRequester(vm.reiter(seite)) else Modifier)
			Knopf(stringResource(R.string.seliku_library), onClick = { reiter = 1; vm.wahl["${seite.schluessel}|reiter"] = 1 }, aktiv = reiter == 1, modifier = if (reiter == 1) Modifier.focusRequester(vm.reiter(seite)) else Modifier)
		}
		Box(Modifier.weight(1f)) {
			if (reiter == 0) BibliothekEmpfohlen(vm, seite, oeffnen) { hero = it }
			else BibliothekSeite(vm, seite, oeffnen, mitKopf = false)
		}
	}
	}
}

@Composable
private fun BibliothekEmpfohlen(vm: SelikuViewModel, seite: Seite.Bibliothek, oeffnen: (BaseItemDto) -> Unit, setzeHero: (BaseItemDto) -> Unit) {
	val daten = koinInject<SelikuDaten>()
	val abspielen = koinInject<Abspielen>()
	val context = LocalContext.current
	val schluessel = "${seite.schluessel}|start-reihen"
	@Suppress("UNCHECKED_CAST")
	var reihen by remember { mutableStateOf(vm.wahl[schluessel] as? List<BibReihe>) }
	LaunchedEffect(seite.ansicht.id, vm.datenStand) {
		// Vorgeladenes sofort zeigen, danach still auffrischen
		if (reihen != null) delay(1500)
		runCatching { bibliothekReihenLaden(context, daten, seite.ansicht) }
			.onSuccess { reihen = it; vm.wahl[schluessel] = it }
			.onFailure { Timber.w(it, "Bibliothek-Startseite") }
	}
	// Persönlicher Banner dieser Bibliothek (ungesehen, Lieblings-Genres)
	val bilder = koinInject<Bilder>()
	@Suppress("UNCHECKED_CAST")
	var banner by remember { mutableStateOf(vm.wahl["${seite.schluessel}|banner"] as? List<BaseItemDto> ?: emptyList()) }
	LaunchedEffect(seite.ansicht.id) {
		runCatching { daten.bannerEmpfehlungen(seite.ansicht) }.onSuccess { if (it.isNotEmpty()) { banner = it; vm.wahl["${seite.schluessel}|banner"] = it } }
	}
	val r = reihen
	if (r == null) { Hinweis(stringResource(R.string.seliku_loading)); return }
	if (r.isEmpty() && banner.isEmpty()) { Hinweis(stringResource(R.string.seliku_nothing_yet)); return }
	val unterSeite = Seite.Ordner(seite.ansicht.id, "empfohlen-" + seite.ansicht.name)
	val bannerAnfrage = remember { androidx.compose.ui.focus.FocusRequester() }
	val rollScope = androidx.compose.runtime.rememberCoroutineScope()
	// Seerr verbunden: beliebte Titel, die hier noch fehlen (bei Filmen bzw. Serien)
	val seerr = koinInject<SeerrDaten>()
	@Suppress("UNCHECKED_CAST")
	var fehlend by remember { mutableStateOf(vm.wahl["${seite.schluessel}|seerr"] as? List<SeerrTitel> ?: emptyList()) }
	val seerrTyp = when (seite.ansicht.collectionType) { CollectionType.MOVIES -> "movie"; CollectionType.TVSHOWS -> "tv"; else -> null }
	LaunchedEffect(seite.ansicht.id) {
		if (seerrTyp == null || !SeerrVerbindung.verbunden(context)) return@LaunchedEffect
		runCatching { if (seerrTyp == "movie") seerr.beliebteFilme() + seerr.beliebteFilme(2) else seerr.beliebteSerien() + seerr.beliebteSerien(2) }
			.onSuccess { l -> fehlend = l.filter { it.zustand != SeerrZustand.ANSEHEN }.take(30); vm.wahl["${seite.schluessel}|seerr"] = fehlend }
	}
	Box(Modifier.fillMaxSize()) {
	BannerRollen(vm.liste(unterSeite), banner.isNotEmpty()) {
		LazyColumn(state = vm.liste(unterSeite), contentPadding = PaddingValues(top = 0.dp, bottom = 48.dp), modifier = Modifier.fillMaxSize()) {
			item(key = "banner") { Banner(banner, oeffnen = oeffnen, zeigt = { }, fokus = bannerAnfrage) }
			items(r, key = { it.titel }) { reihe ->
				Reihe(reihe.titel, reihe.items, vm, unterSeite, beiHoch = if (reihe == r.firstOrNull() && banner.isNotEmpty()) ({
					rollScope.launch {
						runCatching { vm.liste(unterSeite).scrollToItem(0) }
						kotlinx.coroutines.delay(60)
						runCatching { bannerAnfrage.requestFocus() }
					}
					true
				}) else null, onClick = { item ->
					when {
						reihe.weiterschauen -> abspielen.video(context, item)
						item.type == BaseItemKind.EPISODE -> abspielen.video(context, item)
						else -> oeffnen(item)
					}
				}, onFokus = setzeHero, kachel = { item, klick, merken, fokus -> PosterKachel(item, klick, breite = 120.dp, merken = merken, onFokus = fokus, onMenue = { vm.menue = item }, aufklappen = true) })
			}
			// Nach den eigenen Reihen: was beliebt ist, aber fehlt
			if (fehlend.isNotEmpty()) item(key = "seerr") { SeerrReihe(stringResource(if (seerrTyp == "movie") R.string.seliku_seerr_popular_movies_missing else R.string.seliku_seerr_popular_series_missing), fehlend, vm, unterSeite) }
		}
	}
	}
}
