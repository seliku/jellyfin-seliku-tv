package org.jellyfin.androidtv.seliku

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jellyfin.sdk.model.api.BaseItemDto
import timber.log.Timber
import java.io.File

/**
 * Schnellstart: die zuletzt geladene Startseite und die Bibliotheken der Seitenleiste liegen als Datei auf dem Gerät.
 * Beim Kaltstart erscheinen sie sofort; die frischen Daten vom Server ersetzen sie still, sobald sie da sind (wie bei
 * Netflix). Gilt je Server und Anmeldung, höchstens eine Woche alt. Abschaltbar (Einstellung «Schnellstart»).
 */
object Schnellstart {
	@Serializable
	class Reihe(val titel: String, val art: String, val items: List<BaseItemDto>, val loeschdatum: Map<String, String> = emptyMap())

	@Serializable
	private class Reihen(val wer: String, val zeit: Long, val reihen: List<Reihe>)

	@Serializable
	private class Bibliotheken(val wer: String, val zeit: Long, val liste: List<BaseItemDto>)

	private const val MAX_ALTER = 7L * 24 * 60 * 60 * 1000
	private const val PRO_REIHE = 20
	private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
	private fun reihenDatei(c: Context) = File(c.filesDir, "seliku-schnellstart-reihen.json")
	private fun bibDatei(c: Context) = File(c.filesDir, "seliku-schnellstart-bibliotheken.json")

	suspend fun reihen(c: Context, wer: String): List<Reihe>? = lesen(c) {
		json.decodeFromString<Reihen>(reihenDatei(c).readText()).takeIf { it.wer == wer && frisch(it.zeit) }?.reihen
	}

	suspend fun reihenMerken(c: Context, wer: String, reihen: List<Reihe>) = schreiben(c) {
		reihenDatei(c).writeText(json.encodeToString(Reihen(wer, System.currentTimeMillis(), reihen.map { Reihe(it.titel, it.art, it.items.take(PRO_REIHE), it.loeschdatum) })))
	}

	suspend fun bibliotheken(c: Context, wer: String): List<BaseItemDto>? = lesen(c) {
		json.decodeFromString<Bibliotheken>(bibDatei(c).readText()).takeIf { it.wer == wer && frisch(it.zeit) }?.liste
	}

	suspend fun bibliothekenMerken(c: Context, wer: String, liste: List<BaseItemDto>) = schreiben(c) {
		bibDatei(c).writeText(json.encodeToString(Bibliotheken(wer, System.currentTimeMillis(), liste)))
	}

	/** Aufnahme-Ordner (für «Aufnahmen nur unter Live-TV»), damit sie schon in der gemerkten Leiste fehlen. */
	fun aufnahmeOrdner(c: Context): Set<java.util.UUID> = c.getSharedPreferences("seliku_schnellstart", Context.MODE_PRIVATE)
		.getStringSet("aufnahme_ordner", emptySet()).orEmpty().mapNotNull { runCatching { java.util.UUID.fromString(it) }.getOrNull() }.toSet()

	fun aufnahmeOrdnerMerken(c: Context, ids: Set<java.util.UUID>) = c.getSharedPreferences("seliku_schnellstart", Context.MODE_PRIVATE)
		.edit().putStringSet("aufnahme_ordner", ids.map { it.toString() }.toSet()).apply()

	private fun frisch(zeit: Long) = System.currentTimeMillis() - zeit < MAX_ALTER

	private suspend fun <T> lesen(c: Context, block: () -> T?): T? = withContext(Dispatchers.IO) {
		if (!SelikuEinstellungen.schnellstart(c)) return@withContext null
		runCatching { block() }.onFailure { if (it !is java.io.FileNotFoundException) Timber.w(it, "Schnellstart lesen") }.getOrNull()
	}

	private suspend fun schreiben(c: Context, block: () -> Unit) = withContext(Dispatchers.IO) {
		if (!SelikuEinstellungen.schnellstart(c)) { reihenDatei(c).delete(); bibDatei(c).delete(); return@withContext }
		runCatching { block() }.onFailure { Timber.w(it, "Schnellstart schreiben") }
	}
}
