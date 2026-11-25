package ch.seliku.handy.wiedergabe

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Glas-Uhr wie am Fernseher (S 21.11.): nur Uhrzeit, fast durchsichtig, «kaum sichtbar, aber doch sichtbar». */
@Composable
fun GlasUhr(modifier: Modifier = Modifier) {
	val c = LocalContext.current
	val muster = if (android.text.format.DateFormat.is24HourFormat(c)) "HH:mm" else "h:mm"
	var zeit by remember { mutableStateOf("") }
	LaunchedEffect(Unit) {
		while (true) {
			zeit = SimpleDateFormat(muster, Locale.getDefault()).format(Date())
			delay(60_000 - System.currentTimeMillis() % 60_000)
		}
	}
	Text(zeit, modifier, style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.28f),
		shadow = Shadow(Color.Black.copy(alpha = 0.22f), Offset(0f, 1.5f), 3f)))
}
