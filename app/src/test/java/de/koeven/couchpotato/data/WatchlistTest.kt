package de.koeven.couchpotato.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchlistTest {
    private val now = 1_700_000_000_000L

    private fun movie(id: Long, cat: String, genre: String, rating: Float = 7f) =
        SearchEntry(SearchEntry.KIND_MOVIE, id, "Film $id", null, "mp4", "film $id", cat, added = 1_600_000_000L, rating = rating, genre = genre)

    private val catalogue = (1L..8L).map { movie(it, "1", "Komödie") } + (11L..18L).map { movie(it, "2", "Horror") }

    @Test fun `list shows newest addition first and drops titles the provider removed`() {
        val list = mapOf("m:3" to now - 1000, "m:12" to now, "m:999" to now + 5)
        assertEquals(listOf(12L, 3L), Watchlist.entries(list, catalogue, emptySet()).map { it.id })
    }

    @Test fun `hidden categories stay hidden on the list too`() {
        val list = mapOf("m:3" to now, "m:12" to now)
        assertEquals(listOf(3L), Watchlist.entries(list, catalogue, setOf("m:2")).map { it.id })
    }

    @Test fun `keys match the catalogue`() {
        assertEquals("m:42", Watchlist.key(SearchEntry.KIND_MOVIE, 42))
        assertEquals("s:7", Watchlist.key(SearchEntry.KIND_SERIES, 7))
    }

    @Test fun `Für dich learns from the list`() {
        val horror = Recommender.recommend(catalogue, emptySet(), emptyMap(), watchlist = mapOf("m:11" to now), now = now)
        assertTrue(horror.personal)
        assertEquals("2", horror.items.first().categoryId)

        val comedy = Recommender.recommend(catalogue, emptySet(), emptyMap(), watchlist = mapOf("m:1" to now), now = now)
        assertEquals("1", comedy.items.first().categoryId)
    }

    @Test fun `titles on the list are not recommended again`() {
        val r = Recommender.recommend(catalogue, emptySet(), emptyMap(), watchlist = mapOf("m:11" to now), now = now)
        assertFalse(r.items.any { it.id == 11L })
    }

    @Test fun `watching counts more than listing`() {
        // watched a comedy, listed a horror film: comedy wins
        val r = Recommender.recommend(
            catalogue, emptySet(),
            history = mapOf("m:1" to now),
            watchlist = mapOf("m:11" to now),
            now = now,
        )
        assertEquals("1", r.items.first().categoryId)
    }

    @Test fun `without anything it stays by rating`() {
        val r = Recommender.recommend(catalogue, emptySet(), emptyMap(), now = now)
        assertFalse(r.personal)
    }
}
