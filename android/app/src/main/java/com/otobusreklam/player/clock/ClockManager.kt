package com.otobusreklam.player.clock

import android.content.Context
import android.os.SystemClock

/**
 * Saat yonetimi - sessiz ama olumcul konu.
 *
 * Ucuz Android stick'lerde pil destekli RTC genelde YOKTUR; guc kesilince saat
 * sifirlanir veya 1970'e duser. `validUntil` zorlamasi saate bagli oldugu icin
 * bu dogrudan ticari bir risk: suresi bitmis ucretli bir reklami oynatmaya devam
 * etmek, sozlesmesi bitmis envanter yayinlamak demektir.
 *
 * Cozum:
 *  - Her senkronda sunucunun Date basligindan gercek zaman alinir
 *  - Araya gecen sure MONOTONIK sayacla (elapsedRealtime) hesaplanir
 *  - Cihaz yeniden baslarsa monotonik sayac sifirlanir -> saat SUPHELI olur
 *  - SUPHELI durumda kural: "suresi gecmis say, oynatma" (bkz. PlaylistBuilder)
 */
class ClockManager(context: Context) {

    private val prefs = context.getSharedPreferences("reklam_clock", Context.MODE_PRIVATE)

    /** Sunucudan gercek zaman geldiginde cagrilir. */
    fun onServerTime(epochMs: Long) {
        prefs.edit()
            .putLong(K_ANCHOR_EPOCH, epochMs)
            .putLong(K_ANCHOR_ELAPSED, SystemClock.elapsedRealtime())
            .putLong(K_LAST_KNOWN, epochMs)
            .putBoolean(K_TRUSTED, true)
            .apply()
    }

    /**
     * Guvenilir "simdi". Sistem saatine DEGIL, son sunucu zamani + monotonik
     * sayac farkina dayanir.
     */
    fun now(): Long = ClockMath.now(
        anchor = readAnchor(),
        elapsedNow = SystemClock.elapsedRealtime(),
        systemNow = System.currentTimeMillis()
    )

    private fun readAnchor(): ClockMath.Anchor? {
        val epoch = prefs.getLong(K_ANCHOR_EPOCH, 0L)
        val elapsed = prefs.getLong(K_ANCHOR_ELAPSED, -1L)
        return if (epoch > 0L && elapsed >= 0L) ClockMath.Anchor(epoch, elapsed) else null
    }

    /**
     * Saat guvenilir mi?
     * Hayirsa: bitis tarihi olan HICBIR icerik oynatilmaz, sadece evergreen doner.
     */
    fun trusted(): Boolean {
        if (!prefs.getBoolean(K_TRUSTED, false)) return false

        // Monotonik sayac geri gittiyse cihaz yeniden baslamistir -> capa gecersiz
        if (!ClockMath.anchorValid(readAnchor(), SystemClock.elapsedRealtime())) {
            invalidate("yeniden baslatma")
            return false
        }

        // Sistem saati en son bildigimiz zamandan GERIYE gittiyse guvenilmez
        val lastKnown = prefs.getLong(K_LAST_KNOWN, 0L)
        if (lastKnown > 0 && System.currentTimeMillis() < lastKnown - TOLERANCE_MS) {
            // Sistem saati bozuk olabilir ama capamiz hala gecerliyse capaya guveniyoruz.
            // Yine de not dusuyoruz.
            prefs.edit().putString(K_NOTE, "sistem saati geriye gitti").apply()
        }
        return true
    }

    fun invalidate(reason: String) {
        prefs.edit().putBoolean(K_TRUSTED, false).putString(K_NOTE, reason).apply()
    }

    /** Cihaz her acildiginda cagrilir: monotonik capa gecersizdir. */
    fun onBoot() = invalidate("acilis")

    val note: String get() = prefs.getString(K_NOTE, "") ?: ""

    private companion object {
        const val K_ANCHOR_EPOCH = "anchorEpoch"
        const val K_ANCHOR_ELAPSED = "anchorElapsed"
        const val K_LAST_KNOWN = "lastKnown"
        const val K_TRUSTED = "trusted"
        const val K_NOTE = "note"
        const val TOLERANCE_MS = 5 * 60 * 1000L
    }
}
