package ch.seliku.handy.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import ch.seliku.handy.Einstellungen
import ch.seliku.handy.Sitzung
import ch.seliku.handy.daten.SelikuDaten
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CollectionType

/** Eine Reihe der Startseite; Platz wie am TV über [gruppe] (Weiterschauen −2, Favoriten −1, Filme 0, Serien 1, Live-TV 2, Musik 3, Rest 4). */
data class StartReihe(val titel: String, val items: List<BaseItemDto>, val art: Art, val gruppe: Int, val loeschdatum: Map<java.util.UUID, String> = emptyMap()) {
	enum class Art(val rang: Int) { WEITER(0), FAVORITEN(0), TV(0), HOEREN(0), NEU(1), ANDERE(2), WAHR(3) }
}

fun gruppeFuer(b: BaseItemDto, aufnahmen: Set<java.util.UUID>): Int = when {
	b.id in aufnahmen -> 2
	b.collectionType == CollectionType.MOVIES -> 0
	b.collectionType == CollectionType.TVSHOWS -> 1
	b.collectionType == CollectionType.LIVETV -> 2
	b.collectionType == CollectionType.MUSIC || b.collectionType == CollectionType.BOOKS -> 3
	else -> 4
}

/** Lädt alle Startseiten-Reihen parallel (Fehler einer Reihe → leer), Reihenfolge wie am Fernseher. */
suspend fun startReihenLaden(c: android.content.Context, d: SelikuDaten): List<StartReihe> = coroutineScope {
	val bibs = d.bibliotheken()
	val aufnahmen = runCatching { d.aufnahmeOrdner() }.getOrDefault(emptySet())
	fun <T> sicher(f: suspend () -> T) = async { runCatching { f() }.getOrNull() }
	val weiter = sicher {
		val w = d.weiterschauen()
		StartReihe("Weiterschauen", if (Einstellungen.an("weiterschauen_geraet")) w.filter { GeraetVerlauf.enthaelt(c, it) } else w, StartReihe.Art.WEITER, -2)
	}
	val fav = sicher { if (Einstellungen.an("favoriten_reihe")) StartReihe("Deine Favoriten", d.favoriten(), StartReihe.Art.FAVORITEN, -1) else null }
	val tv = sicher {
		if (bibs.none { it.collectionType == CollectionType.LIVETV }) null
		else StartReihe("Jetzt im TV", d.sender().filter { it.currentProgram != null }.take(20), StartReihe.Art.TV, 2)
	}
	val neu = bibs.filter { it.collectionType != CollectionType.LIVETV && it.collectionType != CollectionType.PLAYLISTS }.map { b ->
		sicher { StartReihe("Neu in ${b.name}", d.neuIn(b), StartReihe.Art.NEU, gruppeFuer(b, aufnahmen)) }
	}
	val sammlungen = sicher {
		d.startseitenSammlungen().map { (titel, items, daten) ->
			val filme = items.count { it.type == BaseItemKind.MOVIE }
			StartReihe(titel, items, StartReihe.Art.ANDERE, if (filme * 2 >= items.size) 0 else 1, daten)
		}
	}
	val hoeren = sicher { if (Einstellungen.an("hoeren_start")) StartReihe("Weiterhören", ch.seliku.handy.musik.Hoeren.weiterhoeren(), StartReihe.Art.HOEREN, 3) else null }
	val wahr = sicher { if (Einstellungen.an("wahre_geschichten")) StartReihe("Wahre Geschichten", d.wahreGeschichten(), StartReihe.Art.WAHR, 1) else null }
	(listOf(weiter.await(), fav.await(), tv.await(), wahr.await(), hoeren.await()) + neu.awaitAll() + sammlungen.await().orEmpty())
		.filterNotNull().filter { it.items.isNotEmpty() }
		.sortedWith(compareBy<StartReihe>({ it.gruppe }, { it.art.rang }))
}

/** Startseite: Banner oben, darunter die Reihen wie am Fernseher. Ziehen nach unten lädt neu. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StartSeite() {
	val c = LocalContext.current
	val nav = LocalNav.current
	val d = remember { Sitzung.daten }
	var reihen by remember { mutableStateOf(Zwischenspeicher.startReihen ?: Schnellstart.laden(c)) }
	var banner by remember { mutableStateOf(Zwischenspeicher.banner) }
	var laedt by remember { mutableStateOf(false) }
	var neu by remember { mutableIntStateOf(0) }
	LaunchedEffect(Datenstand.stand, neu) {
		laedt = true
		coroutineScope {
			val b = async { if (banner.isEmpty() || neu > 0) runCatching { bannerLaden(c, d, null) }.getOrNull() else null }
			val r = runCatching { startReihenLaden(c, d) }.getOrNull()
			if (r != null) { reihen = r; Zwischenspeicher.startReihen = r; Schnellstart.speichern(c, r) }
			b.await()?.let { banner = it; Zwischenspeicher.banner = it }
		}
		laedt = false
	}
	PullToRefreshBox(isRefreshing = laedt && reihen != null, onRefresh = { neu++ }, modifier = Modifier.fillMaxSize().selikuHintergrund()) {
		LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
			item(key = "banner") { if (banner.isNotEmpty()) Banner(banner) else Spacer(Modifier.statusBarsPadding().height(8.dp)) }
			val r = reihen
			if (r == null) item { Laden() }
			else r.forEach { reihe ->
				item(key = reihe.titel) {
					when (reihe.art) {
						StartReihe.Art.WEITER -> Reihe(reihe.titel, reihe.items, schluessel = { it.seriesId ?: it.id }) { i ->
							QuerKachel(i, 240.dp, zeile1 = hauptzeile(i), zeile2 = unterzeile(i), bildUrl = Bilder.quer(i, 480)) { abspielen(nav, c, i) }
						}
						StartReihe.Art.TV -> Reihe(reihe.titel, reihe.items, schluessel = { it.id }) { i ->
							SenderKachel(i) { abspielen(nav, c, i) }
						}
						StartReihe.Art.HOEREN -> Reihe(reihe.titel, reihe.items, schluessel = { it.id }) { i ->
							val scope = rememberCoroutineScope()
							ch.seliku.handy.musik.HoerKachel(i) { scope.launch { ch.seliku.handy.musik.hoerenStarten(c, i) } }
						}
						StartReihe.Art.ANDERE -> Reihe(reihe.titel, reihe.items, schluessel = { it.id }) { i ->
							QuerKachel(i, 220.dp, zeile2 = reihe.loeschdatum[i.id]?.let { d -> "Wird am " + d.split("-").let { if (it.size == 3) "${it[2]}.${it[1]}." else d } + " gelöscht" } ?: unterzeile(i)) { oeffnen(nav, c, i) }
						}
						else -> Reihe(reihe.titel, reihe.items, schluessel = { it.id }) { i ->
							val musik = i.type == BaseItemKind.MUSIC_ALBUM || i.type == BaseItemKind.AUDIO
							PosterKachel(i, if (musik) 130.dp else 112.dp, quadrat = musik) { oeffnen(nav, c, i) }
						}
					}
				}
			}
		}
	}
}

/** Merkt Daten über Seitenwechsel hinweg (wie `vm.wahl` am TV). */
object Zwischenspeicher {
	var startReihen: List<StartReihe>? = null
	var banner: List<BannerEintrag> = emptyList()
	val bibReihen = mutableMapOf<java.util.UUID, List<StartReihe>>()
	val bibBanner = mutableMapOf<java.util.UUID, List<BannerEintrag>>()
}
