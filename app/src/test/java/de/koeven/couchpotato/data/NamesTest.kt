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

class LookupNamesTest {
    @Test fun `plain title stays`() {
        assertEquals(listOf("Werwölfe"), Names.lookupNames("Werwölfe"))
    }

    @Test fun `episode and app additions are cut off`() {
        assertEquals("Werwölfe", Names.lookupNames("Werwölfe (1/6)").first())
        assertEquals("Werwölfe", Names.lookupNames("Werwölfe | ARD Mediathek").first())
        assertEquals("Werwölfe", Names.lookupNames("Werwölfe – Folge 3: Vollmond").first())
        assertEquals("Werwölfe", Names.lookupNames("Werwölfe S01E03").first())
        assertEquals("Babylon Berlin", Names.lookupNames("Babylon Berlin - Staffel 4").first())
    }

    @Test fun `series name before a dash is a second try`() {
        assertEquals(listOf("Tatort: Der Fall", "Tatort"), Names.lookupNames("Tatort: Der Fall"))
    }

    @Test fun `subtitle is tried after the title`() {
        assertEquals(listOf("Vollmond", "Werwölfe"), Names.lookupNames("Vollmond", "Werwölfe"))
    }

    @Test fun `placeholders and empty fields give nothing`() {
        assertEquals(emptyList<String>(), Names.lookupNames(null, "", "  "))
    }
}
