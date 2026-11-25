package ch.seliku.handy.ui

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import ch.seliku.handy.daten.Farben
import org.json.JSONObject

/**
 * SSO-Anmeldung über das Jellyfin-SSO-Plugin: Die Seite des Plugins läuft in einem Browserfenster. Am Ende legt sie das
 * Jellyfin-Token in den Browser-Speicher (jellyfin_credentials) und springt zur Weboberfläche – dort holen wir es ab.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SsoFenster(server: String, anbieter: String, fertig: (String?) -> Unit) {
	BackHandler { fertig(null) }
	DisposableEffect(Unit) {
		// Frischer Start: alte Web-Anmeldung im Browserfenster vergessen, sonst würde ein früheres Token übernommen.
		WebStorage.getInstance().deleteAllData(); CookieManager.getInstance().removeAllCookies(null)
		onDispose { WebStorage.getInstance().deleteAllData(); CookieManager.getInstance().removeAllCookies(null) }
	}
	Column(Modifier.fillMaxSize().background(Farben.Hintergrund).statusBarsPadding()) {
		TextButton(onClick = { fertig(null) }, modifier = Modifier.padding(4.dp)) { Text("Abbrechen", color = Farben.Akzent) }
		Box(Modifier.fillMaxWidth().weight(1f)) {
			AndroidView(factory = { ctx ->
				WebView(ctx).apply {
					// Feste Grösse statt «wrap_content»: sonst ist 100vh im Fenster 0 und Anmeldeseiten bleiben leer.
					layoutParams = android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
					settings.javaScriptEnabled = true
					settings.domStorageEnabled = true
					// Manche Anmeldeseiten behandeln eingebettete Browser anders: als normaler Mobil-Browser auftreten.
					settings.userAgentString = settings.userAgentString.replace("; wv)", ")")
					var erledigt = false
					webChromeClient = object : android.webkit.WebChromeClient() {
						override fun onConsoleMessage(m: android.webkit.ConsoleMessage): Boolean {
							ch.seliku.handy.daten.Timber.d("SSO-Konsole: %s", m.message()); return true
						}
					}
					webViewClient = object : WebViewClient() {
						override fun onReceivedError(view: WebView, r: android.webkit.WebResourceRequest, e: android.webkit.WebResourceError) {
							if (r.isForMainFrame) ch.seliku.handy.daten.Timber.w("SSO-Fehler: %s %s", e.errorCode, e.description)
						}
						override fun onPageFinished(view: WebView, url: String) {
							ch.seliku.handy.daten.Timber.d("SSO-Seite: %s", Uri.parse(url).path)
							val pfad = Uri.parse(url).path.orEmpty()
							if (erledigt || !pfad.startsWith("/web/")) return
							// Die Plugin-Seite hat jetzt das Token abgelegt (Servers[0].AccessToken).
							view.evaluateJavascript("localStorage.getItem('jellyfin_credentials')") { roh ->
								val token = runCatching {
									val json = JSONObject(JSONObject("{\"v\":$roh}").getString("v"))
									json.getJSONArray("Servers").getJSONObject(0).optString("AccessToken").takeIf { it.isNotBlank() }
								}.getOrNull()
								if (token != null && !erledigt) { erledigt = true; fertig(token) }
							}
						}
					}
					// Erst laden, wenn das Fenster seine Grösse hat: sonst baut die Anmeldeseite ihr Formular für 0×0 auf und bleibt leer.
					var geladen = false
					addOnLayoutChangeListener { v, l, t, r, b, _, _, _, _ ->
						if (!geladen && r - l > 0 && b - t > 0) { geladen = true; v.post { loadUrl("$server/sso/OID/start/$anbieter") } }
					}
				}
			}, modifier = Modifier.fillMaxSize())
		}
	}
}
