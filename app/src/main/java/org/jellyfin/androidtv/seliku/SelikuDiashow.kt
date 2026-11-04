package org.jellyfin.androidtv.seliku

import android.content.Context
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.ImageLoader
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import org.koin.compose.koinInject

/**
 * Hintergrundbilder für Seiten vor der Anmeldung (Profilauswahl). Vor dem Anmelden darf die App nichts vom Server
 * abfragen; deshalb merkt sie sich bei jeder Nutzung die Hintergründe der zuletzt hinzugefügten Titel.
 */
object Kulissen {
	private const val DATEI = "seliku"
	private const val SCHLUESSEL = "kulissen"

	fun merken(context: Context, urls: List<String>) {
		if (urls.isEmpty()) return
		context.getSharedPreferences(DATEI, Context.MODE_PRIVATE).edit()
			.putString(SCHLUESSEL, urls.distinct().take(30).joinToString("\n")).apply()
	}

	fun laden(context: Context): List<String> =
		context.getSharedPreferences(DATEI, Context.MODE_PRIVATE).getString(SCHLUESSEL, null)
			?.lines()?.filter { it.isNotBlank() }.orEmpty()
}

/** Langsame Diashow aus den gemerkten Hintergründen, abgedunkelt, damit Text und Profile davor lesbar bleiben. */
@Composable
fun Diashow(modifier: Modifier = Modifier) {
	val context = LocalContext.current
	val bilder = remember { Kulissen.laden(context).shuffled() }
	var nr by remember { mutableIntStateOf(0) }
	LaunchedEffect(bilder) {
		while (bilder.size > 1) {
			delay(8_000)
			nr = (nr + 1) % bilder.size
		}
	}
	Box(modifier.fillMaxSize().background(Farben.Hintergrund)) {
		if (bilder.isNotEmpty()) Crossfade(targetState = bilder[nr], animationSpec = tween(1500), label = "Diashow") { url ->
			AsyncImage(
				model = url,
				contentDescription = null,
				imageLoader = koinInject<ImageLoader>(),
				contentScale = ContentScale.Crop,
				modifier = Modifier.fillMaxSize().graphicsLayer { alpha = 0.45f },
			)
		}
		Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0x99000000), Color(0x55000000), Color(0xDD000000)))))
	}
}
