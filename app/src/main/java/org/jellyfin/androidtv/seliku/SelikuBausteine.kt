package org.jellyfin.androidtv.seliku

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
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.koin.compose.koinInject

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
	val anfrage = remember(merken?.second) { merken?.let { (vm, wo) -> vm.ersteAnfrageFuer(wo.first, wo.second) } ?: FocusRequester() }
	if (merken != null) {
		val (vm, wo) = merken
		LaunchedEffect(Unit) {
			if (vm.fokus[wo.first] == wo.second) runCatching { anfrage.requestFocus() }
		}
	}
	var lage by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
	Box(
		modifier = modifier
			.onGloballyPositioned { lage = it.boundsInRoot() }
			.focusRequester(anfrage)
			.onFocusChanged {
				fokussiert = it.isFocused
				if (it.isFocused) FokusSpur.setze(anfrage) { lage }
				if (it.isFocused) {
					merken?.let { (vm, wo) -> vm.fokus[wo.first] = wo.second }
					onFokus()
				}
			}
			.onPreviewKeyEvent { e ->
				if (onMenue != null && e.type == KeyEventType.KeyUp && e.key == Key.Menu) { onMenue(); true } else false
			}
			.graphicsLayer {
				val s = if (fokussiert && vergroessern) 1.06f else 1f
				scaleX = s; scaleY = s
			}
			.clip(shape)
			.border(if (fokussiert) 3.dp else 0.dp, if (fokussiert) Farben.Akzent else Color.Transparent, shape)
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
	var lage: () -> androidx.compose.ui.geometry.Rect = { androidx.compose.ui.geometry.Rect.Zero }; private set
	fun setze(a: FocusRequester, l: () -> androidx.compose.ui.geometry.Rect) { anfrage = a; lage = l }
}

@Composable
fun Bild(url: String?, modifier: Modifier = Modifier, scale: ContentScale = ContentScale.Crop, ersatz: String? = null, grund: Color = Farben.FlaecheHell) {
	Box(modifier.background(grund), contentAlignment = Alignment.Center) {
		if (url == null && ersatz != null) Text(ersatz, style = Schrift.Riesig.copy(color = Farben.TextLeise, fontWeight = FontWeight.Normal))
		if (url != null) AsyncImage(model = url, contentDescription = null, imageLoader = koinInject<ImageLoader>(), contentScale = scale, modifier = Modifier.fillMaxSize())
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
fun BaseItemDto.unterzeile(): String = when (type) {
	BaseItemKind.EPISODE -> listOfNotNull(
		parentIndexNumber?.let { "S$it" }, indexNumber?.let { "F$it" }
	).joinToString(" · ").let { if (name != null) "$it  $name" else it }
	BaseItemKind.MUSIC_ALBUM -> albumArtist ?: productionYear?.toString().orEmpty()
	BaseItemKind.AUDIO -> (artists?.joinToString(", ") ?: albumArtist).orEmpty()
	BaseItemKind.SEASON -> name.orEmpty()
	BaseItemKind.SERIES -> listOfNotNull(productionYear?.toString(), childCount?.let { if (it == 1) "1 Staffel" else "$it Staffeln" }).joinToString(" · ")
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
) {
	val bilder = koinInject<Bilder>()
	Column(Modifier.width(breite)) {
		Waehlbar(onClick = onClick, merken = merken, onFokus = onFokus, onMenue = onMenue, modifier = if (erster != null) Modifier.focusRequester(erster) else Modifier) {
			Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f)) {
				Bild(bilder.poster(item, 300), Modifier.fillMaxSize(), ersatz = "▶")
				Abzeichen(item, Modifier.align(Alignment.TopEnd))
				val anteil = item.anteilGesehen()
				if (anteil > 0f && item.userData?.played != true) Fortschritt(anteil, Modifier.align(Alignment.BottomStart))
			}
		}
		Spacer(Modifier.height(6.dp))
		Text(item.hauptzeile(), style = Schrift.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
		Text(item.unterzeile(), style = Schrift.Klein, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
) {
	val bilder = koinInject<Bilder>()
	val form = if (rund) CircleShape else RoundedCornerShape(8.dp)
	Column(Modifier.width(breite), horizontalAlignment = if (rund) Alignment.CenterHorizontally else Alignment.Start) {
		Waehlbar(onClick = onClick, shape = form, merken = merken, onFokus = onFokus) {
			Bild(bilder.poster(item, 300), Modifier.fillMaxWidth().aspectRatio(1f), ersatz = if (rund) initialen(item.name) else "♫")
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
	BaseItemKind.MUSIC_ALBUM, BaseItemKind.AUDIO, BaseItemKind.PLAYLIST -> QuadratKachel(item, onClick, merken = merken, onFokus = onFokus)
	BaseItemKind.MUSIC_ARTIST -> QuadratKachel(item, onClick, rund = true, merken = merken, onFokus = onFokus)
	else -> PosterKachel(item, onClick, merken = merken, onFokus = onFokus, onMenue = onMenue)
}

/** Eine waagrechte Reihe mit Überschrift. */
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
) {
	if (items.isEmpty()) return
	// beiHoch: «nach oben» aus dieser Reihe selbst behandeln (z. B. zum Banner springen, auch wenn er gerade ausserhalb liegt)
	Column(Modifier.fillMaxWidth().padding(bottom = 18.dp).onPreviewKeyEvent { e ->
		if (beiHoch != null && e.type == KeyEventType.KeyDown && e.key == Key.DirectionUp) beiHoch() else false
	}) {
		Text(titel, style = Schrift.Reihe, modifier = Modifier.padding(start = 48.dp, bottom = 8.dp))
		val zustand = remember(seite.schluessel, titel) { vm.listen.getOrPut("${seite.schluessel}|$titel") { androidx.compose.foundation.lazy.LazyListState() } }
		SeitlichRollen { LazyRow(
			state = zustand,
			contentPadding = PaddingValues(horizontal = 48.dp, vertical = 8.dp),
			horizontalArrangement = Arrangement.spacedBy(18.dp),
		) {
			itemsIndexed(items, key = { _, it -> it.id }) { _, item ->
				val merken = vm to (seite.schluessel to "$titel|${item.id}")
				if (kachel != null) kachel(item, { onClick(item) }, merken) { onFokus(item) }
				else Kachel(item, { onClick(item) }, merken = merken, onFokus = { onFokus(item) }, onMenue = onMenue?.let { m -> { m(item) } } ?: { vm.menue = item })
			}
		} }
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
		if (url != null) AsyncImage(
			model = url,
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
	Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(text, style = Schrift.Leise) }
}

fun laufzeit(ticks: Long?): String? = ticks?.let {
	val min = (it / 600_000_000L).toInt()
	if (min <= 0) null else if (min >= 60) "${min / 60} Std. ${min % 60} Min." else "$min Min."
}

fun BaseItemDto.metazeile(): String = listOfNotNull(
	productionYear?.toString(),
	officialRating,
	laufzeit(runTimeTicks),
	communityRating?.let { "★ %.1f".format(it) },
	genres?.take(3)?.joinToString(", ")?.takeIf { it.isNotBlank() },
).joinToString("   ·   ")

/**
 * Scrollverhalten wie man es vom Fernseher kennt: senkrecht steht die gewählte Reihe immer oben ([oben] Abstand),
 * waagrecht läuft der Fokus bis kurz vor den Rand und erst dann rollt die Reihe.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
object Rollen {
	fun oben(abstandPx: Float) = object : androidx.compose.foundation.gestures.BringIntoViewSpec {
		override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = offset - abstandPx
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
fun SeitlichRollen(rand: Dp = 120.dp, content: @Composable () -> Unit) {
	val px = with(androidx.compose.ui.platform.LocalDensity.current) { rand.toPx() }
	androidx.compose.runtime.CompositionLocalProvider(
		androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides remember(px) { Rollen.seitlich(px) },
		content = content,
	)
}

/** Staffelname auf Deutsch; «Season Unknown» (Folgen ohne Staffel) heisst einfach «Folgen». */
fun staffelName(s: BaseItemDto?): String {
	val n = s?.name.orEmpty()
	return when {
		s == null -> "Folgen"
		n.startsWith("Season Unknown", true) -> "Folgen"
		n.equals("Specials", true) -> "Specials"
		n.startsWith("Season ", true) -> "Staffel " + n.substring(7)
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
	Hinweis("Dieser Titel ist nicht mehr vorhanden.")
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
