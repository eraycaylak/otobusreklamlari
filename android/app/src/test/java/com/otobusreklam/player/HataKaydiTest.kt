package com.otobusreklam.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SON ARIZALAR LISTESI.
 *
 * Bu listenin var olma sebebi: `lastError` tek bir dizgiydi ve cihazda SEKIZ ayri yer
 * ona yaziyordu. Disk dolu -> indirme hatasi -> log tasmasi zincirinde panelde
 * yalnizca son gorunuyor, operator ASIL SEBEBI (disk dolu) hic gormuyor ve fatura
 * kaybinin kokunu bulamiyordu. Kurallar burada, testleri de.
 */
class HataKaydiTest {

    private fun not(kod: String, metin: String = "m", ms: Long = 1000L) = HataKaydi.Not(kod, metin, ms)

    @Test fun `en yeni kayit BASTA olur`() {
        // Panel ilk satirlari gosteriyor: en guncel ariza en ustte olmali.
        var l = emptyList<HataKaydi.Not>()
        l = HataKaydi.ekle(l, "disk", "disk dolu", 1_000)
        l = HataKaydi.ekle(l, "indirme", "parca hatasi", 2_000)
        assertEquals(listOf("indirme", "disk"), l.map { it.kod })
    }

    @Test fun `KOD BASINA TEK KAYIT - tekrar digerlerini atmaz`() {
        /*
         * Kritik kural: "disk dolu" her pencerede tekrar yazilir. Kod basina tek kayit
         * olmasa, o tekrarlar listeyi doldurup ASIL bilgiyi (imza hatasi, log tasmasi)
         * disari atardi - yani cozmeye calistigimiz problemi yeniden uretirdi.
         */
        var l = emptyList<HataKaydi.Not>()
        l = HataKaydi.ekle(l, "imza", "IMZA GECERSIZ", 1_000)
        repeat(20) { i -> l = HataKaydi.ekle(l, "disk", "disk dolu $i", (2_000 + i).toLong()) }
        assertEquals(2, l.size)
        assertTrue("imza kaydi kaybolmamali", l.any { it.kod == "imza" })
        assertEquals("disk dolu 19", l.first { it.kod == "disk" }.metin)
    }

    @Test fun `ayni kod guncellenirken zaman da yenilenir`() {
        var l = HataKaydi.ekle(emptyList(), "disk", "eski", 1_000)
        l = HataKaydi.ekle(l, "disk", "yeni", 9_000)
        assertEquals(1, l.size)
        assertEquals("yeni", l[0].metin)
        assertEquals(9_000L, l[0].uptimeMs)
    }

    @Test fun `liste MAX ile sinirli - heartbeat govdesi sisemez`() {
        // Sunucu heartbeat'i 256 KB'da kesiyor ve her heartbeat IKI yere kaliciya
        // yaziliyor; sinirsiz liste diski dolduran bir yola donusurdu.
        var l = emptyList<HataKaydi.Not>()
        for (i in 1..20) l = HataKaydi.ekle(l, "kod$i", "metin $i", i.toLong())
        assertEquals(HataKaydi.MAX, l.size)
        // En YENI MAX tanesi kalmali
        assertEquals("kod20", l.first().kod)
    }

    @Test fun `sil yalnizca o kodu kaldirir`() {
        /*
         * "Ekran artik bos degil" durumunda eskiden lastError KOSULSUZ temizleniyordu,
         * yani baska arizalarin izi de silinebiliyordu.
         */
        var l = emptyList<HataKaydi.Not>()
        l = HataKaydi.ekle(l, "disk", "disk dolu", 1_000)
        l = HataKaydi.ekle(l, "ekranbos", "EKRAN BOS", 2_000)
        l = HataKaydi.sil(l, "ekranbos")
        assertEquals(listOf("disk"), l.map { it.kod })
    }

    @Test fun `kodla-coz gidis donus ayni listeyi verir`() {
        var l = emptyList<HataKaydi.Not>()
        l = HataKaydi.ekle(l, "disk", "DISK DOLU: 120 MB bos", 4_000)
        l = HataKaydi.ekle(l, "imza", "IMZA GECERSIZ: acik anahtar 32 bayt olmali", 5_000)
        assertEquals(l, HataKaydi.coz(HataKaydi.kodla(l)))
    }

    @Test fun `metindeki AYIRICILAR listeyi bozmaz`() {
        /*
         * Metin serbest ve cihazdan/istisna mesajindan geliyor. Ayiricilar temizlenmezse
         * TEK bir kayit tum listeyi ayristirilamaz hale getirir ve tum teshis sinyali
         * kaybolur - hem de yalnizca belirli hata mesajlarinda, yani en kotu anda.
         */
        val kirli = "hata\u001fara\u001esatir"
        val l = HataKaydi.ekle(emptyList(), "x", kirli, 1_000)
        val geri = HataKaydi.coz(HataKaydi.kodla(l))
        assertEquals(1, geri.size)
        assertEquals("x", geri[0].kod)
        assertEquals("hata ara satir", geri[0].metin)
    }

    @Test fun `bos ve bozuk girdi cokertmez`() {
        assertEquals(emptyList<HataKaydi.Not>(), HataKaydi.coz(null))
        assertEquals(emptyList<HataKaydi.Not>(), HataKaydi.coz(""))
        assertEquals(emptyList<HataKaydi.Not>(), HataKaydi.coz("bozuk-veri"))
        // Yarim kayit atlanir, saglam olan kalir
        assertEquals(1, HataKaydi.coz("kod\u001fmetin\u001f123\u001eyarim").size)
    }

    @Test fun `bos kod genel olur, uzun metin kirpilir`() {
        val l = HataKaydi.ekle(emptyList(), "   ", "x".repeat(500), 1_000)
        assertEquals("genel", l[0].kod)
        assertEquals(200, l[0].metin.length)
    }

    @Test fun `yas monotonik saatten hesaplanir`() {
        // Duvar saati bu cihazlarda guvenilmez; yas elapsedRealtime farkindan geliyor.
        assertEquals(120L, HataKaydi.yasSaniye(not("x", ms = 60_000), 180_000))
        assertEquals(0L, HataKaydi.yasSaniye(not("x", ms = 60_000), 60_500))
    }

    @Test fun `yeniden baslatmadan sonra yas BILINMIYOR doner`() {
        /*
         * elapsedRealtime acilista sifirlanir, yani eski bir notun yasi NEGATIF cikar.
         * Yanlis bir sayi gostermek ("3 gun once" yerine "-12 dk once") hicbir sey
         * gostermemekten kotudur: -1 ile "bilinmiyor" diyoruz.
         */
        assertEquals(-1L, HataKaydi.yasSaniye(not("x", ms = 900_000), 1_000))
    }
}
