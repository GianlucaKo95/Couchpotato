package de.koeven.couchpotato.sonos

import java.nio.ByteBuffer
import java.nio.ByteOrder

/*
 * Sound of the player on Sonos speakers – the parts without Android: SOAP/XML for the speakers'
 * local control (UPnP on port 1400), the endless WAV stream, the downmix to stereo, the timeline
 * "which stream second is which film second" and the clock that works out what the speakers are
 * playing right now. Kept free of Android so it can be tested on the JVM.
 */

/** A Sonos room (a single speaker or the visible master of a stereo pair). */
data class SonosSpeaker(val uuid: String, val room: String, val host: String) {
    fun encode() = "$uuid\t$room\t$host"

    companion object {
        fun decode(s: String): SonosSpeaker? =
            s.split('\t').takeIf { it.size == 3 && it[0].isNotBlank() && it[2].isNotBlank() }?.let { SonosSpeaker(it[0], it[1], it[2]) }
    }
}

object SonosXml {
    const val AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1"
    const val GROUP_RENDERING = "urn:schemas-upnp-org:service:GroupRenderingControl:1"
    const val ZONE_TOPOLOGY = "urn:schemas-upnp-org:service:ZoneGroupTopology:1"

    fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")

    fun unescape(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&apos;", "'").replace("&amp;", "&")

    fun envelope(service: String, action: String, args: List<Pair<String, String>>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
        append("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">")
        append("<s:Body><u:").append(action).append(" xmlns:u=\"").append(service).append("\">")
        for ((k, v) in args) append('<').append(k).append('>').append(escape(v)).append("</").append(k).append('>')
        append("</u:").append(action).append("></s:Body></s:Envelope>")
    }

    /** Text of the first <tag>…</tag> (still escaped), "" for <tag/>, null if missing. */
    fun value(xml: String, tag: String): String? {
        Regex("<(?:\\w+:)?$tag(?:\\s[^>]*)?>(.*?)</(?:\\w+:)?$tag>", RegexOption.DOT_MATCHES_ALL).find(xml)?.let { return it.groupValues[1] }
        return if (Regex("<(?:\\w+:)?$tag(?:\\s[^>]*)?/>").containsMatchIn(xml)) "" else null
    }

    /** Metadata Sonos shows in its app while the player's sound is on. */
    fun didl(uri: String, title: String) =
        "<DIDL-Lite xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\" " +
            "xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\"><item id=\"couchpotato\" parentID=\"0\" restricted=\"1\">" +
            "<dc:title>${escape(title)}</dc:title><upnp:class>object.item.audioItem.musicTrack</upnp:class>" +
            "<res protocolInfo=\"http-get:*:audio/wav:*\">${escape(uri)}</res></item></DIDL-Lite>"

    /**
     * Rooms from GetZoneGroupState (the unescaped ZoneGroupState XML): every visible member, without
     * the hidden half of stereo pairs, surround satellites and Boost/Bridge devices.
     */
    fun zoneMembers(state: String): List<SonosSpeaker> {
        val out = LinkedHashMap<String, SonosSpeaker>()
        for (m in Regex("<ZoneGroupMember\\b([^>]*)>").findAll(state)) {
            val a = Regex("(\\w+)=\"([^\"]*)\"").findAll(m.groupValues[1]).associate { it.groupValues[1] to unescape(it.groupValues[2]) }
            if (a["Invisible"] == "1" || a["IsZoneBridge"] == "1") continue
            val uuid = a["UUID"] ?: continue
            val host = a["Location"]?.let { Regex("^https?://([^:/]+)").find(it)?.groupValues?.get(1) } ?: continue
            out[uuid] = SonosSpeaker(uuid, a["ZoneName"]?.takeIf { it.isNotBlank() } ?: uuid, host)
        }
        return out.values.sortedBy { it.room.lowercase() }
    }

    /** "0:01:05" → 65; "NOT_IMPLEMENTED" or nonsense → null. */
    fun relTimeSeconds(s: String?): Int? {
        val p = s?.trim()?.split(':') ?: return null
        if (p.size != 3) return null
        val h = p[0].toIntOrNull() ?: return null
        val m = p[1].toIntOrNull() ?: return null
        val sec = p[2].substringBefore('.').toIntOrNull() ?: return null
        return h * 3600 + m * 60 + sec
    }
}

/** The endless WAV stream: 16-bit stereo with a "4 GB" header (what Sonos plays as a long file). */
object Wav {
    const val HEADER_BYTES = 44
    const val FRAME_BYTES = 4                    // 16-bit stereo
    const val CONTENT_LENGTH = 4_294_967_295L    // u32 max

    fun header(rate: Int): ByteArray = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray()); putInt((CONTENT_LENGTH - 8).toInt())
        put("WAVE".toByteArray())
        put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(2)
        putInt(rate); putInt(rate * FRAME_BYTES); putShort(FRAME_BYTES.toShort()); putShort(16)
        put("data".toByteArray()); putInt((CONTENT_LENGTH - HEADER_BYTES).toInt())
    }.array()
}

object Pcm {
    /**
     * Interleaved PCM (16-bit or float, any channel count) → little-endian 16-bit stereo.
     * 5.1/7.1 is folded down (front + centre + surround), [step] 2 halves 88.2/96 kHz.
     */
    fun toStereo16(src: ByteBuffer, channels: Int, float: Boolean, step: Int = 1, mute: Boolean = false): ByteArray {
        val b = src.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val bytesPerSample = if (float) 4 else 2
        val frames = b.remaining() / (bytesPerSample * channels.coerceAtLeast(1))
        val outFrames = (frames + step - 1) / step
        val out = ByteBuffer.allocate(outFrames * Wav.FRAME_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        if (mute || channels < 1) return out.array()
        val start = b.position()
        fun sample(frame: Int, ch: Int): Float {
            val i = start + (frame * channels + ch) * bytesPerSample
            return if (float) b.getFloat(i) * 32767f else b.getShort(i).toFloat()
        }
        var f = 0
        while (f < frames) {
            var l: Float
            var r: Float
            when {
                channels == 1 -> { l = sample(f, 0); r = l }
                channels == 2 -> { l = sample(f, 0); r = sample(f, 1) }
                channels >= 6 -> {   // FL FR FC LFE BL BR (SL SR)
                    val c = sample(f, 2) * 0.707f
                    var sl = sample(f, 4) * 0.707f
                    var sr = sample(f, 5) * 0.707f
                    if (channels >= 8) { sl += sample(f, 6) * 0.5f; sr += sample(f, 7) * 0.5f }
                    l = (sample(f, 0) + c + sl) * 0.6f
                    r = (sample(f, 1) + c + sr) * 0.6f
                }
                else -> {            // 3–5 channels: L R C …
                    val c = sample(f, 2) * 0.707f
                    l = (sample(f, 0) + c) * 0.75f
                    r = (sample(f, 1) + c) * 0.75f
                }
            }
            out.putShort(l.coerceIn(-32768f, 32767f).toInt().toShort())
            out.putShort(r.coerceIn(-32768f, 32767f).toInt().toShort())
            f += step
        }
        return out.array()
    }
}

/**
 * Which part of the stream is which moment of the film. Silence the stream had to fill in
 * (pause, buffering) has no film time: there the picture waits at the end of the last sound.
 */
class StreamTimeline {
    private class Seg(val frame: Long, val mediaUs: Long, var frames: Long)

    private val segs = ArrayDeque<Seg>()
    @Volatile var rate: Int = 48_000

    @Synchronized fun clear() = segs.clear()

    @Synchronized fun isEmpty() = segs.isEmpty()

    @Synchronized fun add(streamFrame: Long, mediaUs: Long, frames: Int) {
        if (frames <= 0) return
        val last = segs.lastOrNull()
        if (last != null && last.frame + last.frames == streamFrame &&
            kotlin.math.abs(last.mediaUs + framesToUs(last.frames) - mediaUs) < 2_000
        ) {
            last.frames += frames
        } else {
            segs.addLast(Seg(streamFrame, mediaUs, frames.toLong()))
        }
        // keep the last ~3 minutes
        val keepFrom = streamFrame - 180L * rate
        while (segs.size > 1 && segs.first().frame + segs.first().frames < keepFrom) segs.removeFirst()
    }

    /** Film time heard at stream frame [f]; null while nothing was sent yet. */
    @Synchronized fun mediaAt(f: Long): Long? {
        val first = segs.firstOrNull() ?: return null
        if (f <= first.frame) return first.mediaUs
        var hit = first
        for (s in segs) { if (s.frame <= f) hit = s else break }
        val into = (f - hit.frame).coerceAtMost(hit.frames)
        return hit.mediaUs + framesToUs(into)
    }

    private fun framesToUs(frames: Long) = frames * 1_000_000L / rate
}

/**
 * Works out which second of the stream the speakers play right now, from what they report
 * (play state + RelTime in whole seconds, asked a few times per second over the LAN).
 * Every answer limits the moment the stream "started" (stream second 0) to an interval;
 * the intersection of all of them gets narrow after a few seconds (~ the network round trip).
 * Times are in seconds of any monotonic clock.
 */
class SonosClock {
    private var lo = Double.NaN          // stream second 0 was after lo …
    private var hi = Double.NaN          // … and at or before hi
    var playing = false; private set
    private var frozen = 0.0             // stream position while not playing
    private var started = false          // played at least once since reset
    private var relWorks = false         // RelTime counts up (seen ≥ 1)
    private var notPlayingSince = Double.NaN
    private var resumeSent = Double.NEGATIVE_INFINITY
    private var held = false             // we paused them: late "playing" answers don't count

    @Synchronized fun reset() {
        lo = Double.NaN; hi = Double.NaN; playing = false; frozen = 0.0; started = false
        relWorks = false; notPlayingSince = Double.NaN; resumeSent = Double.NEGATIVE_INFINITY; held = false
    }

    /** Width of the interval: how exact [position] is (seconds); infinity while unknown. */
    @Synchronized fun precision(): Double = if (lo.isNaN()) Double.POSITIVE_INFINITY else hi - lo

    /** Stream second being played at [now]; null until the speakers played at all. */
    @Synchronized fun position(now: Double): Double? = when {
        playing && !lo.isNaN() -> (now - (lo + hi) / 2).coerceAtLeast(0.0)
        started -> frozen
        else -> null
    }

    /** One answer from the speakers, asked between [sent] and [received]. */
    @Synchronized fun observe(sent: Double, received: Double, playingNow: Boolean, rel: Int?) {
        if (held) { if (!playingNow) notPlayingSince = sent; return }
        if (!playingNow) {
            // (right after our "play" the speakers may still say paused: then they did not move yet)
            if (playing && sent - resumeSent > 1.0) {
                val p = position(sent) ?: frozen
                frozen = if (rel != null && relWorks) p.coerceIn(rel.toDouble(), rel + 0.999) else p
            }
            playing = false
            lo = Double.NaN; hi = Double.NaN
            notPlayingSince = sent
            return
        }
        if (rel != null && rel >= 1) relWorks = true
        if (!playing) {
            // just started (or resumed): it happened after the last "not playing" answer was asked
            val from = if (notPlayingSince.isNaN()) received - 1.0 else notPlayingSince
            intersect(from - frozen, received - frozen)
            playing = true
            started = true
        }
        if (relWorks && rel != null) intersect(sent - rel - 1.0, received - rel)
    }

    private var pausedBase = Double.NaN

    /** We are about to tell the speakers to pause (the picture stops now). */
    @Synchronized fun pausedAt(t: Double) {
        position(t)?.let { frozen = it }
        pausedBase = if (playing && !lo.isNaN()) (lo + hi) / 2 else Double.NaN
        held = true
        playing = false
        lo = Double.NaN; hi = Double.NaN
        notPlayingSince = t
    }

    /** The pause command went out at [sent] and was answered at [ack]: they stopped in between. */
    @Synchronized fun pauseConfirmed(sent: Double, ack: Double) {
        if (!held || pausedBase.isNaN()) return
        frozen = ((sent + ack) / 2 - pausedBase).coerceAtLeast(0.0)
    }

    /** We told them to play between [sent] and [ack] (from the frozen position). */
    @Synchronized fun resumedAround(sent: Double, ack: Double) {
        held = false
        if (!started) return
        resumeSent = sent
        lo = sent - frozen - 0.05
        hi = ack - frozen + 0.35
        playing = true
    }

    private fun intersect(a: Double, b: Double) {
        if (lo.isNaN()) { lo = a; hi = b; return }
        val nl = maxOf(lo, a)
        val nh = minOf(hi, b)
        if (nl < nh) { lo = nl; hi = nh } else { lo = a; hi = b }   // contradiction: start over from this answer
    }
}
