package de.koeven.couchpotato.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * The brake towards the IPTV provider (it locks the account with HTTP 404 after too many
 * requests). Runs with a fake clock: waiting just moves the clock forward.
 */
class ProviderThrottleTest {
    private var now = 1_700_000_000_000L
    private val cfg = XtreamConfig("http://provider.example:8080", "user", "pass")

    @Before fun setUp() {
        XtreamClient.resetForTests()
        XtreamClient.clock = { now }
        XtreamClient.sleep = { now += it }
    }

    @After fun tearDown() {
        XtreamClient.resetForTests()
        XtreamClient.clock = { System.currentTimeMillis() }
        XtreamClient.sleep = { Thread.sleep(it) }
    }

    @Test fun `limits are as strict as agreed`() {
        assertEquals(2_500L, XtreamClient.MIN_GAP_MS)
        assertEquals(40, XtreamClient.MAX_PER_HOUR)
        assertEquals(20, XtreamClient.BACKGROUND_PER_HOUR)
        assertEquals(15 * 60_000L, XtreamClient.COOLDOWN_MS)
    }

    @Test fun `requests are spaced at least the minimum gap apart`() {
        val client = XtreamClient(cfg)
        val starts = mutableListOf<Long>()
        repeat(5) { client.gated { starts += now } }
        starts.zipWithNext().forEach { (a, b) -> assertTrue("gap ${b - a} ms", b - a >= XtreamClient.MIN_GAP_MS) }
    }

    @Test fun `hourly budget stops further requests`() {
        val client = XtreamClient(cfg)
        repeat(XtreamClient.MAX_PER_HOUR) { client.gated { } }
        var ran = false
        try {
            client.gated { ran = true }
            fail("41st request in the hour must be refused")
        } catch (_: RequestBudgetException) {
        }
        assertFalse(ran)
        assertEquals(XtreamClient.MAX_PER_HOUR, XtreamClient.requestsLastHour())
    }

    @Test fun `background work stops earlier and leaves room for browsing`() {
        val background = XtreamClient(cfg, background = true)
        repeat(XtreamClient.BACKGROUND_PER_HOUR) { background.gated { } }
        try {
            background.gated { }
            fail("background budget must be used up")
        } catch (_: RequestBudgetException) {
        }
        XtreamClient(cfg).gated { }      // the user can still do something
    }

    @Test fun `budget is free again after an hour`() {
        val client = XtreamClient(cfg)
        repeat(XtreamClient.MAX_PER_HOUR) { client.gated { } }
        now += 60 * 60_000L + 1
        client.gated { }
    }

    @Test fun `while the provider locks us nothing is sent`() {
        val client = XtreamClient(cfg)
        XtreamClient.blockNow()
        var ran = false
        try {
            client.gated { ran = true }
            fail("must not talk to a provider that locked us")
        } catch (_: ProviderBlockedException) {
        }
        assertFalse(ran)
        now += XtreamClient.COOLDOWN_MS + 1
        client.gated { ran = true }
        assertTrue(ran)
    }

    @Test fun `provider pictures go through the same lock`() {
        XtreamClient.blockNow()
        try {
            XtreamClient.gateImage { }
            fail("pictures must wait for the lock too")
        } catch (_: ProviderBlockedException) {
        }
    }

    @Test fun `picture budget is limited per hour`() {
        repeat(XtreamClient.IMAGES_PER_HOUR) { XtreamClient.gateImage { } }
        try {
            XtreamClient.gateImage { }
            fail("picture budget must be used up")
        } catch (_: RequestBudgetException) {
        }
    }
}
