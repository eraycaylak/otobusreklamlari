package com.otobusreklam.player.clock

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import com.otobusreklam.player.BuildConfig

/**
 * Saat yonetimi - sessiz ama olumcul konu.
 *
 * Ucuz Android stick'lerde pil destekli RTC genelde YOKTUR; guc kesilince saat
 * sifirlanir veya 1970'e duser. `validUntil` zorlamasi saate bagli oldugu icin
 * bu dogrudan ticari bir risk: suresi bitmis ucretli bir reklami oynatmaya devam
 * etmek, sozlesmesi bitmis envanter yayinlamak demektir.
 *
 * UC KADEMELI GUVEN:
 *   1. capa yok            -> sistem saati, SUPHELI (yalnizca evergreen oynar)
 *   2. ZAYIF capa          -> imzasiz HTTP `Date`. Zaman makul olur (loglar 1970
 *                             damgasi tasimaz) ama SUPHELI kalir.
 *   3. GUVENILIR capa      -> imzali manifest govdesindeki serverTime. Bitis tarihi
 *                             zorlamasi ancak burada devreye girer.
 *
 * Neden bu ayrim: `Date` basligi imzanin DISINDADIR. Ag duz HTTP (icerik butunlugu
 * Ed25519 ile ayrica saglaniyor), yani AP agina erisebilen biri Date'i istedigi gibi
 * yazabilir. Onceden kod tam tersini yapiyordu: Date'i HER ZAMAN imzali serverTime'a
 * tercih ediyordu - yani tek guvenilir kaynagi birakip tek guvenilmez olani seciyordu.
 */
class ClockManager(context: Context) {

    private val prefs = context.getSharedPreferences("reklam_clock", Context.MODE_PRIVATE)
    private val resolver = context.applicationContext.contentResolver

    /**
     * Cihazin acilis sayaci. Sistem her acilista bir artirir; yalnizca fabrika
     * ayarina donuste sifirlanir. Yeniden baslatmayi TAHMINE dayanmadan tespit
     * etmemizi saglayan tek guvenilir sinyal budur.
     */
    private fun bootCount(): Int = runCatching {
        Settings.Global.getInt(resolver, "boot_count", ClockMath.BOOT_UNKNOWN)
    }.getOrDefault(ClockMath.BOOT_UNKNOWN)

    /**
     * Sunucudan gercek zaman geldiginde cagrilir.
     *
     * @param signed zaman IMZALI manifest govdesinden mi geldi? Yalnizca imzali
     *   kaynak saati GUVENILIR yapar; imzasiz `Date` basligi en fazla ZAYIF capa olur.
     *
     * Uc filtre uygulanir:
     *  1. MAKULLUK  - derleme zamanindan once veya 10 yil sonrasi kabul edilmez.
     *     (Bozuk bir onbellek yaniti veya kasitli bir deger sistemi dondurebilir.)
     *  2. GERIYE GIDEMEZ - suresi dolmus/iptal edilmis reklami diriltmek en pahali
     *     hatadir. Ileriye sicrama kabul edilir: cihaz aylarca kapali kalmis olabilir
     *     ve ileri saat en fazla reklami erken dusurur.
     *  3. IMZASIZ, IMZALIYI EZEMEZ - saldirinin tam kapandigi yer burasi.
     */
    fun onServerTime(epochMs: Long, signed: Boolean) {
        val elapsed = SystemClock.elapsedRealtime()
        val mevcut = readAnchor()
        val gecerliImzali = mevcut != null && mevcut.signed &&
            ClockMath.anchorValid(mevcut, elapsed, bootCount())

        // Karar kurali Android'den BAGIMSIZ ve testli: bkz. ClockMath.kabulEdilirMi
        val kabul = ClockMath.kabulEdilirMi(
            epochMs = epochMs,
            signed = signed,
            signedLatchMs = prefs.getLong(K_LAST_KNOWN_SIGNED, 0L),
            buildTimeMs = BuildConfig.BUILD_TIME_MS,
            gecerliImzaliCapaVar = gecerliImzali
        )

        when (kabul.karar) {
            ClockMath.Karar.RED_MAKUL_DEGIL -> {
                val yil = runCatching {
                    java.time.Instant.ofEpochMilli(epochMs).atZone(java.time.ZoneOffset.UTC).year
                }.getOrDefault(0)
                Log.w(TAG, "sunucu zamani makul degil (yil $yil) - REDDEDILDI")
                not("makul olmayan sunucu zamani (yil $yil) - sunucu saatini kontrol edin")
                return
            }
            ClockMath.Karar.RED_GERIYE -> {
                val farkSaat = (prefs.getLong(K_LAST_KNOWN_SIGNED, 0L) - epochMs) / 3_600_000
                Log.w(TAG, "sunucu zamani GERIYE gidiyor ($farkSaat saat, imzali=$signed) - REDDEDILDI")
                not("geriye giden zaman reddedildi ($farkSaat sa)")
                return
            }
            ClockMath.Karar.RED_IMZASIZ_EZEMEZ -> {
                // Gecerli imzali capa duruyor: imzasiz kaynak sessizce yok sayilir.
                return
            }
            ClockMath.Karar.KABUL -> Unit
        }

        val edit = prefs.edit()
            .putLong(K_ANCHOR_EPOCH, epochMs)
            .putLong(K_ANCHOR_ELAPSED, elapsed)
            .putInt(K_ANCHOR_BOOT, bootCount())
            .putBoolean(K_ANCHOR_SIGNED, signed)
            .putBoolean(K_INVALIDATED, false)
            // Duzelmis cihaz panele bayat alarm notu gondermeye devam etmesin.
            .putString(K_NOTE, if (signed) "" else "imzasiz kaynak (zayif capa)")
        if (kabul.latchUpdated) edit.putLong(K_LAST_KNOWN_SIGNED, epochMs)
        edit.apply()
    }

    /**
     * Zaman VE guven - TEK okumada, tutarli.
     *
     * Cagiranlarin ikisini ayri ayri sormasi yaristi: arada bir senkron gerceklesirse
     * "1970 damgasi + saat guvenilir" gibi imkansiz bir birlesim faturaya girebiliyordu.
     */
    fun snapshot(): ClockMath.Snapshot = ClockMath.snapshot(
        anchor = readAnchor(),
        elapsedNow = SystemClock.elapsedRealtime(),
        systemNow = System.currentTimeMillis(),
        bootCount = bootCount(),
        invalidated = prefs.getBoolean(K_INVALIDATED, false)
    )

    /** Guvenilir "simdi". Guven de gerekiyorsa [snapshot] kullanin. */
    fun now(): Long = snapshot().nowMs

    /**
     * Saat guvenilir mi?
     * Hayirsa: bitis tarihi olan HICBIR icerik oynatilmaz, sadece evergreen doner.
     *
     * Bu fonksiyon artik HICBIR SEY YAZMIYOR. Onceden icinden invalidate() cagriyordu
     * ve es zamanli bir onServerTime ile kayip-guncelleme yarisi olusuyordu (yeni capa
     * yazilir, hemen ardindan eski duruma gore gecersiz isaretlenirdi).
     */
    fun trusted(): Boolean = snapshot().trusted

    fun invalidate(reason: String) {
        prefs.edit().putBoolean(K_INVALIDATED, true).putString(K_NOTE, reason).apply()
    }

    /**
     * Cihaz her acildiginda cagrilir.
     *
     * KOSULSUZ invalidate ETMIYOR. Sebep: BOOT_COMPLETED yayini HOME aktivitesinden
     * SONRA gelir. Oynatici acilir, WiFi gelir, senkron olur ve TAZE bir capa alinir;
     * ardindan bu alici calisip o capayi cope atardi. Sonuc: acilisin ilk turunda
     * saat hep "supheli" olur ve cihaz yalnizca evergreen oynar - tam da senkron
     * basariyla tamamlanmisken.
     *
     * Yeniden baslatma zaten capadaki BOOT_COUNT ile kendiliginden tespit ediliyor;
     * burada yalnizca hala gecerli bir capa yoksa not dusuyoruz.
     */
    fun onBoot() {
        val elapsed = SystemClock.elapsedRealtime()
        val gecerli = ClockMath.anchorValid(readAnchor(), elapsed, bootCount())
        if (gecerli) {
            Log.i(TAG, "acilis: bu acilista alinmis gecerli capa var, korunuyor")
            return
        }
        prefs.edit().putBoolean(K_INVALIDATED, true).putString(K_NOTE, "acilis").apply()
    }

    /**
     * SISTEM SAATINI ONAR.
     *
     * ClockManager'dan gecen her damga dogrudur, ama gecmeyen HER SEY sistem saatini
     * kullanir: Room'un kendi alanlari, dosya zaman damgalari, logcat, TLS gecerlilik
     * kontrolu, HTTP onbellek basliklari. Guc kesintisinden sonra cihaz 1970'te
     * kalirsa bunlarin hepsi anlamsizlasir ve saha teshisi imkansiz hale gelir.
     *
     * Cihaz sahibi oldugumuz icin sistem saatini yazma hakkimiz var (API 28+).
     * Yalnizca GUVENILIR (imzali) capayla ve ancak fark buyukse yaziyoruz.
     */
    fun repairSystemClock(setTime: (Long) -> Boolean) {
        val s = snapshot()
        if (!s.trusted) return
        val fark = kotlin.math.abs(System.currentTimeMillis() - s.nowMs)
        if (fark < SISTEM_SAATI_TOLERANS_MS) return
        if (setTime(s.nowMs)) {
            Log.i(TAG, "sistem saati onarildi (fark ${fark / 1000} sn)")
        }
    }

    val note: String get() = prefs.getString(K_NOTE, "") ?: ""

    private fun not(mesaj: String) {
        prefs.edit().putString(K_NOTE, mesaj).apply()
    }

    private fun readAnchor(): ClockMath.Anchor? {
        val epoch = prefs.getLong(K_ANCHOR_EPOCH, 0L)
        val elapsed = prefs.getLong(K_ANCHOR_ELAPSED, -1L)
        if (epoch <= 0L || elapsed < 0L) return null
        return ClockMath.Anchor(
            epochMs = epoch,
            elapsedMs = elapsed,
            bootCount = prefs.getInt(K_ANCHOR_BOOT, ClockMath.BOOT_UNKNOWN),
            // Eski kurulumlarda bu alan yok; imzali varsaymak GUVENSIZ olurdu.
            signed = prefs.getBoolean(K_ANCHOR_SIGNED, false)
        )
    }

    private companion object {
        const val TAG = "ClockManager"
        const val K_ANCHOR_EPOCH = "anchorEpoch"
        const val K_ANCHOR_ELAPSED = "anchorElapsed"
        const val K_ANCHOR_BOOT = "anchorBoot"
        const val K_ANCHOR_SIGNED = "anchorSigned"
        /**
         * Son IMZALI zaman. "Geriye gidemez" mandalinin tek kaynagi.
         * Yeni anahtar adi bilincli: eski `lastKnown` degeri imzasiz kaynaklarla
         * kirlenmis olabilir ve onu tasimak zehirlenmeyi surumle birlikte tasirdi.
         */
        const val K_LAST_KNOWN_SIGNED = "lastKnownSigned"
        const val K_INVALIDATED = "invalidated"
        const val K_NOTE = "note"
        /** Sistem saatini bu farkin altinda kurcalamiyoruz. */
        const val SISTEM_SAATI_TOLERANS_MS = 60_000L
    }
}
