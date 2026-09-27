package com.otobusreklam.player.player

import java.time.Instant
import java.time.ZoneId

/**
 * Bir icerigin SU AN oynatilabilir olup olmadigi.
 *
 * Android'e bagimli DEGIL -> birim testi yazilabilir. Bu bilincli: bu kural
 * sistemin TICARI OLARAK EN KRITIK karari. Yanlis "evet" demek, sozlesmesi
 * bitmis envanter yayinlamak (reklamverene fatura edilemeyen, hatta iptal edilmis
 * bir reklami donduren) anlamina gelir. Yanlis "hayir" demek ise sadece bir bosluk.
 *
 * Bu yuzden her belirsizlikte HAYIR diyoruz.
 */
object Eligibility {

    /**
     * @param evergreen dolgu icerigi: suresi yoktur, ekranin son guvencesidir
     * @param clockTrusted cihazin saati guvenilir mi (bkz. ClockMath)
     * @param validFrom yayina giris ani (ms) - null ise "her zaman basladi"
     * @param validUntil bitis ani (ms) - evergreen OLMAYANDA null ise UYGUN DEGIL
     * @param dayparts "07:00-10:00,17:00-20:00" - bos ise gun boyu
     * @param nowMs guvenilir simdi
     * @param zone daypart'in yorumlanacagi saat dilimi
     */
    fun isEligible(
        evergreen: Boolean,
        clockTrusted: Boolean,
        validFrom: Long?,
        validUntil: Long?,
        dayparts: String,
        nowMs: Long,
        zone: ZoneId
    ): Boolean {
        // Evergreen her kosulda oynar: ekranin kararmamasi her seyden onemli
        if (evergreen) return true

        // Saat supheliyse tarihli hicbir sey oynatilamaz
        if (!clockTrusted) return false

        // BITIS TARIHI ZORUNLU. Sunucu bunu zaten dayatiyor; burada null gormek
        // ayristirma hatasi demektir ve o durumda reklam SONSUZA KADAR oynardi.
        if (validUntil == null) return false

        if (validFrom != null && nowMs < validFrom) return false
        if (nowMs > validUntil) return false

        return Daypart.matches(dayparts, Instant.ofEpochMilli(nowMs).atZone(zone).toLocalTime())
    }
}
