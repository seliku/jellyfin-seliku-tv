package ch.seliku.handy.seerr

import android.content.Context
import kotlinx.coroutines.*
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.*
import org.jellyfin.sdk.model.api.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Duration
import java.time.Instant
import ch.seliku.handy.daten.Timber

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
enum class SeerrZustand(val abzeichen: String?) {
	ANFRAGEN(null),
	ANGEFRAGT("Angefragt"),          // wartet auf Freigabe
	WIRD_GESUCHT("Angefragt"),       // freigegeben, Radarr/Sonarr sucht
	NOCH_NICHT("Angefragt"),         // gesucht, aber (noch) nichts gefunden
	GLEICH("Gleich verfügbar"),      // gefunden, lädt
	ANSEHEN("Jetzt ansehen"),
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
		status == 4 || status == 5 -> SeerrZustand.ANSEHEN
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
	/** Serie ohne TheTVDB-Nummer: Sonarr kann sie nicht laden, Seerr verwirft die Anfrage still (S 15.11.). */
	val ohneTvdb: Boolean = false,
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
				ohneTvdb = !film && (o.optJSONObject("externalIds")?.optInt("tvdbId", 0) ?: 0) <= 0,
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

	/** Seliku-Banner: eine Liste (Empfehlungen, Trends) samt TMDb-Genre-Nummern je Titel. */
	suspend fun mitGenres(pfad: String, typ: String?): List<Pair<SeerrTitel, List<Int>>> {
		val i = meineId()
		val a = json(pfad).optJSONArray("results") ?: JSONArray()
		return (0 until a.length()).mapNotNull { n ->
			val o = a.getJSONObject(n)
			SeerrTitel.ausJson(o, typ, i)?.let { t -> t to (o.optJSONArray("genreIds")?.let { g -> (0 until g.length()).map { g.optInt(it) } } ?: emptyList()) }
		}
	}
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
		val (text, v) = anfrage("POST", "/api/v1/request", body)
		// 202 heisst bei Seerr: nichts angelegt (keine Staffel mehr offen). Bisher sah das aus wie ein Knopf, der nichts tut.
		if (v.responseCode == 202) throw SeerrFehler(202, text.take(200))
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

