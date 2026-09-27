package com.otobusreklam.player.sync

import com.otobusreklam.player.manifest.ChunkSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PROJENIN CEKIRDEK OZELLIGININ KURALLARI.
 *
 * Devam ettirilebilir parcali indirme, bu sistemin var olma sebebi: 3 dakikalik
 * pencerede yarim kalan dosya, sonraki ziyarette kaldigi bayttan devam ediyor.
 * Bu kararlar dustugunde sonuc her zaman sessizdir ve her zaman pahalidir:
 * ya sonsuz yeniden indirme, ya yakalanamayan OutOfMemoryError, ya bozuk dosya.
 */
class IndirmeKurallariTest {

    private fun parcalar(vararg uzunluklar: Int): List<ChunkSpec> {
        var offset = 0L
        return uzunluklar.mapIndexed { i, len ->
            ChunkSpec(i, offset, len, "%064x".format(i)).also { offset += len }
        }
    }

    // ----------------------------------------------------- ParcaDogrulama

    @Test fun `tam kapsayan parca listesi kabul edilir`() {
        assertNull(ParcaDogrulama.kontrol(parcalar(65536, 65536, 8192), 139_264L))
    }

    @Test fun `tek parcali dosya kabul edilir`() {
        assertNull(ParcaDogrulama.kontrol(parcalar(1024), 1024L))
    }

    @Test fun `toplam size dan KUCUKSE reddedilir`() {
        /*
         * Onemi: reddetmezsek tum parcalar "indi" olur, tam dosya hash'i TUTMAZ ve
         * dosya bastan indirilir - her pencerede yeniden, SONSUZA KADAR.
         */
        val sebep = ParcaDogrulama.kontrol(parcalar(65536, 65536), 200_000L)
        assertNotNull(sebep)
        assertTrue("mesaj beklenen boyutu soylemeli: $sebep", sebep!!.contains("200000"))
        assertTrue("mesaj gelen toplami soylemeli: $sebep", sebep.contains("131072"))
    }

    @Test fun `toplam size dan BUYUKSE reddedilir`() {
        assertNotNull(ParcaDogrulama.kontrol(parcalar(65536, 65536), 100_000L))
    }

    @Test fun `bos parca listesi reddedilir`() {
        val sebep = ParcaDogrulama.kontrol(emptyList(), 1024L)
        assertNotNull(sebep)
        assertTrue(sebep!!.contains("bos"))
    }

    @Test fun `sifir veya negatif uzunluk reddedilir`() {
        assertNotNull(ParcaDogrulama.kontrol(parcalar(0), 0L))
        assertNotNull(ParcaDogrulama.kontrol(parcalar(100, -100), 0L))
    }

    @Test fun `MAX_PARCA sinirinda olan parca KABUL edilir`() {
        val sinir = ParcaDogrulama.MAX_PARCA
        assertNull(ParcaDogrulama.kontrol(parcalar(sinir), sinir.toLong()))
    }

    @Test fun `MAX_PARCA + 1 REDDEDILIR - yakalanamayan OOM sinifi`() {
        /*
         * Parca tamamen bellege aliniyor (hash'i yazmadan once dogrulanmali). 500 MB'lik
         * bir `len` ByteArray(len) ile OutOfMemoryError firlatir; OOM bir Error'dur ve
         * indirme dongusundeki catch(Exception) bloklarinin HICBIRI onu tutmaz - kiosk
         * uygulamasi ekrani karartarak coker.
         */
        val asan = ParcaDogrulama.MAX_PARCA + 1
        val sebep = ParcaDogrulama.kontrol(parcalar(asan), asan.toLong())
        assertNotNull("sinir asan parca kabul edildi - OOM riski", sebep)
        assertTrue("mesaj siniri soylemeli: $sebep", sebep!!.contains("${ParcaDogrulama.MAX_PARCA}"))
        assertTrue("mesaj hangi parca oldugunu soylemeli: $sebep", sebep.contains("idx=0"))
    }

    @Test fun `boyut kontrolu kapsam kontrolunden ONCE gelir`() {
        // Iki hata birden varsa mesaj OOM riskini gostermeli: o daha acil.
        val sebep = ParcaDogrulama.kontrol(parcalar(ParcaDogrulama.MAX_PARCA + 1), 5L)
        assertTrue("mesaj boyut sinirini anlatmali: $sebep", sebep!!.contains("boyutu kabul edilemez"))
    }

    // ----------------------------------------------------- YanitKabul

    private fun kabul(
        code: Int = 206,
        contentRange: String? = "bytes 65536-131071/307200",
        contentLength: Long = 65536,
        offset: Long = 65536,
        len: Int = 65536,
        totalChunks: Int = 5
    ) = YanitKabul.kabulEdilirMi(code, contentRange, contentLength, offset, len, totalChunks, "http://x/a.mp4")

    @Test fun `dogru 206 yaniti kabul edilir`() {
        assertNull(kabul())
    }

    @Test fun `cok parcali dosyada 200 REDDEDILIR`() {
        /*
         * 200 = sunucu/proxy Range'i YOK SAYDI, govde TUM dosya. Onu parcanin
         * offset'ine yazmak dosyayi sessizce bozar. Yanlis yapilandirilmis bir
         * onbellek kutusu tam olarak bunu dondurur.
         */
        val sebep = kabul(code = 200, contentRange = null, contentLength = 307_200)
        assertNotNull("cok parcali dosyada 200 kabul edildi - dosya bozulurdu", sebep)
        assertTrue("mesaj sebebi soylemeli: $sebep", sebep!!.contains("Range desteklemiyor"))
    }

    @Test fun `tek parcali dosyada offset 0 ile 200 KABUL edilir`() {
        // Dosya tek parcadan olusuyorsa 200 govdesi zaten TAM dosyadir.
        assertNull(kabul(code = 200, contentRange = null, contentLength = 1024, offset = 0, len = 1024, totalChunks = 1))
    }

    @Test fun `tek parcali ama offset sifir DEGILSE 200 reddedilir`() {
        assertNotNull(kabul(code = 200, contentRange = null, contentLength = 1024, offset = 512, len = 1024, totalChunks = 1))
    }

    @Test fun `YANLIS aralik reddedilir`() {
        /*
         * Range'i kismen destekleyen bir proxy ya da arada duran bir onbellek baska bir
         * araligi dondurebilir. Onceden bu ancak parca hash'inde anlasiliyor ve
         * teshis edilemez bir "parca hash tutmadi" satiri olarak goruluyordu.
         */
        val sebep = kabul(contentRange = "bytes 0-65535/307200")
        assertNotNull("yanlis aralik kabul edildi - dosya sessizce bozulurdu", sebep)
        assertTrue("mesaj ikisini de gostermeli: $sebep", sebep!!.contains("istenen") && sebep.contains("gelen"))
    }

    @Test fun `Content-Range basligi YOKSA reddetmiyoruz`() {
        // Basligi gondermeyen bir sunucu var olabilir; govde uzunlugu ve parca hash'i
        // zaten ikinci ve ucuncu savunma hatti.
        assertNull(kabul(contentRange = null))
        assertNull(kabul(contentRange = ""))
    }

    @Test fun `govde uzunlugu farkliysa reddedilir`() {
        assertNotNull(kabul(contentLength = 65_535))
        assertNotNull(kabul(contentLength = 65_537))
    }

    @Test fun `govde uzunlugu BILINMIYORSA reddetmiyoruz`() {
        // chunked kodlama: -1 gelir. readExactly zaten kisa govdeyi yakalar.
        assertNull(kabul(contentLength = -1))
    }

    @Test fun `401 ve 403 onbellek kutusunu isaret eden mesaj verir`() {
        for (code in listOf(401, 403)) {
            val sebep = kabul(code = code)
            assertNotNull(sebep)
            assertTrue("mesaj Authorization'i anmali: $sebep", sebep!!.contains("Authorization"))
        }
    }

    @Test fun `404 icerigin sunucuda olmadigini soyler`() {
        assertTrue(kabul(code = 404)!!.contains("404"))
    }

    @Test fun `bilinmeyen kod da anlasilir mesaj verir`() {
        val sebep = kabul(code = 502)
        assertNotNull(sebep)
        assertTrue(sebep!!.contains("502"))
    }

    // ----------------------------------------------------- SifirlamaKarari

    @Test fun `parca yok ve dosya kirpilmis ise SIFIRLA`() {
        /*
         * Bu dal bir zamanlar OLU KODDU: kontrol setLength(size) cagrisindan SONRA
         * yapiliyor ve o noktada uzunluk HER ZAMAN size'a esit oldugu icin kosul asla
         * saglanmiyordu. Testi olmadan yine olu koda donebilir.
         */
        assertTrue(SifirlamaKarari.sifirlaMi(bekleyenParcaVarMi = false, oncekiUzunluk = 0L, size = 200_000L))
        assertTrue(SifirlamaKarari.sifirlaMi(bekleyenParcaVarMi = false, oncekiUzunluk = 131_072L, size = 200_000L))
    }

    @Test fun `parca yok ve dosya TAM ise sifirlama YOK`() {
        assertEquals(false, SifirlamaKarari.sifirlaMi(false, 200_000L, 200_000L))
    }

    @Test fun `bekleyen parca varsa asla sifirlanmaz`() {
        // Ilerleme diskte: sifirlamak, kazanilmis baytlari cope atmak olurdu.
        assertEquals(false, SifirlamaKarari.sifirlaMi(true, 0L, 200_000L))
        assertEquals(false, SifirlamaKarari.sifirlaMi(true, 131_072L, 200_000L))
    }
}
