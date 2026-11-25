package de.koeven.couchpotato.sonos

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.floor

class SonosXmlTest {
    @Test fun `rooms from the zone group state, without hidden pair halves and bridges`() {
        val state = """<ZoneGroupState><ZoneGroups>
            <ZoneGroup Coordinator="RINCON_A" ID="RINCON_A:1">
              <ZoneGroupMember UUID="RINCON_A" Location="http://192.168.1.20:1400/xml/device_description.xml" ZoneName="Wohnzimmer" Invisible="0"/>
              <ZoneGroupMember UUID="RINCON_B" Location="http://192.168.1.21:1400/xml/device_description.xml" ZoneName="Wohnzimmer" Invisible="1"/>
            </ZoneGroup>
            <ZoneGroup Coordinator="RINCON_C" ID="RINCON_C:2"><ZoneGroupMember UUID="RINCON_C" Location="http://192.168.1.30:1400/xml/device_description.xml" ZoneName="K&amp;üche" ><Satellite UUID="RINCON_S" Location="http://192.168.1.31:1400/x" ZoneName="K"/></ZoneGroupMember></ZoneGroup>
            <ZoneGroup Coordinator="RINCON_D" ID="x"><ZoneGroupMember UUID="RINCON_D" Location="http://192.168.1.40:1400/x" ZoneName="Boost" IsZoneBridge="1"/></ZoneGroup>
            </ZoneGroups></ZoneGroupState>"""
        assertEquals(
            listOf(SonosSpeaker("RINCON_C", "K&üche", "192.168.1.30"), SonosSpeaker("RINCON_A", "Wohnzimmer", "192.168.1.20")),
            SonosXml.zoneMembers(state),
        )
        assertEquals(mapOf("RINCON_A" to "RINCON_A", "RINCON_B" to "RINCON_A", "RINCON_C" to "RINCON_C", "RINCON_D" to "RINCON_D"), SonosXml.coordinators(state))
    }

    @Test fun `values, rel time and the speaker line format`() {
        val xml = "<s:Body><u:GetPositionInfoResponse><Track>1</Track><RelTime>0:01:05</RelTime><TrackURI>http://x/cp/ab.wav</TrackURI><Empty/></u:GetPositionInfoResponse>"
        assertEquals("0:01:05", SonosXml.value(xml, "RelTime"))
        assertEquals("", SonosXml.value(xml, "Empty"))
        assertNull(SonosXml.value(xml, "Nope"))
        assertEquals(65, SonosXml.relTimeSeconds("0:01:05"))
        assertNull(SonosXml.relTimeSeconds("NOT_IMPLEMENTED"))
        val s = SonosSpeaker("RINCON_A", "Wohn zimmer", "10.0.0.2")
        assertEquals(s, SonosSpeaker.decode(s.encode()))
        assertTrue(SonosXml.envelope(SonosXml.AV_TRANSPORT, "Play", listOf("InstanceID" to "0", "Speed" to "1")).contains("<u:Play xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\"><InstanceID>0</InstanceID><Speed>1</Speed></u:Play>"))
        assertTrue(SonosXml.didl("http://a/b?c&d", "Film <1>").contains("http://a/b?c&amp;d"))
    }
}

class WavPcmTest {
    @Test fun `endless wav header`() {
        val h = ByteBuffer.wrap(Wav.header(48_000)).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(44, h.capacity())
        assertEquals("RIFF", String(h.array(), 0, 4))
        assertEquals(48_000, h.getInt(24))
        assertEquals(192_000, h.getInt(28))
        assertEquals(16, h.getShort(34).toInt())
        assertEquals("data", String(h.array(), 36, 4))
    }

    private fun shorts(vararg v: Int): ByteBuffer = ByteBuffer.allocate(v.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply { v.forEach { putShort(it.toShort()) }; flip() }
    private fun out(b: ByteArray) = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().let { sb -> IntArray(sb.remaining()) { sb.get(it).toInt() } }

    @Test fun `stereo stays, mono doubles, 5_1 folds down, float converts`() {
        assertArrayEquals(intArrayOf(100, -200, 300, -400), out(Pcm.toStereo16(shorts(100, -200, 300, -400), 2, false)))
        assertArrayEquals(intArrayOf(7, 7, -9, -9), out(Pcm.toStereo16(shorts(7, -9), 1, false)))
        val s51 = out(Pcm.toStereo16(shorts(1000, 0, 0, 5000, 0, 0), 6, false))
        assertArrayEquals(intArrayOf(600, 0), s51)                       // LFE dropped, front left kept
        val f = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).apply { putFloat(0.5f); putFloat(-1f); flip() }
        assertArrayEquals(intArrayOf(16383, -32767), out(Pcm.toStereo16(f, 2, true)))
        assertArrayEquals(intArrayOf(1, 2, 5, 6), out(Pcm.toStereo16(shorts(1, 2, 3, 4, 5, 6, 7, 8), 2, false, step = 2)))
        assertArrayEquals(intArrayOf(0, 0), out(Pcm.toStereo16(shorts(1, 2), 2, false, mute = true)))
    }
}

class StreamTimelineTest {
    @Test fun `film time per stream frame, silence holds the picture`() {
        val t = StreamTimeline().apply { rate = 1000 }
        assertNull(t.mediaAt(0))
        t.add(0, 5_000_000, 500)          // 0.5 s from film second 5
        t.add(500, 5_500_000, 500)        // contiguous: merged
        t.add(2000, 9_000_000, 1000)      // after 1 s of silence, from film second 9
        assertEquals(5_000_000L, t.mediaAt(-10))
        assertEquals(5_250_000L, t.mediaAt(250))
        assertEquals(5_999_000L, t.mediaAt(999))
        assertEquals(6_000_000L, t.mediaAt(1500))       // in the silence: end of the last sound
        assertEquals(9_500_000L, t.mediaAt(2500))
    }
}

class SonosClockTest {
    /** Simulated speakers: start at [start], whole seconds of RelTime, polled every [every] s. */
    private fun run(clock: SonosClock, start: Double, from: Double, to: Double, every: Double = 0.1, rtt: Double = 0.012, relWorks: Boolean = true) {
        var t = from
        while (t < to) {
            val sampled = t + rtt / 2
            val playing = sampled >= start
            val rel = if (!playing) 0 else if (relWorks) floor(sampled - start).toInt() else 0
            clock.observe(t, t + rtt, playing, rel)
            t += every + 0.013 * ((t * 7).toInt() % 3)      // a bit of jitter
        }
    }

    @Test fun `start of the stream is found to a few hundredths of a second`() {
        val c = SonosClock()
        assertNull(c.position(0.0))
        run(c, start = 12.34, from = 10.0, to = 18.0)
        val p = c.position(18.0)!!
        assertTrue("position $p", abs(p - (18.0 - 12.34)) < 0.03)
        assertTrue(c.precision() < 0.06)
    }

    @Test fun `without RelTime the start edge still gives the position`() {
        val c = SonosClock()
        run(c, start = 3.21, from = 1.0, to = 9.0, relWorks = false)
        assertTrue(abs(c.position(9.0)!! - (9.0 - 3.21)) < 0.15)
    }

    @Test fun `pause freezes, resume continues from there`() {
        val c = SonosClock()
        run(c, start = 2.0, from = 0.0, to = 6.0)
        c.pausedAt(6.0)
        val f = c.position(6.0)!!
        assertTrue(abs(f - 4.0) < 0.05)
        assertEquals(f, c.position(30.0)!!, 1e-9)
        c.resumedAround(30.0, 30.02)
        assertTrue(abs(c.position(31.0)!! - (f + 0.85)) < 0.3)
    }
}
