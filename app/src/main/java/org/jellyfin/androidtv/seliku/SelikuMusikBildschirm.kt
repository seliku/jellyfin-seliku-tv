package org.jellyfin.androidtv.seliku

import android.view.KeyEvent
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.RelativeLayout
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.delay
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.ScreensaverLock
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.playback.MediaManager
import org.koin.compose.koinInject

/** Was der Bildschirm zeigt, während Musik läuft und niemand drückt. */
enum class MusikBildschirm(val titel: String) {
	NORMAL("Bildschirm normal"),
	DUNKEL("Bildschirm abdunkeln"),
	HINTERGRUENDE("Filmhintergründe zeigen"),
	TRAILER("Trailer zeigen (stumm)"),
}

/**
 * Musikplayer: ein Knopf neben «Album/Interpret» wählt, was nach einer Weile ohne Tastendruck erscheint –
 * nichts, ein dunkler Bildschirm oder eine Diashow aus Filmhintergründen. Die Wahl wird gemerkt.
 * Der Fernseher geht dabei nie schlafen; jede Taste holt den Player zurück (und tut sonst nichts).
 */
object SelikuMusikBildschirm {
	private const val WARTEN_MS = 20_000L

	/** Gewählter Modus, für Musikplayer und Radio gemeinsam (wird beim ersten Zugriff aus den Einstellungen gelesen). */
	private val zustand = mutableStateOf<MusikBildschirm?>(null)
	fun aktuell(c: android.content.Context): MusikBildschirm = zustand.value ?: modus(c).also { zustand.value = it }
	fun weiter(c: android.content.Context): MusikBildschirm {
		val m = MusikBildschirm.entries[(aktuell(c).ordinal + 1) % MusikBildschirm.entries.size]
		zustand.value = m; modus(c, m)
		Toast.makeText(c, m.titel + if (m != MusikBildschirm.NORMAL) " (nach 20 Sekunden ohne Tastendruck)" else "", Toast.LENGTH_SHORT).show()
		return m
	}
	const val WARTEZEIT_MS = 20_000L

	fun modus(c: android.content.Context): MusikBildschirm =
		runCatching { MusikBildschirm.valueOf(c.getSharedPreferences("seliku", 0).getString("musik_bildschirm", null)!!) }.getOrDefault(MusikBildschirm.NORMAL)

	private fun modus(c: android.content.Context, m: MusikBildschirm) =
		c.getSharedPreferences("seliku", 0).edit().putString("musik_bildschirm", m.name).apply()

	@JvmStatic
	fun einrichten(fragment: Fragment, wurzel: ViewGroup, knopfReihe: ViewGroup, vorlage: ImageButton) {
		val context = fragment.requireContext()
		var modus by mutableStateOf(aktuell(context))
		var zeigt by mutableStateOf(false)
		var letzteTaste by mutableStateOf(System.currentTimeMillis())

		// Knopf wie die Nachbarn (gleiche Grösse, gleicher Stil)
		val knopf = ImageButton(context, null, 0, R.style.Button_Icon).apply {
			layoutParams = vorlage.layoutParams
			setImageResource(R.drawable.seliku_bildschirm)
			contentDescription = "Bildschirm"
			onFocusChangeListener = vorlage.onFocusChangeListener
			setOnClickListener { modus = weiter(context) }
		}
		knopfReihe.addView(knopf)

		// Darüber eine Ebene für Dunkel/Diashow
		val ebene = ComposeView(context).apply {
			layoutParams = RelativeLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
			setContent {
				// Jellyfins eigener Bildschirmschoner würde sonst darüberfahren
				ScreensaverLock(enabled = true)
				LaunchedEffect(modus, letzteTaste) {
					zeigt = false
					if (modus == MusikBildschirm.NORMAL) return@LaunchedEffect
					delay(WARTEN_MS)
					zeigt = true
				}
				AnimatedVisibility(zeigt, enter = fadeIn(tween(1500)), exit = fadeOut(tween(400))) {
					Ebene(modus)
				}
			}
		}
		wurzel.addView(ebene)

		// Tasten mitlesen: jede Taste setzt die Wartezeit zurück; ist die Ebene sichtbar, holt sie nur den Player zurück
		val fenster = fragment.requireActivity().window
		val alt = fenster.callback
		val neu = object : Window.Callback by alt {
			// Der Druck, der aufweckt, wird ganz geschluckt (auch sein Loslassen – sonst löst OK den Knopf darunter aus)
			var schlucken = false
			override fun dispatchKeyEvent(event: KeyEvent): Boolean {
				letzteTaste = System.currentTimeMillis()
				if (zeigt && event.action == KeyEvent.ACTION_DOWN) { schlucken = true; return true }
				if (schlucken) { if (event.action == KeyEvent.ACTION_UP) schlucken = false; return true }
				return alt.dispatchKeyEvent(event)
			}
		}
		fragment.viewLifecycleOwner.lifecycle.addObserver(object : DefaultLifecycleObserver {
			override fun onResume(owner: LifecycleOwner) {
				fenster.callback = neu
				fenster.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
				letzteTaste = System.currentTimeMillis()
			}
			override fun onPause(owner: LifecycleOwner) {
				if (fenster.callback === neu) fenster.callback = alt
				fenster.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
				zeigt = false
			}
		})
	}

	@Composable
	fun Ebene(modus: MusikBildschirm) {
		val mediaManager = koinInject<MediaManager>()
		var titel by remember { mutableStateOf("") }
		var wer by remember { mutableStateOf("") }
		var uhr by remember { mutableStateOf("") }
		LaunchedEffect(Unit) {
			while (true) {
				val radio = RadioSpieler.laeuft.value
				val a = mediaManager.currentAudioItem
				titel = radio?.name ?: a?.name.orEmpty()
				wer = if (radio != null) "Radio" else a?.albumArtist ?: a?.artists?.firstOrNull().orEmpty()
				uhr = java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
				delay(2_000)
			}
		}
		Box(Modifier.fillMaxSize().background(Color.Black)) {
			if (modus == MusikBildschirm.HINTERGRUENDE) Diashow()
			if (modus == MusikBildschirm.TRAILER) TrailerSchleife()
			// Titel klein unten links; im Dunkel-Modus kaum sichtbar, damit nichts einbrennt
			Column(Modifier.align(Alignment.BottomStart).padding(start = 64.dp, bottom = 56.dp)) {
				val a = if (modus == MusikBildschirm.DUNKEL) 0.35f else 0.9f
				Text(uhr, style = Schrift.Riesig.copy(color = Color.White.copy(alpha = a)))
				Text(titel, style = Schrift.Titel.copy(color = Color.White.copy(alpha = a)))
				Text(wer, style = Schrift.Normal.copy(color = Color.White.copy(alpha = a * 0.75f)))
			}
		}
	}

	/** Zufällige Trailer nacheinander, stumm; gibt es (noch) keine, bleibt es bei der Diashow. */
	@Composable
	private fun TrailerSchleife() {
		val api = koinInject<org.jellyfin.sdk.api.client.ApiClient>()
		var liste by remember { mutableStateOf<List<String>?>(null) }
		var nr by remember { mutableStateOf(0) }
		LaunchedEffect(Unit) { liste = Trailer.zufaellige(api) }
		val l = liste
		if (l.isNullOrEmpty()) { Diashow(); return }
		androidx.compose.runtime.key(nr) {
			TrailerVideo(l[nr % l.size], Modifier.fillMaxSize(), onEnde = { nr++ })
		}
		// Abdunkeln, damit Uhr und Titel lesbar bleiben
		Box(Modifier.fillMaxSize().background(Color(0x66000000)))
	}
}
