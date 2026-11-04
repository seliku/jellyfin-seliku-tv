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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
	fun verbunden(c: Context) = adresse(c).isNotBlank() && cookie(c).isNotBlank()
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
		private const val BILD = "https://image.tmdb.org/t/p/"
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

class SeerrFehler(val code: Int, text: String) : Exception(text)

/** Abfragen an Seerr mit dem gespeicherten Sitzungs-Cookie. */
class SeerrDaten(private val context: Context, private val api: ApiClient) {
	private var ich: Int? = null

	private suspend fun anfrage(methode: String, pfad: String, body: JSONObject? = null, basis: String = SeerrVerbindung.adresse(context),
		cookie: String? = SeerrVerbindung.cookie(context), kopf: Map<String, String> = emptyMap()): Pair<String, HttpURLConnection> = withContext(Dispatchers.IO) {
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
	val text = z.abzeichen ?: return
	val grund = when (z) { SeerrZustand.ANSEHEN -> Farben.Akzent; SeerrZustand.GLEICH -> Color(0xFF7FB069); else -> Color(0xE0221A16) }
	Box(modifier.padding(6.dp).background(grund, RoundedCornerShape(8.dp)).padding(horizontal = 7.dp, vertical = 3.dp)) {
		Text(text, style = Schrift.Klein.copy(color = if (z == SeerrZustand.ANSEHEN || z == SeerrZustand.GLEICH) Color.Black else Farben.Text, fontWeight = FontWeight.Bold))
	}
}

@Composable
fun SeerrKachel(t: SeerrTitel, onClick: () -> Unit, breite: Dp = 120.dp, merken: Pair<SelikuViewModel, Pair<String, String>>? = null, onFokus: () -> Unit = {}) {
	Column(Modifier.width(breite)) {
		Waehlbar(onClick = onClick, merken = merken, onFokus = onFokus) {
			Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f)) {
				Bild(t.poster, Modifier.fillMaxSize(), ersatz = initialen(t.titel))
				SeerrAbzeichen(t.zustand, Modifier.align(Alignment.BottomStart))
			}
		}
		Spacer(Modifier.height(6.dp))
		Text(t.titel, style = Schrift.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
		Text(listOf(t.jahr, if (t.typ == "tv") "Serie" else "Film").filter { it.isNotBlank() }.joinToString(" · "), style = Schrift.Klein, maxLines = 1)
	}
}

@Composable
fun SeerrReihe(titel: String, liste: List<SeerrTitel>, vm: SelikuViewModel, seite: Seite, onFokus: (SeerrTitel) -> Unit = {}) {
	if (liste.isEmpty()) return
	Column(Modifier.fillMaxWidth().padding(bottom = 18.dp)) {
		Text(titel, style = Schrift.Reihe, modifier = Modifier.padding(start = 48.dp, bottom = 8.dp))
		SeitlichRollen {
			LazyRow(contentPadding = PaddingValues(horizontal = 48.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
				items(liste, key = { titel + it.schluessel }) { t ->
					SeerrKachel(t, onClick = { vm.zu(Seite.SeerrDetail(t.typ, t.tmdb)) }, merken = vm to (seite.schluessel to titel + t.schluessel), onFokus = { onFokus(t) })
				}
			}
		}
	}
}

// ───────────────────────── Seiten ─────────────────────────

/** «Entdecken»: blättern wie bei einem Bezahldienst, auch durch Titel, die noch fehlen. */
@Composable
fun EntdeckenSeite(vm: SelikuViewModel) {
	val daten = koinInject<SeerrDaten>()
	val seite = Seite.Entdecken
	@Suppress("UNCHECKED_CAST")
	var reihen by remember { mutableStateOf(vm.wahl["entdecken"] as? List<Pair<String, List<SeerrTitel>>>) }
	var fehler by remember { mutableStateOf<String?>(null) }
	var hero by remember { mutableStateOf<SeerrTitel?>(null) }
	LaunchedEffect(Unit) {
		runCatching {
			coroutineScope {
				val anfragen = async { runCatching { daten.meineAnfragen() }.getOrDefault(emptyList()) }
				val trend = async { daten.imTrend() }
				val filme = async { daten.beliebteFilme() }
				val serien = async { daten.beliebteSerien() }
				val bald = async { runCatching { daten.baldImKino() }.getOrDefault(emptyList()) }
				listOf(
					"Deine Anfragen" to anfragen.await().filter { it.zustand != SeerrZustand.ANSEHEN },
					"Im Trend" to trend.await(),
					"Beliebte Filme" to filme.await(),
					"Beliebte Serien" to serien.await(),
					"Bald im Kino" to bald.await(),
				).filter { it.second.isNotEmpty() }
			}
		}.onSuccess { reihen = it; vm.wahl["entdecken"] = it }
			.onFailure { Timber.w(it, "Entdecken"); fehler = if (it is SeerrFehler && it.code == 403 || it is SeerrFehler && it.code == 401) "Die Anmeldung bei Seerr ist abgelaufen – unter Einstellungen neu verbinden." else "Seerr ist gerade nicht erreichbar." }
	}
	Box(Modifier.fillMaxSize()) {
		Kulisse(hero?.hintergrund)
		val r = reihen
		when {
			r == null && fehler != null -> Hinweis(fehler!!)
			r == null -> Hinweis("Wird geladen …")
			else -> SenkrechtRollen {
				LazyColumn(state = vm.liste(seite), contentPadding = PaddingValues(top = 28.dp, bottom = 48.dp), modifier = Modifier.fillMaxSize()) {
					item { Text("Entdecken", style = Schrift.Titel, modifier = Modifier.padding(start = 48.dp, bottom = 16.dp)) }
					items(r, key = { it.first }) { (titel, liste) -> SeerrReihe(titel, liste, vm, seite) { hero = it } }
				}
			}
		}
	}
}

/** Detailseite eines Seerr-Titels mit dem Knopf, der dem Stand folgt: Anfragen → Wird gesucht → Gleich verfügbar / Noch nicht verfügbar → Ansehen. */
@Composable
fun SeerrDetailSeite(vm: SelikuViewModel, seite: Seite.SeerrDetail, oeffnen: (BaseItemDto) -> Unit) {
	val daten = koinInject<SeerrDaten>()
	val scope = rememberCoroutineScope()
	var t by remember { mutableStateOf<SeerrTitel?>(null) }
	var meldung by remember { mutableStateOf<String?>(null) }
	var neu by remember { mutableIntStateOf(0) }
	var arbeitet by remember { mutableStateOf(false) }
	val erster = remember { FocusRequester() }
	LaunchedEffect(seite.tmdb, neu) {
		runCatching { daten.titel(seite.typ, seite.tmdb) }.onSuccess { t = it }.onFailure { Timber.w(it, "Seerr-Titel"); meldung = "Seerr ist gerade nicht erreichbar." }
	}
	// Solange gesucht/geladen wird, still nachsehen, ob sich etwas tut
	LaunchedEffect(t?.zustand) {
		val z = t?.zustand ?: return@LaunchedEffect
		if (z == SeerrZustand.WIRD_GESUCHT || z == SeerrZustand.GLEICH || z == SeerrZustand.ANGEFRAGT) { delay(30_000); neu++ }
	}
	val x = t
	Box(Modifier.fillMaxSize()) {
		Kulisse(x?.hintergrund)
		if (x == null) { Hinweis(meldung ?: "Wird geladen …"); return@Box }
		LaunchedEffect(x.tmdb) { delay(150); runCatching { erster.requestFocus() } }
		Row(Modifier.fillMaxSize().padding(start = 48.dp, top = 48.dp, end = 64.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
			Bild(x.poster, Modifier.width(220.dp).aspectRatio(2f / 3f), ersatz = initialen(x.titel))
			Column(Modifier.weight(1f)) {
				Text(x.titel, style = Schrift.Riesig, maxLines = 2, overflow = TextOverflow.Ellipsis)
				Spacer(Modifier.height(6.dp))
				Text(listOf(x.jahr, if (x.typ == "tv") "Serie" else "Film").filter { it.isNotBlank() }.joinToString(" · "), style = Schrift.Leise)
				Spacer(Modifier.height(14.dp))
				Text(x.text, style = Schrift.Normal, maxLines = 7, overflow = TextOverflow.Ellipsis)
				Spacer(Modifier.height(22.dp))
				val tun: (suspend () -> Unit) -> Unit = { aktion ->
					if (!arbeitet) scope.launch {
						arbeitet = true
						runCatching { aktion() }.onFailure { meldung = if (it is SeerrFehler && it.code == 403) "Dafür fehlt in Seerr die Berechtigung." else "Hat nicht geklappt: ${it.message?.take(80)}" }
						arbeitet = false; neu++
					}
				}
				Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
					val m = Modifier.focusRequester(erster)
					when (x.zustand) {
						SeerrZustand.ANFRAGEN -> Knopf(if (arbeitet) "Wird angefragt …" else "Anfragen", symbol = "+", hervorgehoben = true, modifier = m, onClick = { tun { daten.anfragen(x) } })
						SeerrZustand.ANGEFRAGT -> Knopf("Angefragt – wartet auf Freigabe", symbol = "⏳", modifier = m, onClick = { neu++ })
						SeerrZustand.WIRD_GESUCHT -> Knopf("Wird gesucht", symbol = "⌕", modifier = m, onClick = { neu++ })
						SeerrZustand.NOCH_NICHT -> Knopf("Noch nicht verfügbar", symbol = "…", modifier = m, onClick = { neu++ })
						SeerrZustand.GLEICH -> Knopf("Gleich verfügbar", symbol = "↓", hervorgehoben = true, modifier = m, onClick = { neu++ })
						SeerrZustand.ANSEHEN -> Knopf("Ansehen", symbol = "▶", hervorgehoben = true, modifier = m, onClick = {
							tun {
								val item = daten.inJellyfin(x)
								if (item != null) oeffnen(item) else meldung = "In Jellyfin noch nicht gefunden – die Bibliothek liest es gleich ein."
							}
						})
					}
					if (x.anfrageVonMir && x.zustand in setOf(SeerrZustand.ANGEFRAGT, SeerrZustand.WIRD_GESUCHT, SeerrZustand.NOCH_NICHT))
						Knopf("Anfrage zurücknehmen", symbol = "✕", onClick = { tun { daten.zuruecknehmen(x) } })
				}
				meldung?.let { Spacer(Modifier.height(14.dp)); Text(it, style = Schrift.Leise) }
				if (x.zustand == SeerrZustand.NOCH_NICHT) {
					Spacer(Modifier.height(10.dp))
					Text("Bisher hat sich keine passende Quelle gefunden. Die Anfrage bleibt bestehen und wird weiter gesucht.", style = Schrift.Klein)
				}
			}
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
		Text("Mit Seerr blätterst du auch durch Filme und Serien, die noch fehlen, und fragst sie mit einem Klick an. Verbinde dein eigenes Seerr.",
			style = Schrift.Leise, modifier = Modifier.padding(top = 6.dp, bottom = 18.dp, end = 200.dp))
		if (verbunden) {
			Text("Verbunden mit $url" + if (name.isNotBlank()) " als $name" else "", style = Schrift.Normal)
			Spacer(Modifier.height(14.dp))
			Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
				Knopf("Entdecken öffnen", symbol = "›", hervorgehoben = true, onClick = { vm.wurzel(Seite.Entdecken) })
				Knopf("Trennen", symbol = "✕", onClick = { SeerrVerbindung.trennen(context); verbunden = false; name = "" })
			}
			return@Column
		}
		Eingabe("Adresse, z. B. wunsch.example.ch", adresse, { adresse = it; meldung = null }, KeyboardType.Uri)
		Spacer(Modifier.height(14.dp))
		when (weg) {
			0 -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
				Knopf("Mit Plex anmelden", symbol = "▶", hervorgehoben = true, onClick = {
					if (adresse.isBlank()) { meldung = "Zuerst die Adresse eingeben."; return@Knopf }
					scope.launch {
						runCatching { daten.plexCode() }.onSuccess { (pin, code) ->
							plexCode = code; weg = 1
							// Bis zu 10 Minuten nachsehen, ob der Code eingegeben wurde
							repeat(200) {
								delay(3_000)
								if (weg != 1) return@launch
								val n = runCatching { daten.plexPruefen(url, pin) }.onFailure { e -> meldung = "Seerr lehnt die Anmeldung ab: ${e.message?.take(80)}"; weg = 0; return@launch }.getOrNull()
								if (n != null) { fertig(n); return@launch }
							}
							meldung = "Der Code ist abgelaufen. Nochmals versuchen."; weg = 0
						}.onFailure { meldung = "plex.tv nicht erreichbar." }
					}
				})
				Knopf("Mit Benutzer und Passwort", symbol = "›", onClick = { if (adresse.isBlank()) meldung = "Zuerst die Adresse eingeben." else weg = 2 })
			}
			1 -> Column {
				Text("Auf dem Handy oder Computer plex.tv/link öffnen und diesen Code eingeben:", style = Schrift.Normal)
				Spacer(Modifier.height(10.dp))
				Text(plexCode.orEmpty(), style = Schrift.Riesig.copy(color = Farben.Akzent, letterSpacing = 8.sp))
				Spacer(Modifier.height(10.dp))
				Text("Die App wartet und verbindet sich von selbst.", style = Schrift.Leise)
				Spacer(Modifier.height(14.dp))
				Knopf("Abbrechen", symbol = "✕", onClick = { weg = 0; plexCode = null })
			}
			2 -> Column {
				Eingabe("Benutzername oder E-Mail", benutzer, { benutzer = it }, KeyboardType.Email)
				Spacer(Modifier.height(10.dp))
				Eingabe("Passwort", passwort, { passwort = it }, KeyboardType.Password, geheim = true)
				Spacer(Modifier.height(14.dp))
				Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
					Knopf("Anmelden", symbol = "›", hervorgehoben = true, onClick = {
						scope.launch {
							runCatching { daten.anmeldenMitPasswort(url, benutzer.trim(), passwort) }
								.onSuccess { fertig(it) }.onFailure { meldung = "Anmeldung abgelehnt. Benutzer, Passwort und Adresse prüfen." }
						}
					})
					Knopf("Zurück", symbol = "‹", onClick = { weg = 0 })
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
