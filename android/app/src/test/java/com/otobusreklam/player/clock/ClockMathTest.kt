package com.otobusreklam.player.clock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClockMathTest {

    private val serverTime = 1_800_000_000_000L   // sabit bir an

    @Test fun `capa gecerliyken gecen sure monotonik sayactan hesaplanir`() {
        val anchor = ClockMath.Anchor(epochMs = serverTime, elapsedMs = 10_000L)
        // Senkrondan 5 dakika sonra
        val now = ClockMath.now(anchor, elapsedNow = 310_000L, systemNow = 0L)
        assertEquals(serverTime + 300_000L, now)
    }

    @Test fun `sistem saati bozuk olsa bile capaya guvenilir`() {
        val anchor = ClockMath.Anchor(serverTime, 10_000L)
        // Sistem saati 1970'e dusmus olsun - sonuc etkilenmemeli
        val now = ClockMath.now(anchor, elapsedNow = 10_000L, systemNow = 0L)
        assertEquals(serverTime, now)
    }

    @Test fun `yeniden baslatma capayi gecersiz kilar`() {
        val anchor = ClockMath.Anchor(serverTime, elapsedMs = 500_000L)
        // Cihaz yeniden basladi -> elapsedRealtime sifirlandi
        assertFalse(ClockMath.anchorValid(anchor, elapsedNow = 3_000L))
        // Bu durumda sistem saatine dusulur (ve cagiran taraf SUPHELI isaretler)
        assertEquals(123L, ClockMath.now(anchor, elapsedNow = 3_000L, systemNow = 123L))
    }

    @Test fun `capa yoksa sistem saatine dusulur`() {
        assertFalse(ClockMath.anchorValid(null, 1_000L))
        assertEquals(999L, ClockMath.now(null, elapsedNow = 1_000L, systemNow = 999L))
    }

    @Test fun `bozuk capa degerleri reddedilir`() {
        assertFalse(ClockMath.anchorValid(ClockMath.Anchor(0L, 100L), 200L))
        assertFalse(ClockMath.anchorValid(ClockMath.Anchor(serverTime, -1L), 200L))
    }

    @Test fun `ayni an capa gecerlidir`() {
        val anchor = ClockMath.Anchor(serverTime, 5_000L)
        assertTrue(ClockMath.anchorValid(anchor, 5_000L))
    }
}
