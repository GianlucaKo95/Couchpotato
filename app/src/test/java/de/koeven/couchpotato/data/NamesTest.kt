package de.koeven.couchpotato.data

import org.junit.Assert.assertEquals
import org.junit.Test

class NamesTest {

    @Test fun `removes language and quality tags in front`() {
        assertEquals("Der Pate", Names.display("DE: Der Pate"))
        assertEquals("Der Pate", Names.display("|DE| Der Pate"))
        assertEquals("Der Pate", Names.display("[DE] Der Pate"))
        assertEquals("Der Pate", Names.display("DE - 4K - Der Pate"))
    }

    @Test fun `keeps real titles with a colon`() {
        assertEquals("CSI: Miami", Names.display("CSI: Miami"))
        assertEquals("Der Pate", Names.display("Der Pate"))
    }

    @Test fun `is idempotent`() {
        for (raw in listOf("DE: Der Pate", "|DE| Der Pate", "DE - 4K - Der Pate", "CSI: Miami", "Ted Lasso")) {
            val once = Names.display(raw)
            assertEquals(raw, once, Names.display(once))
        }
    }

    @Test fun `never strips everything`() {
        assertEquals("DE", Names.display("DE"))
    }

    @Test fun `episode name without series and SxxExx`() {
        assertEquals("Der Bote", Names.episode("DE - Breaking Bad - S01E03 - Der Bote", "Breaking Bad", 3))
    }

    @Test fun `episode without name falls back to number`() {
        assertEquals("Folge 3", Names.episode(null, "Breaking Bad", 3))
        assertEquals("Folge 4", Names.episode("Breaking Bad S01E04", "Breaking Bad", 4))
    }
}
