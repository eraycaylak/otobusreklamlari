package com.otobusreklam.player.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import com.otobusreklam.player.Config

/** Sessiz kurulumun sonucu. Basarisizlik panelde gorunsun diye lastError'a yazilir. */
class InstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
        val version = intent.getIntExtra(EXTRA_VERSION, -1)
        val config = Config(context)

        when (status) {
            PackageInstaller.STATUS_SUCCESS -> {
                Log.i(TAG, "kurulum basarili (surum $version)")
                // Yeni surum icin acilis saglik sayacini sifirdan baslat
                config.startupFailures = 0
            }
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // Cihaz sahibi isek buraya DUSMEMELIYIZ. Dustuyse DO yetkisi yok demektir.
                Log.e(TAG, "kurulum kullanici onayi istedi -> cihaz sahibi yetkisi YOK")
                config.lastError = "kurulum kullanici onayi istedi (device owner degil)"
            }
            else -> {
                Log.e(TAG, "kurulum basarisiz ($status): $message")
                config.lastError = "kurulum basarisiz ($status): $message"
            }
        }
    }

    companion object {
        private const val TAG = "InstallResult"
        const val EXTRA_VERSION = "versionCode"
    }
}
