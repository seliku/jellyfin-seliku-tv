package org.jellyfin.androidtv.seliku

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.focusable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.animateScrollBy
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.request.crossfade
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.koin.compose.koinInject
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import org.jellyfin.androidtv.R

/** Schriften der Oberfläche. */
object Schrift {
	val Riesig = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Farben.Text)
	val Titel = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Farben.Text)
	val Reihe = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Farben.Text)
	val Normal = TextStyle(fontSize = 14.sp, color = Farben.Text)
	val Leise = TextStyle(fontSize = 13.sp, color = Farben.TextLeise)
	val Klein = TextStyle(fontSize = 12.sp, color = Farben.TextLeise)
}

/**
 * Fokussierbare Fläche mit Rahmen und leichter Vergrösserung. [merken]: Schlüssel, unter dem die Seite sich den Fokus merkt,
 * damit man nach dem Zurückkommen (z. B. aus dem Player) wieder auf derselben Kachel steht.
 */
@Composable
fun Waehlbar(
	onClick: () -> Unit,
	modifier: Modifier = Modifier,
	shape: Shape = RoundedCornerShape(8.dp),
	vergroessern: Boolean = true,
	merken: Pair<SelikuViewModel, Pair<String, String>>? = null,
	onFokus: () -> Unit = {},
	onMenue: (() -> Unit)? = null,
	content: @Composable (fokussiert: Boolean) -> Unit,
) {
	var fokussiert by remember { mutableStateOf(false) }
	val ohneRahmen = LocalOhneRahmen.current
	val anfrage = remember(merken?.second) { merken?.let { (vm, wo) -> vm.ersteAnfrageFuer(wo.first, wo.second) } ?: FocusRequester() }
	if (merken != null) {
		val (vm, wo) = merken
		LaunchedEffect(Unit) {
			// Nur beim Betreten der Seite; taucht die Kachel später beim Rollen wieder auf, darf sie den Fokus nicht wegziehen
			if (vm.fokus[wo.first] == wo.second && wo.first !in vm.wiederhergestellt) {
				vm.wiederhergestellt += wo.first
				runCatching { anfrage.requestFocus() }
			}
		}
	}
	// Echte Lage (nicht auf den sichtbaren Bereich zugeschnitten): eine gerade aus dem Bild gerollte Reihe ist ein gültiges Ziel.
	// null = noch nie platziert.
	var lage by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
	val imInhalt = LocalImInhalt.current
	Box(
		modifier = modifier
			.onGloballyPositioned { lage = androidx.compose.ui.geometry.Rect(it.positionInRoot(), androidx.compose.ui.geometry.Size(it.size.width.toFloat(), it.size.height.toFloat())) }
			.focusRequester(anfrage)
			.onFocusChanged {
				fokussiert = it.isFocused
				if (it.isFocused) FokusSpur.setze(anfrage, { lage }, imInhalt)
				if (it.isFocused) {
					// Gemerkt wird für das nächste Betreten der Seite; bis dahin darf keine Kachel den Fokus mehr zurückholen
					merken?.let { (vm, wo) -> vm.fokus[wo.first] = wo.second; vm.wiederhergestellt += wo.first }
					onFokus()
				}
			}
			.onPreviewKeyEvent { e ->
				if (onMenue != null && e.type == KeyEventType.KeyUp && e.key == Key.Menu) { onMenue(); true } else false
			}
			.graphicsLayer {
				val s = if (fokussiert && vergroessern && !ohneRahmen) 1.06f else 1f
				scaleX = s; scaleY = s
			}
			.clip(shape)
			.border(if (fokussiert && !ohneRahmen) 3.dp else 0.dp, if (fokussiert && !ohneRahmen) Farben.Akzent else Color.Transparent, shape)
			// Langes Drücken (OK halten) oder Menütaste öffnet das Titelmenü
			.combinedClickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick, onLongClick = onMenue)
	) {
		content(fokussiert)
	}
}

/**
 * Wo der Fokus gerade steht. Compose springt bei «links» manchmal auf etwas, das gar nicht links liegt
 * (z. B. vom ersten Poster auf die Banner-Punkte); damit lässt sich das prüfen und rückgängig machen.
 */
object FokusSpur {
	var anfrage: FocusRequester? = null; private set
	var lage: () -> androidx.compose.ui.geometry.Rect? = { null }; private set
	/** Letzter Fokus im Inhalt (nicht in der Leiste): dorthin führen Zurück/Rechts aus der Leiste zurück. */
	var imInhalt: FocusRequester? = null
	fun setze(a: FocusRequester, l: () -> androidx.compose.ui.geometry.Rect?, inhalt: Boolean) { anfrage = a; lage = l; if (inhalt) imInhalt = a }
}

/** Wurde auf dieser Seite schon eine Taste gedrückt? Vorher klappen Kacheln nicht auf (sonst rollt der Start den Banner weg). */
val LocalTasteGedrueckt = androidx.compose.runtime.compositionLocalOf { true }

/**
 * In einer [Reihe]: ist diese Kachel die offene (erster Platz der Reihe)? null = nicht in einer Reihe.
 * Die offene Kachel bleibt offen, auch wenn der Fokus die Reihe verlässt – so bewegt sich beim Hoch/Runter nichts.
 */
val LocalKachelOffen = androidx.compose.runtime.compositionLocalOf<Boolean?> { null }

/**
 * Bühne einer [Reihe]: der feste grosse erste Platz (Querformat). Ist der Wert gesetzt, zeichnet sich eine Kachel als
 * Bühne (nicht wählbar); true = die Reihe hat den Fokus (Rahmen, Trailer).
 */
val LocalBuehne = androidx.compose.runtime.compositionLocalOf<Boolean?> { null }

/** In Reihen mit Bühne: die Kacheln zeigen keinen eigenen Rahmen – den zeigt die Bühne (sonst springt er kurz aufs nächste Poster). */
val LocalOhneRahmen = androidx.compose.runtime.compositionLocalOf { false }

/** Der feste erste Platz einer Reihe: steht still, nur der Inhalt blendet über. */
@Composable
fun Buehne(breite: Dp, aktiv: Boolean, zeilen: Pair<String, String>, inhalt: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit) {
	val hoehe = breite * 1.5f
	val form = RoundedCornerShape(8.dp)
	Column(Modifier.width(hoehe * 16f / 9f)) {
		Box(Modifier.fillMaxWidth().height(hoehe).clip(form).then(if (aktiv) Modifier.border(3.dp, Farben.Akzent, form) else Modifier)) { inhalt() }
		Spacer(Modifier.height(6.dp))
		Text(zeilen.first, style = Schrift.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
		Text(zeilen.second, style = Schrift.Klein, maxLines = 1, overflow = TextOverflow.Ellipsis)
	}
}

/** True innerhalb des Seiteninhalts, false in der Seitenleiste. */
val LocalImInhalt = androidx.compose.runtime.staticCompositionLocalOf { false }

@Composable
fun Bild(url: String?, modifier: Modifier = Modifier, scale: ContentScale = ContentScale.Crop, ersatz: String? = null, grund: Color = Farben.FlaecheHell, ausrichtung: Alignment = Alignment.Center) {
	Box(modifier.background(grund), contentAlignment = Alignment.Center) {
		if (url == null && ersatz != null) Text(ersatz, style = Schrift.Riesig.copy(color = Farben.TextLeise, fontWeight = FontWeight.Normal))
		if (url != null) AsyncImage(model = url, contentDescription = null, imageLoader = koinInject<ImageLoader>(), contentScale = scale, alignment = ausrichtung, modifier = Modifier.fillMaxSize())
	}
}

@Composable
fun Fortschritt(anteil: Float, modifier: Modifier = Modifier) {
	Box(modifier.fillMaxWidth().height(4.dp).background(Color(0x66000000))) {
		Box(Modifier.fillMaxWidth(anteil.coerceIn(0f, 1f)).fillMaxHeight().background(Farben.Akzent))
	}
}

fun BaseItemDto.anteilGesehen(): Float = (userData?.playedPercentage ?: 0.0).toFloat() / 100f

/** Kleines Abzeichen oben rechts: Haken für gesehen, Zahl für ungesehene Folgen. */
@Composable
fun Abzeichen(item: BaseItemDto, modifier: Modifier = Modifier) {
	val ungesehen = item.userData?.unplayedItemCount ?: 0
	val gesehen = item.userData?.played == true
	when {
		item.type == BaseItemKind.SERIES || item.type == BaseItemKind.SEASON -> if (ungesehen > 0) {
			Box(modifier.padding(6.dp).background(Farben.Akzent, RoundedCornerShape(10.dp)).padding(horizontal = 7.dp, vertical = 2.dp)) {
				Text(ungesehen.toString(), style = Schrift.Klein.copy(color = Color.Black, fontWeight = FontWeight.Bold))
			}
		}
		gesehen -> Box(modifier.padding(6.dp).size(20.dp).background(Farben.Akzent, CircleShape), contentAlignment = Alignment.Center) {
			Text("✓", style = Schrift.Klein.copy(color = Color.Black, fontWeight = FontWeight.Bold))
		}
	}
}

/** Untertitel einer Kachel: Jahr, bei Folgen «S1 · F3», bei Musik der Interpret. */
@Composable
fun BaseItemDto.unterzeile(): String = when (type) {
	BaseItemKind.EPISODE -> listOfNotNull(
		parentIndexNumber?.let { stringResource(R.string.seliku_season_short, it) }, indexNumber?.let { stringResource(R.string.seliku_episode_short, it) }
	).joinToString(" · ").let { if (name != null) "$it  $name" else it }
	BaseItemKind.MUSIC_ALBUM -> albumArtist ?: productionYear?.toString().orEmpty()
	BaseItemKind.AUDIO -> (artists?.joinToString(", ") ?: albumArtist).orEmpty()
	BaseItemKind.SEASON -> name.orEmpty()
	BaseItemKind.SERIES -> listOfNotNull(productionYear?.toString(), childCount?.let { pluralStringResource(R.plurals.seliku_seasons, it, it) }).joinToString(" · ")
	BaseItemKind.TV_CHANNEL -> currentProgram?.name.orEmpty()
	else -> productionYear?.toString().orEmpty()
}

fun BaseItemDto.hauptzeile(): String = when (type) {
	BaseItemKind.EPISODE, BaseItemKind.SEASON -> seriesName ?: name.orEmpty()
	else -> name.orEmpty()
}

/** Hochformat-Kachel (Filme, Serien). */
@Composable
fun PosterKachel(
	item: BaseItemDto,
	onClick: () -> Unit,
	breite: Dp = 140.dp,
	erster: FocusRequester? = null,
	merken: Pair<SelikuViewModel, Pair<String, String>>? = null,
	onFokus: () -> Unit = {},
	onMenue: (() -> Unit)? = null,
	aufklappen: Boolean = false,
) {
	LocalBuehne.current?.let { aktiv ->
		Buehne(breite, aktiv, item.hauptzeile() to item.unterzeile()) {
			// Auch auf dem grossen ersten Platz: Balken, wie viel man schon gesehen hat
			Box(Modifier.fillMaxSize()) {
				AufgeklappteKachel(item, spielen = aktiv)
				val anteil = item.anteilGesehen()
				if (anteil > 0f && item.userData?.played != true) Fortschritt(anteil, Modifier.align(Alignment.BottomStart))
			}
		}
		return
	}
	val bilder = koinInject<Bilder>()
	val context = androidx.compose.ui.platform.LocalContext.current
	var fokussiert by remember { mutableStateOf(false) }
	// Wie bei Netflix: Die gewählte Kachel zieht sich nach kurzem Verweilen ins Querformat auf (Hintergrundbild, Logo, Infos);
	// bleibt man länger, läuft darin stumm der Trailer. Nur in Reihen – im Raster würde das Gitter verrutschen.
	// In einer Reihe ist immer genau eine Kachel offen (der erste Platz), sofort und ohne Animation
	val reihenPlatz = LocalKachelOffen.current
	val offen = aufklappen && SelikuEinstellungen.netflixKacheln(context) && (reihenPlatz ?: fokussiert)
	val hoehe = breite * 1.5f
	// Ohne Animation: die gewählte Kachel steht fest links (siehe Rollen.links), aufklappen schiebt nur die Kacheln rechts davon
	val b = if (offen) hoehe * 16f / 9f else breite
	Column(Modifier.width(b)) {
		Waehlbar(onClick = onClick, merken = merken, onFokus = onFokus, onMenue = onMenue, vergroessern = !aufklappen,
			modifier = (if (erster != null) Modifier.focusRequester(erster) else Modifier).onFocusChanged { fokussiert = it.isFocused }) {
			Box(Modifier.width(b).height(hoehe)) {
				if (!offen) {
					Bild(bilder.poster(item, 300), Modifier.fillMaxSize(), ersatz = "▶")
					Abzeichen(item, Modifier.align(Alignment.TopEnd))
				} else AufgeklappteKachel(item, spielen = fokussiert)
				val anteil = item.anteilGesehen()
				if (anteil > 0f && item.userData?.played != true) Fortschritt(anteil, Modifier.align(Alignment.BottomStart))
			}
		}
		Spacer(Modifier.height(6.dp))
		Text(item.hauptzeile(), style = Schrift.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
		Text(item.unterzeile(), style = Schrift.Klein, maxLines = 1, overflow = TextOverflow.Ellipsis)
	}
}

/** Inhalt der aufgeklappten Kachel: Hintergrund, Logo/Titel, Infozeile; nach ein paar Sekunden stumm der Trailer. */
@Composable
private fun AufgeklappteKachel(item: BaseItemDto, spielen: Boolean) {
	val bilder = koinInject<Bilder>()
	val api = koinInject<org.jellyfin.sdk.api.client.ApiClient>()
	val context = androidx.compose.ui.platform.LocalContext.current
	var trailer by remember { mutableStateOf<String?>(null) }
	var bereit by remember { mutableStateOf(false) }
	// Trailer nur, solange die Kachel gewählt ist (die offene Kachel einer anderen Reihe bleibt still)
	LaunchedEffect(item.id, spielen) {
		trailer = null; bereit = false
		if (!spielen || !SelikuEinstellungen.trailerImBanner(context)) return@LaunchedEffect
		kotlinx.coroutines.delay(2_500)
		trailer = Trailer.adresse(api, item, SelikuEinstellungen.ausschnittOhneTrailer(context))
	}
	val sicht by androidx.compose.animation.core.animateFloatAsState(if (bereit) 1f else 0f, androidx.compose.animation.core.tween(700), label = "kacheltrailer")
	Box(Modifier.fillMaxSize().background(Farben.Flaeche)) {
		Bild(bilder.hintergrund(item, 780) ?: bilder.poster(item, 300), Modifier.fillMaxSize(), grund = Farben.Flaeche)
		trailer?.let { url ->
			TrailerVideo(url, Modifier.fillMaxSize().graphicsLayer { alpha = sicht }, mitTon = true, onBereit = { bereit = true }, onEnde = { trailer = null; bereit = false })
		}
		Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0x22000000), Color(0xE0000000)))))
		Abzeichen(item, Modifier.align(Alignment.TopEnd))
		Column(Modifier.align(Alignment.BottomStart).padding(10.dp).graphicsLayer { alpha = 1f - sicht * 0.6f }) {
			val logo = bilder.logo(item, 400)
			if (logo != null) Bild(logo, Modifier.height(34.dp).width(160.dp), ContentScale.Fit, grund = Color.Transparent)
			else Text(item.hauptzeile(), style = Schrift.Reihe, maxLines = 1, overflow = TextOverflow.Ellipsis)
			Spacer(Modifier.height(4.dp))
			Text(item.metazeile(), style = Schrift.Klein.copy(color = Color.White.copy(alpha = 0.85f)), maxLines = 1, overflow = TextOverflow.Ellipsis)
		}
	}
}

/** Querformat-Kachel mit Fortschritt (Weiterschauen, Folgen). */
@Composable
fun QuerKachel(
	item: BaseItemDto,
	onClick: () -> Unit,
	breite: Dp = 250.dp,
	zeilen: Pair<String, String> = item.hauptzeile() to item.unterzeile(),
	merken: Pair<SelikuViewModel, Pair<String, String>>? = null,
	onFokus: () -> Unit = {},
	onMenue: (() -> Unit)? = null,
) {
	val bilder = koinInject<Bilder>()
	Column(Modifier.width(breite)) {
		Waehlbar(onClick = onClick, merken = merken, onFokus = onFokus, onMenue = onMenue) {
			Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
				if (item.type == BaseItemKind.TV_CHANNEL) Bild(bilder.eigenesBild(item, 400), Modifier.fillMaxSize().background(Farben.Flaeche).padding(28.dp), ContentScale.Fit)
				else Bild(bilder.quer(item, 480), Modifier.fillMaxSize(), ersatz = "▶")
				if (item.type == BaseItemKind.TV_CHANNEL && bilder.quer(item, 480) == null) Text(item.name.orEmpty(), style = Schrift.Titel.copy(color = Farben.TextLeise), modifier = Modifier.align(Alignment.Center))
				Abzeichen(item, Modifier.align(Alignment.TopEnd))
				val anteil = item.anteilGesehen()
				if (anteil > 0f && item.userData?.played != true) Fortschritt(anteil, Modifier.align(Alignment.BottomStart))
			}
		}
		Spacer(Modifier.height(6.dp))
		Text(zeilen.first, style = Schrift.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
		Text(zeilen.second, style = Schrift.Klein, maxLines = 1, overflow = TextOverflow.Ellipsis)
	}
}

/** Quadratische Kachel (Alben, Interpreten rund). */
@Composable
fun QuadratKachel(
	item: BaseItemDto,
	onClick: () -> Unit,
	breite: Dp = 150.dp,
	rund: Boolean = false,
	merken: Pair<SelikuViewModel, Pair<String, String>>? = null,
	onFokus: () -> Unit = {},
	onMenue: (() -> Unit)? = null,
) {
	val bilder = koinInject<Bilder>()
	val form = if (rund) CircleShape else RoundedCornerShape(8.dp)
	Column(Modifier.width(breite), horizontalAlignment = if (rund) Alignment.CenterHorizontally else Alignment.Start) {
		Waehlbar(onClick = onClick, shape = form, merken = merken, onFokus = onFokus, onMenue = onMenue) {
			Box {
				Bild(bilder.poster(item, 300), Modifier.fillMaxWidth().aspectRatio(1f), ersatz = if (rund) initialen(item.name) else "♫")
				if (item.userData?.isFavorite == true && item.type == BaseItemKind.PLAYLIST)
					Text("♥", style = Schrift.Titel.copy(color = Farben.Akzent), modifier = Modifier.align(Alignment.TopEnd).padding(8.dp))
			}
		}
		Spacer(Modifier.height(6.dp))
		Text(item.name.orEmpty(), style = Schrift.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
		if (!rund) Text(item.unterzeile(), style = Schrift.Klein, maxLines = 1, overflow = TextOverflow.Ellipsis)
	}
}

/** Kachel passend zum Typ. */
@Composable
fun Kachel(
	item: BaseItemDto,
	onClick: () -> Unit,
	merken: Pair<SelikuViewModel, Pair<String, String>>? = null,
	onFokus: () -> Unit = {},
	onMenue: (() -> Unit)? = null,
) = when (item.type) {
	BaseItemKind.EPISODE, BaseItemKind.TV_CHANNEL, BaseItemKind.PROGRAM, BaseItemKind.VIDEO -> QuerKachel(item, onClick, merken = merken, onFokus = onFokus, onMenue = onMenue)
	BaseItemKind.MUSIC_ALBUM, BaseItemKind.AUDIO, BaseItemKind.PLAYLIST -> QuadratKachel(item, onClick, merken = merken, onFokus = onFokus, onMenue = onMenue)
	BaseItemKind.MUSIC_ARTIST -> QuadratKachel(item, onClick, rund = true, merken = merken, onFokus = onFokus, onMenue = onMenue)
	else -> PosterKachel(item, onClick, merken = merken, onFokus = onFokus, onMenue = onMenue)
}

/** Eine waagrechte Reihe mit Überschrift. [buehne]: fester grosser erster Platz (nur mit aufklappenden Posterkacheln). */
@Composable
fun Reihe(
	titel: String,
	items: List<BaseItemDto>,
	vm: SelikuViewModel,
	seite: Seite,
	onClick: (BaseItemDto) -> Unit,
	onFokus: (BaseItemDto) -> Unit = {},
	onMenue: ((BaseItemDto) -> Unit)? = null,
	kachel: @Composable ((BaseItemDto, () -> Unit, Pair<SelikuViewModel, Pair<String, String>>, () -> Unit) -> Unit)? = null,
	beiHoch: (() -> Boolean)? = null,
	start: Int? = null,
	buehne: Boolean = false,
) {
	if (items.isEmpty()) return
	// beiHoch: «nach oben» aus dieser Reihe selbst behandeln (z. B. zum Banner springen, auch wenn er gerade ausserhalb liegt)
	Column(Modifier.fillMaxWidth().padding(bottom = 18.dp).onPreviewKeyEvent { e ->
		if (beiHoch != null && e.type == KeyEventType.KeyDown && e.key == Key.DirectionUp) beiHoch() else false
	}) {
		Text(titel, style = Schrift.Reihe, modifier = Modifier.padding(start = 48.dp, bottom = 8.dp))
		val zustand = remember(seite.schluessel, titel) { vm.listen.getOrPut("${seite.schluessel}|$titel") { androidx.compose.foundation.lazy.LazyListState() } }
		// Welche Kachel offen ist (erster Platz), pro Reihe gemerkt – auch über den Player hinweg
		val offenSchluessel = "${seite.schluessel}|$titel|offen"
		var offenNr by remember(offenSchluessel) { mutableStateOf((vm.wahl[offenSchluessel] as? Int ?: start ?: 0).coerceIn(0, items.lastIndex)) }
		// [start]: beim ersten Betreten an dieser Stelle beginnen (z. B. bei der nächsten Folge statt bei Folge 1)
		LaunchedEffect(offenSchluessel, start) {
			if (start != null && start in items.indices && vm.wahl[offenSchluessel] == null && zustand.firstVisibleItemIndex == 0 && zustand.firstVisibleItemScrollOffset == 0) {
				offenNr = start; runCatching { zustand.scrollToItem(start) }
			}
		}
		val mitBuehne = buehne && SelikuEinstellungen.netflixKacheln(androidx.compose.ui.platform.LocalContext.current)
		@Composable fun zeige(item: BaseItemDto, merken: Pair<SelikuViewModel, Pair<String, String>>, gewaehlt: () -> Unit) {
			if (kachel != null) kachel(item, { onClick(item) }, merken, gewaehlt)
			else Kachel(item, { onClick(item) }, merken = merken, onFokus = gewaehlt, onMenue = onMenue?.let { m -> { m(item) } } ?: { vm.menue = item })
		}
		Band(items.size, zustand, offenNr.coerceIn(0, items.lastIndex), mitBuehne, schluessel = { items[it].id },
			buehne = { nr -> zeige(items[nr], vm to (seite.schluessel to "$titel|buehne"), {}) },
		) { nr ->
			val item = items[nr]
			zeige(item, vm to (seite.schluessel to "$titel|${item.id}")) { offenNr = nr; vm.wahl[offenSchluessel] = nr; onFokus(item) }
		}
	}
}

/**
 * Das waagrechte Band einer Reihe. Mit [mitBuehne] (wie Netflix) steht links ein fester grosser Platz mit der gewählten
 * Kachel. Er bewegt sich nie, nur sein Inhalt blendet über; die Kacheln gleiten darunter durch und verschwinden an
 * seinem rechten Rand. Der Fokus liegt dabei auf der (verdeckten) Kachel unter der Bühne.
 * Ohne Bühne: die gewählte Kachel steht links, sie klappt selbst auf ([LocalKachelOffen]).
 */
@Composable
fun Band(
	anzahl: Int,
	zustand: androidx.compose.foundation.lazy.LazyListState,
	offenNr: Int,
	mitBuehne: Boolean,
	schluessel: (Int) -> Any,
	breite: Dp = 120.dp,
	buehne: @Composable (Int) -> Unit,
	kachel: @Composable (Int) -> Unit,
) {
	val buehnenBreite = breite * 1.5f * 16f / 9f
	val anfang = if (mitBuehne) 48.dp + buehnenBreite - breite else 48.dp
	val schnitt = if (mitBuehne) 48.dp + buehnenBreite + 9.dp else 40.dp
	var fokus by remember { mutableStateOf(false) }
	androidx.compose.foundation.layout.BoxWithConstraints {
		// genug Platz am Ende, damit auch die letzte Kachel an den ersten Platz kann
		val ende = (maxWidth - anfang - if (mitBuehne) breite else 100.dp).coerceAtLeast(48.dp)
		SeitlichRollen(links = true, linksRand = anfang) {
			LazyRow(
				state = zustand,
				// Was links aus der Reihe gerollt ist (bzw. unter der Bühne liegt), nicht hervorschauen lassen
				modifier = Modifier.onFocusChanged { fokus = it.hasFocus }
					.drawWithContent { clipRect(left = schnitt.toPx(), top = -40f, right = size.width + 40f, bottom = size.height + 40f) { this@drawWithContent.drawContent() } },
				contentPadding = PaddingValues(start = anfang, end = ende, top = 8.dp, bottom = 8.dp),
				horizontalArrangement = Arrangement.spacedBy(18.dp),
			) {
				items(anzahl, key = schluessel) { nr ->
					// Letzte Kachel: «rechts» schlucken – rechts davon ist nur Leerraum, sonst springt der Fokus nach oben
					Box(if (nr == anzahl - 1) Modifier.onPreviewKeyEvent { e -> e.key == Key.DirectionRight } else Modifier) {
						androidx.compose.runtime.CompositionLocalProvider(LocalKachelOffen provides (!mitBuehne && nr == offenNr), LocalOhneRahmen provides mitBuehne) { kachel(nr) }
					}
				}
			}
		}
		if (mitBuehne && anzahl > 0) Box(Modifier.padding(start = 48.dp, top = 8.dp)) {
			androidx.compose.animation.Crossfade(offenNr.coerceIn(0, anzahl - 1), animationSpec = androidx.compose.animation.core.tween(300), label = "buehne") { nr ->
				androidx.compose.runtime.CompositionLocalProvider(LocalBuehne provides fokus) { buehne(nr) }
			}
		}
	}
}

/**
 * Beschreibung, die abgeschnitten sein kann. Ist sie es, wird sie anwählbar («Weiterlesen») und OK zeigt den ganzen
 * Text in einem Fenster (Hoch/Runter rollt darin). Passt sie ganz, ist sie gewöhnlicher Text.
 */
@Composable
fun Beschreibung(text: String, titel: String, maxZeilen: Int, modifier: Modifier = Modifier, stil: androidx.compose.ui.text.TextStyle = Schrift.Normal) {
	if (text.isBlank()) return
	var abgeschnitten by remember(text) { mutableStateOf(false) }
	var offen by remember { mutableStateOf(false) }
	val inhalt: @Composable (Boolean) -> Unit = { fokussiert ->
		Column {
			Text(text, style = stil, maxLines = maxZeilen, overflow = TextOverflow.Ellipsis, onTextLayout = { if (it.hasVisualOverflow) abgeschnitten = true })
			if (abgeschnitten) Text("› " + stringResource(R.string.seliku_read_more), style = Schrift.Klein.copy(color = if (fokussiert) Farben.Akzent else Farben.TextLeise, fontWeight = FontWeight.Bold))
		}
	}
	if (abgeschnitten) Waehlbar(onClick = { offen = true }, modifier = modifier, vergroessern = false) { f -> Box(Modifier.padding(horizontal = 6.dp, vertical = 4.dp)) { inhalt(f) } }
	else Box(modifier.padding(horizontal = 6.dp, vertical = 4.dp)) { inhalt(false) }
	if (offen) androidx.compose.ui.window.Dialog(onDismissRequest = { offen = false }) {
		val rollen = androidx.compose.foundation.rememberScrollState()
		val fokus = remember { FocusRequester() }
		val scope = androidx.compose.runtime.rememberCoroutineScope()
		Column(Modifier.width(780.dp).heightIn(max = 600.dp).background(Farben.Flaeche, RoundedCornerShape(18.dp)).padding(28.dp)) {
			Text(titel, style = Schrift.Reihe, maxLines = 2, overflow = TextOverflow.Ellipsis)
			Spacer(Modifier.height(14.dp))
			Text(text, style = Schrift.Normal.copy(lineHeight = Schrift.Normal.fontSize * 1.45f),
				modifier = Modifier.focusRequester(fokus).focusable().verticalScroll(rollen).onPreviewKeyEvent { e ->
					val schritt = when (e.key) { Key.DirectionDown -> 220f; Key.DirectionUp -> -220f; else -> 0f }
					if (schritt != 0f && e.type == KeyEventType.KeyDown) { scope.launch { rollen.animateScrollBy(schritt) }; true }
					else if (e.key == Key.DirectionCenter || e.key == Key.Enter) { if (e.type == KeyEventType.KeyUp) offen = false; true }
					else schritt != 0f
				})
		}
		LaunchedEffect(Unit) { runCatching { fokus.requestFocus() } }
	}
}

/** Auswahlknopf (Sortierung, Staffeln, Reiter). */
@Composable
fun Knopf(
	text: String,
	onClick: () -> Unit,
	modifier: Modifier = Modifier,
	aktiv: Boolean = false,
	hervorgehoben: Boolean = false,
	symbol: String? = null,
	breit: Boolean = false,
	onFokus: () -> Unit = {},
) {
	Waehlbar(onClick = onClick, modifier = modifier, shape = RoundedCornerShape(22.dp), vergroessern = false, onFokus = onFokus) { fokussiert ->
		val grund = when {
			fokussiert -> Farben.Text
			hervorgehoben -> Farben.Akzent
			aktiv -> Farben.FlaecheHell
			else -> Farben.Flaeche
		}
		val schrift = if (fokussiert || hervorgehoben) Color.Black else Farben.Text
		Row(
			(if (breit) Modifier.fillMaxWidth() else Modifier).background(grund).padding(start = 20.dp, end = 24.dp, top = 10.dp, bottom = 10.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(8.dp),
		) {
			if (symbol != null) Text(symbol, style = Schrift.Normal.copy(color = schrift, fontWeight = FontWeight.Bold))
			Text(text, style = Schrift.Normal.copy(color = schrift, fontWeight = if (aktiv) FontWeight.Bold else FontWeight.Medium), maxLines = 1, softWrap = false)
		}
	}
}

/** Hintergrundbild mit Verlauf nach links und unten. */
@Composable
fun Kulisse(url: String?, modifier: Modifier = Modifier) {
	// Grundton mit warmem Verlauf (oben links heller), darüber das Hintergrundbild
	Box(modifier.fillMaxSize().background(Brush.linearGradient(listOf(Farben.HintergrundOben, Farben.Hintergrund), end = androidx.compose.ui.geometry.Offset(1400f, 1100f)))) {
		// Langsam überblenden: beim Durchblättern springt der Hintergrund sonst bei jeder Kachel
		val context = androidx.compose.ui.platform.LocalContext.current
		if (url != null) AsyncImage(
			model = remember(url) { coil3.request.ImageRequest.Builder(context).data(url).crossfade(700).build() },
			contentDescription = null,
			imageLoader = koinInject<ImageLoader>(),
			contentScale = ContentScale.Crop,
			modifier = Modifier.fillMaxSize().graphicsLayer { alpha = 0.55f },
		)
		Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Farben.Hintergrund, Color.Transparent), endX = 1400f)))
		Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Farben.Hintergrund), startY = 250f)))
	}
}

/** Lade- und Leer-Anzeige. */
@Composable
fun Hinweis(text: String) {
	// Seliku: fokussierbar, sonst hat eine leere Seite (z. B. leere «Aufnahmen») nichts im Fokus und Zurück öffnet die Leiste nicht
	Box(Modifier.fillMaxSize().focusable(), contentAlignment = Alignment.Center) { Text(text, style = Schrift.Leise) }
}

@Composable
fun laufzeit(ticks: Long?): String? = ticks?.let {
	val min = (it / 600_000_000L).toInt()
	if (min <= 0) null else if (min >= 60) stringResource(R.string.seliku_runtime_hours, min / 60, min % 60) else stringResource(R.string.seliku_runtime_minutes, min)
}

@Composable
fun BaseItemDto.metazeile(): String = listOfNotNull(
	eigeneSterne()?.let { stringResource(R.string.seliku_own_rating_short, sterneText(it)) },
	productionYear?.toString(),
	officialRating,
	laufzeit(runTimeTicks),
	tomaten()?.let { "🍅 $it %" },
	communityRating?.let { "TMDb %.1f".format(it) },
	genres?.take(3)?.joinToString(", ")?.takeIf { it.isNotBlank() },
).joinToString("   ·   ")

/** Eigene Wertung in Sternen (1–5), null = keine oder abgeschaltet. */
@Composable
fun BaseItemDto.eigeneSterne(): Int? {
	if (!SelikuEinstellungen.eigeneWertung(androidx.compose.ui.platform.LocalContext.current)) return null
	return userData?.rating?.let { kotlin.math.round(it / 2).toInt() }?.takeIf { it in 1..5 }
}

fun sterneText(sterne: Int) = "★".repeat(sterne) + "☆".repeat(5 - sterne)

/** Gemerkte Rotten-Tomatoes-Werte aus Seerr (-1 = keiner), damit nicht jede Kachel neu fragt. */
object Tomaten { val werte = java.util.concurrent.ConcurrentHashMap<String, Int>() }

/** Rotten-Tomatoes-Kritikerwertung: aus Jellyfin (OMDb, meist nur Filme), sonst bei Seerr nachgeschlagen (auch Serien). */
@Composable
fun BaseItemDto.tomaten(): Int? {
	val context = androidx.compose.ui.platform.LocalContext.current
	if (!SelikuEinstellungen.tomaten(context)) return null
	criticRating?.let { return it.toInt() }
	val typ = when (type) { BaseItemKind.MOVIE -> "movie"; BaseItemKind.SERIES -> "tv"; else -> return null }
	val tmdb = providerIds?.get("Tmdb")?.toIntOrNull() ?: return null
	val schluessel = "$typ/$tmdb"
	val seerr = koinInject<SeerrDaten>()
	val wert by androidx.compose.runtime.produceState(Tomaten.werte[schluessel], schluessel) {
		if (value == null) value = (runCatching { seerr.rottenTomatoes(typ, tmdb) }.getOrNull() ?: -1).also { Tomaten.werte[schluessel] = it }
	}
	return wert?.takeIf { it >= 0 }
}

/**
 * Scrollverhalten wie man es vom Fernseher kennt: senkrecht steht die gewählte Reihe immer oben ([oben] Abstand),
 * waagrecht läuft der Fokus bis kurz vor den Rand und erst dann rollt die Reihe.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
object Rollen {
	fun oben(abstandPx: Float) = object : androidx.compose.foundation.gestures.BringIntoViewSpec {
		override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = offset - abstandPx
	}

	/** Die gewählte Kachel immer an den linken Rand (Abstand [randPx]). */
	fun links(randPx: Float) = object : androidx.compose.foundation.gestures.BringIntoViewSpec {
		override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = offset - randPx
	}

	fun seitlich(randPx: Float) = object : androidx.compose.foundation.gestures.BringIntoViewSpec {
		override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
			val ende = offset + size
			return when {
				offset < randPx -> offset - randPx
				ende > containerSize - randPx -> ende - (containerSize - randPx)
				else -> 0f
			}
		}
	}
}

/**
 * Senkrecht auf Seiten mit Banner (erstes Element der Liste, 290 dp): der Banner ist ganz da (Banner oder erste Reihe
 * gewählt) oder ganz weg – nie halb abgeschnitten. Ab der zweiten Reihe steht die gewählte Reihe oben.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun BannerRollen(liste: androidx.compose.foundation.lazy.LazyListState, mitBanner: Boolean, content: @Composable () -> Unit) {
	val dichte = androidx.compose.ui.platform.LocalDensity.current
	val bannerPx = with(dichte) { if (mitBanner) 290.dp.toPx() else 0f }
	// Abstand so, dass die Überschrift der gewählten Reihe ganz zu sehen ist
	val obenPx = with(dichte) { 60.dp.toPx() }
	val ersteReihePx = with(dichte) { 120.dp.toPx() }
	val regel = remember(liste, bannerPx) {
		object : androidx.compose.foundation.gestures.BringIntoViewSpec {
			override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
				val jetzt = when (liste.firstVisibleItemIndex) {
					0 -> liste.firstVisibleItemScrollOffset.toFloat()
					1 -> bannerPx + liste.firstVisibleItemScrollOffset
					else -> null
				}
				// Erste Reihe direkt unter dem Banner: Banner ganz zeigen – aber nur, wenn die Reihe dann ganz aufs Bild passt
				// (auf Seiten mit Reitern oben passt sie nicht: dann rollt der Banner ganz weg und die Reihe steht oben)
				if (jetzt != null && jetzt + offset < bannerPx + ersteReihePx) {
					if (jetzt + offset + size <= containerSize) return -jetzt
					return maxOf(offset - obenPx, bannerPx - jetzt)
				}
				// Schon ganz zu sehen: nichts bewegen
				if (offset >= obenPx && offset + size <= containerSize) return 0f
				// Darüber: oben anlegen. Darunter: nur so weit rollen, bis die Reihe unten ganz zu sehen ist
				var weg = if (offset < obenPx || size > containerSize - obenPx) offset - obenPx else offset + size - containerSize
				// Der Banner ist nie halb zu sehen: wäre er es danach, ganz hinausrollen
				if (jetzt != null && jetzt + weg in 0.5f..(bannerPx - 0.5f)) weg = bannerPx - jetzt
				return weg
			}
		}
	}
	androidx.compose.runtime.CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides regel, content = content)
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun SenkrechtRollen(abstand: Dp = 48.dp, content: @Composable () -> Unit) {
	val px = with(androidx.compose.ui.platform.LocalDensity.current) { abstand.toPx() }
	androidx.compose.runtime.CompositionLocalProvider(
		androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides remember(px) { Rollen.oben(px) },
		content = content,
	)
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun SeitlichRollen(rand: Dp = 120.dp, links: Boolean = false, linksRand: Dp = 48.dp, content: @Composable () -> Unit) {
	val px = with(androidx.compose.ui.platform.LocalDensity.current) { (if (links) linksRand else rand).toPx() }
	androidx.compose.runtime.CompositionLocalProvider(
		androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides remember(px, links) { if (links) Rollen.links(px) else Rollen.seitlich(px) },
		content = content,
	)
}

/** Staffelname auf Deutsch; «Season Unknown» (Folgen ohne Staffel) heisst einfach «Folgen». */
@Composable
fun staffelName(s: BaseItemDto?): String {
	val n = s?.name.orEmpty()
	return when {
		s == null -> stringResource(R.string.seliku_episodes)
		n.startsWith("Season Unknown", true) -> stringResource(R.string.seliku_episodes)
		n.equals("Specials", true) -> "Specials"
		n.startsWith("Season ", true) -> stringResource(R.string.seliku_season_name, n.substring(7))
		else -> n
	}
}

fun initialen(name: String?): String = name.orEmpty().split(" ").filter { it.firstOrNull()?.isLetterOrDigit() == true }
	.take(2).joinToString("") { it.first().uppercase() }.ifEmpty { "♫" }

/** Der Titel wurde auf dem Server entfernt oder neu eingelesen (HTTP 404). */
fun istWeg(e: Throwable): Boolean = (e as? org.jellyfin.sdk.api.client.exception.InvalidStatusException)?.status == 404

/** Kurz «Nicht mehr vorhanden» zeigen und eine Seite zurück, statt hängen zu bleiben. */
@Composable
fun NichtMehrDa(vm: SelikuViewModel) {
	Hinweis(stringResource(R.string.seliku_title_gone))
	LaunchedEffect(Unit) { kotlinx.coroutines.delay(2000); vm.zurueck() }
}

/** Sender-Kachel: Bild der laufenden Sendung, Senderlogo klein unten links, Fortschritt der Sendung unten. */
@Composable
fun SenderKachel(
	sender: BaseItemDto,
	onClick: () -> Unit,
	breite: Dp = 250.dp,
	merken: Pair<SelikuViewModel, Pair<String, String>>? = null,
	onFokus: () -> Unit = {},
) {
	val bilder = koinInject<Bilder>()
	val sendungsBild = bilder.sendung(sender)
	val p = sender.currentProgram
	Column(Modifier.width(breite)) {
		Waehlbar(onClick = onClick, merken = merken, onFokus = onFokus) {
			Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Farben.Flaeche)) {
				if (sendungsBild != null) {
					Bild(sendungsBild, Modifier.fillMaxSize())
					Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000)), startY = 120f)))
					Bild(bilder.eigenesBild(sender, 200), Modifier.align(Alignment.BottomStart).padding(8.dp).width(64.dp).aspectRatio(16f / 9f),
						ContentScale.Fit, grund = Color.Transparent)
				} else {
					Bild(bilder.eigenesBild(sender, 400), Modifier.fillMaxSize().padding(28.dp), ContentScale.Fit, grund = Color.Transparent)
				}
				if (p?.startDate != null && p.endDate != null) {
					val gesamt = java.time.Duration.between(p.startDate, p.endDate).seconds.coerceAtLeast(1)
					val vorbei = java.time.Duration.between(p.startDate, java.time.LocalDateTime.now()).seconds
					Fortschritt(vorbei.toFloat() / gesamt, Modifier.align(Alignment.BottomStart))
				}
			}
		}
		Spacer(Modifier.height(6.dp))
		Text(p?.name ?: sender.name.orEmpty(), style = Schrift.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
		Text(sender.name.orEmpty(), style = Schrift.Klein, maxLines = 1, overflow = TextOverflow.Ellipsis)
	}
}
