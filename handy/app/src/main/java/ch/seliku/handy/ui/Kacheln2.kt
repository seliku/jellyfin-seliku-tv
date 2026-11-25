package ch.seliku.handy.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import ch.seliku.handy.daten.Farben
import org.jellyfin.sdk.model.api.BaseItemDto
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/** Bild der laufenden Sendung (Thumb, Primary oder Backdrop), sonst Senderlogo. */
fun sendungsBild(s: BaseItemDto): String? {
	val p = s.currentProgram ?: return Bilder.poster(s, 300)
	p.imageTags?.get(org.jellyfin.sdk.model.api.ImageType.THUMB)?.let { return Bilder.item(p.id, "Thumb", 480) }
	p.imageTags?.get(org.jellyfin.sdk.model.api.ImageType.PRIMARY)?.let { return Bilder.item(p.id, "Primary", 480) }
	p.backdropImageTags?.firstOrNull()?.let { return Bilder.item(p.id, "Backdrop", 480) }
	return Bilder.poster(s, 300)
}

fun ortszeit(t: LocalDateTime?): LocalDateTime? = t?.atOffset(ZoneOffset.UTC)?.atZoneSameInstant(ZoneId.systemDefault())?.toLocalDateTime()

fun sendungsAnteil(s: BaseItemDto): Float? {
	val p = s.currentProgram ?: return null
	val a = p.startDate ?: return null; val e = p.endDate ?: return null
	val jetzt = LocalDateTime.now(ZoneOffset.UTC)
	val ges = java.time.Duration.between(a, e).seconds.toFloat(); if (ges <= 0) return null
	return (java.time.Duration.between(a, jetzt).seconds / ges).coerceIn(0f, 1f)
}

/** Senderkachel 16:9 mit Bild der Sendung, Logo unten links und Sendungsfortschritt. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SenderKachel(s: BaseItemDto, breite: Dp = 240.dp, onClick: () -> Unit) {
	Column(Modifier.width(breite).combinedClickable(onClick = onClick)) {
		Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp))) {
			Bild(sendungsBild(s), Modifier.fillMaxSize())
			Box(Modifier.fillMaxSize().background(VerlaufUnten))
			s.imageTags?.get(org.jellyfin.sdk.model.api.ImageType.PRIMARY)?.let {
				AsyncImage(Bilder.item(s.id, "Primary", 160), null, Modifier.align(Alignment.BottomStart).padding(8.dp).size(48.dp), contentScale = ContentScale.Fit)
			}
			sendungsAnteil(s)?.let { Fortschrittsbalken(it, Modifier.align(Alignment.BottomCenter)) }
		}
		Text(s.currentProgram?.name ?: s.name.orEmpty(), fontSize = Schrift.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
		Text(listOfNotNull(s.channelNumber ?: s.number, s.name).joinToString("  "), fontSize = Schrift.Klein, color = Farben.TextLeise, maxLines = 1)
	}
}
