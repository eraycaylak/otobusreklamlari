package com.otobusreklam.player.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncPlanTest {

    private fun need(
        id: String,
        evergreen: Boolean = false,
        validFrom: Long? = null,
        validUntil: Long? = null,
        remaining: Long = 10_000_000L
    ) = SyncPlan.Need(id, evergreen, validFrom, validUntil, remaining)

    private val simdi = 1_800_000_000_000L

    @Test fun `evergreen her seyden once iner`() {
        // Ekranin bos kalma riski, en acil kampanyadan bile onceliklidir.
        val out = SyncPlan.order(
            listOf(
                need("kampanya", validFrom = 1L, remaining = 1L),
                need("evergreen", evergreen = true, remaining = 99_000_000L)
            ),
            appUpdate = false, appCritical = false
        )
        assertEquals("evergreen", out.first())
    }

    @Test fun `kritik guncelleme evergreenden sonra kampanyalardan once`() {
        val out = SyncPlan.order(
            listOf(need("ever", evergreen = true), need("kamp")),
            appUpdate = true, appCritical = true
        )
        assertEquals(listOf("ever", SyncPlan.APP_UPDATE, "kamp"), out)
    }

    @Test fun `normal guncelleme en sona kalir`() {
        val out = SyncPlan.order(
            listOf(need("ever", evergreen = true), need("kamp")),
            appUpdate = true, appCritical = false
        )
        assertEquals(listOf("ever", "kamp", SyncPlan.APP_UPDATE), out)
    }

    @Test fun `once yayina en yakin kampanya`() {
        val out = SyncPlan.order(
            listOf(
                need("gec", validFrom = 5_000L),
                need("erken", validFrom = 1_000L),
                need("orta", validFrom = 3_000L)
            ),
            appUpdate = false, appCritical = false
        )
        assertEquals(listOf("erken", "orta", "gec"), out)
    }

    @Test fun `esit tarihte kalan bayti en az olan once`() {
        // Kilit kural: yarim 5 dosya yerine TAM 3 dosya cikarmak daha degerli,
        // cunku yarim dosya oynatilamaz.
        val out = SyncPlan.order(
            listOf(
                need("buyuk", validFrom = 1_000L, remaining = 50_000_000L),
                need("kucuk", validFrom = 1_000L, remaining = 2_000_000L),
                need("orta", validFrom = 1_000L, remaining = 9_000_000L)
            ),
            appUpdate = false, appCritical = false
        )
        assertEquals(listOf("kucuk", "orta", "buyuk"), out)
    }

    @Test fun `tarihsiz kampanya en sona atilir`() {
        val out = SyncPlan.order(
            listOf(need("tarihsiz", validFrom = null), need("tarihli", validFrom = 9_999L)),
            appUpdate = false, appCritical = false
        )
        assertEquals(listOf("tarihli", "tarihsiz"), out)
    }

    @Test fun `eksik evergreenler de kucukten buyuge`() {
        val out = SyncPlan.order(
            listOf(
                need("e-buyuk", evergreen = true, remaining = 30_000_000L),
                need("e-kucuk", evergreen = true, remaining = 1_000_000L)
            ),
            appUpdate = false, appCritical = false
        )
        assertEquals(listOf("e-kucuk", "e-buyuk"), out)
    }

    @Test fun `eksik icerik yoksa sadece guncelleme kalir`() {
        val out = SyncPlan.order(emptyList(), appUpdate = true, appCritical = false)
        assertEquals(listOf(SyncPlan.APP_UPDATE), out)
    }

    @Test fun `hicbir is yoksa liste bos`() {
        assertTrue(SyncPlan.order(emptyList(), appUpdate = false, appCritical = false).isEmpty())
    }

    // ------------------------------------------------- suresi bitmis icerik

    /**
     * Siralama yalnizca `validFrom` artan sirada yapildiginda, suresi BITMIS bir
     * kampanyanin validFrom'u tanimi geregi cok geride oldugu icin listenin EN BASINA
     * geciyordu: 3 dakikalik pencere, cihazda ASLA oynatilamayacak (Eligibility zaten
     * reddediyor) bir dosyaya harcaniyordu - her ziyarette yeniden, cunku dosya bir
     * daha hazir olmuyor.
     */
    @Test fun `suresi bitmis kampanya EN SONA atilir`() {
        val out = SyncPlan.order(
            listOf(
                // validFrom cok geride: eski siralamada EN BASA gecerdi
                need("bitmis", validFrom = 1L, validUntil = simdi - 1, remaining = 1L),
                need("gecerli", validFrom = simdi + 1000, validUntil = simdi + 99_000)
            ),
            appUpdate = false, appCritical = false, nowMs = simdi
        )
        assertEquals(listOf("gecerli", "bitmis"), out)
    }

    @Test fun `suresi bitmis icerik LISTEDEN SILINMEZ, sadece sona gider`() {
        // Yer kalirsa insin: silmek, tarih uzatildiginda bastan indirmek olurdu.
        val out = SyncPlan.order(
            listOf(need("bitmis", validUntil = simdi - 1)),
            appUpdate = false, appCritical = false, nowMs = simdi
        )
        assertEquals(listOf("bitmis"), out)
    }

    /**
     * Saat supheliyken "suresi bitmis" karari guvenilir DEGILDIR. O durumda suzgeci
     * hic uygulamiyoruz: yanlis bir saat yuzunden gecerli bir kampanyayi en sona
     * atmak, saat duzeldiginde oynatacak dosya birakmamak olurdu.
     */
    @Test fun `saat supheliyken tarih suzgeci UYGULANMAZ`() {
        val out = SyncPlan.order(
            listOf(
                need("bitmis", validFrom = 1L, validUntil = simdi - 1, remaining = 1L),
                need("gecerli", validFrom = simdi + 1000)
            ),
            appUpdate = false, appCritical = false, nowMs = 0L
        )
        // Eski (tarih suzgecsiz) siralama: validFrom'u en yakin olan once
        assertEquals(listOf("bitmis", "gecerli"), out)
    }

    @Test fun `suresi bitmis EVERGREEN sona atilmaz`() {
        // Evergreen'in suresi yoktur; validUntil dolu gelse bile ekranin son guvencesi.
        val out = SyncPlan.order(
            listOf(
                need("kampanya", validFrom = simdi),
                need("evergreen", evergreen = true, validUntil = simdi - 1)
            ),
            appUpdate = false, appCritical = false, nowMs = simdi
        )
        assertEquals(listOf("evergreen", "kampanya"), out)
    }

    @Test fun `bitmis olanlar kendi aralarinda kucukten buyuge`() {
        val out = SyncPlan.order(
            listOf(
                need("b-buyuk", validUntil = simdi - 1, remaining = 9_000_000L),
                need("b-kucuk", validUntil = simdi - 1, remaining = 1_000L)
            ),
            appUpdate = false, appCritical = false, nowMs = simdi
        )
        assertEquals(listOf("b-kucuk", "b-buyuk"), out)
    }
}
