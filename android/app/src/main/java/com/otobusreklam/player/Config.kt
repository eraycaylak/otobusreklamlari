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

    var provisioned: Boolean
        get() = prefs.getBoolean(K_PROVISIONED, false)
        set(v) = prefs.edit().putBoolean(K_PROVISIONED, v).apply()

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

    var lastSyncAt: Long
        get() = prefs.getLong(K_LAST_SYNC, 0L)
        set(v) = prefs.edit().putLong(K_LAST_SYNC, v).apply()

    /**
     * Yeni surum saglik sayaci.
     * Guncelleme sonrasi uygulama acilista cokerse bu sayac artar; esik asilinca
     * saklanan onceki APK geri kurulur.
     */
    var startupFailures: Int
        get() = prefs.getInt(K_STARTUP_FAILURES, 0)
        set(v) = prefs.edit().putInt(K_STARTUP_FAILURES, v).apply()

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
        const val K_REBOOTS = "reboots"
        const val K_LAST_ERROR = "lastError"
        const val K_LAST_SYNC = "lastSyncAt"
        const val K_LAST_PROOF = "lastProofAt"
        const val K_STARTUP_FAILURES = "startupFailures"
        const val K_GOOD_APK = "knownGoodApk"
    }
}
