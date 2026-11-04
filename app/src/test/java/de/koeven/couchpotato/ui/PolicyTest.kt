package de.koeven.couchpotato.ui

import de.koeven.couchpotato.data.LiveCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogSyncPolicyTest {
    private val hour = 60 * 60_000L

    @Test fun `failed syncs back off 1, 2, 4, 8 hours then every 12`() {
        assertEquals(1 * hour, CatalogController.backoffMs(1))
        assertEquals(2 * hour, CatalogController.backoffMs(2))
        assertEquals(4 * hour, CatalogController.backoffMs(3))
        assertEquals(8 * hour, CatalogController.backoffMs(4))
        assertEquals(12 * hour, CatalogController.backoffMs(5))
        assertEquals(12 * hour, CatalogController.backoffMs(50))
    }

    @Test fun `catalogue syncs once or twice a day, never more often`() {
        assertEquals(24 * hour, CatalogController.syncMaxAgeMs(24))
        assertEquals(12 * hour, CatalogController.syncMaxAgeMs(12))
        assertEquals(12 * hour, CatalogController.syncMaxAgeMs(1))     // never below 12 h
        assertTrue(CatalogController.MIN_MANUAL_SYNC_GAP_MS >= 10 * 60_000L)
    }
}

class CategoryOrderTest {
    private fun cats(vararg ids: String) = ids.map { LiveCategory(it, "Kategorie $it") }
    private fun ids(list: List<LiveCategory>) = list.map { it.id }

    @Test fun `no own order keeps the provider order`() {
        assertEquals(listOf("a", "b", "c"), ids(sortByOwnOrder(cats("a", "b", "c"), emptyList())))
    }

    @Test fun `own order comes first`() {
        assertEquals(listOf("c", "a", "b"), ids(sortByOwnOrder(cats("a", "b", "c"), listOf("c", "a", "b"))))
    }

    @Test fun `new categories stay next to their neighbour at the provider`() {
        // the provider added "d" after "c" and "b" after "a"
        assertEquals(listOf("c", "d", "a", "b"), ids(sortByOwnOrder(cats("a", "b", "c", "d"), listOf("c", "a"))))
    }

    @Test fun `new category at the very top stays on top`() {
        assertEquals(listOf("new", "b", "a"), ids(sortByOwnOrder(cats("new", "a", "b"), listOf("b", "a"))))
    }

    @Test fun `removed categories in the saved order are ignored`() {
        assertEquals(listOf("b", "a"), ids(sortByOwnOrder(cats("a", "b"), listOf("gone", "b", "a"))))
    }
}

class FskGateTest {
    private fun decide(fsk: String?, max: Int = 6, kid: Boolean = true, unlocked: Boolean = false, loaded: Boolean = true) =
        ProfilesController.decide(kid, unlocked, loaded, fsk, max)

    @Test fun `adults may watch everything`() {
        assertEquals(ProfilesController.Gate.Allowed, decide("18", kid = false))
        assertEquals(ProfilesController.Gate.Allowed, decide(null, kid = false, loaded = false))
    }

    @Test fun `kids up to their limit`() {
        assertEquals(ProfilesController.Gate.Allowed, decide("0"))
        assertEquals(ProfilesController.Gate.Allowed, decide("6"))
        assertEquals(ProfilesController.Gate.Blocked("FSK 12"), decide("12"))
        assertEquals(ProfilesController.Gate.Allowed, decide("12", max = 12))
    }

    @Test fun `unknown FSK is blocked for kids`() {
        assertEquals(ProfilesController.Gate.Blocked("FSK unbekannt"), decide(null))
        assertEquals(ProfilesController.Gate.Blocked("FSK unbekannt"), decide("ab 6"))
    }

    @Test fun `still loading means checking, not allowed`() {
        assertEquals(ProfilesController.Gate.Checking, decide(null, loaded = false))
    }

    @Test fun `a PIN release wins`() {
        assertEquals(ProfilesController.Gate.Allowed, decide("16", unlocked = true))
    }
}
