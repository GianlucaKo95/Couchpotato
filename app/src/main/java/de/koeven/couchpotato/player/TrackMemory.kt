package de.koeven.couchpotato.player

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import de.koeven.couchpotato.data.Profiles

/** Audio track & subtitles: what the viewer picked is remembered per profile and used next time. */
internal class TrackMemory(context: Context, private val player: ExoPlayer) {
    private val prefs = context.getSharedPreferences("player" + Profiles.suffix(context), Context.MODE_PRIVATE)
    private var applying = false
    private var userChanged = false

    fun apply() {
        val audio = prefs.getString("audio", null)
        val text = prefs.getString("text", null)
        applying = true
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
            if (audio != null) setPreferredAudioLanguage(audio)
            when (text) {
                null -> Unit
                "off" -> setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                else -> { setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false); setPreferredTextLanguage(text) }
            }
        }.build()
        applying = false
    }

    /** From the player listener: the selection changed (by us or by the viewer). */
    fun onParametersChanged() {
        if (!applying) userChanged = true
    }

    /** From the player listener: after the viewer picked something, remember it. */
    fun onTracksChanged(tracks: Tracks) {
        if (!userChanged) return
        userChanged = false
        fun selectedLang(type: Int): String? = tracks.groups.firstOrNull { it.type == type && it.isSelected }?.let { g ->
            (0 until g.length).firstOrNull { g.isTrackSelected(it) }?.let { g.getTrackFormat(it).language }
        }
        val edit = prefs.edit()
        selectedLang(C.TRACK_TYPE_AUDIO)?.takeIf { it != "und" }?.let { edit.putString("audio", it) }
        val hasText = tracks.groups.any { it.type == C.TRACK_TYPE_TEXT }
        if (hasText) {
            val text = selectedLang(C.TRACK_TYPE_TEXT)
            when {
                text == null -> edit.putString("text", "off")
                text != "und" -> edit.putString("text", text)
            }
        }
        edit.apply()
    }
}
