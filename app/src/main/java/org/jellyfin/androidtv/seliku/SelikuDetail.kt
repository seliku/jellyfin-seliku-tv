package org.jellyfin.androidtv.seliku

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

/** Kopfbereich einer Detailseite: Logo oder Titel, Infozeile, Beschreibung, Knöpfe. */
@Composable
private fun Kopf(
	item: BaseItemDto,
	zusatz: String? = null,
	poster: Boolean = true,
	knoepfe: @Composable () -> Unit,
) {
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
			Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { knoepfe() }
		}
	}
}

fun fortsetzenText(item: BaseItemDto): String? {
	val ticks = item.userData?.playbackPositionTicks ?: 0L
	if (ticks <= 0L) return null
	val s = ticks / 10_000_000L
	return "Fortsetzen ab " + if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/** Serie: Kopf mit «Abspielen/Fortsetzen» der nächsten Folge, Staffelwahl, Folgenreihe, Ähnliches. Klick auf eine Folge spielt sie ab. */
@Composable
fun SerienSeite(vm: SelikuViewModel, seite: Seite.Serie, oeffnen: (BaseItemDto) -> Unit) {
	val daten = koinInject<SelikuDaten>()
	val bilder = koinInject<Bilder>()
	val abspielen = koinInject<Abspielen>()
	val context = LocalContext.current
	val scope = rememberCoroutineScope()

	var serie by remember { mutableStateOf<BaseItemDto?>(null) }
	var naechste by remember { mutableStateOf<BaseItemDto?>(null) }
	var staffeln by remember { mutableStateOf<List<BaseItemDto>>(emptyList()) }
	var staffel by remember { mutableStateOf(vm.wahl["${seite.schluessel}|staffel"] as? UUID) }
	var folgen by remember { mutableStateOf<List<BaseItemDto>?>(null) }
	var aehnlich by remember { mutableStateOf<List<BaseItemDto>>(emptyList()) }
	var gewaehlteFolge by remember { mutableStateOf<BaseItemDto?>(null) }
	var neuLaden by remember { mutableStateOf(0) }
	val start = remember { FocusRequester() }

	LaunchedEffect(seite.id, neuLaden) {
		runCatching {
			serie = daten.item(seite.id)
			naechste = daten.naechsteFolge(seite.id)
			staffeln = daten.staffeln(seite.id)
			if (staffel == null || staffeln.none { it.id == staffel }) staffel = naechste?.seasonId ?: staffeln.firstOrNull()?.id
			if (neuLaden == 0 && vm.fokus[seite.schluessel] == null) runCatching { start.requestFocus() }
			aehnlich = daten.aehnlich(seite.id)
		}.onFailure { Timber.w(it, "Serie laden") }
	}
	LaunchedEffect(staffel, neuLaden) {
		val s = staffel ?: return@LaunchedEffect
		vm.wahl["${seite.schluessel}|staffel"] = s
		folgen = runCatching { daten.folgen(seite.id, s) }.getOrDefault(emptyList())
	}

	val sr = serie
	Box(Modifier.fillMaxSize()) {
		Kulisse(bilder.hintergrund(sr))
		if (sr == null) { Hinweis("Wird geladen …"); return@Box }
		SenkrechtRollen { LazyColumn(state = vm.liste(seite), contentPadding = PaddingValues(bottom = 48.dp), modifier = Modifier.fillMaxSize()) {
			item {
				val n = naechste
				val zusatz = n?.let { "Als Nächstes: " + it.unterzeile() }
				Kopf(sr, zusatz) {
					Knopf(
						text = n?.let { fortsetzenText(it) ?: "Abspielen  ${it.parentIndexNumber?.let { s -> "S$s" } ?: ""} ${it.indexNumber?.let { f -> "F$f" } ?: ""}".trimEnd() } ?: "Abspielen",
						symbol = "▶",
						hervorgehoben = true,
						modifier = Modifier.focusRequester(start),
						onClick = { n?.let { abspielen.video(context, it) } ?: abspielen.zufall(context, sr) },
					)
					Knopf("Zufällig", onClick = { abspielen.zufall(context, sr) }, symbol = "⤨")
					val gesehen = sr.userData?.played == true
					Knopf(if (gesehen) "Ungesehen" else "Gesehen", symbol = if (gesehen) "○" else "✓", onClick = {
						scope.launch { runCatching { daten.gesehen(sr.id, !gesehen) }; neuLaden++ }
					})
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
				if (f == null) Text("Folgen werden geladen …", style = Schrift.Leise, modifier = Modifier.padding(start = 48.dp))
				else {
					Reihe(
						titel = staffelName(staffeln.firstOrNull { it.id == staffel }),
						items = f,
						vm = vm,
						seite = seite,
						onClick = { abspielen.video(context, it) },
						onFokus = { gewaehlteFolge = it },
						kachel = { item, klick, merken, fokus ->
							QuerKachel(item, klick, breite = 270.dp, merken = merken, onFokus = fokus,
								zeilen = folgenTitel(item) to listOfNotNull(laufzeit(item.runTimeTicks), if (item.userData?.played == true) "gesehen" else null).joinToString(" · "))
						},
					)
					val g = gewaehlteFolge
					if (g != null && f.any { it.id == g.id }) Text(
						g.overview.orEmpty(), style = Schrift.Leise, maxLines = 3, overflow = TextOverflow.Ellipsis,
						modifier = Modifier.padding(start = 48.dp, end = 300.dp).heightIn(min = 54.dp),
					)
				}
			}
			item { Spacer(Modifier.height(12.dp)); Reihe("Ähnliche Serien", aehnlich, vm, seite, onClick = oeffnen) }
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
	val start = remember { FocusRequester() }

	LaunchedEffect(seite.id, neuLaden) {
		runCatching {
			titel = daten.item(seite.id)
			if (neuLaden == 0 && vm.fokus[seite.schluessel] == null) runCatching { start.requestFocus() }
			aehnlich = daten.aehnlich(seite.id)
		}.onFailure { Timber.w(it, "Titel laden") }
	}

	val t = titel
	Box(Modifier.fillMaxSize()) {
		Kulisse(bilder.hintergrund(t))
		if (t == null) { Hinweis("Wird geladen …"); return@Box }
		SenkrechtRollen { LazyColumn(state = vm.liste(seite), contentPadding = PaddingValues(bottom = 48.dp), modifier = Modifier.fillMaxSize()) {
			item {
				val fortsetzen = fortsetzenText(t)
				Kopf(t, zusatz = t.people?.filter { it.type?.name == "DIRECTOR" }?.take(2)?.joinToString(", ") { it.name.orEmpty() }?.takeIf { it.isNotBlank() }?.let { "Regie: $it" }) {
					Knopf(fortsetzen ?: "Abspielen", symbol = "▶", hervorgehoben = true, modifier = Modifier.focusRequester(start), onClick = { abspielen.video(context, t) })
					if (fortsetzen != null) Knopf("Von vorn", symbol = "↺", onClick = { abspielen.video(context, t, vonVorn = true) })
					val gesehen = t.userData?.played == true
					Knopf(if (gesehen) "Ungesehen" else "Gesehen", symbol = if (gesehen) "○" else "✓", onClick = {
						scope.launch { runCatching { daten.gesehen(t.id, !gesehen) }; neuLaden++ }
					})
				}
				Spacer(Modifier.height(32.dp))
			}
			item { Reihe("Ähnliche Titel", aehnlich, vm, seite, onClick = oeffnen) }
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
