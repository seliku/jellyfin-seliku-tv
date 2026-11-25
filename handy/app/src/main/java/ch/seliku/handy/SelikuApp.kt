package ch.seliku.handy

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.request.crossfade
import okio.Path.Companion.toOkioPath

class SelikuApp : Application(), SingletonImageLoader.Factory {
	override fun onCreate() {
		super.onCreate()
		Sitzung.start(this)
		Einstellungen.start(this)
	}

	override fun newImageLoader(context: PlatformContext): ImageLoader = ImageLoader.Builder(context)
		.crossfade(true)
		.memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.2).build() }
		.diskCache { DiskCache.Builder().directory(cacheDir.resolve("bilder").toOkioPath()).maxSizeBytes(200L * 1024 * 1024).build() }
		.build()
}
