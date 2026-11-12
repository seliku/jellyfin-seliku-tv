package org.jellyfin.androidtv.seliku

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.displayPreferencesApi
import org.koin.java.KoinJavaComponent
import timber.log.Timber

/**
 * Umfang einer Einstellung: «für alle Geräte» (☁, über das Jellyfin-Konto abgeglichen) oder «nur dieses Gerät» (📺).
 * Der Umfang selbst gilt nur auf diesem Gerät (Datei «seliku_umfang»: Schlüssel → «alle»/«geraet»); abgeglichen werden nur die Werte.
 */
object SelikuUmfang {
	private const val DATEI = "seliku_umfang"
	private fun prefs(c: Context) = c.getSharedPreferences(DATEI, Context.MODE_PRIVATE)

	fun alle(c: Context, schluessel: String): Boolean {
		val vorgabe = SelikuSync.eintraege.firstOrNull { it.schluessel == schluessel }?.vorgabeAlle ?: false
		return when (prefs(c).getString(schluessel, null)) {
			"alle" -> true
			"geraet" -> false
			else -> vorgabe
		}
	}

	/** Wechselt ☁ ↔ 📺; gibt den neuen Umfang zurück (true = alle Geräte). */
	fun umschalten(c: Context, schluessel: String): Boolean {
		val neu = !alle(c, schluessel)
		prefs(c).edit().putString(schluessel, if (neu) "alle" else "geraet").apply()
		return neu
	}
}

/**
 * Abgleich der Einstellungen pro Jellyfin-Benutzer: ein Dokument in den Anzeige-Einstellungen des Kontos
 * (DisplayPreferences «seliku-einstellungen», Client «seliku»), CustomPrefs Schlüssel → Wert als Text.
 * Holen: Server gewinnt; fehlt ein Wert dort, gilt der dieses Geräts (erstes Gerät gewinnt).
 * Fehler werden nur geloggt – ohne Netz läuft alles mit den Werten auf dem Gerät.
 */
/**
 * Was nach einer Folge passiert: die nächste startet nach dem Countdown, sie wartet still auf OK (wie bei Plex),
 * oder Schluss. Abgebildet auf Jellyfins eigene Einstellungen: Warteschlange (pref_enable_tv_queuing) und
 * Countdown der «Als Nächstes»-Seite (next_up_timeout, 0 = kein Countdown).
 */
object NachDerFolge {
	const val STARTET = 0
	const val WARTET = 1
	const val SCHLUSS = 2
	private const val COUNTDOWN = 1000 * 7
	private fun prefs() = KoinJavaComponent.get<UserPreferences>(UserPreferences::class.java)

	fun lesen(): Int = when {
		!prefs()[UserPreferences.mediaQueuingEnabled] -> SCHLUSS
		prefs()[UserPreferences.nextUpTimeout] == org.jellyfin.androidtv.preference.constant.NEXTUP_TIMER_DISABLED -> WARTET
		else -> STARTET
	}

	fun setzen(stufe: Int) {
		val p = prefs()
		p[UserPreferences.mediaQueuingEnabled] = stufe != SCHLUSS
		when (stufe) {
			WARTET -> {
				p[UserPreferences.nextUpTimeout] = org.jellyfin.androidtv.preference.constant.NEXTUP_TIMER_DISABLED
				// Ohne «Als Nächstes»-Seite gäbe es nichts, das wartet
				if (p[UserPreferences.nextUpBehavior] == org.jellyfin.androidtv.preference.constant.NextUpBehavior.DISABLED)
					p[UserPreferences.nextUpBehavior] = org.jellyfin.androidtv.preference.constant.NextUpBehavior.EXTENDED
			}
			STARTET -> if (p[UserPreferences.nextUpTimeout] == org.jellyfin.androidtv.preference.constant.NEXTUP_TIMER_DISABLED) p[UserPreferences.nextUpTimeout] = COUNTDOWN
		}
	}

	/** Für den Abgleich: «true», «warten» oder «false». */
	fun wert(): String = when (lesen()) { WARTET -> "warten"; SCHLUSS -> "false"; else -> "true" }
	fun wertSetzen(w: String) = when (w) { "warten" -> setzen(WARTET); "true" -> setzen(STARTET); "false" -> setzen(SCHLUSS); else -> Unit }
}

object SelikuSync {
	private const val DOKUMENT = "seliku-einstellungen"
	private const val CLIENT = "seliku"

	/** Eine abgleichbare Einstellung: liest bzw. schreibt den Wert auf dem Gerät als Text. */
	class Eintrag(val schluessel: String, val vorgabeAlle: Boolean, val lesen: (Context) -> String, val schreiben: (Context, String) -> Unit)

	private fun userPrefs() = KoinJavaComponent.get<UserPreferences>(UserPreferences::class.java)
	private fun schalter(schluessel: String, vorgabeAlle: Boolean, lesen: (Context) -> Boolean, schreiben: (Context, Boolean) -> Unit) =
		Eintrag(schluessel, vorgabeAlle, { lesen(it).toString() }, { c, w -> w.toBooleanStrictOrNull()?.let { schreiben(c, it) } })

	val eintraege = listOf(
		// Vorgabe ☁ alle Geräte
		schalter("netflix_kacheln", true, SelikuEinstellungen::netflixKacheln, SelikuEinstellungen::netflixKacheln),
		schalter("favoriten_reihe", true, SelikuEinstellungen::favoritenReihe, SelikuEinstellungen::favoritenReihe),
		schalter("wahre_geschichten", true, SelikuEinstellungen::wahreGeschichten, SelikuEinstellungen::wahreGeschichten),
		schalter("eigene_wertung", true, SelikuEinstellungen::eigeneWertung, SelikuEinstellungen::eigeneWertung),
		schalter("loeschen", true, SelikuEinstellungen::loeschen, SelikuEinstellungen::loeschen),
		schalter("musik_favoriten", true, SelikuEinstellungen::musikFavoriten, SelikuEinstellungen::musikFavoriten),
		schalter("tomaten", true, SelikuEinstellungen::tomaten, SelikuEinstellungen::tomaten),
		schalter("trailer_banner", true, SelikuEinstellungen::trailerImBanner, SelikuEinstellungen::trailerImBanner),
		schalter("ausschnitt_ohne_trailer", true, SelikuEinstellungen::ausschnittOhneTrailer, SelikuEinstellungen::ausschnittOhneTrailer),
		schalter("titelmusik", true, SelikuEinstellungen::titelmusik, SelikuEinstellungen::titelmusik),
		schalter("zufall_knopf", true, SelikuEinstellungen::zufallKnopf, SelikuEinstellungen::zufallKnopf),
		schalter("sender_gruppen", true, SelikuEinstellungen::senderGruppen, SelikuEinstellungen::senderGruppen),
		schalter("live_zurueck_programm", true, SelikuEinstellungen::liveZurueckProgramm, SelikuEinstellungen::liveZurueckProgramm),
		schalter("doppel_zurueck", true, SelikuEinstellungen::doppelZurueck, SelikuEinstellungen::doppelZurueck),
		schalter("leiste_mit_links", true, SelikuEinstellungen::leisteMitLinks, SelikuEinstellungen::leisteMitLinks),
		schalter("uhr_leiste", true, SelikuEinstellungen::uhrInLeiste, SelikuEinstellungen::uhrInLeiste),
		schalter("schnellstart", true, SelikuEinstellungen::schnellstart, SelikuEinstellungen::schnellstart),
		schalter("aufnahmen_unter_livetv", true, SelikuEinstellungen::aufnahmenUnterLiveTv, SelikuEinstellungen::aufnahmenUnterLiveTv),
		schalter("untertitel_nachladen", true, SelikuEinstellungen::untertitelNachladen, SelikuEinstellungen::untertitelNachladen),
		schalter("mein_verlauf", true, SelikuEinstellungen::meinVerlauf, SelikuEinstellungen::meinVerlauf),
		schalter("banner_seerr", true, SelikuEinstellungen::bannerSeerr, SelikuEinstellungen::bannerSeerr),
		schalter("audio_player", true, SelikuEinstellungen::neuerAudioPlayer, SelikuEinstellungen::neuerAudioPlayer),
		Eintrag("ueberblenden", true, { SelikuEinstellungen.ueberblenden(it).toString() }, { c, w -> w.toIntOrNull()?.let { SelikuEinstellungen.ueberblenden(c, it) } }),
		schalter("endlos_musik", true, SelikuEinstellungen::endlosMusik, SelikuEinstellungen::endlosMusik),
		schalter("hoeren_start", true, SelikuEinstellungen::hoerenStart, SelikuEinstellungen::hoerenStart),
		// Vorgabe 📺 nur dieses Gerät
		schalter("schlanker_player", false, SelikuEinstellungen::schlankerPlayer, SelikuEinstellungen::schlankerPlayer),
		schalter("nur_bei_wiedergabe_wach", false, SelikuEinstellungen::nurBeiWiedergabeWach, SelikuEinstellungen::nurBeiWiedergabeWach),
		schalter("weiterschauen_geraet", false, SelikuEinstellungen::weiterschauenGeraet, SelikuEinstellungen::weiterschauenGeraet),
		Eintrag("max_bitrate", false, { userPrefs()[UserPreferences.maxBitrate] }, { _, w -> if (w.toIntOrNull() != null) userPrefs()[UserPreferences.maxBitrate] = w }),
		Eintrag("naechste_folge", false, { NachDerFolge.wert() }, { _, w -> NachDerFolge.wertSetzen(w) }),
	)

	private val bereich = CoroutineScope(SupervisorJob() + Dispatchers.IO)
	private val sperre = Mutex()
	private fun api() = KoinJavaComponent.get<ApiClient>(ApiClient::class.java)

	private fun abgeglichen(c: Context) = eintraege.filter { SelikuUmfang.alle(c, it.schluessel) }

	/** Werte vom Server holen (Server gewinnt) und fehlende mit den Werten dieses Geräts ergänzen. Gibt true zurück, wenn sich etwas geändert hat. */
	suspend fun holen(context: Context): Boolean = withContext(Dispatchers.IO) {
		val c = context.applicationContext
		sperre.withLock {
			runCatching {
				if (api().accessToken == null) return@runCatching false
				val server = api().displayPreferencesApi.getDisplayPreferences(displayPreferencesId = DOKUMENT, client = CLIENT).content.customPrefs
				val uebernommen = mutableListOf<String>()
				val fehlen = mutableListOf<String>()
				for (e in abgeglichen(c)) {
					val w = server[e.schluessel]
					when {
						w == null -> fehlen += e.schluessel
						w != e.lesen(c) -> { e.schreiben(c, w); uebernommen += "${e.schluessel}=$w" }
					}
				}
				Timber.i("Seliku-Sync: geholt (${server.size} Werte auf dem Server), übernommen: $uebernommen, fehlen: $fehlen")
				if (fehlen.isNotEmpty()) speichernOhneSperre(c)
				uebernommen.isNotEmpty()
			}.onFailure { Timber.w(it, "Seliku-Sync: holen fehlgeschlagen") }.getOrDefault(false)
		}
	}

	/** Nach einer Änderung: gilt die Einstellung für alle Geräte, alle abgeglichenen Werte hochladen (im Hintergrund). */
	fun geaendert(context: Context, schluessel: String) {
		val c = context.applicationContext
		if (SelikuUmfang.alle(c, schluessel)) speichern(c)
	}

	/** Alle abgeglichenen Werte dieses Geräts hochladen; andere Schlüssel auf dem Server bleiben erhalten. */
	fun speichern(context: Context) {
		val c = context.applicationContext
		bereich.launch {
			sperre.withLock {
				runCatching { speichernOhneSperre(c) }.onFailure { Timber.w(it, "Seliku-Sync: speichern fehlgeschlagen") }
			}
		}
	}

	private suspend fun speichernOhneSperre(c: Context) {
		val werte = abgeglichen(c).associate { it.schluessel to it.lesen(c) }
		if (werte.isEmpty()) return
		val alt = api().displayPreferencesApi.getDisplayPreferences(displayPreferencesId = DOKUMENT, client = CLIENT).content
		api().displayPreferencesApi.updateDisplayPreferences(displayPreferencesId = DOKUMENT, client = CLIENT,
			data = alt.copy(customPrefs = alt.customPrefs + werte))
		Timber.i("Seliku-Sync: gespeichert (PUT $DOKUMENT) $werte")
	}
}
