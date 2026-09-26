package com.otobusreklam.player.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import com.otobusreklam.player.Config
import com.otobusreklam.player.admin.DeviceAdmin
import com.otobusreklam.player.manifest.AppUpdate
import com.otobusreklam.player.store.FileStore
import java.io.File

/**
 * Uygulamanin kendini sessizce guncellemesi.
 *
 * Bu ozellik OPSIYONEL DEGIL: 4G yok, cihazlar noktaya gelmeden erisilemiyor.
 * Sessiz guncelleme olmazsa her kucuk duzeltme icin 50 otobuse fiziksel olarak
 * gidilmesi gerekir - ki projenin cozmeye calistigi problem tam olarak buydu.
 *
 * Cihaz sahibi (device owner) oldugumuz icin PackageInstaller onay ekrani ACMAZ.
 *
 * DIKKAT: Tum surumler AYNI imza anahtariyla imzalanmali, yoksa guncelleme reddedilir.
 */
class Updater(
    private val context: Context,
    private val config: Config,
    private val store: FileStore
) {

    fun install(update: AppUpdate) {
        val admin = DeviceAdmin(context)
        if (!admin.hasInstallPermission) {
            Log.e(TAG, "sessiz kurulum yetkisi yok (cihaz sahibi degil) - guncelleme atlandi")
            config.lastError = "sessiz kurulum yetkisi yok"
            return
        }

        val apk = store.contentFile(update.sha256, update.remotePath)
        if (!apk.exists() || apk.length() != update.size) {
            Log.e(TAG, "APK dosyasi eksik/bozuk, kurulum iptal")
            return
        }

        // GERI DONUS ICIN: su an CALISAN surumun APK'sini sakla.
        // Yeni surum acilista cokerse bu dosya geri kurulur.
        saveKnownGood()

        try {
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(context.packageName)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    // INSTALL_REASON_DEVICE_OWNER @SystemApi'dir, public SDK'da yoktur.
                    // Public karsiligi POLICY: "kurulum bir cihaz politikasi geregi yapiliyor".
                    setInstallReason(android.content.pm.PackageManager.INSTALL_REASON_POLICY)
                }
            }
            val sessionId = installer.createSession(params)

            installer.openSession(sessionId).use { session ->
                session.openWrite("apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out, 1 shl 16) }
                    session.fsync(out)
                }

                val intent = Intent(context, InstallResultReceiver::class.java)
                    .putExtra(InstallResultReceiver.EXTRA_VERSION, update.versionCode)
                val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                else
                    PendingIntent.FLAG_UPDATE_CURRENT

                val pending = PendingIntent.getBroadcast(context, sessionId, intent, flags)
                session.commit(pending.intentSender)
            }
            Log.i(TAG, "kurulum baslatildi: surum ${update.versionCode}")
        } catch (e: Exception) {
            Log.e(TAG, "kurulum basarisiz", e)
            config.lastError = "kurulum: ${e.message}"
        }
    }

    /** Calisan APK'yi geri donus icin kopyala. */
    private fun saveKnownGood() {
        runCatching {
            val running = File(context.applicationInfo.sourceDir)
            val backup = File(store.apkDir, "known-good.apk")
            if (running.exists() && (!backup.exists() || backup.length() != running.length())) {
                running.copyTo(backup, overwrite = true)
                config.knownGoodApk = backup.absolutePath
            }
        }.onFailure { Log.w(TAG, "geri donus kopyasi alinamadi: ${it.message}") }
    }

    /**
     * Acilis saglik kontrolu ve geri donus.
     *
     * DURUST SINIR: uygulama process baslamadan cokuyorsa bu kod hic calismaz.
     * Gercek koruma KADEMELI YAYIMDIR (once 2 cihaz, 48 saat sonra hepsi).
     * Buradaki geri donus sadece "acildi ama saglikli calisamadi" durumunu yakalar.
     */
    fun checkStartupHealth(): Boolean {
        val failures = config.startupFailures
        if (failures < ROLLBACK_THRESHOLD) return false

        val backup = config.knownGoodApk.takeIf { it.isNotBlank() }?.let(::File)
        if (backup == null || !backup.exists()) {
            Log.e(TAG, "acilis $failures kez basarisiz ama geri donulecek APK yok")
            return false
        }
        Log.e(TAG, "acilis $failures kez basarisiz -> onceki surume donuluyor")
        config.lastError = "geri donus: $failures basarisiz acilis"
        config.startupFailures = 0

        return runCatching {
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                .apply { setAppPackageName(context.packageName) }
            val sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                session.openWrite("apk", 0, backup.length()).use { out ->
                    backup.inputStream().use { it.copyTo(out, 1 shl 16) }
                    session.fsync(out)
                }
                val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                else PendingIntent.FLAG_UPDATE_CURRENT
                val pending = PendingIntent.getBroadcast(
                    context, sessionId, Intent(context, InstallResultReceiver::class.java), flags
                )
                session.commit(pending.intentSender)
            }
            true
        }.getOrElse { false }
    }

    private companion object {
        const val TAG = "Updater"
        const val ROLLBACK_THRESHOLD = 3
    }
}
