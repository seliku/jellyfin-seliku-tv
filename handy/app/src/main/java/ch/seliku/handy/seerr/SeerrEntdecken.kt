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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf

data class SeerrRubrik(val art: String, val id: Int, val name: String, val bild: String? = null) {
	val schluessel get() = "$art-$id"
	val istFirma get() = art == "studio" || art == "sender"
}

/** Logos der Studios und Sender: einmal geholt, für die ganze Sitzung gemerkt ("" = es gibt keins). */
internal object SeerrLogos {
	val bekannt = java.util.concurrent.ConcurrentHashMap<String, String>()
	/** Name, wie Seerr ihn liefert (stimmt sicher zur Nummer). */
	val namen = java.util.concurrent.ConcurrentHashMap<String, String>()
}

/** Studios wie in Seerrs Entdecken (TMDB-Firmennummern). Markennamen, darum nicht übersetzt. */
val SeerrStudios = listOf(
	SeerrRubrik("studio", 2, "Disney"),
	SeerrRubrik("studio", 127928, "20th Century Studios"),
	SeerrRubrik("studio", 34, "Sony Pictures"),
	SeerrRubrik("studio", 174, "Warner Bros. Pictures"),
	SeerrRubrik("studio", 33, "Universal"),
	SeerrRubrik("studio", 4, "Paramount"),
	SeerrRubrik("studio", 3, "Pixar"),
	SeerrRubrik("studio", 521, "DreamWorks"),
	SeerrRubrik("studio", 420, "Marvel Studios"),
	SeerrRubrik("studio", 9993, "DC"),
	// Nicht in Seerrs eigener Liste, aber gross (S 07.11.: «warum fehlt MGM?»)
	SeerrRubrik("studio", 21, "MGM"),
	SeerrRubrik("studio", 1632, "Lionsgate"),
	SeerrRubrik("studio", 5, "Columbia Pictures"),
	SeerrRubrik("studio", 12, "New Line Cinema"),
	SeerrRubrik("studio", 1, "Lucasfilm"),
	SeerrRubrik("studio", 923, "Legendary"),
	SeerrRubrik("studio", 41077, "A24"),
	SeerrRubrik("studio", 3172, "Blumhouse"),
	SeerrRubrik("studio", 10342, "Studio Ghibli"),
)

/** Sender und Streamingdienste (TMDB-Sendernummern). */
val SeerrSender = listOf(
	SeerrRubrik("sender", 213, "Netflix"),
	SeerrRubrik("sender", 2739, "Disney+"),
	SeerrRubrik("sender", 1024, "Prime Video"),
	SeerrRubrik("sender", 2552, "Apple TV+"),
	SeerrRubrik("sender", 49, "HBO"),
	SeerrRubrik("sender", 3186, "HBO Max"),
	SeerrRubrik("sender", 4330, "Paramount+"),
	SeerrRubrik("sender", 453, "Hulu"),
	SeerrRubrik("sender", 31, "ZDF"),
	SeerrRubrik("sender", 308, "Das Erste"),
)

/** Eine Reihe der Entdecken-Seite. */
sealed interface EntdeckenReihe {
	val titel: String
	class Titel(override val titel: String, val quelle: SeerrBlaettern, val anfragen: Boolean = false) : EntdeckenReihe
	class Rubriken(override val titel: String, val liste: List<SeerrRubrik>) : EntdeckenReihe
}

/**
 * Liste, die seitenweise nachlädt (Reihe oder Raster): die nächste Seite kommt, wenn der Fokus gegen das Ende läuft.
 * Ohne [pfad] eine feste Liste; mit [alles] wird sie auf einmal geholt (Filmografie).
 */
class SeerrBlaettern(
	val pfad: String?,
	val typ: String?,
	anfang: List<SeerrTitel> = emptyList(),
	private val hoechstens: Int = 5,
	private val alles: (suspend (SeerrDaten) -> List<SeerrTitel>)? = null,
) {
	val liste = mutableStateListOf<SeerrTitel>().apply { addAll(anfang.distinctBy { it.schluessel }) }
	private var geladen = 0
	private var laedt = false
	/** Wurde schon (mindestens einmal) geladen? Vorher zeigt das Raster «Wird geladen». */
	var begonnen by mutableStateOf(anfang.isNotEmpty() || (pfad == null && alles == null)); private set
	var fehler by mutableStateOf<Throwable?>(null); private set
	var fertig by mutableStateOf(pfad == null && alles == null); private set

	/** Erste Seite; Fehler gehen an den Aufrufer (Entdecken zeigt dann «nicht erreichbar»). */
	suspend fun erste(daten: SeerrDaten) { if (geladen == 0 && !laedt) holen(daten) }

	/** Nächste Seite, Fehler still: dann ist eben hier Schluss. */
	suspend fun mehr(daten: SeerrDaten) {
		if (fertig || laedt) return
		try { holen(daten) } catch (e: CancellationException) { throw e } catch (e: Exception) {
			Timber.w(e, "Seerr nachladen: $pfad"); fehler = e; fertig = true
		}
	}

	private suspend fun holen(daten: SeerrDaten) {
		laedt = true
		try {
			if (alles != null) {
				ersetzen(alles.invoke(daten)); fertig = true
			} else if (pfad != null) {
				val (neu, seiten) = daten.blatt(pfad, typ, geladen + 1)
				geladen++
				val da = liste.mapTo(HashSet()) { it.schluessel }
				liste.addAll(neu.filter { da.add(it.schluessel) })
				if (neu.isEmpty() || geladen >= minOf(seiten, hoechstens)) fertig = true
			}
		} finally { laedt = false; begonnen = true }
	}

	fun ersetzen(neu: List<SeerrTitel>) { liste.clear(); liste.addAll(neu.distinctBy { it.schluessel }) }
}

/** Welche Liste eine Unterseite zeigt (art: film-genre, serie-genre, studio, sender, person). */
fun quelleFuer(art: String, id: Int) = when (art) {
	"film-genre" -> SeerrBlaettern("/discover/movies/genre/$id", "movie", hoechstens = 25)
	"serie-genre" -> SeerrBlaettern("/discover/tv/genre/$id", "tv", hoechstens = 25)
	"studio" -> SeerrBlaettern("/discover/movies/studio/$id", "movie", hoechstens = 25)
	"sender" -> SeerrBlaettern("/discover/tv/network/$id", "tv", hoechstens = 25)
	else -> SeerrBlaettern(null, null, alles = { it.filmografie(id) })
}
