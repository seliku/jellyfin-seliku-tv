package org.jellyfin.androidtv.seliku

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.focus.onFocusChanged
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemFields
import org.json.JSONArray
import org.json.JSONObject
import org.koin.compose.koinInject
import timber.log.Timber
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Duration
import java.time.Instant

// ───────────────────────── Verbindung ─────────────────────────

/**
 * Verbindung zu einem Seerr (Overseerr/Jellyseerr). Jeder verbindet sein eigenes – die App geht an die Community.
 * Gespeichert wird nur Adresse, Sitzungs-Cookie und Anzeigename, kein Passwort.
 */
object SeerrVerbindung {
	private fun p(c: Context) = c.getSharedPreferences("seliku", 0)
	fun adresse(c: Context) = p(c).getString("seerr_url", "").orEmpty()
	fun cookie(c: Context) = p(c).getString("seerr_cookie", "").orEmpty()
	fun name(c: Context) = p(c).getString("seerr_name", "").orEmpty()
	/**
	 * Über den Jellyfin-Server vermittelt (Weg /seerr, Anmeldung mit dem Jellyfin-Token): dann braucht es keine
	 * eigene Seerr-Anmeldung. Wird beim Start geprüft; die eigene Adresse bleibt für Server ohne Vermittler.
	 */
	val vermittelt = androidx.compose.runtime.mutableStateOf(false)
	/** Über die öffentliche Adresse /seerr oder direkt Port 8097 im Heimnetz. */
	@Volatile var vermittlerAdresse: String? = null
	fun verbunden(c: Context) = vermittelt.value || (adresse(c).isNotBlank() && cookie(c).isNotBlank())
	fun speichern(c: Context, url: String, cookie: String, name: String) =
		p(c).edit().putString("seerr_url", url).putString("seerr_cookie", cookie).putString("seerr_name", name).apply()
	fun trennen(c: Context) = p(c).edit().remove("seerr_cookie").remove("seerr_name").apply()
	fun geraetId(c: Context): String = p(c).getString("seerr_geraet", null) ?: java.util.UUID.randomUUID().toString().also { p(c).edit().putString("seerr_geraet", it).apply() }

	/** «wunsch.example.ch» → «https://wunsch.example.ch»; Schrägstrich am Ende weg. */
	fun normalisieren(eingabe: String): String {
		val t = eingabe.trim().trimEnd('/')
		return if (t.startsWith("http://") || t.startsWith("https://")) t else "https://$t"
	}
}

// ───────────────────────── Daten ─────────────────────────

/** Was der Nutzer sieht: wenig Status, keine Restzeit (stimmt meistens nicht). */
enum class SeerrZustand(@androidx.annotation.StringRes val abzeichen: Int?) {
	ANFRAGEN(null),
	ANGEFRAGT(R.string.seliku_seerr_requested),          // wartet auf Freigabe
	WIRD_GESUCHT(R.string.seliku_seerr_requested),       // freigegeben, Radarr/Sonarr sucht
	NOCH_NICHT(R.string.seliku_seerr_requested),         // gesucht, aber (noch) nichts gefunden
	GLEICH(R.string.seliku_seerr_available_soon),      // gefunden, lädt
	ANSEHEN(R.string.seliku_seerr_watch_now),
}

data class SeerrTitel(
	val tmdb: Int,
	val typ: String, // "movie" oder "tv"
	val titel: String,
	val jahr: String,
	val poster: String?,
	val hintergrund: String?,
	val text: String,
	val status: Int,          // mediaInfo.status: 1 unbekannt, 2 wartet, 3 in Arbeit, 4 teilweise, 5 verfügbar
	val laedt: Boolean,       // downloadStatus nicht leer
	val anfrageId: Int?,
	val anfrageSeit: Instant?,
	val anfrageVonMir: Boolean,
) {
	val zustand: SeerrZustand get() = when {
		status >= 4 -> SeerrZustand.ANSEHEN
		status == 3 && laedt -> SeerrZustand.GLEICH
		// Ohne Download nach ein paar Stunden: die Suche hat nichts gefunden
		status == 3 && anfrageSeit != null && Duration.between(anfrageSeit, Instant.now()).toHours() >= 6 -> SeerrZustand.NOCH_NICHT
		status == 3 -> SeerrZustand.WIRD_GESUCHT
		status == 2 -> SeerrZustand.ANGEFRAGT
		else -> SeerrZustand.ANFRAGEN
	}
	val schluessel get() = "$typ-$tmdb"

	companion object {
		private const val BILD = TMDB_BILD
		fun ausJson(o: JSONObject, typVorgabe: String? = null, ich: Int? = null): SeerrTitel? {
			val typ = o.optString("mediaType").ifBlank { typVorgabe ?: "" }
			if (typ != "movie" && typ != "tv") return null
			val info = o.optJSONObject("mediaInfo")
			val anfragen = info?.optJSONArray("requests")
			val letzte = anfragen?.let { a -> (0 until a.length()).map { a.getJSONObject(it) }.maxByOrNull { it.optString("createdAt") } }
			val meine = anfragen?.let { a -> (0 until a.length()).map { a.getJSONObject(it) }.firstOrNull { ich != null && it.optJSONObject("requestedBy")?.optInt("id") == ich } }
			val anfrage = meine ?: letzte
			return SeerrTitel(
				tmdb = o.optInt("id"),
				typ = typ,
				titel = o.optString(if (typ == "movie") "title" else "name").ifBlank { o.optString("originalTitle", o.optString("originalName")) },
				jahr = o.optString(if (typ == "movie") "releaseDate" else "firstAirDate").take(4),
				poster = o.optString("posterPath").takeIf { it.startsWith("/") }?.let { BILD + "w342" + it },
				hintergrund = o.optString("backdropPath").takeIf { it.startsWith("/") }?.let { BILD + "w1280" + it },
				text = o.optString("overview"),
				status = info?.optInt("status", 1) ?: 1,
				laedt = (info?.optJSONArray("downloadStatus")?.length() ?: 0) > 0 || (info?.optJSONArray("downloadStatus4k")?.length() ?: 0) > 0,
				anfrageId = anfrage?.optInt("id"),
				anfrageSeit = anfrage?.optString("createdAt")?.let { runCatching { Instant.parse(it) }.getOrNull() },
				anfrageVonMir = meine != null,
			)
		}
	}
}

/** Bilder von TMDB (Seerr liefert nur die Pfade). */
internal const val TMDB_BILD = "https://image.tmdb.org/t/p/"

/** Person aus der Besetzung. */
data class SeerrPerson(val id: Int, val name: String, val rolle: String, val bild: String?)

/** Was die Detailseite zusätzlich zeigt: Genres, Laufzeit bzw. Staffeln, Bewertung, Macher, Besetzung. */
data class SeerrMehr(
	val genres: List<String>,
	val laufzeit: Int?,      // Minuten (bei Serien: eine Folge)
	val staffeln: Int?,
	val folgen: Int?,
	val bewertung: Double?,  // TMDB, 0–10
	val stimmen: Int,
	val macher: String?,     // Regie bzw. Idee
	val firmen: String?,     // Studios bzw. Sender
	val besetzung: List<SeerrPerson>,
	val youtube: String? = null, // Schlüssel des Trailers auf YouTube
) {
	companion object {
		fun ausJson(o: JSONObject, typ: String): SeerrMehr {
			fun liste(a: JSONArray?) = a?.let { (0 until it.length()).mapNotNull { i -> it.optJSONObject(i) } }.orEmpty()
			fun namen(a: JSONArray?, n: Int) = liste(a).map { it.optString("name") }.filter { it.isNotBlank() }.distinct().take(n).joinToString(", ").ifBlank { null }
			val credits = o.optJSONObject("credits")
			val film = typ == "movie"
			return SeerrMehr(
				genres = liste(o.optJSONArray("genres")).map { it.optString("name") }.filter { it.isNotBlank() },
				laufzeit = (if (film) o.optInt("runtime") else o.optJSONArray("episodeRunTime")?.optInt(0) ?: 0).takeIf { it > 0 },
				staffeln = if (film) null else o.optInt("numberOfSeasons").takeIf { it > 0 },
				folgen = if (film) null else o.optInt("numberOfEpisodes").takeIf { it > 0 },
				bewertung = o.optDouble("voteAverage", 0.0).takeIf { it > 0.0 },
				stimmen = o.optInt("voteCount"),
				macher = if (film) liste(credits?.optJSONArray("crew")).filter { it.optString("job") == "Director" }.map { it.optString("name") }.distinct().take(2).joinToString(", ").ifBlank { null }
					else namen(o.optJSONArray("createdBy"), 2),
				firmen = if (film) namen(o.optJSONArray("productionCompanies"), 2) else namen(o.optJSONArray("networks"), 2),
				besetzung = liste(credits?.optJSONArray("cast")).distinctBy { it.optInt("id") }.take(20).map {
					SeerrPerson(it.optInt("id"), it.optString("name"), it.optString("character"),
						it.optString("profilePath").takeIf { p -> p.startsWith("/") }?.let { p -> TMDB_BILD + "w185" + p })
				}.filter { it.id > 0 && it.name.isNotBlank() },
				youtube = liste(o.optJSONArray("relatedVideos")).filter { it.optString("site").equals("YouTube", true) && it.optString("key").isNotBlank() }
					.sortedBy { if (it.optString("type") == "Trailer") 0 else 1 }.firstOrNull()?.optString("key"),
			)
		}
	}
}

class SeerrFehler(val code: Int, text: String) : Exception(text)

/** Abfragen an Seerr mit dem gespeicherten Sitzungs-Cookie. */
class SeerrDaten(private val context: Context, private val api: ApiClient) {
	private var ich: Int? = null

	/** Anmeldung beim Vermittler mit dem Jellyfin-Token (Jellyfin nimmt nur noch den Authorization-Kopf an). */
	private fun jellyfinKopf() = mapOf("Authorization" to "MediaBrowser Token=\"${api.accessToken.orEmpty()}\"")

	/** Gefundener Weg zum Vermittler – zentral, weil jede Seite ihre eigene SeerrDaten-Instanz bekommt. */
	private var vermittler: String?
		get() = SeerrVerbindung.vermittlerAdresse
		set(v) { SeerrVerbindung.vermittlerAdresse = v }

	private fun vermittlerKandidaten(): List<String> {
		val basis = api.baseUrl?.trimEnd('/') ?: return emptyList()
		val direkt = runCatching { java.net.URI(basis).let { u -> "http://${u.host}:8097" } }.getOrNull()
		return listOfNotNull("$basis/seerr", direkt).distinct()
	}

	private suspend fun anfrage(methode: String, pfad: String, body: JSONObject? = null,
		basis: String = if (SeerrVerbindung.vermittelt.value) vermittler.orEmpty() else SeerrVerbindung.adresse(context),
		cookie: String? = if (SeerrVerbindung.vermittelt.value) null else SeerrVerbindung.cookie(context),
		kopf: Map<String, String> = if (SeerrVerbindung.vermittelt.value) jellyfinKopf() else emptyMap(),
	): Pair<String, HttpURLConnection> = withContext(Dispatchers.IO) {
		val v = URL(basis + pfad).openConnection() as HttpURLConnection
		v.requestMethod = methode
		v.connectTimeout = 10_000; v.readTimeout = 20_000
		v.setRequestProperty("Accept", "application/json")
		if (!cookie.isNullOrBlank()) v.setRequestProperty("Cookie", cookie)
		kopf.forEach { (k, w) -> v.setRequestProperty(k, w) }
		if (body != null) {
			v.doOutput = true
			v.setRequestProperty("Content-Type", "application/json")
			v.outputStream.use { it.write(body.toString().toByteArray()) }
		}
		val code = v.responseCode
		val text = (if (code < 400) v.inputStream else v.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
		if (code >= 400) throw SeerrFehler(code, text.take(200))
		text to v
	}

	private suspend fun json(pfad: String) = JSONObject(anfrage("GET", "/api/v1$pfad").first)

	/** Beim Start: bietet der Jellyfin-Server Seerr an? Dann ist man ohne weitere Anmeldung verbunden. */
	suspend fun vermittlerPruefen() {
		for (basis in vermittlerKandidaten()) {
			val ok = runCatching {
				val (text, _) = anfrage("GET", "/api/v1/auth/me", basis = basis, cookie = null, kopf = jellyfinKopf())
				ich = JSONObject(text).optInt("id").takeIf { it > 0 }
			}.onFailure { Timber.d("Seerr-Vermittler nicht unter $basis: ${it.message}") }.isSuccess
			if (ok) { vermittler = basis; SeerrVerbindung.vermittelt.value = true; return }
		}
		SeerrVerbindung.vermittelt.value = false
	}

	private suspend fun meineId(): Int? = ich ?: runCatching { json("/auth/me").optInt("id") }.getOrNull()?.also { ich = it }

	private suspend fun liste(pfad: String, typ: String? = null): List<SeerrTitel> {
		val i = meineId()
		val a = json(pfad).optJSONArray("results") ?: JSONArray()
		return (0 until a.length()).mapNotNull { SeerrTitel.ausJson(a.getJSONObject(it), typ, i) }
	}

	suspend fun imTrend() = liste("/discover/trending?page=1&language=de")
	suspend fun beliebteFilme(seite: Int = 1) = liste("/discover/movies?page=$seite&language=de", "movie")
	suspend fun beliebteSerien(seite: Int = 1) = liste("/discover/tv?page=$seite&language=de", "tv")
	suspend fun baldImKino() = liste("/discover/movies/upcoming?page=1&language=de", "movie")
	suspend fun suche(text: String) = liste("/search?query=${URLEncoder.encode(text, "UTF-8").replace("+", "%20")}&page=1&language=de")

	/** Eigene Anfragen (neueste zuerst), mit den Titeldaten nachgeladen. */
	suspend fun meineAnfragen(): List<SeerrTitel> = coroutineScope {
		val i = meineId() ?: return@coroutineScope emptyList()
		val a = json("/request?take=20&skip=0&sort=added&requestedBy=$i").optJSONArray("results") ?: JSONArray()
		(0 until a.length()).map { a.getJSONObject(it) }.map { r ->
			async { runCatching { titel(r.optString("type"), r.optJSONObject("media")?.optInt("tmdbId") ?: 0) }.getOrNull() }
		}.mapNotNull { it.await() }
	}

	suspend fun titel(typ: String, tmdb: Int): SeerrTitel =
		SeerrTitel.ausJson(json("/$typ/$tmdb?language=de"), typ, meineId()) ?: throw SeerrFehler(404, "unbekannt")

	/** Titel samt Genres, Laufzeit, Besetzung … aus derselben Antwort. */
	suspend fun detail(typ: String, tmdb: Int): Pair<SeerrTitel, SeerrMehr> {
		val o = json("/$typ/$tmdb?language=de")
		val t = SeerrTitel.ausJson(o, typ, meineId()) ?: throw SeerrFehler(404, "unbekannt")
		return t to SeerrMehr.ausJson(o, typ)
	}

	/** Kritiker-Wertung von Rotten Tomatoes, falls Seerr eine kennt. */
	suspend fun rottenTomatoes(typ: String, tmdb: Int): Int? =
		runCatching { json("/$typ/$tmdb/ratings").optInt("criticsScore", -1).takeIf { it in 0..100 } }.getOrNull()

	/** Eine Seite einer Liste (Genre, Studio, Sender, Empfehlungen …) und wie viele Seiten es gibt. */
	suspend fun blatt(pfad: String, typ: String?, seite: Int): Pair<List<SeerrTitel>, Int> {
		val i = meineId()
		val o = json(pfad + (if ('?' in pfad) "&" else "?") + "page=$seite&language=de")
		val a = o.optJSONArray("results") ?: JSONArray()
		return (0 until a.length()).mapNotNull { SeerrTitel.ausJson(a.getJSONObject(it), typ, i) } to o.optInt("totalPages", seite)
	}

	/** Genres mit einem Hintergrundbild, wie in Seerrs Genre-Reihe. */
	suspend fun genres(typ: String): List<SeerrRubrik> {
		val a = JSONArray(anfrage("GET", "/api/v1/discover/genreslider/$typ?language=de").first)
		return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { g ->
			val bild = g.optJSONArray("backdrops")?.optString(0)?.takeIf { it.startsWith("/") }?.let { TMDB_BILD + "w780" + it }
			SeerrRubrik(if (typ == "movie") "film-genre" else "serie-genre", g.optInt("id"), g.optString("name"), bild)
		}.filter { it.id > 0 && it.name.isNotBlank() }
	}

	/** Logo eines Studios oder Senders (weiss eingefärbt wie bei Seerr); einmal geholt, dann gemerkt. */
	suspend fun logo(r: SeerrRubrik): String? {
		SeerrLogos.bekannt[r.schluessel]?.let { return it.ifEmpty { null } }
		val sender = r.art == "sender"
		val o = json(if (sender) "/discover/tv/network/${r.id}?page=1&language=de" else "/discover/movies/studio/${r.id}?page=1&language=de")
		val firma = o.optJSONObject(if (sender) "network" else "studio")
		firma?.optString("name")?.takeIf { it.isNotBlank() }?.let { SeerrLogos.namen[r.schluessel] = it }
		val pfad = firma?.optString("logoPath")?.takeIf { it.startsWith("/") }
		val url = pfad?.let { TMDB_BILD + "w500_filter(duotone,ffffff,bababa)" + it }.orEmpty()
		SeerrLogos.bekannt[r.schluessel] = url
		return url.ifEmpty { null }
	}

	/** Filme und Serien einer Person, die bekanntesten zuerst (ohne Auftritte als Gast in Talkshows u. ä.). */
	suspend fun filmografie(person: Int): List<SeerrTitel> {
		val i = meineId()
		val a = json("/person/$person/combined_credits?language=de").optJSONArray("cast") ?: JSONArray()
		val auftritte = setOf(10763, 10764, 10767) // Nachrichten, Reality, Talk
		return (0 until a.length()).mapNotNull { a.optJSONObject(it) }
			.filterNot { o -> o.optJSONArray("genreIds")?.let { g -> (0 until g.length()).any { g.optInt(it) in auftritte } } == true }
			.sortedByDescending { it.optInt("voteCount") }
			.mapNotNull { SeerrTitel.ausJson(it, null, i) }
			// Einträge ohne Bild ans Ende; TMDB führt manche Titel doppelt (einmal ohne Bild)
			.sortedBy { it.poster == null }.distinctBy { it.schluessel }.distinctBy { "${it.typ}|${it.titel}|${it.jahr}" }
	}

	suspend fun anfragen(t: SeerrTitel) {
		val body = JSONObject().put("mediaType", t.typ).put("mediaId", t.tmdb)
		if (t.typ == "tv") body.put("seasons", "all")
		anfrage("POST", "/api/v1/request", body)
	}

	suspend fun zuruecknehmen(t: SeerrTitel) {
		val id = t.anfrageId ?: return
		anfrage("DELETE", "/api/v1/request/$id")
	}

	/** Den Titel in Jellyfin finden (über die TMDB-Nummer), um ihn zu öffnen. */
	suspend fun inJellyfin(t: SeerrTitel): BaseItemDto? = withContext(Dispatchers.IO) {
		val art = if (t.typ == "movie") BaseItemKind.MOVIE else BaseItemKind.SERIES
		api.itemsApi.getItems(searchTerm = t.titel, recursive = true, includeItemTypes = listOf(art), fields = listOf(ItemFields.PROVIDER_IDS), limit = 30)
			.content.items.firstOrNull { it.providerIds?.entries?.any { (k, v) -> k.equals("Tmdb", true) && v == t.tmdb.toString() } == true }
	}

	// ── Anmeldung ──

	/** Benutzername/Passwort: zuerst als Jellyfin-Konto, sonst als lokales Seerr-Konto. Liefert den Anzeigenamen. */
	suspend fun anmeldenMitPasswort(url: String, benutzer: String, passwort: String): String {
		val versuche = listOf(
			"/api/v1/auth/jellyfin" to JSONObject().put("username", benutzer).put("password", passwort),
			"/api/v1/auth/local" to JSONObject().put("email", benutzer).put("password", passwort),
		)
		var letzter: Exception? = null
		for ((pfad, body) in versuche) {
			try {
				val (text, v) = anfrage("POST", pfad, body, basis = url, cookie = null)
				return sitzungMerken(url, text, v)
			} catch (e: Exception) { letzter = e }
		}
		throw letzter ?: SeerrFehler(401, "Anmeldung abgelehnt")
	}

	/** Plex: Code anzeigen, der auf plex.tv/link eingegeben wird. Liefert (PIN-Nummer, Code). */
	suspend fun plexCode(): Pair<Int, String> {
		val (text, _) = anfrage("POST", "/api/v2/pins?strong=false", null, basis = "https://plex.tv", cookie = null, kopf = plexKopf())
		val o = JSONObject(text)
		return o.optInt("id") to o.optString("code")
	}

	/** Fragt plex.tv, ob der Code eingegeben wurde; dann bei Seerr anmelden. Null = noch nicht. */
	suspend fun plexPruefen(url: String, pin: Int): String? {
		val (text, _) = anfrage("GET", "/api/v2/pins/$pin", null, basis = "https://plex.tv", cookie = null, kopf = plexKopf())
		val token = JSONObject(text).optString("authToken").takeIf { it.isNotBlank() && it != "null" } ?: return null
		val (t2, v) = anfrage("POST", "/api/v1/auth/plex", JSONObject().put("authToken", token), basis = url, cookie = null)
		return sitzungMerken(url, t2, v)
	}

	private fun plexKopf() = mapOf("X-Plex-Product" to "Seliku TV", "X-Plex-Client-Identifier" to SeerrVerbindung.geraetId(context))

	private fun sitzungMerken(url: String, text: String, v: HttpURLConnection): String {
		val cookie = v.headerFields.entries.filter { it.key.equals("Set-Cookie", true) }.flatMap { it.value }
			.map { it.substringBefore(";") }.firstOrNull { it.startsWith("connect.sid=") } ?: throw SeerrFehler(500, "Seerr hat keine Sitzung geliefert")
		val o = runCatching { JSONObject(text) }.getOrNull()
		val name = o?.optString("displayName")?.ifBlank { null } ?: o?.optString("username")?.ifBlank { null } ?: o?.optString("email").orEmpty()
		SeerrVerbindung.speichern(context, url, cookie, name)
		ich = o?.optInt("id")
		return name
	}
}

// ───────────────────────── Bausteine ─────────────────────────

@Composable
fun SeerrAbzeichen(z: SeerrZustand, modifier: Modifier = Modifier) {
	val text = stringResource(z.abzeichen ?: return)
	val grund = when (z) { SeerrZustand.ANSEHEN -> Farben.Akzent; SeerrZustand.GLEICH -> Color(0xFF7FB069); else -> Color(0xE0221A16) }
	Box(modifier.padding(6.dp).background(grund, RoundedCornerShape(8.dp)).padding(horizontal = 7.dp, vertical = 3.dp)) {
		Text(text, style = Schrift.Klein.copy(color = if (z == SeerrZustand.ANSEHEN || z == SeerrZustand.GLEICH) Color.Black else Farben.Text, fontWeight = FontWeight.Bold))
	}
}

@Composable
fun SeerrKachel(t: SeerrTitel, onClick: () -> Unit, breite: Dp = 120.dp, merken: Pair<SelikuViewModel, Pair<String, String>>? = null, onFokus: () -> Unit = {}) {
	val jahrUndArt = listOf(t.jahr, stringResource(if (t.typ == "tv") R.string.seliku_series else R.string.seliku_movie)).filter { it.isNotBlank() }.joinToString(" · ")
	LocalBuehne.current?.let { aktiv ->
		Buehne(breite, aktiv, t.titel to jahrUndArt) {
			Bild(t.hintergrund ?: t.poster, Modifier.fillMaxSize(), grund = Farben.Flaeche)
			Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Transparent, Color(0x22000000), Color(0xE0000000)))))
			Text(t.titel, style = Schrift.Reihe, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.align(Alignment.BottomStart).padding(10.dp))
			SeerrAbzeichen(t.zustand, Modifier.align(Alignment.TopStart))
			if (t.zustand == SeerrZustand.ANFRAGEN)
				Box(Modifier.align(Alignment.TopEnd).padding(6.dp).background(Color(0xE0221A16), RoundedCornerShape(8.dp)).padding(horizontal = 7.dp, vertical = 2.dp)) {
					Text("+ " + stringResource(R.string.seliku_seerr_request), style = Schrift.Klein.copy(color = Farben.Text, fontWeight = FontWeight.Bold))
				}
		}
		return
	}
	// In einer gemischten Reihe: offener erster Platz im Querformat wie bei den eigenen Titeln
	val offen = LocalKachelOffen.current == true && SelikuEinstellungen.netflixKacheln(LocalContext.current)
	val hoehe = breite * 1.5f
	Column(Modifier.width(if (offen) hoehe * 16f / 9f else breite)) {
		Waehlbar(onClick = onClick, merken = merken, onFokus = onFokus, vergroessern = LocalKachelOffen.current == null) {
			Box(Modifier.fillMaxWidth().height(hoehe)) {
				if (offen) {
					Bild(t.hintergrund ?: t.poster, Modifier.fillMaxSize(), grund = Farben.Flaeche)
					Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Transparent, Color(0x22000000), Color(0xE0000000)))))
					Text(t.titel, style = Schrift.Reihe, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.align(Alignment.BottomStart).padding(10.dp))
					SeerrAbzeichen(t.zustand, Modifier.align(Alignment.TopStart))
				} else {
					Bild(t.poster, Modifier.fillMaxSize(), ersatz = initialen(t.titel))
					SeerrAbzeichen(t.zustand, Modifier.align(Alignment.BottomStart))
				}
				// Noch nicht da: kleines «+» oben rechts, damit man fehlende Titel in gemischten Reihen erkennt
				if (LocalKachelOffen.current != null && t.zustand == SeerrZustand.ANFRAGEN)
					Box(Modifier.align(Alignment.TopEnd).padding(6.dp).background(Color(0xE0221A16), RoundedCornerShape(8.dp)).padding(horizontal = 7.dp, vertical = 2.dp)) {
						Text("+ " + stringResource(R.string.seliku_seerr_request), style = Schrift.Klein.copy(color = Farben.Text, fontWeight = FontWeight.Bold))
					}
			}
		}
		Spacer(Modifier.height(6.dp))
		Text(t.titel, style = Schrift.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
		Text(jahrUndArt, style = Schrift.Klein, maxLines = 1)
	}
}

/** [nachladen]: wird aufgerufen, wenn der Fokus gegen das Ende der Reihe läuft (nächste Seite holen). */
@Composable
fun SeerrReihe(titel: String, liste: List<SeerrTitel>, vm: SelikuViewModel, seite: Seite, nachladen: (() -> Unit)? = null, onFokus: (SeerrTitel) -> Unit = {}) {
	if (liste.isEmpty()) return
	Column(Modifier.fillMaxWidth().padding(bottom = 18.dp)) {
		Text(titel, style = Schrift.Reihe, modifier = Modifier.padding(start = 48.dp, bottom = 8.dp))
		// Rollstand merken: nach dem Zurückkommen steht die Reihe wieder dort, wo die gewählte Kachel ist
		val zustand = remember(seite.schluessel, titel) { vm.listen.getOrPut("${seite.schluessel}|$titel") { androidx.compose.foundation.lazy.LazyListState() } }
		SeitlichRollen {
			LazyRow(state = zustand, contentPadding = PaddingValues(horizontal = 48.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
				itemsIndexed(liste, key = { _, t -> titel + t.schluessel }) { i, t ->
					SeerrKachel(t, onClick = { vm.zu(Seite.SeerrDetail(t.typ, t.tmdb)) }, merken = vm to (seite.schluessel to titel + t.schluessel), onFokus = {
						onFokus(t)
						if (nachladen != null && i >= liste.size - 6) nachladen()
					})
				}
			}
		}
	}
}

/** Reihe, die beim Weiterblättern die nächsten Seiten nachlädt. */
@Composable
fun SeerrReihe(titel: String, quelle: SeerrBlaettern, vm: SelikuViewModel, seite: Seite, onFokus: (SeerrTitel) -> Unit = {}) {
	val daten = koinInject<SeerrDaten>()
	val scope = rememberCoroutineScope()
	SeerrReihe(titel, quelle.liste, vm, seite, nachladen = { scope.launch { quelle.mehr(daten) } }, onFokus = onFokus)
}

// ───────────────────────── Seiten ─────────────────────────

/** «Entdecken» wie bei Seerr: Trends, Beliebtes, Genres, Demnächst, Studios, Sender – durch Titel, die noch fehlen. */
@Composable
fun EntdeckenSeite(vm: SelikuViewModel) {
	val context = LocalContext.current
	val daten = koinInject<SeerrDaten>()
	val seite = Seite.Entdecken
	@Suppress("UNCHECKED_CAST")
	var reihen by remember { mutableStateOf(vm.wahl["entdecken"] as? List<EntdeckenReihe>) }
	var fehler by remember { mutableStateOf<String?>(null) }
	var hero by remember { mutableStateOf<String?>(null) }
	LaunchedEffect(Unit) {
		val alt = reihen
		if (alt != null) {
			// Zurück aus einer Unterseite: die nachgeladenen Seiten bleiben, nur «Meine Anfragen» auffrischen
			val anfragen = alt.filterIsInstance<EntdeckenReihe.Titel>().firstOrNull { it.anfragen } ?: return@LaunchedEffect
			runCatching { daten.meineAnfragen() }.onSuccess { l -> anfragen.quelle.ersetzen(l.filter { it.zustand != SeerrZustand.ANSEHEN }) }
			return@LaunchedEffect
		}
		runCatching {
			coroutineScope {
				fun q(pfad: String, typ: String?) = SeerrBlaettern(pfad, typ)
				val trend = q("/discover/trending", null)
				val filme = q("/discover/movies", "movie")
				val kino = q("/discover/movies/upcoming", "movie")
				val serien = q("/discover/tv", "tv")
				val serienBald = q("/discover/tv/upcoming", "tv")
				// Ohne die drei Hauptreihen gilt Seerr als nicht erreichbar; der Rest darf fehlen
				val wichtig = listOf(trend, filme, serien).map { async { it.erste(daten) } }
				val rest = listOf(kino, serienBald).map { async { runCatching { it.erste(daten) } } }
				val filmGenres = async { runCatching { daten.genres("movie") }.getOrDefault(emptyList()) }
				val serienGenres = async { runCatching { daten.genres("tv") }.getOrDefault(emptyList()) }
				val anfragen = async { runCatching { daten.meineAnfragen() }.getOrDefault(emptyList()) }
				wichtig.awaitAll(); rest.awaitAll()
				listOf(
					EntdeckenReihe.Titel(context.getString(R.string.seliku_seerr_trending), trend),
					EntdeckenReihe.Titel(context.getString(R.string.seliku_seerr_popular_movies), filme),
					EntdeckenReihe.Rubriken(context.getString(R.string.seliku_seerr_movie_genres), filmGenres.await()),
					EntdeckenReihe.Titel(context.getString(R.string.seliku_seerr_coming_soon), kino),
					EntdeckenReihe.Rubriken(context.getString(R.string.seliku_seerr_studios), SeerrStudios),
					EntdeckenReihe.Titel(context.getString(R.string.seliku_seerr_popular_series), serien),
					EntdeckenReihe.Rubriken(context.getString(R.string.seliku_seerr_series_genres), serienGenres.await()),
					EntdeckenReihe.Titel(context.getString(R.string.seliku_seerr_upcoming_series), serienBald),
					EntdeckenReihe.Rubriken(context.getString(R.string.seliku_seerr_networks), SeerrSender),
					EntdeckenReihe.Titel(context.getString(R.string.seliku_seerr_your_requests),
						SeerrBlaettern(null, null, anfragen.await().filter { it.zustand != SeerrZustand.ANSEHEN }), anfragen = true),
				)
			}
		}.onSuccess { reihen = it; vm.wahl["entdecken"] = it }
			.onFailure { Timber.w(it, "Entdecken"); fehler = if (it is SeerrFehler && it.code == 403 || it is SeerrFehler && it.code == 401) context.getString(R.string.seliku_seerr_session_expired) else context.getString(R.string.seliku_seerr_unreachable) }
	}
	Box(Modifier.fillMaxSize()) {
		Kulisse(hero)
		val r = reihen
		when {
			r == null && fehler != null -> Hinweis(fehler!!)
			r == null -> Hinweis(stringResource(R.string.seliku_loading))
			else -> SenkrechtRollen {
				LazyColumn(state = vm.liste(seite), contentPadding = PaddingValues(top = 28.dp, bottom = 48.dp), modifier = Modifier.fillMaxSize()) {
					item { Text(stringResource(R.string.seliku_discover), style = Schrift.Titel, modifier = Modifier.padding(start = 48.dp, bottom = 16.dp)) }
					items(r, key = { it.titel }) { reihe ->
						when (reihe) {
							is EntdeckenReihe.Titel -> SeerrReihe(reihe.titel, reihe.quelle, vm, seite) { hero = it.hintergrund }
							is EntdeckenReihe.Rubriken -> SeerrRubrikReihe(reihe.titel, reihe.liste, vm, seite) { rb -> rb.bild?.let { hero = it } }
						}
					}
				}
			}
		}
	}
}

/** Detailseite eines Seerr-Titels mit dem Knopf, der dem Stand folgt: Anfragen → Wird gesucht → Gleich verfügbar / Noch nicht verfügbar → Ansehen. */
@Composable
fun SeerrDetailSeite(vm: SelikuViewModel, seite: Seite.SeerrDetail, oeffnen: (BaseItemDto) -> Unit) {
	val context = LocalContext.current
	val daten = koinInject<SeerrDaten>()
	val scope = rememberCoroutineScope()
	// Gemerkt, damit beim Zurückkommen alles sofort da ist und die zuletzt gewählte Kachel den Fokus zurückholen kann
	@Suppress("UNCHECKED_CAST")
	val gemerkt = remember(seite.schluessel) { vm.wahl["${seite.schluessel}|titel"] as? Pair<SeerrTitel, SeerrMehr> }
	val zurueckGekommen = remember(seite.schluessel) { vm.fokus[seite.schluessel] != null }
	var t by remember { mutableStateOf(gemerkt?.first) }
	var mehr by remember { mutableStateOf(gemerkt?.second) }
	var tomaten by remember { mutableStateOf(vm.wahl["${seite.schluessel}|tomaten"] as? Int) }
	var meldung by remember { mutableStateOf<String?>(null) }
	var neu by remember { mutableIntStateOf(0) }
	var arbeitet by remember { mutableStateOf(false) }
	// Der Knopf ist die erste Station der Seite (Zurück-Logik und Fokus beim Betreten), nicht die erste Kachel weiter unten
	val erster = remember(seite.schluessel) { vm.ersteAnfrageFuer(seite.schluessel, "knopf") }
	val liste = vm.liste(seite)
	@Suppress("UNCHECKED_CAST")
	val empfehlungen = remember(seite.schluessel) { vm.wahl.getOrPut("${seite.schluessel}|empfehlungen") { SeerrBlaettern("/${seite.typ}/${seite.tmdb}/recommendations", seite.typ, hoechstens = 3) } as SeerrBlaettern }
	val aehnliche = remember(seite.schluessel) { vm.wahl.getOrPut("${seite.schluessel}|aehnliche") { SeerrBlaettern("/${seite.typ}/${seite.tmdb}/similar", seite.typ, hoechstens = 3) } as SeerrBlaettern }
	LaunchedEffect(seite.tmdb, neu) {
		runCatching { daten.detail(seite.typ, seite.tmdb) }.onSuccess { (titel, m) -> t = titel; mehr = m; vm.wahl["${seite.schluessel}|titel"] = titel to m }.onFailure { Timber.w(it, "Seerr-Titel"); meldung = context.getString(R.string.seliku_seerr_unreachable) }
	}
	LaunchedEffect(seite.schluessel) {
		launch { if (empfehlungen.liste.isEmpty()) empfehlungen.mehr(daten) }
		launch { if (aehnliche.liste.isEmpty()) aehnliche.mehr(daten) }
		if (tomaten == null) tomaten = daten.rottenTomatoes(seite.typ, seite.tmdb)?.also { vm.wahl["${seite.schluessel}|tomaten"] = it }
	}
	// Solange gesucht/geladen wird, still nachsehen, ob sich etwas tut
	LaunchedEffect(t?.zustand) {
		val z = t?.zustand ?: return@LaunchedEffect
		if (z == SeerrZustand.WIRD_GESUCHT || z == SeerrZustand.GLEICH || z == SeerrZustand.ANGEFRAGT) { delay(30_000); neu++ }
	}
	val x = t
	// Schon in der Bibliothek: gleich die eigene Seite des Titels öffnen (Abspielen, Trailer, Favorit), nicht die Seerr-Seite.
	// Die Seerr-Seite verschwindet dabei aus dem Verlauf, Zurück führt also dorthin, woher man kam.
	var eigeneSeiteGesucht by remember(seite.schluessel) { mutableStateOf(zurueckGekommen) }
	LaunchedEffect(x?.tmdb, x?.zustand) {
		if (x == null || eigeneSeiteGesucht) return@LaunchedEffect
		if (x.zustand == SeerrZustand.ANSEHEN) {
			val item = runCatching { daten.inJellyfin(x) }.getOrNull()
			if (item != null && seiteFuer(item) != null) { vm.zurueck(); oeffnen(item); return@LaunchedEffect }
		}
		eigeneSeiteGesucht = true
	}
	Box(Modifier.fillMaxSize()) {
		Kulisse(x?.hintergrund)
		if (x == null || (x.zustand == SeerrZustand.ANSEHEN && !eigeneSeiteGesucht)) { Hinweis(meldung ?: stringResource(R.string.seliku_loading)); return@Box }
		// Beim Betreten auf den Knopf – ausser man kommt von einer Kachel weiter unten zurück, die holt den Fokus selbst
		LaunchedEffect(x.tmdb) {
			if (zurueckGekommen) return@LaunchedEffect
			delay(150); runCatching { erster.requestFocus() }
		}
		// Wechselt der Stand (Anfragen → Angefragt …), ist es ein anderer Knopf: Fokus gleich wieder darauf setzen
		var letzterZustand by remember { mutableStateOf(x.zustand) }
		LaunchedEffect(x.zustand) {
			if (x.zustand != letzterZustand) { letzterZustand = x.zustand; delay(80); runCatching { erster.requestFocus() } }
		}
		// Auf den Knöpfen ganz nach oben rollen (Poster und Titel ganz sichtbar); die Kachel unten ist dann nicht mehr «gemerkt»
		val knopfFokus: () -> Unit = { vm.fokus.remove(seite.schluessel); scope.launch { delay(30); liste.animateScrollToItem(0) } }
		val m = mehr
		LazyColumn(state = liste, contentPadding = PaddingValues(bottom = 48.dp), modifier = Modifier.fillMaxSize()) {
			item(key = "kopf") {
				Row(Modifier.fillMaxWidth().padding(start = 48.dp, top = 48.dp, end = 64.dp, bottom = 26.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
					Bild(x.poster, Modifier.width(220.dp).aspectRatio(2f / 3f), ersatz = initialen(x.titel))
					Column(Modifier.weight(1f)) {
						Text(x.titel, style = Schrift.Riesig, maxLines = 2, overflow = TextOverflow.Ellipsis)
						Spacer(Modifier.height(6.dp))
						val staffeln = m?.staffeln
						val umfang = if (staffeln != null) listOfNotNull(
							androidx.compose.ui.res.pluralStringResource(R.plurals.seliku_seasons, staffeln, staffeln),
							m?.folgen?.let { androidx.compose.ui.res.pluralStringResource(R.plurals.seliku_seerr_episode_count, it, it) },
						).joinToString(", ") else laufzeit(m?.laufzeit?.let { it * 600_000_000L })
						Text(listOfNotNull(x.jahr, stringResource(if (x.typ == "tv") R.string.seliku_series else R.string.seliku_movie), umfang)
							.filter { it.isNotBlank() }.joinToString(" · "), style = Schrift.Leise)
						if (!m?.genres.isNullOrEmpty()) {
							Spacer(Modifier.height(4.dp))
							Text(m!!.genres.joinToString(", "), style = Schrift.Leise, maxLines = 1, overflow = TextOverflow.Ellipsis)
						}
						val wertung = listOfNotNull(
							m?.bewertung?.let { "★ %.1f".format(it) + "  (TMDB)" },
							tomaten?.let { stringResource(R.string.seliku_seerr_rotten_tomatoes, it) },
						)
						if (wertung.isNotEmpty()) {
							Spacer(Modifier.height(4.dp))
							Text(wertung.joinToString("   ·   "), style = Schrift.Leise.copy(color = Farben.Akzent))
						}
						Spacer(Modifier.height(12.dp))
						Text(x.text, style = Schrift.Normal, maxLines = 5, overflow = TextOverflow.Ellipsis)
						Spacer(Modifier.height(18.dp))
						val tun: (suspend () -> Unit) -> Unit = { aktion ->
							if (!arbeitet) scope.launch {
								arbeitet = true
								runCatching { aktion() }.onFailure { meldung = if (it is SeerrFehler && it.code == 403) context.getString(R.string.seliku_seerr_no_permission) else context.getString(R.string.seliku_failed, it.message?.take(80).toString()) }
								arbeitet = false; neu++
							}
						}
						Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
							val k = Modifier.focusRequester(erster)
							when (x.zustand) {
								SeerrZustand.ANFRAGEN -> Knopf(stringResource(if (arbeitet) R.string.seliku_seerr_requesting else R.string.seliku_seerr_request), symbol = "+", hervorgehoben = true, modifier = k, onFokus = knopfFokus, onClick = { tun { daten.anfragen(x) } })
								SeerrZustand.ANGEFRAGT -> Knopf(stringResource(R.string.seliku_seerr_awaiting_approval), symbol = "⏳", modifier = k, onFokus = knopfFokus, onClick = { neu++ })
								SeerrZustand.WIRD_GESUCHT -> Knopf(stringResource(R.string.seliku_seerr_searching), symbol = "⌕", modifier = k, onFokus = knopfFokus, onClick = { neu++ })
								SeerrZustand.NOCH_NICHT -> Knopf(stringResource(R.string.seliku_seerr_not_yet_available), symbol = "…", modifier = k, onFokus = knopfFokus, onClick = { neu++ })
								SeerrZustand.GLEICH -> Knopf(stringResource(R.string.seliku_seerr_available_soon), symbol = "↓", hervorgehoben = true, modifier = k, onFokus = knopfFokus, onClick = { neu++ })
								SeerrZustand.ANSEHEN -> Knopf(stringResource(R.string.seliku_watch), symbol = "▶", hervorgehoben = true, modifier = k, onFokus = knopfFokus, onClick = {
									tun {
										val item = daten.inJellyfin(x)
										if (item != null) oeffnen(item) else meldung = context.getString(R.string.seliku_seerr_not_in_jellyfin)
									}
								})
							}
							// Trailer von YouTube: in der YouTube-App (die läuft auf jedem Google TV), sonst im Browser
							m?.youtube?.let { key ->
								Knopf(stringResource(R.string.seliku_trailer), symbol = "▸", onFokus = knopfFokus, onClick = {
									val app = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("vnd.youtube:$key"))
									val web = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://www.youtube.com/watch?v=$key"))
									runCatching { context.startActivity(app) }.recoverCatching { context.startActivity(web) }
										.onFailure { meldung = context.getString(R.string.seliku_seerr_no_youtube) }
								})
							}
							if (x.anfrageVonMir && x.zustand in setOf(SeerrZustand.ANGEFRAGT, SeerrZustand.WIRD_GESUCHT, SeerrZustand.NOCH_NICHT))
								Knopf(stringResource(R.string.seliku_seerr_withdraw), symbol = "✕", onFokus = knopfFokus, onClick = { tun { daten.zuruecknehmen(x) } })
						}
						meldung?.let { Spacer(Modifier.height(14.dp)); Text(it, style = Schrift.Leise) }
						if (x.zustand == SeerrZustand.NOCH_NICHT) {
							Spacer(Modifier.height(10.dp))
							Text(stringResource(R.string.seliku_seerr_no_source_yet), style = Schrift.Klein)
						}
						val macher = listOfNotNull(
							m?.macher?.let { stringResource(if (x.typ == "tv") R.string.seliku_seerr_created_by else R.string.seliku_seerr_directed_by, it) },
							m?.firmen,
						)
						if (macher.isNotEmpty()) {
							Spacer(Modifier.height(14.dp))
							Text(macher.joinToString("   ·   "), style = Schrift.Klein, maxLines = 1, overflow = TextOverflow.Ellipsis)
						}
					}
				}
			}
			if (!m?.besetzung.isNullOrEmpty()) item(key = "besetzung") { SeerrBesetzungReihe(stringResource(R.string.seliku_seerr_cast), m!!.besetzung, vm, seite) }
			item(key = "empfehlungen") { SeerrReihe(stringResource(R.string.seliku_seerr_recommendations), empfehlungen, vm, seite) }
			item(key = "aehnliche") { SeerrReihe(stringResource(R.string.seliku_seerr_similar), aehnliche, vm, seite) }
		}
	}
}

/** Seerr verbinden: Adresse, dann mit Plex (Code auf plex.tv/link) oder mit Benutzer und Passwort. */
@Composable
fun SeerrVerbindenSeite(vm: SelikuViewModel) {
	val context = LocalContext.current
	val daten = koinInject<SeerrDaten>()
	val scope = rememberCoroutineScope()
	var adresse by remember { mutableStateOf(SeerrVerbindung.adresse(context)) }
	var verbunden by remember { mutableStateOf(SeerrVerbindung.verbunden(context)) }
	var name by remember { mutableStateOf(SeerrVerbindung.name(context)) }
	var weg by remember { mutableStateOf(0) } // 0 Auswahl, 1 Plex-Code, 2 Passwort
	var plexCode by remember { mutableStateOf<String?>(null) }
	var benutzer by remember { mutableStateOf("") }
	var passwort by remember { mutableStateOf("") }
	var meldung by remember { mutableStateOf<String?>(null) }
	val url = SeerrVerbindung.normalisieren(adresse)

	fun fertig(n: String) { name = n; verbunden = true; weg = 0; plexCode = null; meldung = null; passwort = "" }

	Column(Modifier.fillMaxSize().background(Farben.Hintergrund).padding(start = 48.dp, top = 28.dp, end = 48.dp)) {
		Text("Seerr", style = Schrift.Titel)
		Text(stringResource(R.string.seliku_seerr_intro),
			style = Schrift.Leise, modifier = Modifier.padding(top = 6.dp, bottom = 18.dp, end = 200.dp))
		if (verbunden) {
			Text(if (name.isNotBlank()) stringResource(R.string.seliku_seerr_connected_as, url, name) else stringResource(R.string.seliku_seerr_connected, url), style = Schrift.Normal)
			Spacer(Modifier.height(14.dp))
			Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
				Knopf(stringResource(R.string.seliku_seerr_open_discover), symbol = "›", hervorgehoben = true, onClick = { vm.wurzel(Seite.Entdecken) })
				Knopf(stringResource(R.string.seliku_disconnect), symbol = "✕", onClick = { SeerrVerbindung.trennen(context); verbunden = false; name = "" })
			}
			return@Column
		}
		Eingabe(stringResource(R.string.seliku_seerr_address_hint), adresse, { adresse = it; meldung = null }, KeyboardType.Uri)
		Spacer(Modifier.height(14.dp))
		when (weg) {
			0 -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
				Knopf(stringResource(R.string.seliku_seerr_sign_in_plex), symbol = "▶", hervorgehoben = true, onClick = {
					if (adresse.isBlank()) { meldung = context.getString(R.string.seliku_seerr_enter_address_first); return@Knopf }
					scope.launch {
						runCatching { daten.plexCode() }.onSuccess { (pin, code) ->
							plexCode = code; weg = 1
							// Bis zu 10 Minuten nachsehen, ob der Code eingegeben wurde
							repeat(200) {
								delay(3_000)
								if (weg != 1) return@launch
								val n = runCatching { daten.plexPruefen(url, pin) }.onFailure { e -> meldung = context.getString(R.string.seliku_seerr_sign_in_refused, e.message?.take(80).toString()); weg = 0; return@launch }.getOrNull()
								if (n != null) { fertig(n); return@launch }
							}
							meldung = context.getString(R.string.seliku_seerr_code_expired); weg = 0
						}.onFailure { meldung = context.getString(R.string.seliku_seerr_plex_unreachable) }
					}
				})
				Knopf(stringResource(R.string.seliku_seerr_sign_in_password), symbol = "›", onClick = { if (adresse.isBlank()) meldung = context.getString(R.string.seliku_seerr_enter_address_first) else weg = 2 })
			}
			1 -> Column {
				Text(stringResource(R.string.seliku_seerr_plex_link), style = Schrift.Normal)
				Spacer(Modifier.height(10.dp))
				Text(plexCode.orEmpty(), style = Schrift.Riesig.copy(color = Farben.Akzent, letterSpacing = 8.sp))
				Spacer(Modifier.height(10.dp))
				Text(stringResource(R.string.seliku_seerr_plex_waiting), style = Schrift.Leise)
				Spacer(Modifier.height(14.dp))
				Knopf(stringResource(R.string.seliku_cancel), symbol = "✕", onClick = { weg = 0; plexCode = null })
			}
			2 -> Column {
				Eingabe(stringResource(R.string.seliku_seerr_username_hint), benutzer, { benutzer = it }, KeyboardType.Email)
				Spacer(Modifier.height(10.dp))
				Eingabe(stringResource(R.string.seliku_password), passwort, { passwort = it }, KeyboardType.Password, geheim = true)
				Spacer(Modifier.height(14.dp))
				Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
					Knopf(stringResource(R.string.seliku_sign_in), symbol = "›", hervorgehoben = true, onClick = {
						scope.launch {
							runCatching { daten.anmeldenMitPasswort(url, benutzer.trim(), passwort) }
								.onSuccess { fertig(it) }.onFailure { meldung = context.getString(R.string.seliku_seerr_sign_in_failed) }
						}
					})
					Knopf(stringResource(R.string.seliku_back), symbol = "‹", onClick = { weg = 0 })
				}
			}
		}
		meldung?.let { Spacer(Modifier.height(14.dp)); Text(it, style = Schrift.Leise.copy(color = Farben.Akzent)) }
	}
}

/** Eingabefeld, das die Tastatur erst nach OK öffnet (ein Textfeld holt sie auf dem TV sonst schon beim Vorbeiscrollen). */
@Composable
private fun Eingabe(hinweis: String, wert: String, aendern: (String) -> Unit, art: KeyboardType, geheim: Boolean = false) {
	var bearbeiten by remember { mutableStateOf(false) }
	var hatteFokus by remember { mutableStateOf(false) }
	val feld = remember { FocusRequester() }
	if (!bearbeiten) {
		Waehlbar(onClick = { bearbeiten = true }, vergroessern = false, shape = RoundedCornerShape(10.dp)) { fokussiert ->
			Box(Modifier.width(620.dp).background(if (fokussiert) Farben.FlaecheHell else Farben.Flaeche).padding(horizontal = 16.dp, vertical = 12.dp)) {
				Text(if (wert.isEmpty()) hinweis else if (geheim) "•".repeat(wert.length) else wert, style = if (wert.isEmpty()) Schrift.Leise else Schrift.Normal)
			}
		}
	} else {
		LaunchedEffect(Unit) { runCatching { feld.requestFocus() } }
		Box(Modifier.width(620.dp).border(2.dp, Farben.Akzent, RoundedCornerShape(10.dp)).background(Farben.Flaeche, RoundedCornerShape(10.dp)).padding(horizontal = 16.dp, vertical = 12.dp)) {
			if (wert.isEmpty()) Text(hinweis, style = Schrift.Leise)
			BasicTextField(value = wert, onValueChange = aendern, singleLine = true, textStyle = Schrift.Normal, cursorBrush = SolidColor(Farben.Akzent),
				visualTransformation = if (geheim) PasswordVisualTransformation() else VisualTransformation.None,
				keyboardOptions = KeyboardOptions(keyboardType = art),
				keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { bearbeiten = false }),
				modifier = Modifier.fillMaxWidth().focusRequester(feld).onFocusChanged {
					// Verlässt das Feld den Fokus, wird es wieder zur Anzeige
					if (it.isFocused) hatteFokus = true else if (hatteFokus) { hatteFokus = false; bearbeiten = false }
				})
		}
	}
}
