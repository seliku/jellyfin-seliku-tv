package ch.seliku.handy.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import ch.seliku.handy.daten.Farben
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind

/** Schriften wie am Fernseher (Riesig 34, Titel 22, Reihe 17, Normal 14, Leise 13, Klein 12), fürs Handy etwas kleiner. */
object Schrift {
	val Riesig = 28.sp
	val Titel = 20.sp
	val Reihe = 17.sp
	val Normal = 14.sp
	val Leise = 13.sp
	val Klein = 12.sp
}

/** Zweite Zeile unter einer Kachel (wie `unterzeile` am TV). */
fun unterzeile(i: BaseItemDto): String = when (i.type) {
	BaseItemKind.EPISODE -> "S${i.parentIndexNumber ?: 0} · F${i.indexNumber ?: 0}  ${i.name.orEmpty()}"
	BaseItemKind.MUSIC_ALBUM -> listOfNotNull(i.albumArtist, i.productionYear?.toString()).joinToString(" · ")
	BaseItemKind.AUDIO -> i.artists.orEmpty().joinToString(", ")
	BaseItemKind.SERIES -> listOfNotNull(i.productionYear?.toString(), i.childCount?.let { if (it == 1) "1 Staffel" else "$it Staffeln" }).joinToString(" · ")
	BaseItemKind.TV_CHANNEL -> i.currentProgram?.name.orEmpty()
	else -> i.productionYear?.toString().orEmpty()
}

/** Erste Zeile: bei Folgen/Staffeln der Serienname. */
fun hauptzeile(i: BaseItemDto): String = when (i.type) {
	BaseItemKind.EPISODE, BaseItemKind.SEASON -> i.seriesName ?: i.name.orEmpty()
	else -> i.name.orEmpty()
}

fun laufzeit(ticks: Long?): String? {
	val min = ((ticks ?: return null) / 600_000_000L).toInt()
	if (min <= 0) return null
	return if (min >= 60) "${min / 60} Std. ${min % 60} Min." else "$min Min."
}

/** Metazeile wie am TV: eigene Sterne · Jahr · Freigabe · Laufzeit · RT · TMDb · Genres. */
fun metazeile(i: BaseItemDto, mitGenres: Boolean = true): String = listOfNotNull(
	i.userData?.rating?.let { r -> (Math.round(r / 2)).toInt().takeIf { it in 1..5 }?.let { "Du: " + "★".repeat(it) + "☆".repeat(5 - it) } },
	i.productionYear?.toString(),
	i.officialRating,
	if (i.type != BaseItemKind.SERIES) laufzeit(i.runTimeTicks) else null,
	i.criticRating?.let { "🍅 ${it.toInt()} %" },
	i.communityRating?.let { "TMDb %.1f".format(it) },
	if (mitGenres) i.genres?.take(3)?.joinToString(", ")?.ifBlank { null } else null,
).joinToString("  ·  ")

fun fortschritt(i: BaseItemDto): Float? {
	val pos = i.userData?.playbackPositionTicks ?: 0L
	val dauer = i.runTimeTicks ?: 0L
	if (pos <= 0 || dauer <= 0 || i.userData?.played == true) return null
	return (pos.toFloat() / dauer).coerceIn(0.02f, 1f)
}

@Composable
fun Fortschrittsbalken(anteil: Float, modifier: Modifier = Modifier) {
	Box(modifier.fillMaxWidth().height(4.dp).background(Color(0x66000000))) {
		Box(Modifier.fillMaxWidth(anteil).fillMaxHeight().background(Farben.Akzent))
	}
}

/** Abzeichen oben rechts: ungesehene Folgen als Zahl, gesehen als Haken. */
@Composable
fun Abzeichen(i: BaseItemDto, modifier: Modifier = Modifier) {
	val ungesehen = i.userData?.unplayedItemCount ?: 0
	if ((i.type == BaseItemKind.SERIES || i.type == BaseItemKind.SEASON) && ungesehen > 0 && i.userData?.played != true) {
		Box(modifier.padding(6.dp).clip(RoundedCornerShape(10.dp)).background(Farben.Akzent).padding(horizontal = 7.dp, vertical = 2.dp)) {
			Text("$ungesehen", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold)
		}
	} else if (i.userData?.played == true) {
		Box(modifier.padding(6.dp).size(20.dp).clip(CircleShape).background(Farben.Akzent), contentAlignment = Alignment.Center) {
			Text("✓", color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold)
		}
	}
}

@Composable
fun Bild(url: String?, modifier: Modifier = Modifier, scale: ContentScale = ContentScale.Crop) {
	Box(modifier.background(Farben.FlaecheHell)) {
		if (url != null) AsyncImage(url, null, Modifier.fillMaxSize(), contentScale = scale)
	}
}

/** Poster-Kachel (2:3) mit Abzeichen, Fortschritt, Haupt- und Unterzeile. Langes Drücken öffnet das Titelmenü. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PosterKachel(i: BaseItemDto, breite: Dp = 112.dp, quadrat: Boolean = false, rund: Boolean = false, onClick: () -> Unit) {
	val menue = LocalTitelMenue.current
	Column(Modifier.width(breite).combinedClickable(onClick = onClick, onLongClick = { menue(i) })) {
		Box(Modifier.fillMaxWidth().aspectRatio(if (quadrat || rund) 1f else 2f / 3f).clip(if (rund) CircleShape else RoundedCornerShape(8.dp))) {
			Bild(Bilder.poster(i, 300), Modifier.fillMaxSize())
			Abzeichen(i, Modifier.align(Alignment.TopEnd))
			fortschritt(i)?.let { Fortschrittsbalken(it, Modifier.align(Alignment.BottomCenter)) }
		}
		Text(hauptzeile(i), fontSize = Schrift.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
		val u = unterzeile(i)
		if (u.isNotBlank()) Text(u, fontSize = Schrift.Klein, color = Farben.TextLeise, maxLines = 1, overflow = TextOverflow.Ellipsis)
	}
}

/** Querkachel (16:9): Folgen, Weiterschauen, Sender. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun QuerKachel(i: BaseItemDto, breite: Dp = 240.dp, zeile1: String = hauptzeile(i), zeile2: String = unterzeile(i), bildUrl: String? = Bilder.quer(i, 480), onClick: () -> Unit) {
	val menue = LocalTitelMenue.current
	Column(Modifier.width(breite).combinedClickable(onClick = onClick, onLongClick = { menue(i) })) {
		Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp))) {
			Bild(bildUrl, Modifier.fillMaxSize())
			Abzeichen(i, Modifier.align(Alignment.TopEnd))
			fortschritt(i)?.let { Fortschrittsbalken(it, Modifier.align(Alignment.BottomCenter)) }
		}
		if (zeile1.isNotBlank()) Text(zeile1, fontSize = Schrift.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
		if (zeile2.isNotBlank()) Text(zeile2, fontSize = Schrift.Klein, color = Farben.TextLeise, maxLines = 1, overflow = TextOverflow.Ellipsis)
	}
}

@Composable
fun ReihenTitel(titel: String, modifier: Modifier = Modifier, mehr: (() -> Unit)? = null) {
	Row(modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 18.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
		Text(titel, fontSize = Schrift.Reihe, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
		if (mehr != null) TextButton(mehr) { Text("Alle ›", color = Farben.Akzent) }
	}
}

/** Waagrechte Reihe von Kacheln mit Überschrift. */
@Composable
fun <T> Reihe(titel: String, liste: List<T>, mehr: (() -> Unit)? = null, schluessel: ((T) -> Any)? = null, kachel: @Composable (T) -> Unit) {
	if (liste.isEmpty()) return
	Column {
		ReihenTitel(titel, mehr = mehr)
		LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
			items(liste, key = schluessel) { kachel(it) }
		}
	}
}

/** Pillen-Knopf wie am TV (Ecken 22 dp); hervorgehoben = Akzent. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Pille(text: String, hervorgehoben: Boolean = false, aktiv: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
	val farbe = when { hervorgehoben -> Farben.Akzent; aktiv -> Farben.FlaecheHell; else -> Farben.Flaeche }
	Box(modifier.clip(RoundedCornerShape(22.dp)).background(farbe).combinedClickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 10.dp)) {
		Text(text, color = if (hervorgehoben) Color.Black else Farben.Text, fontSize = Schrift.Normal, fontWeight = if (aktiv || hervorgehoben) FontWeight.Bold else FontWeight.Normal)
	}
}

@Composable
fun Laden(modifier: Modifier = Modifier) {
	Box(modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Farben.Akzent) }
}

@Composable
fun Hinweis(text: String, modifier: Modifier = Modifier) {
	Box(modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { Text(text, color = Farben.TextLeise) }
}

/** Kopf einer Unterseite mit Zurück. */
@Composable
fun SeitenKopf(titel: String, zurueck: (() -> Unit)?, rechts: @Composable RowScope.() -> Unit = {}) {
	Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
		if (zurueck != null) IconButton(zurueck) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück") }
		else Spacer(Modifier.width(12.dp))
		Text(titel, fontSize = Schrift.Titel, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
		rechts()
	}
}

val VerlaufUnten = Brush.verticalGradient(listOf(Color.Transparent, Color(0x22000000), Color(0xE0000000)))
