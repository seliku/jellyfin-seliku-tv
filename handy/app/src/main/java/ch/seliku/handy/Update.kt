package ch.seliku.handy

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File

/**
 * Selbst-Update wie bei der TV-App: «Update» erscheint, wenn eine neuere Version bereitliegt – zuerst auf dem eigenen
 * Jellyfin-Server (<server>/web/seliku/handy-version.json + seliku-handy.bin), sonst als GitHub-Release im gemeinsamen
 * Repo (UPDATE_REPO, Tags «handy-v…»; die TV-App nutzt dort die Tags «v…»).
 */
object Update {
	var neu by mutableStateOf<Pair<Int, String>?>(null); private set
	var laedt by mutableStateOf(false); private set
	private val http by lazy { OkHttpClient() }
	private var quelle: String? = null

	suspend fun pruefen() = withContext(Dispatchers.IO) {
		val server = runCatching {
			http.newCall(Request.Builder().url("${Sitzung.server}/web/seliku/handy-version.json").build()).execute().use { r ->
				if (!r.isSuccessful) return@runCatching null
				val o = JSONObject(r.body!!.string())
				Triple(o.getInt("versionCode"), o.optString("versionName"), "${Sitzung.server}/web/seliku/seliku-handy.bin")
			}
		}.getOrNull()
		val gefunden = server ?: runCatching {
			http.newCall(Request.Builder().url("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases?per_page=30")
				.header("Accept", "application/vnd.github+json").build()).execute().use { r ->
				if (!r.isSuccessful) return@runCatching null
				val liste = org.json.JSONArray(r.body!!.string())
				val o = (0 until liste.length()).map { liste.getJSONObject(it) }
					.filter { it.getString("tag_name").startsWith("handy-v") }
					.maxByOrNull { it.getString("tag_name").substringAfterLast('.').toIntOrNull() ?: 0 } ?: return@runCatching null
				val name = o.getString("tag_name").removePrefix("handy-v")
				val code = name.substringAfterLast('.').toIntOrNull() ?: return@runCatching null
				val assets = o.getJSONArray("assets")
				val apk = (0 until assets.length()).map { assets.getJSONObject(it) }.firstOrNull { it.getString("name").endsWith(".apk") }
					?: return@runCatching null
				Triple(code, name, apk.getString("browser_download_url"))
			}
		}.getOrNull()
		neu = gefunden?.takeIf { it.first > BuildConfig.VERSION_CODE }?.let { quelle = it.third; it.first to it.second }
	}

	suspend fun installieren(c: Context) {
		laedt = true
		val datei = withContext(Dispatchers.IO) {
			runCatching {
				val d = File(c.cacheDir, "update").apply { mkdirs() }.resolve("seliku-handy.apk")
				http.newCall(Request.Builder().url(quelle ?: error("keine Quelle")).build()).execute().use { r ->
					if (!r.isSuccessful) error("Download ${r.code}")
					d.outputStream().use { r.body!!.byteStream().copyTo(it) }
				}
				d
			}.getOrNull()
		}
		laedt = false
		datei ?: return
		val uri = FileProvider.getUriForFile(c, "${c.packageName}.dateien", datei)
		c.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
			.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
	}
}
