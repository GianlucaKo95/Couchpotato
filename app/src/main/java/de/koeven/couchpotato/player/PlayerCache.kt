package de.koeven.couchpotato.player

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

/**
 * Films & series are kept on the stick while they play (up to [MAX_BYTES], oldest first out).
 * Rewinding or watching again then comes from the stick – no new request to the provider.
 * It is filled by the player's one and only connection; nothing is downloaded in parallel.
 */
@UnstableApi
object PlayerCache {
    private const val MAX_BYTES = 800L * 1024 * 1024
    @Volatile private var cache: SimpleCache? = null

    fun get(context: Context): SimpleCache = cache ?: synchronized(this) {
        cache ?: SimpleCache(
            File(context.applicationContext.cacheDir, "media"),
            LeastRecentlyUsedCacheEvictor(MAX_BYTES),
            StandaloneDatabaseProvider(context.applicationContext),
        ).also { cache = it }
    }
}
