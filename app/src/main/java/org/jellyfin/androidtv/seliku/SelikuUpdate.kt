package org.jellyfin.androidtv.seliku

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.BuildConfig
import org.json.JSONObject
import timber.log.Timber
import java.net.HttpURLConnection
import java.net.URL

/**
 * Selbst-Update der App: liest version.json von der eigenen Ablage, lädt bei neuerer Version die APK
 * und übergibt sie dem Android-Installer. Der Fernseher fragt einmal nach der Erlaubnis
 * «Unbekannte Apps installieren», danach genügt ein Klick auf «Installieren».
 */
object SelikuUpdate {
	const val ABLAGE = "https://jellyfin.example.org/seliku-app/"
	private const val AKTION = "ch.seliku.jellyfin.tv.UPDATE_STATUS"

	data class Version(val code: Int, val name: String, val apk: String, val notiz: String)

	suspend fun pruefen(): Version? = withContext(Dispatchers.IO) {
		runCatching {
			val text = (URL(ABLAGE + "version.json").openConnection() as HttpURLConnection).run {
				connectTimeout = 10_000; readTimeout = 10_000; useCaches = false
				inputStream.bufferedReader().use { it.readText() }
			}
			val j = JSONObject(text)
			Version(j.getInt("versionCode"), j.optString("versionName"), j.getString("apk"), j.optString("notiz"))
		}.onFailure { Timber.w(it, "Update prüfen") }.getOrNull()?.takeIf { it.code > BuildConfig.VERSION_CODE }
	}

	fun darfInstallieren(context: Context) =
		Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

	/** Öffnet die Einstellung «Unbekannte Apps installieren» für diese App (einmalig nötig). */
	fun erlaubnisOeffnen(context: Context) {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			context.startActivity(
				Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + context.packageName))
					.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
			)
		}
	}

	/** Lädt die APK herunter und startet die Installation. [fortschritt] bekommt 0..1. */
	suspend fun installieren(context: Context, v: Version, fortschritt: (Float) -> Unit): Result<Unit> = withContext(Dispatchers.IO) {
		runCatching {
			val installer = context.packageManager.packageInstaller
			val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
			val sitzung = installer.openSession(installer.createSession(params))
			sitzung.use { s ->
				val verbindung = URL(ABLAGE + v.apk).openConnection() as HttpURLConnection
				verbindung.connectTimeout = 15_000; verbindung.readTimeout = 30_000
				val gesamt = verbindung.contentLengthLong.coerceAtLeast(1)
				verbindung.inputStream.use { ein ->
					s.openWrite("app.apk", 0, gesamt).use { aus ->
						val puffer = ByteArray(64 * 1024)
						var gelesen = 0L
						while (true) {
							val n = ein.read(puffer)
							if (n < 0) break
							aus.write(puffer, 0, n)
							gelesen += n
							fortschritt((gelesen.toFloat() / gesamt).coerceIn(0f, 1f))
						}
						s.fsync(aus)
					}
				}
				val empfaenger = object : BroadcastReceiver() {
					override fun onReceive(c: Context, intent: Intent) {
						when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)) {
							PackageInstaller.STATUS_PENDING_USER_ACTION -> {
								@Suppress("DEPRECATION")
								val bestaetigen = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
								bestaetigen?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let(c::startActivity)
							}
							else -> {
								Timber.i("Update-Status: %s", intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
								runCatching { c.unregisterReceiver(this) }
							}
						}
					}
				}
				ContextCompat.registerReceiver(context.applicationContext, empfaenger, IntentFilter(AKTION), ContextCompat.RECEIVER_NOT_EXPORTED)
				val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
				val rueckmeldung = PendingIntent.getBroadcast(context, 0, Intent(AKTION).setPackage(context.packageName), flags)
				s.commit(rueckmeldung.intentSender)
			}
		}.onFailure { Timber.w(it, "Update installieren") }
	}
}
