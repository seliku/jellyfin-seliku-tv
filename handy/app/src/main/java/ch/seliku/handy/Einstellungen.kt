package ch.seliku.handy

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.extensions.displayPreferencesApi
import java.util.UUID

/**
 * Seliku-Einstellungen wie am Fernseher: gleiche Schlüssel, gleicher Abgleich über das Jellyfin-Konto
 * (DisplayPreferences «seliku-einstellungen», Client «seliku»). Server gewinnt beim Holen; fehlt ein Wert dort,
 * gilt der des Geräts. Schlüssel, die das Handy nicht kennt (z. B. Fernseher-Bedienung), bleiben auf dem Server unberührt.
 */
object Einstellungen {
	class Schalter(val schluessel: String, val titel: String, val beschreibung: String, val vorgabe: Boolean, val alleGeraete: Boolean, val gruppe: String)

	val schalter = listOf(
		Schalter("favoriten_reihe", "Deine Favoriten", "Reihe «Deine Favoriten» auf der Startseite", true, true, "Startseite"),
		Schalter("wahre_geschichten", "Wahre Geschichten", "Reihe mit Filmen und Serien nach wahren Begebenheiten", true, true, "Startseite"),
		Schalter("hoeren_start", "Weiterhören", "Hörbücher und Podcasts auf der Startseite", true, true, "Startseite"),
		Schalter("banner_seerr", "Fehlende Titel im Banner", "1–2 Vorschläge, die es noch nicht gibt (zum Anfragen)", true, true, "Startseite"),
		Schalter("trailer_banner", "Trailer im Banner", "Banner zeigt den Trailer (stumm, Ton per Knopf)", true, true, "Startseite"),
		Schalter("schnellstart", "Schnellstart", "Startseite sofort aus dem Speicher zeigen, dann auffrischen", true, true, "Startseite"),
		Schalter("mein_verlauf", "Mein Seliku", "Profilseite mit Verlauf, Meine Liste und Sehzeit", true, true, "Startseite"),
		Schalter("weiterschauen_geraet", "Weiterschauen nur von diesem Gerät", "Zeigt nur, was auf diesem Handy angefangen wurde", false, false, "Startseite"),
		Schalter("uhr_film", "Uhr im Film", "Uhrzeit unten links während der Wiedergabe, fast durchsichtig", true, true, "Wiedergabe"),
		Schalter("intro_auto", "Vorspann automatisch überspringen", "Sonst erscheint ein Knopf «Vorspann überspringen»", false, false, "Wiedergabe"),
		Schalter("untertitel_nachladen", "Untertitel nachladen", "Film startet sofort, Untertitel kommen nach", true, true, "Wiedergabe"),
		Schalter("titelmusik", "Titelmusik", "Leise Titelmusik auf Serienseiten", true, true, "Serien"),
		Schalter("zufall_knopf", "Knopf «Zufällig»", "Zufällige Folge direkt auf der Serienseite", false, true, "Serien"),
		Schalter("eigene_wertung", "Eigene Sterne", "Titel mit 1–5 Sternen bewerten", true, true, "Inhalte"),
		Schalter("tomaten", "Rotten Tomatoes", "Kritikerwert neben dem Titel", true, true, "Inhalte"),
		Schalter("loeschen", "Löschen erlauben", "«Löschen» im Titelmenü (wenn das Konto darf)", true, true, "Inhalte"),
		Schalter("sender_gruppen", "Sender gruppieren", "Live-TV nach Sprachblöcken (1–99, 100–199 …)", true, true, "Inhalte"),
		Schalter("aufnahmen_unter_livetv", "Aufnahmen unter Live-TV", "Kein eigener Punkt für Aufnahmen", true, true, "Inhalte"),
		Schalter("musik_favoriten", "Musik-Favoriten", "«Deine Favoriten» oben in der Musik", true, true, "Musik"),
		Schalter("endlos_musik", "Endlos-Musik", "Nach dem letzten Titel passende Musik weiterspielen", true, true, "Musik"),
	)

	private lateinit var prefs: SharedPreferences
	private lateinit var app: Context
	private val werte = mutableStateMapOf<String, String>()

	fun start(c: Context) {
		app = c.applicationContext
		prefs = c.getSharedPreferences("seliku", Context.MODE_PRIVATE)
		prefs.all.forEach { (k, v) -> if (v != null) werte[k] = v.toString() }
	}

	fun an(schluessel: String): Boolean = werte[schluessel]?.toBooleanStrictOrNull()
		?: schalter.firstOrNull { it.schluessel == schluessel }?.vorgabe ?: false

	fun setzen(schluessel: String, wert: String) {
		werte[schluessel] = wert
		prefs.edit().putString(schluessel, wert).apply()
		val s = schalter.firstOrNull { it.schluessel == schluessel }
		if (s == null || alle(s)) speichern()
	}

	fun text(schluessel: String): String? = werte[schluessel]

	// Umfang ☁/📺 je Einstellung (nur dieses Gerät merkt sich, was es abgleicht)
	fun alle(s: Schalter): Boolean = prefs.getString("umfang_${s.schluessel}", null)?.let { it == "alle" } ?: s.alleGeraete
	fun umfangWechseln(s: Schalter) {
		prefs.edit().putString("umfang_${s.schluessel}", if (alle(s)) "geraet" else "alle").apply()
		werte["umfang_${s.schluessel}"] = if (alle(s)) "alle" else "geraet"
		if (alle(s)) speichern()
	}

	// Bequeme Abfragen
	fun uhrImFilm() = an("uhr_film")
	fun introAutomatisch() = an("intro_auto")
	fun seerrAn() = seerrVerbunden
	var seerrVerbunden by androidx.compose.runtime.mutableStateOf(false)

	/** Was nach einer Folge passiert (wie am Fernseher): «true» nächste startet, «warten» Knopf ohne Countdown, «false» Schluss. */
	fun nachDerFolge(): String = werte["naechste_folge"] ?: "true"
	fun naechsteAutomatisch() = nachDerFolge() == "true"

	/** Höchste Bitrate (Bit/s) für unterwegs; null = Original. */
	fun maxBitrate(): Int? = werte["handy_max_bitrate"]?.toIntOrNull()?.takeIf { it > 0 }

	/** Von Hand gewählte Tonsprache je Serie (nur dieses Gerät, wie am Fernseher). */
	fun tonspracheFuer(serie: UUID): String? = prefs.getString("ton_$serie", null)
	fun tonspracheMerken(serie: UUID, sprache: String) { prefs.edit().putString("ton_$serie", sprache).apply() }

	// --- Abgleich mit dem Konto ---
	private const val DOKUMENT = "seliku-einstellungen"
	private const val CLIENT = "seliku"
	private val sperre = Mutex()
	private val bereich = CoroutineScope(SupervisorJob() + Dispatchers.IO)

	suspend fun holen() = withContext(Dispatchers.IO) {
		val api = Sitzung.api ?: return@withContext
		sperre.withLock {
			runCatching {
				val server = api.displayPreferencesApi.getDisplayPreferences(displayPreferencesId = DOKUMENT, client = CLIENT).content.customPrefs
				var fehlt = false
				for (s in schalter.filter { alle(it) }) {
					val w = server[s.schluessel]
					if (w == null) fehlt = true
					else if (w != werte[s.schluessel]) { werte[s.schluessel] = w; prefs.edit().putString(s.schluessel, w).apply() }
				}
				if (fehlt) hochladen()
			}
		}
	}

	private fun speichern() { bereich.launch { sperre.withLock { runCatching { hochladen() } } } }

	private suspend fun hochladen() {
		val api = Sitzung.api ?: return
		val neu = schalter.filter { alle(it) }.associate { it.schluessel to an(it.schluessel).toString() }
		val alt = api.displayPreferencesApi.getDisplayPreferences(displayPreferencesId = DOKUMENT, client = CLIENT).content
		api.displayPreferencesApi.updateDisplayPreferences(displayPreferencesId = DOKUMENT, client = CLIENT, data = alt.copy(customPrefs = alt.customPrefs + neu))
	}
}
