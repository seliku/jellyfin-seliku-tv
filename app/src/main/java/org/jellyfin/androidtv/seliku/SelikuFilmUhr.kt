package org.jellyfin.androidtv.seliku

import android.content.Context
import android.util.AttributeSet
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import org.jellyfin.androidtv.ui.base.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * Uhrzeit während eines Films, an derselben Stelle wie die Uhr der zugeklappten Seitenleiste (unten links),
 * aber ohne Wochentag und wie in Glas geätzt: fast durchsichtiges Weiss mit einem Hauch Schatten,
 * damit sie auf hellen und dunklen Bildern gleich zurückhaltend lesbar bleibt.
 * Abschaltbar («Uhr im Film», Schlüssel uhr_film).
 */
class SelikuFilmUhr @JvmOverloads constructor(
	context: Context,
	attrs: AttributeSet? = null,
	defStyle: Int = 0,
) : AbstractComposeView(context, attrs, defStyle) {
	init {
		isFocusable = false
		isFocusableInTouchMode = false
	}

	@Composable
	override fun Content() {
		if (!SelikuEinstellungen.uhrImFilm(LocalContext.current)) return
		Box(Modifier.fillMaxSize()) {
			GlasUhr(Modifier.align(Alignment.BottomStart).width(64.dp).padding(bottom = 26.dp))
		}
	}
}

@Composable
private fun GlasUhr(modifier: Modifier) {
	val context = LocalContext.current
	var jetzt by remember { mutableStateOf(java.time.LocalTime.now()) }
	LaunchedEffect(Unit) {
		while (true) {
			jetzt = java.time.LocalTime.now()
			delay(60_000L - jetzt.second * 1000L - jetzt.nano / 1_000_000L + 200L)
		}
	}
	val vierundzwanzig = android.text.format.DateFormat.is24HourFormat(context)
	val text = jetzt.format(java.time.format.DateTimeFormatter.ofPattern(if (vierundzwanzig) "HH:mm" else "h:mm"))
	Box(modifier, contentAlignment = Alignment.Center) {
		Text(
			text,
			maxLines = 1,
			softWrap = false,
			style = TextStyle(
				fontSize = 15.sp,
				fontWeight = FontWeight.Bold,
				color = Color.White.copy(alpha = 0.28f),
				shadow = Shadow(Color.Black.copy(alpha = 0.22f), Offset(0f, 1.5f), blurRadius = 3f),
			),
		)
	}
}
