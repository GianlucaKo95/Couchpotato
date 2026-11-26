package de.koeven.couchpotato.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepTimerTest {
    private var now = 1_700_000_000_000L
    private val timer = SleepTimer { now }

    @Test fun `film steps through 15, 30, 60, 90 and off`() {
        assertEquals(listOf(15, 30, 60, 90, 0, 15), List(6) { timer.cycle(episode = false) })
    }

    @Test fun `series also offers after this episode`() {
        assertEquals(listOf(15, 30, 60, 90, -1, 0), List(6) { timer.cycle(episode = true) })
    }

    @Test fun `pressing again on a running timer goes to the next longer one`() {
        timer.cycle(false)                 // 15
        timer.cycle(false)                 // 30
        now += 10 * 60_000L                // 20 minutes left
        assertEquals(60, timer.cycle(false))
    }

    @Test fun `label shows the minutes rounded up`() {
        assertNull(timer.label())
        timer.cycle(false)
        assertEquals("15 Min.", timer.label())
        now += 14 * 60_000L + 30_000L
        assertEquals("1 Min.", timer.label())
        timer.cycle(true); timer.cycle(true); timer.cycle(true); timer.cycle(true)
        assertEquals("nach dieser Folge", timer.label())
    }

    @Test fun `due once when the time is up`() {
        timer.cycle(false)
        assertFalse(timer.due())
        now += 15 * 60_000L
        assertTrue(timer.due())
        assertFalse(timer.due())
        assertNull(timer.label())
        assertEquals(15, timer.cycle(false))     // starts from the beginning again
    }

    @Test fun `messages`() {
        assertEquals("Sleep-Timer aus", SleepTimer.message(0))
        assertEquals("Sleep-Timer: 30 Minuten", SleepTimer.message(30))
        assertEquals("Sleep-Timer: nach dieser Folge", SleepTimer.message(-1))
    }
}

class ZapTest {
    @Test fun `zapping wraps around at both ends`() {
        assertEquals(1, LiveSession.zapStep(0, 1, 5))
        assertEquals(0, LiveSession.zapStep(4, 1, 5))
        assertEquals(4, LiveSession.zapStep(0, -1, 5))
        assertEquals(2, LiveSession.zapStep(0, -3, 5))
    }

    @Test fun `zapping waits for the viewer to stop before opening a stream`() {
        assertTrue(LiveSession.ZAP_DELAY_MS >= 500L)
    }

    @Test fun `time format`() {
        assertEquals("0:30", LiveSession.fmt(30_000))
        assertEquals("5:00", LiveSession.fmt(300_000))
    }
}
