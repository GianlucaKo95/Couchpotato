package de.koeven.couchpotato.data

import android.content.Context
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException

/**
 * Picture loading for the whole app.
 *
 * - Every picture is kept on the stick for good (covers practically never change), so each
 *   one costs at most ONE download ever.
 * - Pictures from the provider's own server go through the same brake as the API
 *   (one at a time, pause in between, own hourly budget) – or are skipped entirely if the
 *   user switched that off. Pictures from elsewhere (e.g. image.tmdb.org) load normally.
 */
object ImageGate {

    @Volatile private var started = false

    /**
     * Called first thing in MainActivity: restores the request brake from disk, tells it which
     * host belongs to the provider, and installs the throttled, permanently caching image loader.
     */
    fun start(context: Context) {
        if (started) return
        synchronized(this) {
            if (started) return
            val app = context.applicationContext
            XtreamClient.init(app)
            val settings = SettingsStore(app)
            XtreamClient.providerHost = settings.config().host
            XtreamClient.loadProviderImages = settings.loadProviderImages
            coil.Coil.setImageLoader(buildImageLoader(app))
            started = true
        }
    }

    fun buildImageLoader(context: Context): ImageLoader {
        val app = context.applicationContext
        val client = OkHttpClient.Builder()
            .addInterceptor(ProviderImageInterceptor)
            .build()
        return ImageLoader.Builder(app)
            .okHttpClient(client)
            .respectCacheHeaders(false)      // keep pictures even if the server says otherwise
            .memoryCache { MemoryCache.Builder(app).maxSizePercent(0.15).build() }
            .diskCache {
                DiskCache.Builder()
                    .directory(app.filesDir.resolve("image_cache"))   // filesDir: not wiped by the system
                    .maxSizeBytes(300L * 1024 * 1024)
                    .build()
            }
            .crossfade(false)
            .build()
    }

    private object ProviderImageInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val host = XtreamClient.providerHost
            if (host == null || !request.url.host.equals(host, ignoreCase = true)) {
                return chain.proceed(request)
            }
            if (!XtreamClient.loadProviderImages) return skipped(chain, "disabled")
            return try {
                XtreamClient.gateImage { chain.proceed(request) }
            } catch (e: IOException) {
                // budget used up / provider locked: no picture now, the title is shown instead
                skipped(chain, e.message ?: "throttled")
            }
        }

        private fun skipped(chain: Interceptor.Chain, why: String): Response =
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(503)
                .message("Couchpotato: $why")
                .body(ByteArray(0).toResponseBody(null))
                .build()
    }
}
