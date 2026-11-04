package org.jellyfin.androidtv.seliku

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.displayPreferencesApi
import org.json.JSONArray
import org.json.JSONObject
import org.koin.compose.koinInject
import timber.log.Timber
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Ein Radiosender aus dem freien Verzeichnis radio-browser.info. */
data class RadioSender(val id: String, val name: String, val url: String, val logo: String?, val land: String, val tags: String) {
	fun alsJson() = JSONObject().put("id", id).put("name", name).put("url", url).put("logo", logo ?: "").put("land", land).put("tags", tags)

	companion object {
		fun ausJson(o: JSONObject) = RadioSender(
			id = o.optString("stationuuid", o.optString("id")),
			name = o.optString("name").trim(),
			url = o.optString("url_resolved").ifBlank { o.optString("url") },
			logo = o.optString("favicon", o.optString("logo")).takeIf { it.startsWith("http") },
			land = o.optString("countrycode", o.optString("land")),
			tags = o.optString("tags"),
		)
	}
}

/** Abfragen an radio-browser.info (ohne Anmeldung) und Favoriten im Jellyfin-Konto (gelten auf allen Geräten). */
class RadioDaten(private val api: ApiClient) {
	private val server = "https://de1.api.radio-browser.info/json/"

	private suspend fun holen(pfad: String): List<RadioSender> = withContext(Dispatchers.IO) {
		val v = URL(server + pfad).openConnection() as HttpURLConnection
		v.setRequestProperty("User-Agent", "Seliku-TV/1.0")
		v.connectTimeout = 10_000; v.readTimeout = 15_000
		val a = JSONArray(v.inputStream.bufferedReader().use { it.readText() })
		(0 until a.length()).map { RadioSender.ausJson(a.getJSONObject(it)) }.filter { it.url.isNotBlank() && it.name.isNotBlank() }
	}

	suspend fun beliebtIn(land: String, anzahl: Int = 40) =
		holen("stations/bycountrycodeexact/$land?order=clickcount&reverse=true&hidebroken=true&limit=$anzahl")

	suspend fun nachGenre(tag: String, anzahl: Int = 30) =
		holen("stations/bytagexact/${URLEncoder.encode(tag, "UTF-8")}?order=clickcount&reverse=true&hidebroken=true&limit=$anzahl")

	suspend fun suche(text: String) =
		holen("stations/search?name=${URLEncoder.encode(text, "UTF-8")}&order=clickcount&reverse=true&hidebroken=true&limit=40")

	// Favoriten: in den Anzeige-Einstellungen des Jellyfin-Kontos (CustomPrefs), damit sie auf jedem Gerät da sind
	private val praefId = "seliku-radio"
	private val client = "seliku"

	suspend fun favoriten(): List<RadioSender> = withContext(Dispatchers.IO) {
		runCatching {
			val json = api.displayPreferencesApi.getDisplayPreferences(displayPreferencesId = praefId, client = client).content.customPrefs["favoriten"]
			if (json.isNullOrBlank()) emptyList() else JSONArray(json).let { a -> (0 until a.length()).map { RadioSender.ausJson(a.getJSONObject(it)) } }
		}.onFailure { Timber.w(it, "Radio-Favoriten laden") }.getOrDefault(emptyList())
	}

	suspend fun favoritenSetzen(liste: List<RadioSender>) = withContext(Dispatchers.IO) {
		val alt = api.displayPreferencesApi.getDisplayPreferences(displayPreferencesId = praefId, client = client).content
		val json = JSONArray().apply { liste.forEach { put(it.alsJson()) } }.toString()
		api.displayPreferencesApi.updateDisplayPreferences(displayPreferencesId = praefId, client = client,
			data = alt.copy(customPrefs = alt.customPrefs + ("favoriten" to json)))
	}
}

/** Eigener kleiner Spieler für Radio-Streams (die Musikwiedergabe der App kennt nur Jellyfin-Titel). */
object RadioSpieler {
	private var spieler: ExoPlayer? = null
	val laeuft = MutableStateFlow<RadioSender?>(null)
	val spielt = MutableStateFlow(false)

	fun spielen(context: Context, sender: RadioSender) {
		val p = spieler ?: ExoPlayer.Builder(context.applicationContext).build().also {
			it.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
			it.addListener(object : androidx.media3.common.Player.Listener {
				override fun onIsPlayingChanged(isPlaying: Boolean) { spielt.value = isPlaying }
			})
			spieler = it
		}
		p.setMediaItem(MediaItem.fromUri(sender.url))
		p.prepare(); p.play()
		laeuft.value = sender
	}

	fun umschalten() { spieler?.let { if (it.isPlaying) it.pause() else it.play() } }

	fun stoppen() {
		spieler?.stop()
		laeuft.value = null
		spielt.value = false
	}
}

/**
 * Radio-Reiter unter Musik: Favoriten, beliebt in der Schweiz, Kosovo/Albanien, Genres und Suche.
 * OK spielt, Menütaste oder langes Drücken nimmt den Sender in die Favoriten auf bzw. wieder heraus.
 */
@Composable
fun RadioReiter(vm: SelikuViewModel) {
	val daten = koinInject<RadioDaten>()
	val mediaManager = koinInject<org.jellyfin.androidtv.ui.playback.MediaManager>()
	val context = LocalContext.current
	val scope = rememberCoroutineScope()
	@Suppress("UNCHECKED_CAST")
	var reihen by remember { mutableStateOf(vm.wahl["radio-reihen"] as? List<Pair<String, List<RadioSender>>> ?: emptyList()) }
	var favoriten by remember { mutableStateOf<List<RadioSender>>(emptyList()) }
	var suchtext by remember { mutableStateOf("") }
	var treffer by remember { mutableStateOf<List<RadioSender>>(emptyList()) }
	val genres = listOf("pop" to "Pop", "rock" to "Rock", "dance" to "Dance", "schlager" to "Schlager", "classical" to "Klassik", "jazz" to "Jazz", "news" to "Nachrichten")

	LaunchedEffect(Unit) {
		favoriten = daten.favoriten()
		if (reihen.isEmpty()) {
			val neu = buildList {
				runCatching { daten.beliebtIn("CH") }.getOrNull()?.let { add("Beliebt in der Schweiz" to it) }
				runCatching { daten.beliebtIn("XK") + daten.beliebtIn("AL", 20) }.getOrNull()?.let { add("Kosovo und Albanien" to it) }
				runCatching { daten.beliebtIn("DE", 30) }.getOrNull()?.let { add("Beliebt in Deutschland" to it) }
				genres.forEach { (tag, name) -> runCatching { daten.nachGenre(tag) }.getOrNull()?.let { add(name to it) } }
			}.filter { it.second.isNotEmpty() }
			reihen = neu; vm.wahl["radio-reihen"] = neu
		}
	}
	LaunchedEffect(suchtext) {
		if (suchtext.trim().length < 2) { treffer = emptyList(); return@LaunchedEffect }
		kotlinx.coroutines.delay(450)
		treffer = runCatching { daten.suche(suchtext.trim()) }.getOrDefault(emptyList())
	}

	val spielen: (RadioSender) -> Unit = { s -> mediaManager.stopAudio(true); RadioSpieler.spielen(context, s) }
	val favoritUmschalten: (RadioSender) -> Unit = { s ->
		val neu = if (favoriten.any { it.id == s.id }) favoriten.filter { it.id != s.id } else favoriten + s
		favoriten = neu
		scope.launch { runCatching { daten.favoritenSetzen(neu) }.onFailure { Timber.w(it, "Radio-Favoriten speichern") } }
		android.widget.Toast.makeText(context, if (neu.any { it.id == s.id }) "${s.name} zu Favoriten" else "${s.name} aus Favoriten entfernt", android.widget.Toast.LENGTH_SHORT).show()
	}
	val seite = Seite.Ordner(java.util.UUID.nameUUIDFromBytes("radio".toByteArray()), "radio")

	SenkrechtRollen {
		LazyColumn(state = vm.liste(seite), contentPadding = PaddingValues(top = 8.dp, bottom = 48.dp), modifier = Modifier.fillMaxSize()) {
			item {
				Box(Modifier.padding(start = 48.dp, bottom = 12.dp).width(520.dp).background(Farben.Flaeche, RoundedCornerShape(10.dp)).padding(horizontal = 16.dp, vertical = 12.dp)) {
					if (suchtext.isEmpty()) Text("Sender suchen, z. B. SRF 3 oder Radio Kosova …", style = Schrift.Leise)
					androidx.compose.foundation.text.BasicTextField(value = suchtext, onValueChange = { suchtext = it }, singleLine = true, textStyle = Schrift.Normal,
						cursorBrush = androidx.compose.ui.graphics.SolidColor(Farben.Akzent), modifier = Modifier.fillMaxWidth())
				}
			}
			if (treffer.isNotEmpty()) item { SenderReihe("Treffer", treffer, favoriten, vm, seite, spielen, favoritUmschalten) }
			if (favoriten.isNotEmpty()) item { SenderReihe("Deine Favoriten", favoriten, favoriten, vm, seite, spielen, favoritUmschalten) }
			items(reihen, key = { it.first }) { (titel, liste) -> SenderReihe(titel, liste, favoriten, vm, seite, spielen, favoritUmschalten) }
			if (reihen.isEmpty()) item { Text("Sender werden geladen …", style = Schrift.Leise, modifier = Modifier.padding(start = 48.dp)) }
		}
	}
}

@Composable
private fun SenderReihe(
	titel: String, liste: List<RadioSender>, favoriten: List<RadioSender>, vm: SelikuViewModel, seite: Seite,
	spielen: (RadioSender) -> Unit, favoritUmschalten: (RadioSender) -> Unit,
) {
	Column(Modifier.fillMaxWidth().padding(bottom = 18.dp)) {
		Text(titel, style = Schrift.Reihe, modifier = Modifier.padding(start = 48.dp, bottom = 8.dp))
		SeitlichRollen {
			LazyRow(contentPadding = PaddingValues(horizontal = 48.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
				items(liste, key = { titel + it.id }) { s ->
					val fav = favoriten.any { it.id == s.id }
					Column(Modifier.width(140.dp)) {
						Waehlbar(onClick = { spielen(s) }, onMenue = { favoritUmschalten(s) }, merken = vm to (seite.schluessel to titel + s.id)) {
							Box(Modifier.fillMaxWidth().aspectRatio(1f).background(Farben.FlaecheHell), contentAlignment = Alignment.Center) {
								if (s.logo != null) Bild(s.logo, Modifier.fillMaxSize().padding(14.dp), ContentScale.Fit, grund = androidx.compose.ui.graphics.Color.Transparent)
								else Text(initialen(s.name), style = Schrift.Riesig.copy(color = Farben.TextLeise))
								if (fav) Text("★", style = Schrift.Titel.copy(color = Farben.Akzent), modifier = Modifier.align(Alignment.TopEnd).padding(6.dp))
							}
						}
						Spacer(Modifier.height(6.dp))
						Text(s.name, style = Schrift.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
						Text(s.tags.split(",").take(2).joinToString(", "), style = Schrift.Klein, maxLines = 1, overflow = TextOverflow.Ellipsis)
					}
				}
			}
		}
	}
}
