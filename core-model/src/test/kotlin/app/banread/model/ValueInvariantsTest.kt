package app.banread.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ValueInvariantsTest {

    @Test
    fun `workId accepts alphanumeric underscore hyphen and rejects invalid characters`() {
        assertEquals("B0001_v2-cn", WorkId("B0001_v2-cn").raw)
        assertThrows(IllegalArgumentException::class.java) { WorkId("") }
        assertThrows(IllegalArgumentException::class.java) { WorkId("bad id") }
        assertThrows(IllegalArgumentException::class.java) { WorkId("../escape") }
    }

    @Test
    fun `chapterOrdinal enforces one-based invariant and formats twoDigit`() {
        assertThrows(IllegalArgumentException::class.java) { ChapterOrdinal(0) }
        assertThrows(IllegalArgumentException::class.java) { ChapterOrdinal(-3) }
        assertEquals("01", ChapterOrdinal(1).twoDigit())
        assertEquals("09", ChapterOrdinal(9).twoDigit())
        assertEquals("12", ChapterOrdinal(12).twoDigit())
        assertEquals("105", ChapterOrdinal(105).twoDigit())
    }

    @Test
    fun `blockId enforces B-prefixed digits`() {
        assertEquals("B01", BlockId("B01").value)
        assertEquals("B142", BlockId("B142").value)
        assertThrows(IllegalArgumentException::class.java) { BlockId("B") }
        assertThrows(IllegalArgumentException::class.java) { BlockId("A01") }
        assertThrows(IllegalArgumentException::class.java) { BlockId("B01a") }
    }

    @Test
    fun `source and content hashes require 64 hex chars and stay distinct types`() {
        val hex64 = "a".repeat(64)
        assertEquals(hex64, BookSourceHash(hex64).sha256Hex)
        assertEquals(hex64, ChapterContentHash(hex64).sha256Hex)
        assertThrows(IllegalArgumentException::class.java) { BookSourceHash("deadbeef") }
        assertThrows(IllegalArgumentException::class.java) { ChapterContentHash("z".repeat(64)) }
    }

    @Test
    fun `spoilerGate cacheKey encodes chapter boundary or full unlock`() {
        assertEquals("gate:ch03", SpoilerGate.UpTo(ChapterOrdinal(3)).cacheKey)
        assertEquals("gate:ch14", SpoilerGate.UpTo(ChapterOrdinal(14)).cacheKey)
        assertEquals("gate:full", SpoilerGate.FullBookUnlocked.cacheKey)
    }
}
