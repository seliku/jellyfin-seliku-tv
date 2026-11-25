package ch.seliku.handy.daten

import android.content.Context
import ch.seliku.handy.Einstellungen
import ch.seliku.handy.seerr.SeerrDaten
import ch.seliku.handy.seerr.SeerrTitel
import ch.seliku.handy.seerr.SeerrVerbindung
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

// Aus der TV-App übernommen (SelikuVerlauf.kt): Sehzeit, Banner-Gedächtnis, Banner-Auswahl, Seerr-Banner.

/** Jellyfin liefert LastPlayedDate in UTC (ohne Zone); hier in Ortszeit. */
fun BaseItemDto.zuletztOrtszeit(): LocalDateTime? = userData?.lastPlayedDate
	?.atOffset(ZoneOffset.UTC)?.atZoneSameInstant(ZoneId.systemDefault())?.toLocalDateTime()

/** Sehzeit in Minuten; [geschaetzt]: aus Laufzeit und Fortschritt hochgerechnet, nicht vom Plugin «Playback Reporting». */
data class Sehzeit(val minuten: Int, val geschaetzt: Boolean)

/** Überblick oben auf «Mein Verlauf». */
data class VerlaufUeberblick(val woche: Sehzeit, val monat: Sehzeit, val serien: List<String>, val genres: List<String>)

/**
 * Echte Sehzeit vom Plugin «Playback Reporting» (Tabelle PlaybackActivity, PlayDuration in Sekunden).
 * Liefert null, wenn das Plugin fehlt, der Benutzer es nicht abfragen darf oder es erst nach [ab] Daten hat.
 */
private suspend fun pluginSehzeit(api: ApiClient, benutzer: UUID, ab: LocalDate): Int? = withContext(Dispatchers.IO) {
	runCatching {
		val id = benutzer.toString().replace("-", "").lowercase()
		fun abfrage(sql: String): org.json.JSONArray {
			val v = java.net.URL("${api.baseUrl?.trimEnd('/')}/user_usage_stats/submit_custom_query").openConnection() as java.net.HttpURLConnection
			v.requestMethod = "POST"; v.doOutput = true; v.connectTimeout = 5_000; v.readTimeout = 10_000
			v.setRequestProperty("Authorization", "MediaBrowser Token=\"${api.accessToken.orEmpty()}\"")
			v.setRequestProperty("Content-Type", "application/json")
			v.outputStream.use { it.write(org.json.JSONObject().put("CustomQueryString", sql).put("ReplaceUserId", false).toString().toByteArray()) }
			if (v.responseCode !in 200..299) error("Playback Reporting: ${v.responseCode}")
			return org.json.JSONObject(v.inputStream.bufferedReader().readText()).getJSONArray("results")
		}
		val wer = "replace(lower(UserId),'-','') = '$id'"
		// Erst ab dem ersten Eintrag ist die Zahl vollständig; davor schätzen
		val erster = abfrage("SELECT MIN(DateCreated) FROM PlaybackActivity WHERE $wer").optJSONArray(0)?.optString(0).orEmpty()
		if (erster.length < 10 || erster.take(10) > ab.toString()) return@runCatching null
		val sekunden = abfrage("SELECT SUM(PlayDuration) FROM PlaybackActivity WHERE $wer AND DateCreated >= '$ab'")
			.optJSONArray(0)?.optString(0)?.toDoubleOrNull() ?: 0.0
		(sekunden / 60).toInt()
	}.onFailure { Timber.d(it, "Playback Reporting") }.getOrNull()
}

/** Geschätzte Minuten seit [ab]: Gesehenes mit voller Laufzeit, Angefangenes bis zur Position. */
private fun geschaetzt(verlauf: List<BaseItemDto>, ab: LocalDate): Int = verlauf
	.filter { (it.zuletztOrtszeit()?.toLocalDate() ?: LocalDate.MIN) >= ab }
	.sumOf { i -> ((if (i.userData?.played == true) i.runTimeTicks else i.userData?.playbackPositionTicks) ?: 0L) / 600_000_000L }
	.toInt()

suspend fun verlaufUeberblick(api: ApiClient, daten: SelikuDaten, verlauf: List<BaseItemDto>): VerlaufUeberblick {
	val heute = LocalDate.now()
	val wochenAnfang = heute.with(java.time.DayOfWeek.MONDAY)
	val monatsAnfang = heute.withDayOfMonth(1)
	val ich = runCatching { daten.benutzer().id }.getOrNull()
	fun zeit(ab: LocalDate, echt: Int?) = if (echt != null) Sehzeit(echt, false) else Sehzeit(geschaetzt(verlauf, ab), true)
	val woche = zeit(wochenAnfang, ich?.let { pluginSehzeit(api, it, wochenAnfang) })
	val monat = zeit(monatsAnfang, ich?.let { pluginSehzeit(api, it, monatsAnfang) })
	// Meistgeschaute Serien der letzten 30 Tage (nach Anzahl Folgen)
	val grenze = heute.minusDays(30)
	val serien = verlauf.filter { it.type == BaseItemKind.EPISODE && (it.zuletztOrtszeit()?.toLocalDate() ?: LocalDate.MIN) >= grenze }
		.groupingBy { it.seriesName.orEmpty() }.eachCount().filterKeys { it.isNotBlank() }
		.entries.sortedByDescending { it.value }.take(3).map { it.key }
	val genres = runCatching { daten.lieblingsGenres().take(4) }.getOrDefault(emptyList())
	return VerlaufUeberblick(woche, monat, serien, genres)
}

/** Zuletzt im Banner gezeigte Titel (nur auf diesem Gerät), damit sie ein paar Tage nicht gleich wiederkommen. */
object BannerGedaechtnis {
	private const val DATEI = "seliku_banner"
	private const val TAGE = 3L

	fun gezeigt(c: Context): Set<String> = runCatching {
		val grenze = System.currentTimeMillis() - TAGE * 24 * 3600_000L
		c.getSharedPreferences(DATEI, Context.MODE_PRIVATE).getString("gezeigt", "").orEmpty().split(",")
			.mapNotNull { e -> e.split(":").takeIf { it.size == 2 }?.let { (id, t) -> id.takeIf { (t.toLongOrNull() ?: 0) > grenze } } }.toSet()
	}.getOrDefault(emptySet())

	fun merken(c: Context, items: List<BaseItemDto>) = merkenSchluessel(c, items.map { it.id.toString() })

	fun merkenSchluessel(c: Context, schluessel: List<String>) {
		runCatching {
			val p = c.getSharedPreferences(DATEI, Context.MODE_PRIVATE)
			val grenze = System.currentTimeMillis() - TAGE * 24 * 3600_000L
			val alt = p.getString("gezeigt", "").orEmpty().split(",").filter { e -> (e.substringAfter(":", "").toLongOrNull() ?: 0) > grenze }
			val jetzt = System.currentTimeMillis()
			val neu = (alt.filter { e -> schluessel.none { e.startsWith("$it:") } } + schluessel.map { "$it:$jetzt" }).takeLast(400)
			p.edit().putString("gezeigt", neu.joinToString(",")).apply()
		}.onFailure { Timber.d(it, "Banner merken") }
	}
}

/** Banner laden: kürzlich Gezeigtes meiden und das Gezeigte merken. */
suspend fun bannerLaden(context: Context, daten: SelikuDaten, bib: BaseItemDto? = null): List<BaseItemDto> {
	val liste = daten.bannerEmpfehlungen(bib, ausser = BannerGedaechtnis.gezeigt(context))
	BannerGedaechtnis.merken(context, liste)
	return liste
}

/** Genre-Nummern von Seerr/TMDb je Name (deutsch), einmal geholt. */
private val seerrGenres = java.util.concurrent.ConcurrentHashMap<String, Int>()

/**
 * Banner: 1–2 Titel, die es auf dem Server (noch) nicht gibt, die aber gefallen könnten – TMDb-Empfehlungen zu
 * Lieblingstiteln und Aktuelles in den Lieblingsgenres, über Seerr. Ohne Seerr oder abgeschaltet: keine.
 * [typ] «movie»/«tv» schränkt ein (Bibliotheksseiten).
 */
suspend fun seerrBanner(context: Context, daten: SelikuDaten, seerr: SeerrDaten, typ: String? = null): List<SeerrTitel> {
	if (!Einstellungen.an("banner_seerr")) return emptyList()
	if (!SeerrVerbindung.verbunden(context)) return emptyList()
	return runCatching {
		val nicht = daten.magNicht()
		val gezeigt = BannerGedaechtnis.gezeigt(context)
		// Empfehlungen zu ein paar zufälligen Lieblingstiteln (Favoriten, 4–5 Sterne)
		val gemocht = daten.gemochteTitel().mapNotNull { i ->
			val tmdb = i.providerIds?.entries?.firstOrNull { it.key.equals("Tmdb", true) }?.value?.toIntOrNull() ?: return@mapNotNull null
			val t = if (i.type == BaseItemKind.SERIES) "tv" else "movie"
			if (typ != null && t != typ) null else t to tmdb
		}.shuffled().take(4)
		val empfohlen = gemocht.flatMap { (t, tmdb) -> runCatching { seerr.mitGenres("/$t/$tmdb/recommendations?page=1&language=de", t) }.getOrDefault(emptyList()) }
		// Aktuelles nur in den Lieblingsgenres
		if (seerrGenres.isEmpty()) listOf("movie", "tv").forEach { t -> runCatching { seerr.genres(t).forEach { seerrGenres[it.name.lowercase()] = it.id } } }
		val lieblinge = daten.lieblingsGenres().take(6).mapNotNull { seerrGenres[it.lowercase()] }.toSet()
		val trend = if (lieblinge.isEmpty()) emptyList()
		else runCatching { seerr.mitGenres("/discover/trending?page=1&language=de", null) }.getOrDefault(emptyList()).filter { (_, g) -> g.any { it in lieblinge } }
		val passend = (empfohlen.shuffled() + trend.shuffled()).map { it.first }
			// Nur, was nicht schon da ist (Status 4/5 = auf dem Server), mit Bild, nicht abgelehnt
			.filter { it.status < 4 && it.hintergrund != null && (typ == null || it.typ == typ) && "seerr-${it.schluessel}" !in nicht }
			// Ohne deutsche Beschreibung (TMDb fällt dann auf Englisch zurück) lieber nicht in den Banner
			.filter { deutscheBeschreibung(it.text) }
			.distinctBy { it.schluessel }
		val frisch = passend.filter { "seerr-${it.schluessel}" !in gezeigt }.ifEmpty { passend }
		// Mal einer, mal zwei
		val wahl = frisch.take(if (Math.random() < 0.5) 1 else 2)
		BannerGedaechtnis.merkenSchluessel(context, wahl.map { "seerr-${it.schluessel}" })
		wahl
	}.onFailure { Timber.d(it, "Seerr-Banner") }.getOrDefault(emptyList())
}

/** Grobe Prüfung, ob eine Beschreibung deutsch ist: mehr typisch deutsche als typisch englische Füllwörter. */
fun deutscheBeschreibung(text: String?): Boolean {
	val w = text.orEmpty().lowercase().split(Regex("[^a-zäöüß]+"))
	if (w.size < 5) return false
	val de = setOf("der", "die", "das", "und", "ist", "ein", "eine", "nicht", "mit", "sich", "auf", "zu", "den", "von")
	val en = setOf("the", "and", "is", "of", "to", "a", "with", "his", "her", "their", "in", "who")
	return w.count { it in de } > w.count { it in en }
}
