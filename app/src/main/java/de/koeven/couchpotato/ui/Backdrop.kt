package de.koeven.couchpotato.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.util.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class BackdropColors(val start: Color, val middle: Color, val end: Color) {
    companion object {
        /** The warm glow from the mockup, used when nothing with a picture is focused. */
        val Default = BackdropColors(Color(0xFF1C2A2E), Color(0xFF25302F), Color(0xFF3A3A33))
    }
}

val LocalBackdrop = staticCompositionLocalOf<BackdropState?> { null }

/**
 * Holds the colours of the background glow. Cards report what got focus; the colours are
 * taken from the picture with Android's Palette (cached, debounced, off the main thread).
 */
class BackdropState(private val context: Context, private val scope: CoroutineScope) {

    var colors by mutableStateOf(BackdropColors.Default)
        private set

    private val cache = LruCache<String, BackdropColors>(150)
    private var job: Job? = null

    /** A cover/logo from the network (or a local file). */
    fun focusImage(url: String?) {
        if (url.isNullOrBlank()) return
        update(url) {
            val request = ImageRequest.Builder(context)
                .data(url)
                .size(96)                 // small is plenty for colours
                .allowHardware(false)     // Palette needs to read the pixels
                .build()
            val result = context.imageLoader.execute(request) as? SuccessResult
            (result?.drawable as? BitmapDrawable)?.bitmap
        }
    }

    /** An app banner/icon that is already in memory. */
    fun focusBitmap(key: String, bitmap: ImageBitmap?) {
        if (bitmap == null) return
        update("app:$key") { bitmap.asAndroidBitmap() }
    }

    private fun update(key: String, loadBitmap: suspend () -> Bitmap?) {
        job?.cancel()
        cache.get(key)?.let { colors = it; return }
        job = scope.launch {
            delay(180)   // moving quickly along a row shouldn't recolour on every tile
            val bmp = runCatching { loadBitmap() }.getOrNull() ?: return@launch
            val extracted = withContext(Dispatchers.Default) { runCatching { extract(bmp) }.getOrNull() }
                ?: return@launch
            cache.put(key, extracted)
            colors = extracted
        }
    }

    private fun extract(bitmap: Bitmap): BackdropColors {
        val p = Palette.from(bitmap).maximumColorCount(16).generate()
        val fallback = p.getDominantColor(BackdropColors.Default.middle.toArgb())
        val start = p.darkMutedSwatch?.rgb ?: p.darkVibrantSwatch?.rgb ?: fallback
        val middle = p.mutedSwatch?.rgb ?: p.dominantSwatch?.rgb ?: fallback
        val end = p.vibrantSwatch?.rgb ?: p.lightVibrantSwatch?.rgb ?: p.dominantSwatch?.rgb ?: fallback
        return BackdropColors(tame(start, 0.26f), tame(middle, 0.46f), tame(end, 0.58f))
    }

    /**
     * Keeps the glow calm: a bit of petrol mixed in and the lightness capped,
     * so white text stays readable on top of it.
     */
    private fun tame(argb: Int, maxLightness: Float): Color {
        val mixed = ColorUtils.blendARGB(argb, CpColors.Background.toArgb(), 0.4f)
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(mixed, hsl)
        hsl[2] = hsl[2].coerceIn(0.14f, maxLightness)
        hsl[1] = hsl[1].coerceAtMost(0.55f)
        return Color(ColorUtils.HSLToColor(hsl))
    }
}
