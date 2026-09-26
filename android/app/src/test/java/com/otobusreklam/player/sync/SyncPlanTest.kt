package com.otobusreklam.player.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncPlanTest {

    private fun need(id: String, evergreen: Boolean = false, validFrom: Long? = null, remaining: Long = 10_000_000L) =
        SyncPlan.Need(id, evergreen, validFrom, remaining)

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
}
