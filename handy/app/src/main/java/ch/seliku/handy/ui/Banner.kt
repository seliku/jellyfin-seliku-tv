package ch.seliku.handy.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import ch.seliku.handy.Sitzung
import ch.seliku.handy.daten.Farben
import ch.seliku.handy.daten.SelikuDaten
import ch.seliku.handy.daten.bannerLaden as jellyfinBanner
import ch.seliku.handy.daten.seerrBanner
import ch.seliku.handy.seerr.SeerrDaten
import ch.seliku.handy.seerr.SeerrTitel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind

/** Ein Banner-Platz: eigener Titel oder ein fehlender aus Seerr (zum Anfragen). */
sealed interface BannerEintrag {
	data class Eigen(val item: BaseItemDto) : BannerEintrag
	data class Fehlt(val titel: SeerrTitel) : BannerEintrag
}

/** Banner wie am TV: Empfehlungen nach Sehgewohnheiten, 1–2 fehlende Titel an Stelle 2 und 5 eingestreut. */
suspend fun bannerLaden(c: Context, d: SelikuDaten, bib: BaseItemDto?, seerrTyp: String? = null): List<BannerEintrag> {
	val eigen = jellyfinBanner(c, d, bib).map { BannerEintrag.Eigen(it) }.toMutableList<BannerEintrag>()
	val fehlt = if (bib == null || seerrTyp != null) runCatching { seerrBanner(c, d, SeerrDaten(c, Sitzung.api!!), seerrTyp) }.getOrDefault(emptyList()) else emptyList()
	fehlt.forEachIndexed { i, t -> eigen.add((1 + i * 3).coerceAtMost(eigen.size), BannerEintrag.Fehlt(t)) }
	return eigen
}

@Composable
fun Banner(liste: List<BannerEintrag>) {
	val nav = LocalNav.current
	val c = LocalContext.current
	val scope = rememberCoroutineScope()
	val pager = rememberPagerState { liste.size }
	// Wechsel alle 9 s, nicht während man wischt
	LaunchedEffect(liste) {
		while (true) {
			delay(9_000)
			if (!pager.isScrollInProgress && liste.size > 1) pager.animateScrollToPage((pager.currentPage + 1) % liste.size)
		}
	}
	Box(Modifier.fillMaxWidth().height(500.dp)) {
		HorizontalPager(pager, Modifier.fillMaxSize()) { s ->
			when (val e = liste[s]) {
				is BannerEintrag.Eigen -> BannerSeite(
					bild = Bilder.hintergrund(e.item, 1280) ?: Bilder.poster(e.item, 800), logo = Bilder.logo(e.item), name = e.item.name.orEmpty(),
					meta = metazeile(e.item), text = e.item.overview.orEmpty(), abzeichen = null,
					knopf = "▶  Abspielen", knopfAktion = {
						scope.launch {
							if (e.item.type == BaseItemKind.SERIES) serieAbspielen(nav, c, e.item.id) else abspielen(nav, c, e.item)
						}
					},
					mehr = { oeffnen(nav, c, e.item) },
				)
				is BannerEintrag.Fehlt -> BannerSeite(
					bild = e.titel.hintergrund, logo = null, name = e.titel.titel,
					meta = listOf(e.titel.jahr, if (e.titel.typ == "movie") "Film" else "Serie").filter { it.isNotBlank() }.joinToString("  ·  "),
					text = e.titel.text, abzeichen = "Nicht auf dem Server",
					knopf = "+  Anfragen", knopfAktion = { nav.navigate(Wege.seerr(e.titel.typ, e.titel.tmdb)) },
					mehr = { nav.navigate(Wege.seerr(e.titel.typ, e.titel.tmdb)) },
				)
			}
		}
		// Punkte je Empfehlung
		Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
			repeat(liste.size) { i ->
				Box(Modifier.size(if (i == pager.currentPage) 9.dp else 6.dp).clip(CircleShape)
					.background(if (i == pager.currentPage) Farben.Akzent else Farben.TextLeise.copy(alpha = 0.6f)))
			}
		}
	}
}

@Composable
private fun BannerSeite(bild: String?, logo: String?, name: String, meta: String, text: String, abzeichen: String?, knopf: String, knopfAktion: () -> Unit, mehr: () -> Unit) {
	Box(Modifier.fillMaxSize()) {
		if (bild != null) AsyncImage(bild, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
		Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color(0x66120D0B), 0.35f to Color.Transparent, 0.62f to Color(0x99120D0B), 1f to Farben.Hintergrund)))
		Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
			abzeichen?.let {
				Text(it, color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold,
					modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Farben.Akzent).padding(horizontal = 8.dp, vertical = 3.dp))
				Spacer(Modifier.height(8.dp))
			}
			if (logo != null) AsyncImage(logo, name, Modifier.height(70.dp).widthIn(max = 280.dp), contentScale = ContentScale.Fit)
			else Text(name, fontSize = Schrift.Riesig, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
			Spacer(Modifier.height(8.dp))
			if (meta.isNotBlank()) Text(meta, fontSize = Schrift.Klein, color = Farben.TextLeise, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
			if (text.isNotBlank()) Text(text, fontSize = Schrift.Leise, color = Farben.Text.copy(alpha = 0.85f), maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
			Spacer(Modifier.height(14.dp))
			Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
				Pille(knopf, hervorgehoben = true, onClick = knopfAktion)
				Pille("ⓘ  Mehr Infos", onClick = mehr)
			}
		}
	}
}
