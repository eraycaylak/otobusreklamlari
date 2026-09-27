package com.otobusreklam.player.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WeightingSinirTest {

    private data class Oge(val id: String, val w: Int)

    @Test fun `asiri agirlik listeyi sisirmez`() {
        // Panelde yanlislikla girilen 10000, sinir olmasaydi 10000 elemanli bir liste
        // uretir, bellegi yer ve kapasite hesabinda tasma riskine yol acardi.
        val out = Weighting.interleave(listOf(Oge("A", 10_000))) { it.w }
        assertEquals(Weighting.MAX_WEIGHT, out.size)
    }

    @Test fun `Int MAX_VALUE agirlik cokme yaratmaz`() {
        val out = Weighting.interleave(listOf(Oge("A", Int.MAX_VALUE), Oge("B", 1))) { it.w }
        assertEquals(Weighting.MAX_WEIGHT + 1, out.size)
        assertEquals(listOf("A", "B"), out.map { it.id }.take(2))
    }

    @Test fun `sinir altindaki agirliklar aynen korunur`() {
        val out = Weighting.interleave(listOf(Oge("A", 3), Oge("B", 1))) { it.w }
        assertEquals(listOf("A", "B", "A", "A"), out.map { it.id })
    }

    @Test fun `kapasite hesabi tasmiyor`() {
        val cok = (1..50).map { Oge("O$it", Int.MAX_VALUE) }
        val out = Weighting.interleave(cok) { it.w }
        assertTrue(out.size == 50 * Weighting.MAX_WEIGHT)
    }
}
