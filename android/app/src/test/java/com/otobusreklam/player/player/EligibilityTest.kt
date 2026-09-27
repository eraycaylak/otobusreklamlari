package com.otobusreklam.player.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/**
 * Sistemin TICARI OLARAK EN KRITIK kurali.
 * Yanlis "evet" = sozlesmesi bitmis envanter yayinlamak.
 * Yanlis "hayir" = sadece bir bosluk.
 * Bu yuzden testler belirsizlikte HAYIR beklentisiyle yazildi.
 */
class EligibilityTest {

    private val zone = ZoneId.of("Europe/Istanbul")

    // 2026-10-05 08:30 Istanbul
    private val simdi = java.time.ZonedDateTime.of(2026, 10, 5, 8, 30, 0, 0, zone)
        .toInstant().toEpochMilli()

    private fun gun(g: Int, saat: Int = 0) = java.time.ZonedDateTime
        .of(2026, 10, g, saat, 0, 0, 0, zone).toInstant().toEpochMilli()

    private fun uygunMu(
        evergreen: Boolean = false,
        clockTrusted: Boolean = true,
        validFrom: Long? = gun(1),
        validUntil: Long? = gun(31),
        dayparts: String = "",
        now: Long = simdi
    ) = Eligibility.isEligible(evergreen, clockTrusted, validFrom, validUntil, dayparts, now, zone)

    @Test fun `gecerli kampanya oynar`() {
        assertTrue(uygunMu())
    }

    @Test fun `BITIS TARIHI YOKSA OYNAMAZ`() {
        // En kritik test: onceden null validUntil kontrolu hic calismiyordu ve
        // reklam SONSUZA KADAR oynuyordu.
        assertFalse(uygunMu(validUntil = null))
    }

    @Test fun `suresi gecmis kampanya oynamaz`() {
        assertFalse(uygunMu(validUntil = gun(4)))
    }

    @Test fun `bitis ani sinirinda hala oynar`() {
        assertTrue(uygunMu(validUntil = simdi))
        assertFalse(uygunMu(validUntil = simdi - 1))
    }

    @Test fun `henuz baslamamis kampanya oynamaz`() {
        assertFalse(uygunMu(validFrom = gun(10)))
    }

    @Test fun `baslangic ani sinirinda oynar`() {
        assertTrue(uygunMu(validFrom = simdi))
        assertFalse(uygunMu(validFrom = simdi + 1))
    }

    @Test fun `baslangic yoksa her zaman baslamis sayilir`() {
        assertTrue(uygunMu(validFrom = null))
    }

    @Test fun `SAAT SUPHELIYKEN tarihli hicbir sey oynamaz`() {
        assertFalse(uygunMu(clockTrusted = false))
        assertFalse(uygunMu(clockTrusted = false, validFrom = null))
    }

    @Test fun `EVERGREEN tarihlere bakmaz`() {
        // Ekranin kararmamasi her seyden onemli: evergreen'in suresi yoktur
        assertTrue(uygunMu(evergreen = true, clockTrusted = false))
        assertTrue(uygunMu(evergreen = true, validUntil = null))
        assertTrue(uygunMu(evergreen = true, validUntil = gun(1)))
        assertTrue(uygunMu(evergreen = true, validFrom = gun(30)))
    }

    /**
     * Sunucu evergreen kampanyalar icin de `dayparts` gonderiyor ve panelde bu alan
     * doldurulabiliyor. Onceden kural KAYDEDILIYOR, KABUL EDILIYOR ve HIC
     * UYGULANMIYORDU: evergreen kosulsuz "uygun" sayiliyordu.
     */
    @Test fun `EVERGREEN daypart'a UYAR`() {
        assertTrue(uygunMu(evergreen = true, dayparts = "07:00-10:00"))   // 08:30 icinde
        assertFalse(uygunMu(evergreen = true, dayparts = "23:00-23:30"))  // 08:30 disinda
    }

    /**
     * Saat supheliyken daypart'i DEGERLENDIREMEYIZ (gercek saati bilmiyoruz). O durumda
     * evergreen kosulsuz oynar: ekranin son guvencesi odur. Yanlis saatle "araliga
     * uymuyor" deyip ekrani karartmak, en kotu sonucu secmek olurdu.
     */
    @Test fun `saat supheliyken EVERGREEN daypart'a BAKMADAN oynar`() {
        assertTrue(uygunMu(evergreen = true, clockTrusted = false, dayparts = "23:00-23:30"))
    }

    @Test fun `EVERGREEN daypart'i da saat dilimine gore degerlendirilir`() {
        // 08:30 Istanbul = 05:30 UTC
        assertTrue(Eligibility.isEligible(true, true, null, null, "07:00-10:00", simdi, zone))
        assertFalse(Eligibility.isEligible(true, true, null, null, "07:00-10:00", simdi, ZoneId.of("UTC")))
    }

    @Test fun `daypart disinda oynamaz`() {
        assertTrue(uygunMu(dayparts = "07:00-10:00"))    // 08:30 icinde
        assertFalse(uygunMu(dayparts = "17:00-20:00"))   // 08:30 disinda
    }

    @Test fun `daypart saat dilimine gore degerlendirilir`() {
        // 08:30 Istanbul = 05:30 UTC. Yanlis saat diliminde "07:00-10:00" kacirilirdi.
        assertTrue(Eligibility.isEligible(false, true, gun(1), gun(31), "07:00-10:00", simdi, zone))
        assertFalse(Eligibility.isEligible(false, true, gun(1), gun(31), "07:00-10:00", simdi, ZoneId.of("UTC")))
    }

    @Test fun `tum kosullar birlikte`() {
        // Tarih uygun ama daypart degil -> oynamaz
        assertFalse(uygunMu(validFrom = gun(1), validUntil = gun(31), dayparts = "20:00-22:00"))
        // Daypart uygun ama suresi gecmis -> oynamaz
        assertFalse(uygunMu(validUntil = gun(4), dayparts = "07:00-10:00"))
    }
}
