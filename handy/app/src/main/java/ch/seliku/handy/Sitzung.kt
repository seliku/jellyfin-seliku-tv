package ch.seliku.handy

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ch.seliku.handy.daten.SelikuDaten
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.Jellyfin
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.quickConnectApi
import org.jellyfin.sdk.api.client.extensions.systemApi
import org.jellyfin.sdk.api.client.extensions.userApi
import org.jellyfin.sdk.createJellyfin
import org.jellyfin.sdk.model.ClientInfo
import org.jellyfin.sdk.model.api.AuthenticateUserByName
import org.jellyfin.sdk.model.api.QuickConnectDto
import java.util.UUID

/**
 * Anmeldung am Jellyfin-Server. Server, Token und Benutzer bleiben gespeichert. Nichts ist fest eingebaut: die
 * Server-Adresse wird eingegeben, damit die App für alle funktioniert.
 */
object Sitzung {
	private lateinit var prefs: SharedPreferences
	lateinit var jellyfin: Jellyfin; private set
	var api: ApiClient? by mutableStateOf(null); private set
	var benutzerId: UUID? = null; private set
	var benutzerName: String = ""; private set
	val daten: SelikuDaten get() = SelikuDaten(api!!)

	fun start(c: Context) {
		prefs = c.getSharedPreferences("sitzung", Context.MODE_PRIVATE)
		jellyfin = createJellyfin {
			clientInfo = ClientInfo("Jellyfin Seliku Handy", BuildConfig.VERSION_NAME)
			context = c.applicationContext
		}
		val server = prefs.getString("server", null)
		val token = prefs.getString("token", null)
		if (server != null && token != null) {
			api = jellyfin.createApi(baseUrl = server, accessToken = token)
			benutzerId = prefs.getString("benutzer", null)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
			benutzerName = prefs.getString("name", "").orEmpty()
		}
	}

	val server: String get() = prefs.getString("server", null).orEmpty()
	val token: String? get() = api?.accessToken
	val geraeteId: String get() = api?.deviceInfo?.id ?: "seliku-handy"

	/** Prüft die Adresse (mit und ohne https://) und gibt die funktionierende zurück. */
	suspend fun serverPruefen(eingabe: String): String = withContext(Dispatchers.IO) {
		val roh = eingabe.trim().trimEnd('/')
		val kandidaten = if (roh.startsWith("http")) listOf(roh) else listOf("https://$roh", "http://$roh", "http://$roh:8096")
		for (k in kandidaten) {
			val ok = runCatching { jellyfin.createApi(baseUrl = k).systemApi.getPublicSystemInfo().content.id != null }.getOrDefault(false)
			if (ok) return@withContext k
		}
		error("Server nicht erreichbar")
	}

	suspend fun anmelden(server: String, name: String, passwort: String) = withContext(Dispatchers.IO) {
		val a = jellyfin.createApi(baseUrl = server)
		val r = a.userApi.authenticateUserByName(AuthenticateUserByName(username = name, pw = passwort)).content
		speichern(server, r.accessToken!!, r.user?.id, r.user?.name ?: name)
	}

	/** Quick Connect: Code anzeigen, auf Bestätigung in einer anderen App warten (für SSO-Konten ohne Passwort). */
	suspend fun quickConnect(server: String, code: (String) -> Unit) = withContext(Dispatchers.IO) {
		val a = jellyfin.createApi(baseUrl = server)
		val start = a.quickConnectApi.initiateQuickConnect().content
		withContext(Dispatchers.Main) { code(start.code) }
		while (true) {
			delay(3000)
			val s = a.quickConnectApi.getQuickConnectState(start.secret).content
			if (s.authenticated) break
		}
		val r = a.userApi.authenticateWithQuickConnect(QuickConnectDto(secret = start.secret)).content
		speichern(server, r.accessToken!!, r.user?.id, r.user?.name.orEmpty())
	}

	/** SSO-Knopf des Servers (SSO-Plugin), aus dem Anmeldehinweis gelesen: Name des Anbieters und Beschriftung. */
	suspend fun ssoAnbieter(server: String): Pair<String, String>? = withContext(Dispatchers.IO) {
		runCatching {
			val text = java.net.URL("$server/Branding/Configuration").readText()
			val hinweis = org.json.JSONObject(text).optString("LoginDisclaimer")
			val name = Regex("""/sso/OID/(?:start|p)/([A-Za-z0-9_.-]+)""").find(hinweis)?.groupValues?.get(1) ?: return@runCatching null
			val knopf = Regex("""<button[^>]*>([^<]+)</button>""").find(hinweis)?.groupValues?.get(1)?.trim()
			name to (knopf ?: "Mit SSO anmelden")
		}.getOrNull()
	}

	/** Nach der SSO-Anmeldung im Browserfenster: Token übernehmen und den Benutzer abfragen. */
	suspend fun ssoUebernehmen(server: String, token: String) = withContext(Dispatchers.IO) {
		val ich = jellyfin.createApi(baseUrl = server, accessToken = token).userApi.getCurrentUser().content
		speichern(server, token, ich.id, ich.name.orEmpty())
	}

	private suspend fun speichern(server: String, token: String, id: UUID?, name: String) = withContext(Dispatchers.Main) {
		prefs.edit().putString("server", server).putString("token", token).putString("benutzer", id?.toString())
			.putString("name", name).apply()
		benutzerId = id; benutzerName = name
		api = jellyfin.createApi(baseUrl = server, accessToken = token)
	}

	fun abmelden() {
		prefs.edit().remove("token").remove("benutzer").remove("name").apply()
		api = null; benutzerId = null
	}
}
