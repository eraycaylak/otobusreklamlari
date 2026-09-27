package com.otobusreklam.player

import android.content.Context
import android.content.SharedPreferences

/**
 * Cihazin kalici ayarlari. Provizyonda bir kez yazilir, sonra sadece okunur
 * (sayaclar ve son hata haric).
 */
class Config(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var deviceId: String
        get() = prefs.getString(K_DEVICE_ID, "") ?: ""
        set(v) = prefs.edit().putString(K_DEVICE_ID, v).apply()

    var token: String
        get() = prefs.getString(K_TOKEN, "") ?: ""
        set(v) = prefs.edit().putString(K_TOKEN, v).apply()

    /** Icerik ve APK'nin indirildigi taban adres - normalde noktadaki onbellek kutusu. */
    var baseUrl: String
        get() = prefs.getString(K_BASE_URL, "") ?: ""
        set(v) = prefs.edit().putString(K_BASE_URL, v.trimEnd('/')).apply()

    /** API (manifest/log/heartbeat) adresi. Bos ise baseUrl kullanilir. */
    var apiUrl: String
        get() = (prefs.getString(K_API_URL, "") ?: "").ifBlank { baseUrl }
        set(v) = prefs.edit().putString(K_API_URL, v.trimEnd('/')).apply()

    var ssid: String
        get() = prefs.getString(K_SSID, "") ?: ""
        set(v) = prefs.edit().putString(K_SSID, v).apply()

    var psk: String
        get() = prefs.getString(K_PSK, "") ?: ""
        set(v) = prefs.edit().putString(K_PSK, v).apply()

    /**
     * Kurulum basina bir kez uretilen kalici kimlik.
     *
     * Oynatma logunun seq sayaci cihaz veritabanindan gelir ve fabrika ayarindan
     * sonra 1'den yeniden baslar. Sunucu sadece seq'e baksaydi bu cihazin tum yeni
     * loglarini "zaten gordum" diye atar, ustelik ack dondurdugu icin cihaz onlari
     * silerdi - yani o otobusun fatura verisi kaybolurdu.
     *
     * Bu deger her yeni kurulumda degistigi icin sunucu sayaci temiz baslatabiliyor.
     */
    val logEpoch: String
        get() {
            prefs.getString(K_LOG_EPOCH, null)?.let { return it }
            val yeni = java.util.UUID.randomUUID().toString()
            // commit(): bu degerin kaybolmasi tekrar elemesini bozar, apply() yetmez.
            prefs.edit().putString(K_LOG_EPOCH, yeni).commit()
            return yeni
        }

    /**
     * Kurulum kimligini YENILE.
     *
     * Veritabani sifirdan olustugunda (fabrika ayari, uygulama verisi temizligi,
     * bozuk dosyanin arsivlenmesi) seq 1'den baslar. Epoch de degismezse sunucu yeni
     * satirlari tekrar sayip atar ve cihaza onlari silmesini soyler. Bu yuzden cagiran
     * yer AppDatabase'in onCreate geri cagrisidir: veritabani kimligiyle BIRLIKTE
     * degismesi gerekir, tek basina SharedPreferences'in ayakta kalmasi yetmez.
     */
    fun newLogEpoch(): String {
        val yeni = java.util.UUID.randomUUID().toString()
        prefs.edit().putString(K_LOG_EPOCH, yeni).commit()
        return yeni
    }

    var provisioned: Boolean
        get() = prefs.getBoolean(K_PROVISIONED, false)
        set(v) = prefs.edit().putBoolean(K_PROVISIONED, v).apply()

    /**
     * Sunucudan gelen saat dilimi (daypart bu dilimde yorumlanir).
     *
     * Bos ise cihazin kendi dilimi kullanilir - bu bir GERI DUSME, tercih degil:
     * stick'in dilimi tipik olarak dokunulmamis ROM varsayilanidir.
     */
    var timezone: String
        get() = prefs.getString(K_TIMEZONE, "") ?: ""
        set(v) = prefs.edit().putString(K_TIMEZONE, v).apply()

    /** Daypart icin kullanilacak saat dilimi; bozuk/eksik degerde cihaz dilimi. */
    val zoneId: java.time.ZoneId
        get() = runCatching { java.time.ZoneId.of(timezone) }
            .getOrElse { java.time.ZoneId.systemDefault() }

    var playlistVersion: Int
        get() = prefs.getInt(K_PLAYLIST_VERSION, 0)
        set(v) = prefs.edit().putInt(K_PLAYLIST_VERSION, v).apply()

    var rebootCount: Int
        get() = prefs.getInt(K_REBOOTS, 0)
        set(v) = prefs.edit().putInt(K_REBOOTS, v).apply()

    var lastError: String
        get() = prefs.getString(K_LAST_ERROR, "") ?: ""
        set(v) = prefs.edit().putString(K_LAST_ERROR, v.take(500)).apply()

    /** Son kanit karesinin gonderildigi an - gunde bir defadan sik gondermemek icin. */
    var lastProofAt: Long
        get() = prefs.getLong(K_LAST_PROOF, 0L)
        set(v) = prefs.edit().putLong(K_LAST_PROOF, v).apply()

    /**
     * En son gece yeniden baslatmasinin yapildigi gun (yyyy-MM-dd).
     *
     * KALICI olmak ZORUNDA: bellekte tutulsaydi cihaz yeniden baslar, acilis ~1 dakika
     * surer ve oynatici hala 03:25-03:35 penceresindeyken tekrar yeniden baslatirdi.
     * Sonuc: pencere kapanana kadar suren bir yeniden baslatma dongusu.
     */
    /**
     * Guvenli mod: art arda basarisiz acilislardan sonra aciliyor.
     * Yalnizca OYNATMA ve SENKRON calisir; zorunlu olmayan isler atlanir.
     * Duzeltilmis surum indiginde kendiliginden kapanir.
     */
    var safeMode: Boolean
        get() = prefs.getBoolean(K_SAFE_MODE, false)
        set(v) = prefs.edit().putBoolean(K_SAFE_MODE, v).apply()

    var lastRebootDay: String
        get() = prefs.getString(K_LAST_REBOOT_DAY, "") ?: ""
        set(v) {
            // commit(): yeniden baslatma hemen ardindan geliyor, apply() diske
            // yazmaya yetismeyebilir ve donguyu tam da onlemek istedigimiz yerde acar.
            prefs.edit().putString(K_LAST_REBOOT_DAY, v).commit()
        }

    var lastSyncAt: Long
        get() = prefs.getLong(K_LAST_SYNC, 0L)
        set(v) = prefs.edit().putLong(K_LAST_SYNC, v).apply()

    /**
     * Yeni surum saglik sayaci.
     * Guncelleme sonrasi uygulama acilista cokerse bu sayac artar; esik asilinca
     * saklanan onceki APK geri kurulur.
     */
    /**
     * Onceki acilisin MONOTONIK zamani (SystemClock.elapsedRealtime).
     *
     * Acilis saglik sayacini dogru degerlendirmek icin: iki acilis arasi sure uzunsa
     * onceki calisma saglikliydi ve sayac sifirlanir. Sistem saati DEGIL kullaniliyor;
     * bu cihazlarda sistem saati guvenilmez ve negatif fark (yeniden baslatma) da
     * dogru bicimde "sifirla" anlamina gelir.
     */
    var lastStartElapsed: Long
        get() = prefs.getLong(K_LAST_START_ELAPSED, 0L)
        set(v) {
            // commit(): hemen ardindan cokebiliriz ve bu deger tam da o durumu olcuyor.
            prefs.edit().putLong(K_LAST_START_ELAPSED, v).commit()
        }

    var startupFailures: Int
        get() = prefs.getInt(K_STARTUP_FAILURES, 0)
        set(v) = prefs.edit().putInt(K_STARTUP_FAILURES, v).apply()

    /**
     * KURULUM HATASI - lastError'dan AYRI alan.
     *
     * Kurulum sonucu ASENKRON gelir (PackageInstaller yayini). lastError'a yazsaydi
     * bir sonraki senkron penceresinin basindaki temizlik onu siler ve operator
     * "guncelleme neden gelmedi?" sorusunu cevaplayamazdi - hata hicbir yerde
     * gorunmezdi. Bu alan yalnizca BASARILI bir kurulumda temizlenir.
     */
    var lastInstallError: String
        get() = prefs.getString(K_INSTALL_ERROR, "") ?: ""
        set(v) = prefs.edit().putString(K_INSTALL_ERROR, v.take(300)).apply()

    /**
     * Kurulumu basarisiz olan surum ve kac kez denendigi.
     *
     * Onceden basarisiz surum HIC kaydedilmiyordu: ayni APK her pencerede yeniden
     * yaziliyor ve commit ediliyordu. Her deneme tam bir APK kopyasini /data'ya
     * yaziyor ve pencereden saniyeler yiyor - hem de hicbir zaman basarili olmayacak
     * bir is icin (bozuk APK, imza uyusmazligi, yetersiz alan kalicidir).
     */
    var failedVersion: Int
        get() = prefs.getInt(K_FAILED_VERSION, 0)
        set(v) = prefs.edit().putInt(K_FAILED_VERSION, v).apply()

    var failedVersionTries: Int
        get() = prefs.getInt(K_FAILED_TRIES, 0)
        set(v) = prefs.edit().putInt(K_FAILED_TRIES, v).apply()

    /** Geri donus icin saklanan, calistigi kanitlanmis APK yolu. */
    var knownGoodApk: String
        get() = prefs.getString(K_GOOD_APK, "") ?: ""
        set(v) = prefs.edit().putString(K_GOOD_APK, v).apply()

    val configured: Boolean
        get() = provisioned && deviceId.isNotBlank() && token.isNotBlank() && baseUrl.isNotBlank()

    fun provision(deviceId: String, token: String, baseUrl: String, apiUrl: String, ssid: String, psk: String) {
        prefs.edit()
            .putString(K_DEVICE_ID, deviceId)
            .putString(K_TOKEN, token)
            .putString(K_BASE_URL, baseUrl.trimEnd('/'))
            .putString(K_API_URL, apiUrl.trimEnd('/'))
            .putString(K_SSID, ssid)
            .putString(K_PSK, psk)
            .putBoolean(K_PROVISIONED, true)
            .apply()
    }

    private companion object {
        const val PREFS = "reklam_config"
        const val K_DEVICE_ID = "deviceId"
        const val K_TOKEN = "token"
        const val K_BASE_URL = "baseUrl"
        const val K_API_URL = "apiUrl"
        const val K_SSID = "ssid"
        const val K_PSK = "psk"
        const val K_PROVISIONED = "provisioned"
        const val K_PLAYLIST_VERSION = "playlistVersion"
        const val K_TIMEZONE = "timezone"
        const val K_REBOOTS = "reboots"
        const val K_LAST_ERROR = "lastError"
        const val K_LAST_SYNC = "lastSyncAt"
        const val K_LAST_PROOF = "lastProofAt"
        const val K_LOG_EPOCH = "logEpoch"
        const val K_LAST_REBOOT_DAY = "lastRebootDay"
        const val K_SAFE_MODE = "safeMode"
        const val K_STARTUP_FAILURES = "startupFailures"
        const val K_LAST_START_ELAPSED = "lastStartElapsed"
        const val K_GOOD_APK = "knownGoodApk"
        const val K_INSTALL_ERROR = "lastInstallError"
        const val K_FAILED_VERSION = "failedVersion"
        const val K_FAILED_TRIES = "failedVersionTries"
    }
}
