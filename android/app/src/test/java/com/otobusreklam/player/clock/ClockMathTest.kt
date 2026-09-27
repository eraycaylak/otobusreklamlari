package com.otobusreklam.player.clock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClockMathTest {

    private val serverTime = 1_800_000_000_000L   // sabit bir an

    // ------------------------------------------------------- temel hesaplama

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

    // ------------------------------------------- yeniden baslatma tespiti

    @Test fun `sayac geriye gittiyse yeniden baslatma tespit edilir`() {
        val anchor = ClockMath.Anchor(serverTime, elapsedMs = 500_000L)
        // Cihaz yeniden basladi -> elapsedRealtime sifirlandi
        assertFalse(ClockMath.anchorValid(anchor, elapsedNow = 3_000L))
        // Bu durumda sistem saatine dusulur ve saat SUPHELI olur
        val s = ClockMath.snapshot(anchor, elapsedNow = 3_000L, systemNow = 123L)
        assertEquals(123L, s.nowMs)
        assertFalse(s.trusted)
    }

    /**
     * SAHADA KIRILAN YON.
     *
     * Eski tek yonlu sezgi yalnizca "sayac geriye gitti" durumunu yakaliyordu. Capa
     * acilistan kisa sure sonra alinmissa, cihaz saatlerce calisip yeniden baslatildiktan
     * SONRAKI sayac degeri capadan BUYUK olur ve yeniden baslatma HIC tespit edilmez:
     * saatler geride kalmis bir capa "guvenilir" sayilir. Iptal edilmis reklamin
     * yayinda kalmasi tam olarak bu demek.
     */
    @Test fun `acilistan hemen sonra alinan capa, yeniden baslatmadan sonra ileri sayacla bile gecersizdir`() {
        // Capa: onceki acilisin 2. dakikasinda alindi, acilis sayaci 7
        val anchor = ClockMath.Anchor(serverTime, elapsedMs = 120_000L, bootCount = 7)

        // Cihaz 5 saat calisti, yeniden basladi (sayac 8), simdi 3. dakikadayiz.
        // elapsedNow (180_000) > anchor.elapsedMs (120_000): ESKI SEZGI BUNU KACIRIRDI.
        assertFalse(ClockMath.anchorValid(anchor, elapsedNow = 180_000L, bootCount = 8))

        val s = ClockMath.snapshot(anchor, elapsedNow = 180_000L, systemNow = 55L, bootCount = 8)
        assertFalse(s.trusted)
        assertEquals("yeniden baslatma", s.reason)
    }

    @Test fun `ayni acilista capa gecerli kalir`() {
        val anchor = ClockMath.Anchor(serverTime, elapsedMs = 120_000L, bootCount = 7)
        assertTrue(ClockMath.anchorValid(anchor, elapsedNow = 180_000L, bootCount = 7))
        val s = ClockMath.snapshot(anchor, elapsedNow = 180_000L, systemNow = 0L, bootCount = 7)
        assertTrue(s.trusted)
        assertEquals(serverTime + 60_000L, s.nowMs)
    }

    @Test fun `acilis sayaci okunamiyorsa eski sezgi kullanilir`() {
        val anchor = ClockMath.Anchor(serverTime, elapsedMs = 500_000L, bootCount = ClockMath.BOOT_UNKNOWN)
        // Sayac yok: yalnizca geri gitme yakalanabilir
        assertFalse(ClockMath.anchorValid(anchor, 3_000L, ClockMath.BOOT_UNKNOWN))
        assertTrue(ClockMath.anchorValid(anchor, 600_000L, ClockMath.BOOT_UNKNOWN))
    }

    @Test fun `cihaz sayaci bilinmiyorsa capadaki sayac tek basina karar vermez`() {
        val anchor = ClockMath.Anchor(serverTime, elapsedMs = 100L, bootCount = 3)
        // Simdiki sayac okunamadi: kesin testi uygulayamayiz, sezgiye duseriz
        assertTrue(ClockMath.anchorValid(anchor, 200L, ClockMath.BOOT_UNKNOWN))
    }

    // --------------------------------------------------- imza ve guven kademesi

    @Test fun `imzasiz capa zamani verir ama GUVEN vermez`() {
        val anchor = ClockMath.Anchor(serverTime, elapsedMs = 1_000L, bootCount = 1, signed = false)
        val s = ClockMath.snapshot(anchor, elapsedNow = 61_000L, systemNow = 0L, bootCount = 1)
        // Zaman kullanilir: 1970 damgasi atmaktan iyidir
        assertEquals(serverTime + 60_000L, s.nowMs)
        // Ama bitis tarihi zorlamasi icin yeterli DEGILDIR
        assertFalse(s.trusted)
    }

    @Test fun `imzali capa guven verir`() {
        val anchor = ClockMath.Anchor(serverTime, elapsedMs = 1_000L, bootCount = 1, signed = true)
        val s = ClockMath.snapshot(anchor, elapsedNow = 1_000L, systemNow = 0L, bootCount = 1)
        assertTrue(s.trusted)
    }

    @Test fun `acikca gecersiz kilinan capa guven vermez ama zamani korur`() {
        val anchor = ClockMath.Anchor(serverTime, elapsedMs = 1_000L, bootCount = 1)
        val s = ClockMath.snapshot(anchor, 1_000L, systemNow = 0L, bootCount = 1, invalidated = true)
        assertEquals(serverTime, s.nowMs)
        assertFalse(s.trusted)
    }

    // ----------------------------------------------------------- capa yasi

    @Test fun `cok yaslanmis capa guven vermez`() {
        val anchor = ClockMath.Anchor(serverTime, elapsedMs = 0L, bootCount = 1)
        val yas = ClockMath.MAX_ANCHOR_AGE_MS + 1
        val s = ClockMath.snapshot(anchor, elapsedNow = yas, systemNow = 0L, bootCount = 1)
        assertFalse(s.trusted)
        // Zaman yine capadan uretilir: sistem saatinden daha iyidir
        assertEquals(serverTime + yas, s.nowMs)
    }

    @Test fun `sinirin hemen altindaki capa hala guvenilir`() {
        val anchor = ClockMath.Anchor(serverTime, elapsedMs = 0L, bootCount = 1)
        val s = ClockMath.snapshot(
            anchor, elapsedNow = ClockMath.MAX_ANCHOR_AGE_MS, systemNow = 0L, bootCount = 1
        )
        assertTrue(s.trusted)
    }

    @Test fun `yas hesabi gecersiz capada sonsuzdur`() {
        assertEquals(Long.MAX_VALUE, ClockMath.ageMs(null, 100L))
    }
}
