package org.jellyfin.androidtv.seliku

import androidx.compose.ui.Alignment

import androidx.compose.foundation.background

import androidx.compose.foundation.layout.size

import androidx.compose.ui.focus.onFocusChanged

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.sdk.model.api.BaseItemDto
import org.koin.compose.koinInject
import timber.log.Timber
import java.util.UUID
import androidx.compose.ui.res.stringResource
import org.jellyfin.androidtv.R

/** Kopfbereich einer Detailseite: Logo oder Titel, Infozeile, Beschreibung, Knöpfe. */
@Composable
private fun Kopf(
	item: BaseItemDto,
	zusatz: String? = null,
	poster: Boolean = true,
	liste: androidx.compose.foundation.lazy.LazyListState? = null,
	knoepfe: @Composable () -> Unit,
) {
	val scope = androidx.compose.runtime.rememberCoroutineScope()
	val bilder = koinInject<Bilder>()
	Row(Modifier.fillMaxWidth().padding(start = 48.dp, top = 40.dp, end = 48.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
		if (poster) Bild(bilder.eigenesBild(item, 400), Modifier.width(190.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp)))
		Column(Modifier.weight(1f)) {
			val logo = bilder.logo(item)
			if (logo != null) Bild(logo, Modifier.height(80.dp).width(340.dp), ContentScale.Fit, grund = androidx.compose.ui.graphics.Color.Transparent)
			else Text(item.name.orEmpty(), style = Schrift.Riesig, maxLines = 2, overflow = TextOverflow.Ellipsis)
			Spacer(Modifier.height(10.dp))
			Text(item.metazeile(), style = Schrift.Leise, maxLines = 1)
			if (zusatz != null) { Spacer(Modifier.height(4.dp)); Text(zusatz, style = Schrift.Leise, maxLines = 1) }
			item.taglines?.firstOrNull()?.let { Spacer(Modifier.height(10.dp)); Text(it, style = Schrift.Normal.copy(color = Farben.Akzent), maxLines = 1) }
			Spacer(Modifier.height(10.dp))
			Text(item.overview.orEmpty().takeUnless { it.trimStart().startsWith("http") }.orEmpty(), style = Schrift.Normal, maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(end = 160.dp))
			Spacer(Modifier.height(20.dp))
			// Steht der Fokus auf den Knöpfen, bleibt die Seite ganz oben (sonst schiebt die Rollregel den Titel hinaus)
			Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.onFocusChanged {
				if (it.hasFocus && liste != null && (liste.firstVisibleItemIndex > 0 || liste.firstVisibleItemScrollOffset > 0))
					scope.launch { kotlinx.coroutines.delay(80); runCatching { liste.animateScrollToItem(0) } }
			}) { knoepfe() }
		}
	}
}

@Composable
fun fortsetzenText(item: BaseItemDto): String? {
	val ticks = item.userData?.playbackPositionTicks ?: 0L
	if (ticks <= 0L) return null
	val s = ticks / 10_000_000L
	return stringResource(R.string.seliku_resume_from, if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60) else "%d:%02d".format(s / 60, s % 60))
}

/** Serie: Kopf mit «Abspielen/Fortsetzen» der nächsten Folge, Staffelwahl, Folgenreihe, Ähnliches. Klick auf eine Folge spielt sie ab. */
@Composable
fun SerienSeite(vm: SelikuViewModel, seite: Seite.Serie, oeffnen: (BaseItemDto) -> Unit) {
	val daten = koinInject<SelikuDaten>()
	val bilder = koinInject<Bilder>()
	val abspielen = koinInject<Abspielen>()
	val context = LocalContext.current
	TitelMusik(seite.id)
	val scope = rememberCoroutineScope()

	var serie by remember { mutableStateOf<BaseItemDto?>(null) }
	var naechste by remember { mutableStateOf<BaseItemDto?>(null) }
	var staffeln by remember { mutableStateOf<List<BaseItemDto>>(emptyList()) }
	var staffel by remember { mutableStateOf(vm.wahl["${seite.schluessel}|staffel"] as? UUID) }
	var folgen by remember { mutableStateOf<List<BaseItemDto>?>(null) }
	var aehnlich by remember { mutableStateOf<List<BaseItemDto>>(emptyList()) }
	var gewaehlteFolge by remember { mutableStateOf<BaseItemDto?>(null) }
	var neuLaden by remember { mutableStateOf(0) }
	var fehltSerie by remember { mutableStateOf(false) }
	// «Abspielen» ist der erste Platz der Seite: die Startlogik holt den Fokus dorthin, nicht zu «Ähnliche Titel»
	val start = remember(seite.schluessel) { vm.ersteAnfrageFuer(seite.schluessel, "abspielen") }

	// Auch nach Änderungen im Titelmenü (Favorit, Gesehen) neu laden
	LaunchedEffect(seite.id, neuLaden, vm.datenStand) {
		runCatching {
			val zuerst = serie == null
			serie = daten.item(seite.id)
			naechste = daten.naechsteFolge(seite.id)
			staffeln = daten.staffeln(seite.id)
			if (staffel == null || staffeln.none { it.id == staffel }) staffel = naechste?.seasonId ?: staffeln.firstOrNull()?.id
			if (zuerst && vm.fokus[seite.schluessel] == null) runCatching { start.requestFocus() }
			aehnlich = daten.aehnlich(seite.id)
		}.onFailure { Timber.w(it, "Serie laden"); if (istWeg(it)) fehltSerie = true }
	}
	LaunchedEffect(staffel, neuLaden) {
		val s = staffel ?: return@LaunchedEffect
		vm.wahl["${seite.schluessel}|staffel"] = s
		folgen = runCatching { daten.folgen(seite.id, s) }.getOrDefault(emptyList())
	}

	val sr = serie
	Box(Modifier.fillMaxSize()) {
		Kulisse(bilder.hintergrund(sr))
		if (fehltSerie) { NichtMehrDa(vm); return@Box }
		if (sr == null) { Hinweis(stringResource(R.string.seliku_loading)); return@Box }
		SenkrechtRollen { LazyColumn(state = vm.liste(seite), contentPadding = PaddingValues(bottom = 48.dp), modifier = Modifier.fillMaxSize()) {
			item {
				val n = naechste
				val zusatz = n?.let { stringResource(R.string.seliku_up_next, it.unterzeile()) }
				Kopf(sr, zusatz, liste = vm.liste(seite)) {
					Knopf(
						text = n?.let { fortsetzenText(it) ?: "${stringResource(R.string.seliku_play)}  ${it.parentIndexNumber?.let { s -> stringResource(R.string.seliku_season_short, s) } ?: ""} ${it.indexNumber?.let { f -> stringResource(R.string.seliku_episode_short, f) } ?: ""}".trimEnd() } ?: stringResource(R.string.seliku_play),
						symbol = "▶",
						hervorgehoben = true,
						modifier = Modifier.focusRequester(start),
						onClick = { n?.let { abspielen.video(context, it) } ?: abspielen.zufall(context, sr) },
					)
					// «Zufällig» nur auf Wunsch (Einstellung); sonst steht es im Menü «⋯ Mehr»
					if (SelikuEinstellungen.zufallKnopf(context)) Knopf(stringResource(R.string.seliku_shuffle), onClick = { abspielen.zufall(context, sr) }, symbol = "⤨")
					val gesehen = sr.userData?.played == true
					Knopf(stringResource(if (gesehen) R.string.seliku_mark_unwatched else R.string.seliku_mark_watched), symbol = if (gesehen) "○" else "✓", onClick = {
						scope.launch { runCatching { daten.gesehen(sr.id, !gesehen) }; neuLaden++ }
					})
					FavoritKnopf(sr) { vm.datenStand++ }
					TrailerKnopf(sr)
					MehrKnopf { vm.menue = sr }
				}
				Spacer(Modifier.height(28.dp))
			}
			if (staffeln.size > 1) item {
				LazyRow(contentPadding = PaddingValues(horizontal = 48.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
					items(staffeln, key = { it.id }) { s ->
						Knopf(staffelName(s), onClick = { staffel = s.id }, aktiv = s.id == staffel, onFokus = { staffel = s.id })
					}
				}
				Spacer(Modifier.height(16.dp))
			}
			item {
				val f = folgen
				if (f == null) Text(stringResource(R.string.seliku_episodes_loading), style = Schrift.Leise, modifier = Modifier.padding(start = 48.dp))
				else {
					Reihe(
						titel = staffelName(staffeln.firstOrNull { it.id == staffel }),
						items = f,
						vm = vm,
						seite = seite,
						onClick = { abspielen.video(context, it) },
						onFokus = { gewaehlteFolge = it },
						// Die Reihe beginnt bei der nächsten Folge
						start = naechste?.let { n -> f.indexOfFirst { it.id == n.id } }?.takeIf { it > 0 },
						kachel = { item, klick, merken, fokus ->
							QuerKachel(item, klick, breite = 270.dp, merken = merken, onFokus = fokus,
								zeilen = folgenTitel(item) to listOfNotNull(laufzeit(item.runTimeTicks), if (item.userData?.played == true) stringResource(R.string.seliku_watched_lower) else null).joinToString(" · "))
						},
					)
					val g = gewaehlteFolge
					if (g != null && f.any { it.id == g.id }) Text(
						g.overview.orEmpty(), style = Schrift.Leise, maxLines = 3, overflow = TextOverflow.Ellipsis,
						modifier = Modifier.padding(start = 48.dp, end = 300.dp).heightIn(min = 54.dp),
					)
				}
			}
			item { Spacer(Modifier.height(12.dp)); GemischteReihe(stringResource(R.string.seliku_similar_series), aehnlich, seerrFehlendeAehnliche(sr, vm, seite), vm, seite, oeffnen) }
		} }
	}
}

/** Film oder einzelnes Video: «Abspielen» hat den Fokus, darunter Ähnliches. */
@Composable
fun TitelSeite(vm: SelikuViewModel, seite: Seite.Titel, oeffnen: (BaseItemDto) -> Unit) {
	val daten = koinInject<SelikuDaten>()
	val bilder = koinInject<Bilder>()
	val abspielen = koinInject<Abspielen>()
	val context = LocalContext.current
	val scope = rememberCoroutineScope()

	var titel by remember { mutableStateOf<BaseItemDto?>(null) }
	var aehnlich by remember { mutableStateOf<List<BaseItemDto>>(emptyList()) }
	var neuLaden by remember { mutableStateOf(0) }
	var fehltTitel by remember { mutableStateOf(false) }
	// «Abspielen» ist der erste Platz der Seite: die Startlogik holt den Fokus dorthin, nicht zu «Ähnliche Titel»
	val start = remember(seite.schluessel) { vm.ersteAnfrageFuer(seite.schluessel, "abspielen") }

	LaunchedEffect(seite.id, neuLaden, vm.datenStand) {
		runCatching {
			val zuerst = titel == null
			titel = daten.item(seite.id)
			if (zuerst && vm.fokus[seite.schluessel] == null) runCatching { start.requestFocus() }
			aehnlich = daten.aehnlich(seite.id)
		}.onFailure { Timber.w(it, "Titel laden"); if (istWeg(it)) fehltTitel = true }
	}

	val t = titel
	Box(Modifier.fillMaxSize()) {
		Kulisse(bilder.hintergrund(t))
		if (fehltTitel) { NichtMehrDa(vm); return@Box }
		if (t == null) { Hinweis(stringResource(R.string.seliku_loading)); return@Box }
		SenkrechtRollen { LazyColumn(state = vm.liste(seite), contentPadding = PaddingValues(bottom = 48.dp), modifier = Modifier.fillMaxSize()) {
			item {
				val fortsetzen = fortsetzenText(t)
				Kopf(t, zusatz = t.people?.filter { it.type?.name == "DIRECTOR" }?.take(2)?.joinToString(", ") { it.name.orEmpty() }?.takeIf { it.isNotBlank() }?.let { stringResource(R.string.seliku_director, it) }, liste = vm.liste(seite)) {
					Knopf(fortsetzen ?: stringResource(R.string.seliku_play), symbol = "▶", hervorgehoben = true, modifier = Modifier.focusRequester(start), onClick = { abspielen.video(context, t) })
					if (fortsetzen != null) Knopf(stringResource(R.string.seliku_from_start), symbol = "↺", onClick = { abspielen.video(context, t, vonVorn = true) })
					val gesehen = t.userData?.played == true
					Knopf(stringResource(if (gesehen) R.string.seliku_mark_unwatched else R.string.seliku_mark_watched), symbol = if (gesehen) "○" else "✓", onClick = {
						scope.launch { runCatching { daten.gesehen(t.id, !gesehen) }; neuLaden++ }
					})
					FavoritKnopf(t) { vm.datenStand++ }
					TrailerKnopf(t)
					MehrKnopf { vm.menue = t }
				}
				Spacer(Modifier.height(32.dp))
			}
			item { GemischteReihe(stringResource(R.string.seliku_similar_titles), aehnlich, seerrFehlendeAehnliche(t, vm, seite), vm, seite, oeffnen) }
		} }
	}
}

/** «6. Titel» – ohne doppelte Nummer, wenn der Dateiname sie schon enthält. */
fun folgenTitel(f: BaseItemDto): String {
	val name = f.name.orEmpty()
	val nr = f.indexNumber ?: return name
	val ohne = name.replace(Regex("^\\s*0*$nr[.)\\-:]?\\s+"), "")
	return "$nr. $ohne"
}

/** Kleiner runder Knopf mit drei senkrechten Punkten: öffnet das Menü zum Titel (wie bei Plex). */
@Composable
fun MehrKnopf(onClick: () -> Unit) {
	Waehlbar(onClick = onClick, vergroessern = false, shape = androidx.compose.foundation.shape.CircleShape) { fokussiert ->
		Box(Modifier.size(44.dp).background(if (fokussiert) Farben.Text else Farben.Flaeche), contentAlignment = Alignment.Center) {
			Text("⋮", style = Schrift.Titel.copy(color = if (fokussiert) androidx.compose.ui.graphics.Color.Black else Farben.Text))
		}
	}
}

/** «Favorit»: Herz gefüllt, wenn der Titel unter den Favoriten ist; eine kurze Meldung sagt, wo man ihn findet. */
@Composable
fun FavoritKnopf(item: BaseItemDto, geaendert: () -> Unit) {
	val daten = koinInject<SelikuDaten>()
	val context = LocalContext.current
	val scope = rememberCoroutineScope()
	var favorit by remember(item.id, item.userData?.isFavorite) { mutableStateOf(item.userData?.isFavorite == true) }
	Knopf(stringResource(R.string.seliku_favorite), symbol = if (favorit) "♥" else "♡", aktiv = favorit, onClick = {
		val neu = !favorit
		favorit = neu
		scope.launch {
			runCatching { daten.favorit(item.id, neu) }
				.onSuccess { favoritMeldung(context, neu); geaendert() }
				.onFailure { favorit = !neu; android.widget.Toast.makeText(context, context.getString(R.string.seliku_failed, it.message?.take(80).toString()), android.widget.Toast.LENGTH_SHORT).show() }
		}
	})
}

/** Trailer-Knopf (nur wenn ein Trailer auf dem Server liegt): spielt ihn mit Ton im Vollbild. */
@Composable
fun TrailerKnopf(item: BaseItemDto) {
	val api = koinInject<org.jellyfin.sdk.api.client.ApiClient>()
	val abspielen = koinInject<Abspielen>()
	val context = androidx.compose.ui.platform.LocalContext.current
	var trailer by remember(item.id) { mutableStateOf<BaseItemDto?>(null) }
	LaunchedEffect(item.id) { trailer = Trailer.eintrag(api, item) }
	val t = trailer ?: return
	Knopf(stringResource(R.string.seliku_trailer), symbol = "▸", onClick = { abspielen.video(context, t) })
}
