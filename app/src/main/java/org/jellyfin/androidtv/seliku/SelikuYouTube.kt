package org.jellyfin.androidtv.seliku

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * YouTube-Trailer: zuerst in SmartTube (ohne Werbung, im Wohnzimmer installiert), sonst in der YouTube-App,
 * sonst im Browser.
 */
object YouTube {
	private val smartTube = listOf("org.smarttube.stable", "com.teamsmart.videomanager.tv", "com.liskovsoft.smarttubetv.beta", "org.smarttube.beta")

	/** Erstes installiertes SmartTube-Paket, sonst null. */
	fun smartTube(context: Context): String? = smartTube.firstOrNull { p ->
		runCatching { context.packageManager.getPackageInfo(p, 0) }.isSuccess
	}

	fun adresse(key: String): Uri = Uri.parse("https://www.youtube.com/watch?v=$key")

	/** Spielt den Trailer [key] ab; false, wenn keine App ihn öffnen kann. */
	fun abspielen(context: Context, key: String): Boolean {
		fun starten(intent: Intent) = runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
		smartTube(context)?.let { p -> if (starten(Intent(Intent.ACTION_VIEW, adresse(key)).setPackage(p))) return true }
		if (starten(Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:$key")))) return true
		return starten(Intent(Intent.ACTION_VIEW, adresse(key)))
	}
}
