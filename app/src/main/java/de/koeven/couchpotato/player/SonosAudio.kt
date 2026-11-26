package de.koeven.couchpotato.player

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import de.koeven.couchpotato.sonos.Pcm
import de.koeven.couchpotato.sonos.SonosLink
import java.nio.ByteBuffer

/*
 * Player side of the Sonos sound. The decoded sound is copied off (tee) and streamed to the
 * speakers; the stick's own output keeps running silently. The audio sink's clock reports what
 * the speakers play, so ExoPlayer itself holds the picture back by exactly their delay – and when
 * the sound comes back to the stick, the clock is the stick's again and the delay is gone at once.
 */

/** Copies the decoded sound (after decoding, before the stick's output) to [link]. */
@UnstableApi
internal class SonosTap(private val link: SonosLink) : TeeAudioProcessor.AudioBufferSink {
    @Volatile var muted = false
    private var rate = 0
    private var channels = 2
    private var float = false
    private var usable = false
    private var step = 1
    private var anchorUs = 0L
    private var frames = 0L
    private var needAnchor = true

    /** Film time of the buffer the sink receives next (first after a flush/discontinuity). */
    fun anchor(presentationTimeUs: Long) {
        if (!needAnchor) return
        anchorUs = presentationTimeUs
        frames = 0
        needAnchor = false
    }

    fun reanchor() { needAnchor = true }

    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        rate = sampleRateHz
        channels = channelCount.coerceAtLeast(1)
        float = encoding == C.ENCODING_PCM_FLOAT
        usable = encoding == C.ENCODING_PCM_16BIT || float
        step = if (rate > 48_000) 2 else 1
        if (usable) link.onFormat(rate / step)
    }

    override fun handleBuffer(buffer: ByteBuffer) {
        if (rate <= 0) return
        val n = buffer.remaining() / ((if (float) 4 else 2) * channels)
        if (usable && link.active) link.onPcm(Pcm.toStereo16(buffer, channels, float, step, muted), anchorUs + frames * 1_000_000L / rate)
        frames += n
    }
}

@UnstableApi
internal class SonosAudioSink(inner: AudioSink, private val link: SonosLink, private val tap: SonosTap) : ForwardingAudioSink(inner) {
    /** Dolby/DTS goes to HDMI undecoded right now – nothing to copy for Sonos. */
    @Volatile var passthrough = false; private set
    private var volume = 1f
    private var appliedVolume = -1f

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        passthrough = inputFormat.sampleMimeType != MimeTypes.AUDIO_RAW
        super.configure(inputFormat, specifiedBufferSize, outputChannels)
    }

    // while Sonos plays, the sound is always decoded (Sonos needs PCM)
    override fun getFormatSupport(format: Format): Int =
        if (link.active && format.sampleMimeType != MimeTypes.AUDIO_RAW) AudioSink.SINK_FORMAT_UNSUPPORTED else super.getFormatSupport(format)

    override fun supportsFormat(format: Format): Boolean = getFormatSupport(format) != AudioSink.SINK_FORMAT_UNSUPPORTED

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        syncVolume()
        tap.anchor(presentationTimeUs)
        return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
    }

    override fun handleDiscontinuity() {
        tap.reanchor()
        super.handleDiscontinuity()
    }

    override fun flush() {
        super.flush()
        tap.reanchor()
        link.onFlush()
    }

    @Volatile private var running = false

    override fun pause() {
        running = false
        super.pause()
        link.onPause()
    }

    override fun play() {
        running = true
        super.play()
        link.onPlay()
    }

    /** How far the picture is held back right now (ms), for the menu; null while unknown. */
    @Volatile var delayMs: Long? = null; private set
    private var stuckSince = 0L
    private var lastHeard = Long.MIN_VALUE
    private var lastMove = 0L

    override fun setVolume(volume: Float) {
        this.volume = volume
        tap.muted = volume == 0f          // e.g. Twitch splitscreen has the sound
        syncVolume()
    }

    /** The stick stays silent while Sonos plays (applied on the playback thread). */
    private fun syncVolume() {
        val v = if (link.active && !passthrough) 0f else volume
        if (v != appliedVolume) { appliedVolume = v; super.setVolume(v) }
    }

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        syncVolume()
        val local = super.getCurrentPositionUs(sourceEnded)
        if (!link.active || passthrough) { delayMs = null; stuckSince = 0; return local }
        // the film time the speakers play; unknown yet → the picture waits
        val heard = link.heardPositionUs()
        delayMs = if (heard != null && local != AudioSink.CURRENT_POSITION_NOT_SET) (local - heard) / 1000 else null
        // never leave the picture standing: if it cannot follow the speakers, back to the stick's sound
        val now = android.os.SystemClock.elapsedRealtime()
        if (heard != null && heard != lastHeard) { lastHeard = heard; lastMove = now }
        if (!running) lastMove = now
        val stuck = running && (heard == null || now - lastMove > 8_000 ||
            (local != AudioSink.CURRENT_POSITION_NOT_SET && local - heard > 20_000_000))
        if (!stuck) stuckSince = 0
        else if (stuckSince == 0L) stuckSince = now
        else if (now - stuckSince > 10_000) { stuckSince = 0; link.giveUp("Sonos: das Bild kommt nicht hinterher (${link.status(delayMs)})") }
        return heard ?: AudioSink.CURRENT_POSITION_NOT_SET
    }
}

@UnstableApi
internal class SonosRenderersFactory(context: Context, private val link: SonosLink) : DefaultRenderersFactory(context) {
    var sink: SonosAudioSink? = null; private set

    override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink? {
        val tap = SonosTap(link)
        val inner = DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(false)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .setAudioProcessors(arrayOf<AudioProcessor>(TeeAudioProcessor(tap)))
            .build()
        return SonosAudioSink(inner, link, tap).also { sink = it }
    }
}
