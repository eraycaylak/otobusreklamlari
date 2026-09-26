package com.otobusreklam.player

import android.app.Application
import android.util.Log
import com.otobusreklam.player.admin.DeviceAdmin
import com.otobusreklam.player.sync.NetworkWatcher
import com.otobusreklam.player.sync.SyncWorker

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        val config = Config(this)
        val admin = DeviceAdmin(this)

        // Cihaz sahibi politikalari her acilista yeniden uygulanir (idempotent).
        admin.applyPolicies()

        if (!config.configured) {
            Log.w(TAG, "cihaz provizyonlanmamis - sadece provizyon yayini bekleniyor")
            return
        }

        // AP profilini garanti altina al: cihaz noktaya girince KENDI baglanmali.
        if (!admin.ensureWifi(config.ssid, config.psk)) {
            Log.w(TAG, "WiFi profili yazilamadi (cihaz sahibi degil olabilir)")
        }

        // ASIL TETIKLEYICI: WiFi gorulur gorulmez senkron.
        NetworkWatcher(this).apply {
            start()
            triggerIfAlreadyConnected()
        }

        // Emniyet kemeri (15 dk) - ana yol degil
        SyncWorker.schedule(this)
    }

    private companion object { const val TAG = "App" }
}
