package ch.seliku.handy.ui

import android.content.Context
import ch.seliku.handy.Einstellungen
import ch.seliku.handy.Sitzung
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jellyfin.sdk.model.api.BaseItemDto
import java.io.File

/**
 * Schnellstart wie am TV (S 04.11.: «Die Startseite muss sofort stehen»): die letzten Reihen liegen in einer Datei,
 * werden beim Kaltstart sofort gezeigt und still ersetzt, sobald frische Daten da sind. Höchstens 7 Tage alt, je Anmeldung.
 */
object Schnellstart {
	@Serializable private data class Gemerkt(val wer: String, val zeit: Long, val reihen: List<R>)
	@Serializable private data class R(val titel: String, val art: String, val gruppe: Int, val items: List<BaseItemDto>, val loeschdatum: Map<String, String> = emptyMap())
	private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; explicitNulls = false }
	private fun datei(c: Context) = File(c.filesDir, "seliku-schnellstart-reihen.json")
	private fun wer() = "${Sitzung.server}|${Sitzung.token?.hashCode()}"

	fun speichern(c: Context, reihen: List<StartReihe>) {
		if (!Einstellungen.an("schnellstart")) { datei(c).delete(); return }
		runCatching {
			val g = Gemerkt(wer(), System.currentTimeMillis(), reihen.map { r -> R(r.titel, r.art.name, r.gruppe, r.items.take(20), r.loeschdatum.mapKeys { it.key.toString() }) })
			datei(c).writeText(json.encodeToString(Gemerkt.serializer(), g))
		}
	}

	fun laden(c: Context): List<StartReihe>? = runCatching {
		if (!Einstellungen.an("schnellstart")) return null
		val g = json.decodeFromString(Gemerkt.serializer(), datei(c).readText())
		if (g.wer != wer() || System.currentTimeMillis() - g.zeit > 7 * 24 * 3600_000L) return null
		g.reihen.map { r -> StartReihe(r.titel, r.items, StartReihe.Art.valueOf(r.art), r.gruppe, r.loeschdatum.mapKeys { java.util.UUID.fromString(it.key) }) }
	}.getOrNull()
}
