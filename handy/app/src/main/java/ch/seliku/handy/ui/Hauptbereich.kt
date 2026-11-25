package ch.seliku.handy.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.layout.statusBars
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import ch.seliku.handy.Einstellungen
import ch.seliku.handy.daten.Farben
import ch.seliku.handy.musik.MiniSpieler
import ch.seliku.handy.musik.MusikSeite
import ch.seliku.handy.musik.RadioSeite
import ch.seliku.handy.musik.AlbumSeite
import ch.seliku.handy.musik.InterpretSeite
import ch.seliku.handy.musik.LaeuftSeite
import ch.seliku.handy.seerr.SeerrListenSeite
import ch.seliku.handy.seerr.EntdeckenSeite
import ch.seliku.handy.seerr.SeerrDetailSeite
import ch.seliku.handy.wiedergabe.VideoSpieler
import java.util.UUID

val LocalNav = staticCompositionLocalOf<NavHostController> { error("keine Navigation") }

/** Wege durch die App. Ein Titel öffnet immer die Detailseite, «Abspielen» den Spieler. */
object Wege {
	fun detail(id: UUID) = "detail/$id"
	fun bibliothek(id: UUID) = "bib/$id"
	fun raster(id: UUID, genre: String? = null) = "raster/$id" + (genre?.let { "?genre=" + android.net.Uri.encode(it) } ?: "")
	fun spielen(id: UUID, posMs: Long? = null) = "spieler/$id" + (posMs?.let { "?pos=$it" } ?: "")
	fun seerr(typ: String, tmdb: Int) = "seerr/$typ/$tmdb"
	fun ordner(id: UUID) = "ordner/$id"
}

private data class Reiter(val weg: String, val titel: String, val symbol: ImageVector)

/**
 * Handy statt Seitenleiste: unten vier Reiter (Start, Suche, Entdecken bzw. Bibliotheken, Mehr). «Mehr» enthält, was am
 * Fernseher in der Leiste steht (Bibliotheken, Live-TV, Musik, Radio, Verlauf, Einstellungen).
 */
@Composable
fun Hauptbereich() {
	val nav = rememberNavController()
	val eintrag by nav.currentBackStackEntryAsState()
	val aktuell = eintrag?.destination?.route.orEmpty()
	val reiter = buildList {
		add(Reiter("start", "Start", Icons.Filled.Home))
		add(Reiter("suche", "Suche", Icons.Filled.Search))
		add(Reiter("bibliotheken", "Bibliotheken", Icons.Filled.VideoLibrary))
		if (Einstellungen.seerrAn()) add(Reiter("entdecken", "Entdecken", Icons.Outlined.Explore))
		add(Reiter("mehr", if (ch.seliku.handy.Update.neu != null) "Mehr •" else "Mehr", Icons.Filled.Menu))
	}
	val vollbild = aktuell.startsWith("spieler") || aktuell == "laeuft"
	var menue by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<org.jellyfin.sdk.model.api.BaseItemDto?>(null) }
	val c = androidx.compose.ui.platform.LocalContext.current
	// Beim Start: Einstellungen vom Konto holen, Seerr-Vermittler suchen (bis 4× im Abstand von 15 s, wie am TV)
	androidx.compose.runtime.LaunchedEffect(Unit) {
		runCatching { Einstellungen.holen() }
		ch.seliku.handy.Update.pruefen()
		repeat(4) {
			runCatching { ch.seliku.handy.seerr.SeerrDaten(c, ch.seliku.handy.Sitzung.api!!).vermittlerPruefen() }
			Einstellungen.seerrVerbunden = ch.seliku.handy.seerr.SeerrVerbindung.verbunden(c)
			if (Einstellungen.seerrVerbunden) return@LaunchedEffect
			kotlinx.coroutines.delay(15_000)
		}
	}
	CompositionLocalProvider(LocalNav provides nav, LocalTitelMenue provides { menue = it }) {
		menue?.let { m -> TitelMenue(m, nav) { menue = null } }
		GeraetWahl()
		Scaffold(
			containerColor = Farben.Hintergrund,
			contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
			bottomBar = {
				if (!vollbild) Column {
					MiniSpieler()
					NavigationBar(containerColor = Farben.Flaeche) {
						reiter.forEach { r ->
							NavigationBarItem(
								selected = aktuell == r.weg,
								onClick = {
									nav.navigate(r.weg) {
										popUpTo(nav.graph.findStartDestination().id) { saveState = true }
										launchSingleTop = true; restoreState = true
									}
								},
								icon = { Icon(r.symbol, null) },
								label = { Text(r.titel) },
								colors = NavigationBarItemDefaults.colors(
									selectedIconColor = Farben.Akzent, selectedTextColor = Farben.Akzent,
									indicatorColor = Farben.FlaecheHell, unselectedIconColor = Farben.TextLeise, unselectedTextColor = Farben.TextLeise,
								),
							)
						}
					}
				}
			},
		) { innen ->
			androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
			NavHost(nav, startDestination = "start", modifier = Modifier.fillMaxSize().padding(bottom = if (vollbild) 0.dp else innen.calculateBottomPadding())) {
				composable("start") { StartSeite() }
				composable("suche") { SuchSeite() }
				composable("bibliotheken") { BibliothekenSeite() }
				composable("entdecken") { EntdeckenSeite() }
				composable("mehr") { MehrSeite() }
				composable("einstellungen") { EinstellungenSeite() }
				composable("verlauf") { VerlaufSeite() }
				composable("favoriten") { FavoritenSeite() }
				composable("livetv") { LiveTvSeite() }
				composable("programm") { ProgrammSeite() }
				composable("musik") { MusikSeite() }
				composable("radio") { RadioSeite() }
				composable("bib/{id}") { b -> BibliothekStartSeite(UUID.fromString(b.arguments!!.getString("id"))) }
				composable("raster/{id}?genre={genre}", listOf(navArgument("genre") { type = NavType.StringType; nullable = true })) { b ->
					RasterSeite(UUID.fromString(b.arguments!!.getString("id")), b.arguments?.getString("genre"))
				}
				composable("album/{id}") { b -> AlbumSeite(UUID.fromString(b.arguments!!.getString("id"))) }
				composable("interpret/{id}") { b -> InterpretSeite(UUID.fromString(b.arguments!!.getString("id"))) }
				composable("laeuft") { LaeuftSeite() }
				composable("buch/{id}") { b -> ch.seliku.handy.musik.BuchSeite(UUID.fromString(b.arguments!!.getString("id"))) }
				composable("podcast/{id}") { b -> ch.seliku.handy.musik.PodcastSeite(UUID.fromString(b.arguments!!.getString("id"))) }
				composable("seerrliste/{art}/{id}?titel={titel}", listOf(navArgument("titel") { type = NavType.StringType; nullable = true })) { b ->
					SeerrListenSeite(b.arguments!!.getString("art")!!, b.arguments!!.getString("id")!!.toInt(), b.arguments?.getString("titel").orEmpty())
				}
				composable("ordner/{id}") { b -> OrdnerSeite(UUID.fromString(b.arguments!!.getString("id"))) }
				composable("detail/{id}") { b -> DetailSeite(UUID.fromString(b.arguments!!.getString("id"))) }
				composable("seerr/{typ}/{tmdb}") { b -> SeerrDetailSeite(b.arguments!!.getString("typ")!!, b.arguments!!.getString("tmdb")!!.toInt()) }
				composable("spieler/{id}?pos={pos}", listOf(navArgument("pos") { type = NavType.StringType; nullable = true })) { b ->
					VideoSpieler(UUID.fromString(b.arguments!!.getString("id")), b.arguments?.getString("pos")?.toLongOrNull()) { nav.popBackStack() }
				}
			}
			// Statusleiste lesbar halten, wenn Inhalt darunter rollt
			if (!vollbild) androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().windowInsetsTopHeight(androidx.compose.foundation.layout.WindowInsets.Companion.statusBars)
				.background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Farben.Hintergrund.copy(alpha = 0.85f), Farben.Hintergrund.copy(alpha = 0.4f)))))
			}
		}
	}
}