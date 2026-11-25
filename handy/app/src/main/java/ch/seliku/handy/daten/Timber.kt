package ch.seliku.handy.daten

/** Kleiner Ersatz für Timber (die TV-App nutzt es; hier nur ins System-Log). */
object Timber {
	fun i(m: String, vararg a: Any?) { android.util.Log.i("Seliku", runCatching { m.format(*a) }.getOrDefault(m)) }
	fun d(m: String, vararg a: Any?) { android.util.Log.d("Seliku", runCatching { m.format(*a) }.getOrDefault(m)) }
	fun d(t: Throwable?, m: String, vararg a: Any?) { android.util.Log.d("Seliku", runCatching { m.format(*a) }.getOrDefault(m), t) }
	fun w(m: String, vararg a: Any?) { android.util.Log.w("Seliku", runCatching { m.format(*a) }.getOrDefault(m)) }
	fun w(t: Throwable?, m: String, vararg a: Any?) { android.util.Log.w("Seliku", runCatching { m.format(*a) }.getOrDefault(m), t) }
	fun e(t: Throwable?, m: String, vararg a: Any?) { android.util.Log.e("Seliku", runCatching { m.format(*a) }.getOrDefault(m), t) }
}
