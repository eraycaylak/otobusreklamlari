package com.otobusreklam.player.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SUNUCU ILE UYUM TESTI.
 *
 * Asagidaki beklenen degerler sunucudaki JS uygulamasindan
 * (sunucu/src/routes/admin.js -> rolloutGroupOf) hesaplanip buraya sabitlendi.
 * Iki taraftan biri degisirse bu test kirilir - kademeli yayimin sessizce
 * bozulmasindansa testin kirilmasi cok daha iyidir.
 */
class RolloutGroupTest {

    @Test fun `sunucuyla ayni grubu uretir - 4 grup`() {
        assertEquals(3, RolloutGroup.of("OTOBUS-001", 4))
        assertEquals(1, RolloutGroup.of("OTOBUS-014", 4))
        assertEquals(3, RolloutGroup.of("OTOBUS-137", 4))
        assertEquals(4, RolloutGroup.of("34 ABC 123", 4))
        assertEquals(4, RolloutGroup.of("hat-14-arac-7", 4))
        assertEquals(4, RolloutGroup.of("ŞOFÖR-ÖZEL-9", 4))
        assertEquals(2, RolloutGroup.of("a", 4))
        assertEquals(1, RolloutGroup.of("", 4))
    }

    @Test fun `sunucuyla ayni grubu uretir - 8 grup`() {
        assertEquals(3, RolloutGroup.of("OTOBUS-001", 8))
        assertEquals(5, RolloutGroup.of("OTOBUS-014", 8))
        assertEquals(7, RolloutGroup.of("OTOBUS-137", 8))
        assertEquals(4, RolloutGroup.of("34 ABC 123", 8))
    }

    @Test fun `Int MIN_VALUE kenar durumu grubu bozmaz`() {
        // "polygenelubricants" un Java hashCode'u tam olarak Int.MIN_VALUE'dur.
        // abs(Int.MIN_VALUE) yine negatif doner; ozel ele alinmasaydi grup 0 veya
        // eksi cikar, cihaz hicbir guncelleme almazdi.
        val g = RolloutGroup.of("polygenelubricants", 4)
        assertEquals(1, g)
        assertTrue(g >= 1)
    }

    @Test fun `sonuc her zaman 1 ile grup sayisi arasindadir`() {
        for (groups in 1..12) {
            for (i in 0..300) {
                val g = RolloutGroup.of("OTOBUS-$i", groups)
                assertTrue("grup $g, $groups grup icinde degil", g in 1..groups)
            }
        }
    }

    @Test fun `ayni kimlik her zaman ayni grubu verir`() {
        val a = RolloutGroup.of("OTOBUS-042", 4)
        repeat(10) { assertEquals(a, RolloutGroup.of("OTOBUS-042", 4)) }
    }

    @Test fun `tek grup varsa herkes birinci gruptadir`() {
        assertEquals(1, RolloutGroup.of("herhangi", 1))
        assertEquals(1, RolloutGroup.of("baska", 0))
    }

    @Test fun `gruplar makul dengeli dagilir`() {
        // Kademeli yayimin ise yaramasi icin ilk grubun filonun kucuk ama
        // anlamli bir kismini kapsamasi gerekir.
        val counts = IntArray(5)
        for (i in 1..200) counts[RolloutGroup.of("OTOBUS-%03d".format(i), 4)]++
        for (g in 1..4) {
            assertTrue("grup $g cok az cihaz aldi: ${counts[g]}", counts[g] in 25..75)
        }
    }
}
