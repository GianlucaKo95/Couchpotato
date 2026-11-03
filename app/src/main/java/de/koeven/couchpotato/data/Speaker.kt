package de.koeven.couchpotato.data

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * Reads short sentences aloud for kids with the Fire TV's own speech engine – a bit higher and
 * slower than normal, so it sounds friendly. Without a (German) voice it simply stays silent.
 */
object Speaker {
    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending: String? = null

    fun say(context: Context, text: String) {
        val t = tts
        if (t == null) {
            pending = text
            tts = TextToSpeech(context.applicationContext) { status ->
                val engine = tts ?: return@TextToSpeech
                ready = status == TextToSpeech.SUCCESS &&
                    engine.setLanguage(Locale.GERMANY) >= TextToSpeech.LANG_AVAILABLE
                if (ready) {
                    // a warm, friendly voice: prefer a German voice that works offline
                    runCatching {
                        engine.voices?.filter { it.locale.language == "de" && !it.isNetworkConnectionRequired }
                            ?.maxByOrNull { it.quality }?.let { engine.voice = it }
                    }
                    engine.setPitch(1.18f)
                    engine.setSpeechRate(0.9f)
                    pending?.let { speak(engine, it) }
                }
                pending = null
            }
            return
        }
        if (ready) speak(t, text) else pending = text
    }

    fun stop() {
        pending = null
        runCatching { tts?.stop() }
    }

    private fun speak(engine: TextToSpeech, text: String) {
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "kid-${text.hashCode()}")
    }
}
