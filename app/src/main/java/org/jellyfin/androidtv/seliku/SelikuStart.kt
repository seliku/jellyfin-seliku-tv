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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.koin.compose.koinInject
import timber.log.Timber

private data class StartReihe(val titel: String, val items: List<BaseItemDto>, val loeschdatum: Map<java.util.UUID, String> = emptyMap())

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

	LaunchedEffect(Unit) {
		// Immer frisch laden (Fortschritt ändert sich nach dem Schauen), alte Reihen bis dahin stehen lassen
		val neu = runCatching {
			val bibs = daten.bibliotheken()
			buildList {
				add(StartReihe("Weiterschauen", daten.weiterschauen()))
				if (bibs.any { it.istLiveTv() }) add(StartReihe("Jetzt im TV", daten.sender().filter { it.currentProgram != null }.take(20)))
				// Sonderreihen wie «Diese Filme werden bald gelöscht» (Maintainerr, gespiegelt als Jellyfin-Sammlung)
				runCatching { daten.startseitenSammlungen() }.getOrDefault(emptyList()).forEach { (name, items, datum) -> add(StartReihe(name, items, datum)) }
				bibs.filter { !it.istLiveTv() }.forEach { bib ->
					add(StartReihe("Neu in ${bib.name}", runCatching { daten.neuIn(bib) }.getOrDefault(emptyList())))
				}
			}.filter { it.items.isNotEmpty() }
		}.onFailure { Timber.w(it, "Startseite laden") }.getOrNull()
		if (neu != null) {
			reihen = neu
			vm.wahl["start-reihen"] = neu
			// Hintergründe für die Anmeldeseite merken (neu hinzugefügte zuerst)
			Kulissen.merken(context, neu.filter { it.titel.startsWith("Neu in") || it.titel == "Weiterschauen" }
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
		if (r == null) { Hinweis("Wird geladen …"); return@Box }
		if (r.isEmpty()) { Hinweis("Noch nichts da."); return@Box }

		Column(Modifier.fillMaxSize()) {
			// Kopf: Titel, Infos, Beschreibung des gewählten Elements
			Column(Modifier.fillMaxWidth().heightIn(min = 190.dp).padding(start = 48.dp, top = 36.dp, end = 360.dp)) {
				val h = hero
				val logo = bilder.logo(h)
				if (logo != null) Bild(logo, Modifier.height(70.dp).width(300.dp), ContentScale.Fit, grund = androidx.compose.ui.graphics.Color.Transparent)
				else Text(h?.hauptzeile().orEmpty(), style = Schrift.Riesig, maxLines = 1, overflow = TextOverflow.Ellipsis)
				Spacer(Modifier.height(8.dp))
				if (h != null) {
					val zeile = if (h.type == BaseItemKind.EPISODE) h.unterzeile() else h.metazeile()
					Text(zeile, style = Schrift.Leise, maxLines = 1)
					Spacer(Modifier.height(8.dp))
					Text((h.currentProgram?.overview ?: h.overview).orEmpty().takeUnless { it.trimStart().startsWith("http") }.orEmpty(), style = Schrift.Normal, maxLines = 3, overflow = TextOverflow.Ellipsis)
				}
			}
			SenkrechtRollen { LazyColumn(
				state = vm.liste(seite),
				contentPadding = PaddingValues(bottom = 40.dp),
				modifier = Modifier.fillMaxSize(),
			) {
				items(r, key = { it.titel }) { reihe ->
					Reihe(
						titel = reihe.titel,
						items = reihe.items,
						vm = vm,
						seite = seite,
						onFokus = { gewaehlt = it },
						onClick = { item ->
							when {
								reihe.titel == "Jetzt im TV" -> abspielen.sender(context, item)
								reihe.titel == "Weiterschauen" -> abspielen.video(context, item)
								item.type == BaseItemKind.EPISODE -> abspielen.video(context, item)
								else -> oeffnen(item)
							}
						},
						kachel = if (reihe.titel == "Weiterschauen") { item, klick, merken, fokus ->
							QuerKachel(item, klick, merken = merken, onFokus = fokus, breite = 280.dp)
						} else if (reihe.titel == "Jetzt im TV") { item, klick, merken, fokus ->
							SenderKachel(item, klick, merken = merken, onFokus = fokus)
						} else if (reihe.loeschdatum.isNotEmpty()) { item, klick, merken, fokus ->
							val tag = reihe.loeschdatum[item.id]?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }
							val wann = tag?.let { "Wird am " + it.format(java.time.format.DateTimeFormatter.ofPattern("d.M.")) + " gelöscht" } ?: "Wird bald gelöscht"
							QuerKachel(item, klick, merken = merken, onFokus = fokus, zeilen = item.name.orEmpty() to wann)
						} else if (reihe.titel.startsWith("Neu in") && reihe.items.none { it.type == BaseItemKind.MUSIC_ALBUM || it.type == BaseItemKind.AUDIO }) { item, klick, merken, fokus ->
							PosterKachel(item, klick, merken = merken, onFokus = fokus)
						} else null,
					)
				}
			} }
		}
	}
}
