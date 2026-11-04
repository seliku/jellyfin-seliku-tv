package org.jellyfin.androidtv.seliku

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
) {
	if (items.isEmpty()) return
	val bilder = koinInject<Bilder>()
	val abspielen = koinInject<Abspielen>()
	val daten = koinInject<SelikuDaten>()
	val context = LocalContext.current
	val scope = androidx.compose.runtime.rememberCoroutineScope()
	var nr by remember { mutableIntStateOf(0) }
	var hatFokus by remember { mutableStateOf(false) }
	val item = items[nr % items.size]
	LaunchedEffect(item.id) { zeigt(item) }
	LaunchedEffect(hatFokus, items) {
		while (!hatFokus && items.size > 1) { delay(9_000); nr = (nr + 1) % items.size }
	}
	// Eigene Karte mit eigenem Bild: der Seitenhintergrund folgt den Reihen, der Banner bleibt für sich
	Box(modifier.fillMaxWidth().height(262.dp).padding(start = 40.dp, end = 40.dp, top = 16.dp)
		.clip(androidx.compose.foundation.shape.RoundedCornerShape(18.dp)).background(Farben.Flaeche)
		.onFocusChanged { hatFokus = it.hasFocus }) {
		Bild(bilder.hintergrund(item, 1280), Modifier.matchParentSize())
		Box(Modifier.matchParentSize().background(androidx.compose.ui.graphics.Brush.horizontalGradient(
			listOf(androidx.compose.ui.graphics.Color(0xF0120D0B), androidx.compose.ui.graphics.Color(0x99120D0B), androidx.compose.ui.graphics.Color.Transparent))))
	Column(Modifier.fillMaxWidth().padding(start = 28.dp, top = 22.dp, end = 340.dp)) {
		val logo = bilder.logo(item)
		if (logo != null) Bild(logo, Modifier.height(76.dp).width(320.dp), ContentScale.Fit, grund = androidx.compose.ui.graphics.Color.Transparent)
		else Text(item.name.orEmpty(), style = Schrift.Riesig, maxLines = 1, overflow = TextOverflow.Ellipsis)
		Spacer(Modifier.height(8.dp))
		Text(item.metazeile(), style = Schrift.Leise, maxLines = 1)
		Spacer(Modifier.height(6.dp))
		Text(item.overview.orEmpty(), style = Schrift.Normal, maxLines = 2, overflow = TextOverflow.Ellipsis)
		Spacer(Modifier.height(12.dp))
		Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
			Knopf("Abspielen", symbol = "▶", hervorgehoben = true,
				modifier = if (fokus != null) Modifier.focusRequester(fokus) else Modifier, onClick = {
				// Serie: nächste ungesehene Folge
				if (item.type == BaseItemKind.SERIES) scope.launch {
					val folge = runCatching { daten.naechsteFolge(item.id) }.getOrNull()
					if (folge != null) abspielen.video(context, folge) else abspielen.zufall(context, item)
				} else abspielen.video(context, item)
			})
			Knopf("Mehr Infos", symbol = "ⓘ", onClick = { oeffnen(item) })
			Spacer(Modifier.width(12.dp))
			// Punkte: welche Empfehlung gerade läuft
			items.indices.forEach { i ->
				Box(Modifier.size(if (i == nr % items.size) 9.dp else 6.dp).background(if (i == nr % items.size) Farben.Akzent else Farben.TextLeise, CircleShape))
			}
		}
	}
	}
}

