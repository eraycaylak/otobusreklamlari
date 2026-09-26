package com.otobusreklam.player.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WeightingTest {

    private data class Item(val id: String, val weight: Int)

    @Test fun `agirliklar donguye yayilir arka arkaya gelmez`() {
        val items = listOf(Item("A", 3), Item("B", 1), Item("C", 1))
        val out = Weighting.interleave(items) { it.weight }.map { it.id }

        // A 3 kez, B ve C birer kez
        assertEquals(3, out.count { it == "A" })
        assertEquals(1, out.count { it == "B" })
        assertEquals(1, out.count { it == "C" })

        // Ilk turda ucu de gecmeli -> A B C ile baslamali
        assertEquals(listOf("A", "B", "C"), out.take(3))
        assertEquals(listOf("A", "B", "C", "A", "A"), out)
    }

    @Test fun `esit agirliklar sirayi korur`() {
        val items = listOf(Item("A", 1), Item("B", 1))
        assertEquals(listOf("A", "B"), Weighting.interleave(items) { it.weight }.map { it.id })
    }

    @Test fun `bos liste bos doner`() {
        assertTrue(Weighting.interleave(emptyList<Item>()) { it.weight }.isEmpty())
    }

    @Test fun `sifir ve negatif agirlik bir kabul edilir`() {
        // Aksi halde icerik hic oynamazdi; sunucu tarafinda da weight >= 1 zorlaniyor.
        val items = listOf(Item("A", 0), Item("B", -5), Item("C", 2))
        val out = Weighting.interleave(items) { it.weight }.map { it.id }
        assertEquals(listOf("A", "B", "C", "C"), out)
    }

    @Test fun `tek oge agirligi kadar tekrarlanir`() {
        val out = Weighting.interleave(listOf(Item("A", 4))) { it.weight }.map { it.id }
        assertEquals(listOf("A", "A", "A", "A"), out)
    }
}
