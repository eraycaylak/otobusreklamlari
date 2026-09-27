package com.otobusreklam.player.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import com.otobusreklam.player.BuildConfig
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

        val installer = context.packageManager.packageInstaller
        var sessionId = -1

        try {
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(context.packageName)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    // INSTALL_REASON_DEVICE_OWNER @SystemApi'dir, public SDK'da yoktur.
                    // Public karsiligi POLICY: "kurulum bir cihaz politikasi geregi yapiliyor".
                    setInstallReason(android.content.pm.PackageManager.INSTALL_REASON_POLICY)
                }
            }
            sessionId = installer.createSession(params)

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
                sessionId = -1   // commit edildi, artik bizim degil
            }
            Log.i(TAG, "kurulum baslatildi: surum ${update.versionCode}")
        } catch (e: Exception) {
            Log.e(TAG, "kurulum basarisiz", e)
            config.lastError = "kurulum: ${e.message}"
        } finally {
            // Commit edilmeyen oturum kendiliginden GITMEZ; sistemde birikir ve
            // uygulama basina oturum siniri dolunca sonraki guncellemeler de
            // basarisiz olur. Basarisizlikta acikca birakiyoruz.
            if (sessionId >= 0) {
                runCatching { installer.abandon(sessionId) }
                    .onFailure { Log.w(TAG, "oturum birakilamadi: ${it.message}") }
            }
        }
    }

    /**
     * Calisan APK'yi sakla.
     *
     * NE ICIN DEGIL: otomatik geri donus icin degil. PackageInstaller surum
     * DUSURMEYI reddeder (INSTALL_FAILED_VERSION_DOWNGRADE); bunu asan
     * setRequestDowngrade @SystemApi'dir ve bize acik degildir. Yani "bozuk surumu
     * kendi kendine geri al" diye bir mekanizma stok Android'de KURULAMAZ.
     *
     * NE ICIN: sahadaki teknisyen icin. Cihaza baglanip
     *     adb install -r -d /data/data/<paket>/files/app/known-good.apk
     * ile (-d = downgrade'e izin ver) saglam surume elle donebilir. Dosyanin
     * cihazda hazir durmasi, otobuse ikinci kez gitmeyi onler.
     */
    private fun saveKnownGood() {
        runCatching {
            val running = File(context.applicationInfo.sourceDir)
            val backup = File(store.apkDir, FileStore.KNOWN_GOOD_APK)
            if (running.exists() && (!backup.exists() || backup.length() != running.length())) {
                running.copyTo(backup, overwrite = true)
                config.knownGoodApk = backup.absolutePath
            }
        }.onFailure { Log.w(TAG, "geri donus kopyasi alinamadi: ${it.message}") }
    }

    /**
     * Acilis saglik kontrolu.
     *
     * Onceden burada saklanan APK yeniden kurulmaya calisiliyordu. O YOL CALISMIYOR:
     * surum dusurme reddedilir, dolayisiyla mekanizma sessizce basarisiz olup
     * "geri donusum var" yanilsamasi yaratiyordu. Calismayan bir emniyet kemeri,
     * hic olmamasindan daha tehlikelidir.
     *
     * Gercekte yapabilecegimiz uc sey var, ucunu de yapiyoruz:
     *  1. MERKEZE HABER VER  - lastError panele dusur, operator fix-forward yayinlasin
     *  2. EKRANI AYAKTA TUT  - zorunlu olmayan alt sistemleri devre disi birak
     *                          (cogu cokme oynatmada degil, cevre islerde olur)
     *  3. SENKRONU ACIK TUT  - duzeltilmis surum ancak boyle ulasabilir
     *
     * @return true ise cihaz GUVENLI MODDA: yalnizca oynatma ve senkron calisir.
     */
    fun startupHealthCheck(): Boolean {
        val failures = config.startupFailures
        if (failures < SAFE_MODE_THRESHOLD) return false

        Log.e(TAG, "acilis $failures kez basarisiz -> GUVENLI MOD")
        config.lastError =
            "GUVENLI MOD: $failures basarisiz acilis. Surum ${BuildConfig.VERSION_CODE} " +
            "bozuk olabilir - duzeltilmis bir surumu critical=1 ile yayinlayin."
        return true
    }

    private companion object {
        const val TAG = "Updater"
        const val SAFE_MODE_THRESHOLD = 3
    }
}
