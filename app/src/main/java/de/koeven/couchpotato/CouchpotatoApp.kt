package de.koeven.couchpotato

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache

/** One image loader for the whole launcher, tuned for a Fire TV stick with little RAM. */
class CouchpotatoApp : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        de.koeven.couchpotato.player.TvLink.install(this)   // link to the iPhone app (same household, same Wi-Fi)
        de.koeven.couchpotato.player.HausgeistLink.install(this)   // voice control via the family's Hausgeist (if paired)
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.20).build() }
        // posters survive a reboot of the stick: no reloading every row from the provider
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("posters")).maxSizeBytes(200L * 1024 * 1024).build() }
        // IPTV panels often send "no-cache" for posters that never change
        .respectCacheHeaders(false)
        .allowRgb565(true)      // half the memory per poster, no visible difference on posters
        .crossfade(false)       // no fade animations while scrolling fast with the remote
        .build()
}
