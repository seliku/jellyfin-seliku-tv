package ch.seliku.handy

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import ch.seliku.handy.ui.Anmeldung
import ch.seliku.handy.ui.Hauptbereich
import ch.seliku.handy.ui.SelikuThema

class MainActivity : ComponentActivity() {
	override fun onCreate(savedInstanceState: Bundle?) {
		enableEdgeToEdge(statusBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), navigationBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
		super.onCreate(savedInstanceState)
		setContent { SelikuThema { androidx.compose.material3.Surface(color = ch.seliku.handy.daten.Farben.Hintergrund, contentColor = ch.seliku.handy.daten.Farben.Text) { Wurzel() } } }
	}
}

@Composable
private fun Wurzel() {
	if (Sitzung.api == null) Anmeldung() else Hauptbereich()
}
