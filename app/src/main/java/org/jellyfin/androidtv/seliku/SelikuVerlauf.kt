package org.jellyfin.androidtv.seliku

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import org.jellyfin.androidtv.ui.base.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.R
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.koin.compose.koinInject
import timber.log.Timber
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

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

/** Wie oft der Banner neu gezogen wird, solange die Seite offen ist (der Fernseher lässt die App tagelang laufen). */
const val BANNER_NEU_MS = 3 * 60 * 60 * 1000L

/**
 * «Mein Verlauf» wie bei Netflix: oben Sehzeit, meistgeschaute Serien und Lieblingsgenres, darunter alles Gesehene
 * nach Tagen. Daumen hoch = Favorit, Daumen runter = nicht mehr empfehlen (beides fliesst in den Banner ein).
 */
@Composable
fun VerlaufSeite(vm: SelikuViewModel, oeffnen: (BaseItemDto) -> Unit) {
	val daten = koinInject<SelikuDaten>()
	val api = koinInject<ApiClient>()
	val bilder = koinInject<Bilder>()
	val context = LocalContext.current
	val scope = rememberCoroutineScope()
	@Suppress("UNCHECKED_CAST")
	var verlauf by remember { mutableStateOf(vm.wahl["verlauf|liste"] as? List<BaseItemDto>) }
	var ueberblick by remember { mutableStateOf(vm.wahl["verlauf|ueberblick"] as? VerlaufUeberblick) }
	// Geschmack je Titel (bei Folgen: der Serie): Favorit, eigene Sterne, «gefällt mir nicht»
	var favoriten by remember { mutableStateOf<Set<UUID>>(emptySet()) }
	var sterne by remember { mutableStateOf<Map<UUID, Int>>(emptyMap()) }
	var magNicht by remember { mutableStateOf<Set<String>>(emptySet()) }

	LaunchedEffect(vm.datenStand) {
		runCatching {
			val l = daten.verlauf()
			verlauf = l; vm.wahl["verlauf|liste"] = l
			val titel = daten.titelZu(l.map { it.geschmackId() }.distinct())
			favoriten = titel.filter { it.userData?.isFavorite == true }.map { it.id }.toSet()
			sterne = titel.mapNotNull { t -> t.userData?.rating?.let { t.id to kotlin.math.round(it / 2).toInt() } }.toMap()
			magNicht = daten.magNicht()
			val u = verlaufUeberblick(api, daten, l)
			ueberblick = u; vm.wahl["verlauf|ueberblick"] = u
		}.onFailure { Timber.w(it, "Verlauf laden") }
	}

	Column(Modifier.fillMaxSize().background(Farben.Hintergrund)) {
		Text(stringResource(R.string.seliku_history), style = Schrift.Titel, modifier = Modifier.padding(start = 48.dp, top = 28.dp, bottom = 12.dp))
		val l = verlauf
		if (l == null) { Hinweis(stringResource(R.string.seliku_loading)); return@Column }
		if (l.isEmpty()) { Hinweis(stringResource(R.string.seliku_history_empty)); return@Column }
		val heute = LocalDate.now()
		val tage = l.groupBy { it.zuletztOrtszeit()?.toLocalDate() ?: heute }.toSortedMap(compareByDescending { it })
		// Überblick fest oben: die Karten sind nicht anwählbar, in der Liste rollten sie sonst gleich aus dem Bild
		ueberblick?.let { u -> Box(Modifier.padding(horizontal = 48.dp)) { Ueberblick(u) } }
		LazyColumn(state = vm.liste(Seite.Verlauf), contentPadding = PaddingValues(start = 48.dp, end = 48.dp, bottom = 48.dp)) {
			tage.forEach { (tag, eintraege) ->
				item(key = "tag-$tag") {
					Text(tagName(tag, heute), style = Schrift.Reihe, modifier = Modifier.padding(top = 18.dp, bottom = 8.dp))
				}
				items(eintraege, key = { it.id }) { i ->
					val g = i.geschmackId()
					val gefaellt = g in favoriten || (sterne[g] ?: 0) >= 4
					val gefaelltNicht = g.toString() in magNicht || (sterne[g] ?: 0) in 1..2
					VerlaufZeile(i, bilder, vm, gefaellt, gefaelltNicht,
						onClick = { if (i.type == BaseItemKind.EPISODE) i.seriesId?.let { vm.zu(Seite.Serie(it)) } else oeffnen(i) },
						onGefaellt = {
							val ja = !gefaellt
							favoriten = if (ja) favoriten + g else favoriten - g
							if (ja) magNicht = magNicht - g.toString()
							scope.launch {
								runCatching { daten.favorit(g, ja); if (ja) daten.magNichtSetzen(g, false) }.onFailure { Timber.w(it, "Gefällt mir") }
								vm.wahl.remove("start-banner")
							}
						},
						onGefaelltNicht = {
							val ja = !gefaelltNicht
							magNicht = if (ja) magNicht + g.toString() else magNicht - g.toString()
							if (ja) favoriten = favoriten - g
							scope.launch {
								runCatching { daten.magNichtSetzen(g, ja); if (ja && g in favoriten) daten.favorit(g, false) }.onFailure { Timber.w(it, "Gefällt mir nicht") }
								vm.wahl.remove("start-banner")
							}
						})
				}
			}
		}
	}
}

/** Für «Gefällt mir»: bei Folgen gilt die Serie, sonst der Titel selbst. */
fun BaseItemDto.geschmackId(): UUID = if (type == BaseItemKind.EPISODE) seriesId ?: id else id

@Composable
private fun tagName(tag: LocalDate, heute: LocalDate): String = when (tag) {
	heute -> stringResource(R.string.seliku_history_today)
	heute.minusDays(1) -> stringResource(R.string.seliku_history_yesterday)
	else -> tag.format(java.time.format.DateTimeFormatter.ofPattern(if (tag.year == heute.year) "EEEE, d. MMMM" else "EEEE, d. MMMM yyyy"))
		.replaceFirstChar { it.uppercase() }
}

@Composable
private fun dauer(s: Sehzeit): String {
	val text = if (s.minuten >= 60) stringResource(R.string.seliku_runtime_hours, s.minuten / 60, s.minuten % 60)
	else stringResource(R.string.seliku_runtime_minutes, s.minuten)
	return if (s.geschaetzt) stringResource(R.string.seliku_history_approx, text) else text
}

@Composable
private fun Ueberblick(u: VerlaufUeberblick) {
	Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
		Karte(stringResource(R.string.seliku_history_week), dauer(u.woche), Modifier.weight(1f))
		Karte(stringResource(R.string.seliku_history_month), dauer(u.monat), Modifier.weight(1f))
		Karte(stringResource(R.string.seliku_history_top_series), u.serien.joinToString("\n").ifBlank { "–" }, Modifier.weight(1.4f), klein = true)
		Karte(stringResource(R.string.seliku_history_genres), u.genres.joinToString(" · ").ifBlank { "–" }, Modifier.weight(1.4f), klein = true)
	}
}

@Composable
private fun Karte(titel: String, wert: String, modifier: Modifier, klein: Boolean = false) {
	Column(modifier.height(104.dp).clip(RoundedCornerShape(12.dp)).background(Farben.Flaeche).padding(horizontal = 16.dp, vertical = 12.dp)) {
		Text(titel, style = Schrift.Klein)
		Text(wert, style = if (klein) Schrift.Normal.copy(fontWeight = FontWeight.SemiBold) else Schrift.Titel.copy(color = Farben.Akzent),
			maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
	}
}

@Composable
private fun VerlaufZeile(
	i: BaseItemDto, bilder: Bilder, vm: SelikuViewModel, gefaellt: Boolean, gefaelltNicht: Boolean,
	onClick: () -> Unit, onGefaellt: () -> Unit, onGefaelltNicht: () -> Unit,
) {
	Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
		Waehlbar(onClick = onClick, vergroessern = false, merken = vm to (Seite.Verlauf.schluessel to i.id.toString()), modifier = Modifier.weight(1f)) { fokussiert ->
			Row(Modifier.fillMaxWidth().background(if (fokussiert) Farben.FlaecheHell else Farben.Flaeche).padding(8.dp),
				verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
				Box(Modifier.width(128.dp).height(72.dp).clip(RoundedCornerShape(6.dp))) {
					Bild(bilder.quer(i, 320), Modifier.fillMaxSize(), ersatz = i.seriesName ?: i.name)
					val anteil = i.userData?.playedPercentage?.toFloat()?.div(100f) ?: 0f
					if (i.userData?.played != true && anteil > 0f) Fortschritt(anteil, Modifier.align(Alignment.BottomStart))
				}
				Column(Modifier.weight(1f)) {
					if (i.type == BaseItemKind.EPISODE) {
						Text(i.seriesName.orEmpty(), style = Schrift.Normal.copy(fontWeight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
						Text("S${i.parentIndexNumber ?: 0} · F${i.indexNumber ?: 0}  ${i.name.orEmpty()}", style = Schrift.Leise, maxLines = 1, overflow = TextOverflow.Ellipsis)
					} else {
						Text(i.name.orEmpty(), style = Schrift.Normal.copy(fontWeight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
						i.productionYear?.let { Text(it.toString(), style = Schrift.Leise) }
					}
				}
				i.zuletztOrtszeit()?.let { Text("%02d:%02d".format(it.hour, it.minute), style = Schrift.Klein) }
			}
		}
		SymbolKnopf(R.drawable.seliku_daumen_hoch, stringResource(if (gefaellt) R.string.seliku_tip_like_remove else R.string.seliku_tip_like), aktiv = gefaellt, onClick = onGefaellt)
		SymbolKnopf(R.drawable.seliku_daumen_runter, stringResource(if (gefaelltNicht) R.string.seliku_tip_dislike_remove else R.string.seliku_tip_dislike), aktiv = gefaelltNicht, onClick = onGefaelltNicht)
	}
}

/** Genre-Nummern von Seerr/TMDb je Name (deutsch), einmal geholt. */
private val seerrGenres = java.util.concurrent.ConcurrentHashMap<String, Int>()

/**
 * Banner: 1–2 Titel, die es auf dem Server (noch) nicht gibt, die aber gefallen könnten – TMDb-Empfehlungen zu
 * Lieblingstiteln und Aktuelles in den Lieblingsgenres, über Seerr. Ohne Seerr oder abgeschaltet: keine.
 * [typ] «movie»/«tv» schränkt ein (Bibliotheksseiten).
 */
suspend fun seerrBanner(context: Context, daten: SelikuDaten, seerr: SeerrDaten, typ: String? = null): List<SeerrTitel> {
	if (!SelikuEinstellungen.bannerSeerr(context)) return emptyList()
	if (!SeerrVerbindung.vermittelt.value && !SeerrVerbindung.verbunden(context)) return emptyList()
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
private fun deutscheBeschreibung(text: String?): Boolean {
	val w = text.orEmpty().lowercase().split(Regex("[^a-zäöüß]+"))
	if (w.size < 5) return false
	val de = setOf("der", "die", "das", "und", "ist", "ein", "eine", "nicht", "mit", "sich", "auf", "zu", "den", "von")
	val en = setOf("the", "and", "is", "of", "to", "a", "with", "his", "her", "their", "in", "who")
	return w.count { it in de } > w.count { it in en }
}
