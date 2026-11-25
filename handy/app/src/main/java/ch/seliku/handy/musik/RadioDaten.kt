package ch.seliku.handy.musik

import ch.seliku.handy.daten.Timber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.displayPreferencesApi
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

// Aus der TV-App übernommen (SelikuRadio.kt): Sender aus radio-browser.info, Favoriten im Konto («seliku-radio»).
/** Ein Radiosender aus dem freien Verzeichnis radio-browser.info. */
data class RadioSender(val id: String, val name: String, val url: String, val logo: String?, val land: String, val tags: String) {
	fun alsJson() = JSONObject().put("id", id).put("name", name).put("url", url).put("logo", logo ?: "").put("land", land).put("tags", tags)

	companion object {
		fun ausJson(o: JSONObject) = RadioSender(
			id = o.optString("stationuuid", o.optString("id")),
			name = o.optString("name").trim(),
			url = o.optString("url_resolved").ifBlank { o.optString("url") },
			logo = o.optString("favicon", o.optString("logo")).takeIf { it.startsWith("http") },
			land = o.optString("countrycode", o.optString("land")),
			tags = o.optString("tags"),
		)
	}
}

/** Abfragen an radio-browser.info (ohne Anmeldung) und Favoriten im Jellyfin-Konto (gelten auf allen Geräten). */
class RadioDaten(private val api: ApiClient) {
	private val server = "https://de1.api.radio-browser.info/json/"

	private suspend fun holen(pfad: String): List<RadioSender> = withContext(Dispatchers.IO) {
		val v = URL(server + pfad).openConnection() as HttpURLConnection
		v.setRequestProperty("User-Agent", "Seliku-Handy/1.0")
		v.connectTimeout = 10_000; v.readTimeout = 15_000
		val a = JSONArray(v.inputStream.bufferedReader().use { it.readText() })
		(0 until a.length()).map { RadioSender.ausJson(a.getJSONObject(it)) }.filter { it.url.isNotBlank() && it.name.isNotBlank() }
	}

	suspend fun beliebtIn(land: String, anzahl: Int = 40) =
		holen("stations/bycountrycodeexact/$land?order=clickcount&reverse=true&hidebroken=true&limit=$anzahl")

	suspend fun nachGenre(tag: String, anzahl: Int = 30) =
		holen("stations/bytagexact/${URLEncoder.encode(tag, "UTF-8")}?order=clickcount&reverse=true&hidebroken=true&limit=$anzahl")

	suspend fun suche(text: String) =
		holen("stations/search?name=${URLEncoder.encode(text, "UTF-8")}&order=clickcount&reverse=true&hidebroken=true&limit=40")

	// Favoriten: in den Anzeige-Einstellungen des Jellyfin-Kontos (CustomPrefs), damit sie auf jedem Gerät da sind
	private val praefId = "seliku-radio"
	private val client = "seliku"

	suspend fun favoriten(): List<RadioSender> = withContext(Dispatchers.IO) {
		runCatching {
			val json = api.displayPreferencesApi.getDisplayPreferences(displayPreferencesId = praefId, client = client).content.customPrefs["favoriten"]
			if (json.isNullOrBlank()) emptyList() else JSONArray(json).let { a -> (0 until a.length()).map { RadioSender.ausJson(a.getJSONObject(it)) } }
		}.onFailure { Timber.w(it, "Radio-Favoriten laden") }.getOrDefault(emptyList())
	}

	suspend fun favoritenSetzen(liste: List<RadioSender>) = withContext(Dispatchers.IO) {
		val alt = api.displayPreferencesApi.getDisplayPreferences(displayPreferencesId = praefId, client = client).content
		val json = JSONArray().apply { liste.forEach { put(it.alsJson()) } }.toString()
		api.displayPreferencesApi.updateDisplayPreferences(displayPreferencesId = praefId, client = client,
			data = alt.copy(customPrefs = alt.customPrefs + ("favoriten" to json)))
	}
}

