package org.jellyfin.androidtv.seliku

import androidx.compose.ui.graphics.graphicsLayer

import androidx.compose.runtime.key

import androidx.compose.foundation.layout.wrapContentWidth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
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
import androidx.compose.ui.res.stringResource
import org.jellyfin.androidtv.R

/**
 * Grosser Banner oben (wie bei den Bezahldiensten): persönliche Empfehlungen, die man noch nicht gesehen hat.
 * Wechselt alle 9 Sekunden, solange der Fokus nicht auf den Knöpfen liegt. [zeigt] meldet das gerade gezeigte
 * Element, damit die Seite den Hintergrund dazu setzt.
 */
@Composable
fun Banner(
	items: List<BaseItemDto>,
	oeffnen: (BaseItemDto) -> Unit,
	zeigt: (BaseItemDto) -> Unit,
	modifier: Modifier = Modifier,
	fokus: androidx.compose.ui.focus.FocusRequester? = null,
	seerr: List<SeerrTitel> = emptyList(),
	seerrOeffnen: (SeerrTitel) -> Unit = {},
) {
	// Seliku: Titel aus Seerr (nicht auf dem Server) an Stelle 2 und 5 einstreuen
	val alle: List<Any> = remember(items, seerr) {
		items.toMutableList<Any>().apply { seerr.forEachIndexed { i, t -> add(minOf(size, 1 + i * 3), t) } }
	}
	if (alle.isEmpty()) return
	val bilder = koinInject<Bilder>()
	val abspielen = koinInject<Abspielen>()
	val daten = koinInject<SelikuDaten>()
	val context = LocalContext.current
	val scope = androidx.compose.runtime.rememberCoroutineScope()
	var nr by remember { mutableIntStateOf(0) }
	var hatFokus by remember { mutableStateOf(false) }
	val ersatz = items.firstOrNull() ?: BaseItemDto(id = java.util.UUID(0, 0), type = BaseItemKind.MOVIE)
	fun hintergrundVon(e: Any): String? = (e as? SeerrTitel)?.hintergrund ?: (e as? BaseItemDto)?.let { bilder.hintergrund(it, 1280) }
	// Weich überblenden: das nächste Bild erst laden, dann Bild und Text gemeinsam in einer Sekunde überblenden
	// (sonst springt der Banner, oder er wird kurz dunkel, solange das Bild noch lädt)
	val gewaehlt = alle[nr % alle.size]
	val bildLader = koinInject<coil3.ImageLoader>()
	var eintrag by remember { mutableStateOf(gewaehlt) }
	LaunchedEffect(gewaehlt) {
		if (gewaehlt == eintrag) return@LaunchedEffect
		hintergrundVon(gewaehlt)?.let { url ->
			runCatching { kotlinx.coroutines.withTimeoutOrNull(3_000) { bildLader.execute(coil3.request.ImageRequest.Builder(context).data(url).build()) } }
		}
		eintrag = gewaehlt
	}
	val fremd = eintrag as? SeerrTitel
	val item = (eintrag as? BaseItemDto) ?: ersatz
	LaunchedEffect(eintrag) { if (fremd == null) zeigt(item) }
	// Nach 3 Sekunden stumm den Trailer zeigen (falls einer auf dem Server liegt); solange er läuft, wird nicht weitergeblättert
	val api = koinInject<org.jellyfin.sdk.api.client.ApiClient>()
	var trailer by remember { mutableStateOf<String?>(null) }
	var trailerBereit by remember { mutableStateOf(false) }
	// Der Trailer läuft nur, solange der Fokus im Banner steht (ein paar Sekunden nach dem Hineingehen)
	LaunchedEffect(eintrag, hatFokus) {
		trailer = null; trailerBereit = false
		if (fremd != null || !hatFokus || !SelikuEinstellungen.trailerImBanner(context)) return@LaunchedEffect
		delay(2_000)
		trailer = Trailer.adresse(api, item, SelikuEinstellungen.ausschnittOhneTrailer(context))
	}
	val trailerSicht by androidx.compose.animation.core.animateFloatAsState(if (trailerBereit) 1f else 0f, androidx.compose.animation.core.tween(900), label = "trailer")
	// Läuft der Trailer, verschwindet die Schrift nach ein paar Sekunden; jede Taste holt sie zurück
	var tasten by remember { mutableIntStateOf(0) }
	var schrift by remember { mutableStateOf(true) }
	LaunchedEffect(trailerBereit, tasten) {
		schrift = true
		if (trailerBereit) { delay(4_000); schrift = false }
	}
	val schriftSicht by androidx.compose.animation.core.animateFloatAsState(if (schrift) 1f else 0f, androidx.compose.animation.core.tween(700), label = "schrift")
	// Fester Takt: alle 9 s die nächste Empfehlung, aber nur, solange man nicht im Banner ist
	LaunchedEffect(alle) {
		var sekunden = 0
		while (alle.size > 1) {
			delay(1_000)
			if (hatFokus || trailer != null) { sekunden = 0; continue }
			if (++sekunden >= 9) { sekunden = 0; nr = (nr + 1) % alle.size }
		}
	}
	// Eigene Karte mit eigenem Bild: der Seitenhintergrund folgt den Reihen, der Banner bleibt für sich
	Box(modifier.fillMaxWidth().height(290.dp).padding(start = 40.dp, end = 40.dp, top = 16.dp, bottom = 28.dp)
		.clip(androidx.compose.foundation.shape.RoundedCornerShape(18.dp)).background(Farben.Flaeche)
		.onFocusChanged { hatFokus = it.hasFocus }
		.onPreviewKeyEvent { if (it.type == androidx.compose.ui.input.key.KeyEventType.KeyDown) tasten++; false }) {
		androidx.compose.animation.Crossfade(eintrag, Modifier.matchParentSize(), androidx.compose.animation.core.tween(1_000), label = "bannerbild") { e ->
			Bild(hintergrundVon(e), Modifier.fillMaxSize())
		}
		trailer?.let { url ->
			key(url) {
				TrailerVideo(url, Modifier.matchParentSize().graphicsLayer { alpha = trailerSicht }, mitTon = true, onBereit = { trailerBereit = true },
					onEnde = { trailer = null; trailerBereit = false; if (alle.size > 1) nr = (nr + 1) % alle.size },
					onWeg = { trailer = null; trailerBereit = false })
			}
		}
		Box(Modifier.matchParentSize().graphicsLayer { alpha = 0.25f + 0.75f * schriftSicht }.background(androidx.compose.ui.graphics.Brush.horizontalGradient(
			listOf(androidx.compose.ui.graphics.Color(0xF0120D0B), androidx.compose.ui.graphics.Color(0x99120D0B), androidx.compose.ui.graphics.Color.Transparent))))
	Column(Modifier.fillMaxWidth().padding(start = 28.dp, top = 22.dp, end = 340.dp).graphicsLayer { alpha = schriftSicht }) {
		// Text: erst der alte weg, dann der neue (übereinander wäre er unlesbar)
		androidx.compose.animation.AnimatedContent(eintrag, transitionSpec = {
			androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(500, delayMillis = 350)) togetherWith
				androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(300))
		}, label = "bannertext") { e ->
		val f = e as? SeerrTitel
		val it2 = (e as? BaseItemDto) ?: ersatz
		Column {
			val logo = if (f == null) bilder.logo(it2) else null
			if (logo != null) Bild(logo, Modifier.height(76.dp).width(320.dp), ContentScale.Fit, grund = androidx.compose.ui.graphics.Color.Transparent, ausrichtung = androidx.compose.ui.Alignment.CenterStart)
			else Text(f?.titel ?: it2.name.orEmpty(), style = Schrift.Riesig, maxLines = 1, overflow = TextOverflow.Ellipsis)
			Spacer(Modifier.height(8.dp))
			// Gesehen: Häkchen vor der Infozeile (wie auf den Postern)
			Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
				if (f != null) {
					// Seliku: klar erkennbar, dass es den Titel (noch) nicht gibt
					Text(stringResource(R.string.seliku_banner_not_on_server), style = Schrift.Klein.copy(color = androidx.compose.ui.graphics.Color.Black),
						modifier = Modifier.padding(end = 10.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(5.dp)).background(Farben.Akzent).padding(horizontal = 8.dp, vertical = 2.dp))
					Text(listOf(f.jahr, stringResource(if (f.typ == "movie") R.string.seliku_banner_movie else R.string.seliku_banner_series))
						.filter { it.isNotBlank() }.joinToString("   ·   "), style = Schrift.Leise, maxLines = 1)
				} else {
					if (it2.userData?.played == true) Abzeichen(it2, Modifier.padding(end = 4.dp))
					Text(it2.metazeile(), style = Schrift.Leise, maxLines = 1)
				}
			}
			Spacer(Modifier.height(6.dp))
			Text(f?.text ?: it2.overview.orEmpty(), style = Schrift.Normal, maxLines = 2, overflow = TextOverflow.Ellipsis)
		}
		}
		Spacer(Modifier.height(12.dp))
		// Die Knopfreihe darf über den Textrand hinaus: sonst bekommen hintere Punkte keinen Platz, bleiben aber
		// anwählbar – unsichtbar, und der Fokus verschwindet darin (passierte beim Linksdrücken vom ersten Poster)
		Row(Modifier.wrapContentWidth(androidx.compose.ui.Alignment.Start, unbounded = true),
			horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
			if (fremd != null) Knopf(stringResource(R.string.seliku_seerr_request), symbol = "+", hervorgehoben = true,
				modifier = if (fokus != null) Modifier.focusRequester(fokus) else Modifier, onClick = { seerrOeffnen(fremd) })
			else Knopf(stringResource(R.string.seliku_play), symbol = "▶", hervorgehoben = true,
				modifier = if (fokus != null) Modifier.focusRequester(fokus) else Modifier, onClick = {
				// Serie: nächste ungesehene Folge
				if (item.type == BaseItemKind.SERIES) scope.launch {
					val folge = runCatching { daten.naechsteFolge(item.id) }.getOrNull()
					if (folge != null) abspielen.video(context, folge) else abspielen.zufall(context, item)
				} else abspielen.video(context, item)
			})
			Knopf(stringResource(R.string.seliku_more_info), symbol = "ⓘ", onClick = { if (fremd != null) seerrOeffnen(fremd) else oeffnen(item) })
			Spacer(Modifier.width(12.dp))
			// Punkte: welche Empfehlung gerade läuft – anwählbar, Links/Rechts blättert durch die Empfehlungen
			alle.indices.forEach { i ->
				val aktiv = i == nr % alle.size
				Waehlbar(onClick = { nr = i }, vergroessern = false, shape = CircleShape, onFokus = { nr = i }) { fokussiert ->
					Box(Modifier.size(22.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
						Box(Modifier.size(if (fokussiert) 12.dp else if (aktiv) 9.dp else 6.dp)
							.background(if (fokussiert || aktiv) Farben.Akzent else Farben.TextLeise, CircleShape))
					}
				}
			}
		}
	}
	}
}

