package ch.seliku.handy.ui

import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import ch.seliku.handy.daten.Farben

/** Warmes Seliku-Schema wie auf dem Fernseher: dunkles Braunschwarz, Akzent Bernstein bis Koralle. */
private val schema = darkColorScheme(
	primary = Farben.Akzent,
	onPrimary = Color(0xFF1C120D),
	primaryContainer = Farben.AkzentDunkel,
	onPrimaryContainer = Farben.Text,
	secondary = Color(0xFFFFB347),
	onSecondary = Color(0xFF1C120D),
	background = Farben.Hintergrund,
	onBackground = Farben.Text,
	surface = Farben.Flaeche,
	onSurface = Farben.Text,
	surfaceVariant = Farben.FlaecheHell,
	onSurfaceVariant = Farben.TextLeise,
	surfaceContainer = Farben.Flaeche,
	surfaceContainerHigh = Farben.FlaecheHell,
	surfaceContainerHighest = Farben.FlaecheHell,
	surfaceContainerLow = Farben.Flaeche,
	outline = Farben.TextLeise.copy(alpha = 0.5f),
	error = Color(0xFFFF6B4A),
)

private val schrift = Typography(
	headlineLarge = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold),
	headlineMedium = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold),
	titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold),
	titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
	bodyLarge = TextStyle(fontSize = 16.sp),
	bodyMedium = TextStyle(fontSize = 14.sp),
	labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
)

@Composable
fun SelikuThema(inhalt: @Composable () -> Unit) = MaterialTheme(colorScheme = schema, typography = schrift, content = inhalt)

/** Hintergrund mit warmem Verlauf von oben (wie die TV-Seiten). */
fun Modifier.selikuHintergrund() = background(Brush.verticalGradient(0f to Farben.HintergrundOben, 0.45f to Farben.Hintergrund))

/** Akzent-Verlauf des App-Zeichens (Bernstein → Koralle). */
val AkzentVerlauf = Brush.linearGradient(listOf(Color(0xFFFFB347), Color(0xFFFF6B4A)))
