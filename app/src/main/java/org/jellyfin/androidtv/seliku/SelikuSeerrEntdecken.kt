package org.jellyfin.androidtv.seliku

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.base.Text
import org.koin.compose.koinInject
import timber.log.Timber

// ───────────────────────── Daten fürs Entdecken ─────────────────────────

/**
 * Genre, Studio oder Sender: eine Kachel, die eine eigene Liste öffnet.
 * art: «film-genre», «serie-genre», «studio», «sender» (und «person» für die Filmografie).
 */
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

/** Welche Liste eine Unterseite zeigt. */
private fun quelleFuer(s: Seite.SeerrListe) = when (s.art) {
	"film-genre" -> SeerrBlaettern("/discover/movies/genre/${s.id}", "movie", hoechstens = 25)
	"serie-genre" -> SeerrBlaettern("/discover/tv/genre/${s.id}", "tv", hoechstens = 25)
	"studio" -> SeerrBlaettern("/discover/movies/studio/${s.id}", "movie", hoechstens = 25)
	"sender" -> SeerrBlaettern("/discover/tv/network/${s.id}", "tv", hoechstens = 25)
	else -> SeerrBlaettern(null, null, alles = { it.filmografie(s.id) })
}

// ───────────────────────── Bausteine ─────────────────────────

/** Kachel für Genre (Hintergrundbild mit Namen) bzw. Studio/Sender (Logo). */
@Composable
fun SeerrRubrikKachel(r: SeerrRubrik, onClick: () -> Unit, merken: Pair<SelikuViewModel, Pair<String, String>>? = null, onFokus: () -> Unit = {}) {
	val daten = koinInject<SeerrDaten>()
	var logo by remember(r.schluessel) { mutableStateOf(SeerrLogos.bekannt[r.schluessel]?.ifEmpty { null }) }
	var logoKaputt by remember(r.schluessel) { mutableStateOf(false) }
	if (r.istFirma) LaunchedEffect(r.schluessel) {
		if (logo == null && SeerrLogos.bekannt[r.schluessel] == null) logo = runCatching { daten.logo(r) }.onFailure { Timber.d("Logo ${r.name}: ${it.message}") }.getOrNull()
	}
	val breite = if (r.istFirma) 200.dp else 230.dp
	val name = if (logo != null) SeerrLogos.namen[r.schluessel] ?: r.name else r.name
	Column(Modifier.width(breite)) {
		Waehlbar(onClick = onClick, merken = merken, onFokus = onFokus) {
			Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Farben.Flaeche), contentAlignment = Alignment.Center) {
				if (!r.istFirma) {
					Bild(r.bild, Modifier.fillMaxSize(), grund = Farben.Flaeche)
					Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0x66000000), Color(0xD0000000)))))
					Text(r.name, style = Schrift.Titel, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(12.dp))
				} else {
					val l = logo
					if (l != null && !logoKaputt) AsyncImage(model = l, contentDescription = r.name, imageLoader = koinInject<ImageLoader>(), contentScale = ContentScale.Fit,
						onError = { logoKaputt = true }, modifier = Modifier.fillMaxSize().padding(horizontal = 26.dp, vertical = 22.dp))
					else Text(r.name, style = Schrift.Titel, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(12.dp))
				}
			}
		}
		if (r.istFirma) {
			Spacer(Modifier.height(6.dp))
			Text(name, style = Schrift.Klein, maxLines = 1, overflow = TextOverflow.Ellipsis)
		}
	}
}

/** Reihe aus Genres, Studios oder Sendern; jede Kachel öffnet ihre Liste. */
@Composable
fun SeerrRubrikReihe(titel: String, liste: List<SeerrRubrik>, vm: SelikuViewModel, seite: Seite, onFokus: (SeerrRubrik) -> Unit = {}) {
	if (liste.isEmpty()) return
	Column(Modifier.fillMaxWidth().padding(bottom = 18.dp)) {
		Text(titel, style = Schrift.Reihe, modifier = Modifier.padding(start = 48.dp, bottom = 8.dp))
		val zustand = remember(seite.schluessel, titel) { vm.listen.getOrPut("${seite.schluessel}|$titel") { LazyListState() } }
		SeitlichRollen {
			LazyRow(state = zustand, contentPadding = PaddingValues(horizontal = 48.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
				items(liste, key = { titel + it.schluessel }) { r ->
					SeerrRubrikKachel(r, onClick = { vm.zu(Seite.SeerrListe(r.art, r.id, SeerrLogos.namen[r.schluessel] ?: r.name)) },
						merken = vm to (seite.schluessel to titel + r.schluessel), onFokus = { onFokus(r) })
				}
			}
		}
	}
}

/** Besetzung: runde Bilder mit Name und Rolle; öffnet die Filmografie der Person. */
@Composable
fun SeerrBesetzungReihe(titel: String, liste: List<SeerrPerson>, vm: SelikuViewModel, seite: Seite) {
	if (liste.isEmpty()) return
	Column(Modifier.fillMaxWidth().padding(bottom = 18.dp)) {
		Text(titel, style = Schrift.Reihe, modifier = Modifier.padding(start = 48.dp, bottom = 8.dp))
		val zustand = remember(seite.schluessel, titel) { vm.listen.getOrPut("${seite.schluessel}|$titel") { LazyListState() } }
		SeitlichRollen {
			LazyRow(state = zustand, contentPadding = PaddingValues(horizontal = 48.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
				items(liste, key = { "person-${it.id}" }) { p ->
					Column(Modifier.width(112.dp), horizontalAlignment = Alignment.CenterHorizontally) {
						Waehlbar(onClick = { vm.zu(Seite.SeerrListe("person", p.id, p.name)) }, shape = CircleShape, merken = vm to (seite.schluessel to "person-${p.id}")) {
							Bild(p.bild, Modifier.size(104.dp), ersatz = initialen(p.name))
						}
						Spacer(Modifier.height(6.dp))
						Text(p.name, style = Schrift.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
						Text(p.rolle, style = Schrift.Klein, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
					}
				}
			}
		}
	}
}

// ───────────────────────── Seite ─────────────────────────

/** Alle Titel eines Genres, Studios, Senders oder einer Person als Raster; lädt beim Herunterblättern nach. */
@Composable
fun SeerrListenSeite(vm: SelikuViewModel, seite: Seite.SeerrListe) {
	val daten = koinInject<SeerrDaten>()
	val scope = rememberCoroutineScope()
	// In der Seitenablage, damit nach der Detailseite die nachgeladenen Seiten und die Stelle noch da sind
	val quelle = remember(seite.schluessel) { vm.wahl.getOrPut("${seite.schluessel}|liste") { quelleFuer(seite) } as SeerrBlaettern }
	var hero by remember { mutableStateOf<String?>(null) }
	LaunchedEffect(seite.schluessel) { if (quelle.liste.isEmpty()) quelle.mehr(daten) }
	val untertitel = stringResource(when (seite.art) {
		"film-genre" -> R.string.seliku_movies
		"serie-genre" -> R.string.seliku_series_plural
		"studio" -> R.string.seliku_seerr_studio
		"sender" -> R.string.seliku_seerr_network
		else -> R.string.seliku_seerr_filmography
	})
	Box(Modifier.fillMaxSize()) {
		Kulisse(hero)
		Column(Modifier.fillMaxSize()) {
			Row(Modifier.fillMaxWidth().padding(start = 48.dp, top = 28.dp, end = 48.dp, bottom = 4.dp), verticalAlignment = Alignment.Bottom) {
				Text(seite.name, style = Schrift.Titel, maxLines = 1, overflow = TextOverflow.Ellipsis)
				Spacer(Modifier.width(16.dp))
				Text(untertitel, style = Schrift.Leise)
			}
			when {
				quelle.liste.isEmpty() && !quelle.begonnen -> Hinweis(stringResource(R.string.seliku_loading))
				quelle.liste.isEmpty() && quelle.fehler != null -> Hinweis(stringResource(R.string.seliku_seerr_unreachable))
				quelle.liste.isEmpty() -> Hinweis(stringResource(R.string.seliku_no_titles))
				else -> LazyVerticalGrid(
					state = vm.raster(seite),
					columns = GridCells.Adaptive(128.dp),
					contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 16.dp, bottom = 40.dp),
					horizontalArrangement = Arrangement.spacedBy(20.dp),
					verticalArrangement = Arrangement.spacedBy(22.dp),
					modifier = Modifier.fillMaxSize(),
				) {
					itemsIndexed(quelle.liste, key = { _, t -> t.schluessel }) { i, t ->
						SeerrKachel(t, onClick = { vm.zu(Seite.SeerrDetail(t.typ, t.tmdb)) }, breite = 128.dp, merken = vm to (seite.schluessel to t.schluessel), onFokus = {
							hero = t.hintergrund
							// Etwa zwei Zeilen vor dem Ende die nächste Seite holen
							if (i >= quelle.liste.size - 12) scope.launch { quelle.mehr(daten) }
						})
					}
				}
			}
		}
	}
}

/**
 * Von Seerr empfohlene ähnliche Titel, die in der Bibliothek noch fehlen (für die gemischte Reihe «Ähnliche Titel»).
 * Leer ohne Seerr-Verbindung oder TMDB-Nummer.
 */
@Composable
fun seerrFehlendeAehnliche(item: org.jellyfin.sdk.model.api.BaseItemDto?, vm: SelikuViewModel, seite: Seite): List<SeerrTitel> {
	val context = androidx.compose.ui.platform.LocalContext.current
	val daten = koinInject<SeerrDaten>()
	val typ = when (item?.type) {
		org.jellyfin.sdk.model.api.BaseItemKind.MOVIE -> "movie"
		org.jellyfin.sdk.model.api.BaseItemKind.SERIES -> "tv"
		else -> null
	}
	val tmdb = item?.providerIds?.entries?.firstOrNull { it.key.equals("Tmdb", true) }?.value?.toIntOrNull()
	if (typ == null || tmdb == null || !SeerrVerbindung.verbunden(context)) return emptyList()
	val quelle = remember(seite.schluessel, tmdb) {
		vm.wahl.getOrPut("${seite.schluessel}|seerr-aehnlich") { SeerrBlaettern("/$typ/$tmdb/recommendations", typ, hoechstens = 2) } as SeerrBlaettern
	}
	LaunchedEffect(seite.schluessel, tmdb) { if (quelle.liste.isEmpty()) quelle.mehr(daten) }
	return quelle.liste.filter { it.zustand != SeerrZustand.ANSEHEN }
}

/**
 * «Ähnliche Titel» aus einem Guss: was schon da ist und was noch fehlt (Seerr), abwechselnd in einer Reihe.
 * Gleiches Verhalten wie [Reihe]: der erste Platz ist offen, die Reihe gleitet darunter durch.
 */
@Composable
fun GemischteReihe(
	titel: String,
	eigene: List<org.jellyfin.sdk.model.api.BaseItemDto>,
	fehlende: List<SeerrTitel>,
	vm: SelikuViewModel,
	seite: Seite,
	oeffnen: (org.jellyfin.sdk.model.api.BaseItemDto) -> Unit,
) {
	val eintraege: List<Any> = remember(eigene, fehlende.toList()) {
		buildList {
			val n = maxOf(eigene.size, fehlende.size)
			for (i in 0 until n) { eigene.getOrNull(i)?.let(::add); fehlende.getOrNull(i)?.let(::add) }
		}
	}
	if (eintraege.isEmpty()) return
	fun schluessel(e: Any) = if (e is SeerrTitel) "seerr-" + e.schluessel else (e as org.jellyfin.sdk.model.api.BaseItemDto).id.toString()
	Column(Modifier.fillMaxWidth().padding(bottom = 18.dp)) {
		Text(titel, style = Schrift.Reihe, modifier = Modifier.padding(start = 48.dp, bottom = 8.dp))
		val zustand = remember(seite.schluessel, titel) { vm.listen.getOrPut("${seite.schluessel}|$titel") { LazyListState() } }
		val offenSchluessel = "${seite.schluessel}|$titel|offen"
		var offenNr by remember(offenSchluessel) { mutableStateOf(vm.wahl[offenSchluessel] as? Int ?: 0) }
		@Composable fun zeige(e: Any, merken: Pair<SelikuViewModel, Pair<String, String>>, gewaehlt: () -> Unit) {
			if (e is SeerrTitel) SeerrKachel(e, onClick = { vm.zu(Seite.SeerrDetail(e.typ, e.tmdb)) }, merken = merken, onFokus = gewaehlt)
			else (e as org.jellyfin.sdk.model.api.BaseItemDto).let { item ->
				PosterKachel(item, { oeffnen(item) }, breite = 120.dp, merken = merken, onFokus = gewaehlt, onMenue = { vm.menue = item }, aufklappen = true)
			}
		}
		Band(eintraege.size, zustand, offenNr.coerceIn(0, eintraege.lastIndex), SelikuEinstellungen.netflixKacheln(androidx.compose.ui.platform.LocalContext.current),
			schluessel = { schluessel(eintraege[it]) },
			buehne = { nr -> zeige(eintraege[nr], vm to (seite.schluessel to "$titel|buehne"), {}) },
		) { nr ->
			val e = eintraege[nr]
			zeige(e, vm to (seite.schluessel to "$titel|${schluessel(e)}")) { offenNr = nr; vm.wahl[offenSchluessel] = nr }
		}
	}
}
