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
            config.lastInstallError = "sessiz kurulum yetkisi yok (cihaz sahibi degil)"
            return
        }

        /*
         * AYNI BOZUK APK'YI SONSUZA KADAR DENEMEYELIM.
         *
         * Kurulum basarisizliklarinin cogu KALICIDIR: imza uyusmazligi, bozuk APK,
         * surum dusurme, yetersiz alan. Onceden basarisiz surum hic kaydedilmiyordu;
         * ayni APK her pencerede yeniden yaziliyor ve commit ediliyordu. Her deneme tam
         * bir APK kopyasini /data'ya yazar ve 3 dakikalik pencereden saniyeler yer -
         * hem de hicbir zaman basarili olmayacak bir is icin.
         *
         * Yeni bir surum yayinlandiginda sayac kendiliginden sifirlanir (surum degisti),
         * yani fix-forward yolu hic etkilenmiyor.
         */
        if (config.failedVersion == update.versionCode && config.failedVersionTries >= MAX_TRIES) {
            Log.e(TAG, "surum ${update.versionCode} $MAX_TRIES kez kurulamadi - yeni surum beklenecek")
            config.lastInstallError =
                "surum ${update.versionCode} $MAX_TRIES kez kurulamadi - DUZELTILMIS BIR SURUM yayinlayin"
            return
        }

        val apk = store.contentFile(update.sha256, update.remotePath)
        if (!apk.exists() || apk.length() != update.size) {
            Log.e(TAG, "APK dosyasi eksik/bozuk, kurulum iptal")
            // Onceden bu dalda hicbir sey yazilmiyordu: kurulumun HIC DENENMEDIGININ
            // tek izi logcat'ti, yani sahada gorunmuyordu.
            config.lastInstallError =
                "APK eksik/bozuk (${apk.length()}/${update.size} bayt) - indirme tamamlanmamis"
            return
        }

        // Surec olumu/guc kesintisi ile kalmis oturumlar her biri TAM BIR APK kopyasi
        // tutar; once onlari birak (bkz. birakOksuzOturumlari).
        birakOksuzOturumlari()

        /*
         * DISK: GUNCELLEME AYNI ANDA UC KOPYA ISTER.
         *
         * 1) indirilen APK            app/<sha>.apk
         * 2) geri donus kopyasi       app/known-good.apk
         * 3) PackageInstaller oturumu sistemin kendi hazirlama alani
         * Uzerine kurulum sirasinda dex/oat uretimi de yer ister.
         *
         * Downloader'in 50 MB emniyet payi indirmeyi gecirir ama bu ucluyu KARSILAMAZ:
         * oturum yazimi ENOSPC ile duser, hata "kurulum basarisiz" olarak gorunur,
         * sebebi anlasilmaz ve her pencerede tekrarlanir - guncelleme KALICI olarak
         * tikanir. Onceden bunu hicbir yer kontrol etmiyordu.
         */
        val gereken = update.size * 2 + KURULUM_PAYI
        val bos = store.freeBytes()
        if (bos in 0 until gereken) {
            Log.e(TAG, "guncelleme icin yer yok: ${bos / 1_000_000} MB bos, ${gereken / 1_000_000} MB gerekiyor")
            config.lastInstallError =
                "guncelleme icin disk yetersiz: ${bos / 1_000_000} MB bos, ${gereken / 1_000_000} MB gerekiyor"
            return
        }

        // GERI DONUS ICIN: su an calisan surumun APK'sini sakla (yalnizca o surum
        // SAGLIKLI ise - bkz. saveKnownGood).
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
                // Sisteme beklenen boyutu bildir: yer yoksa YAZMAYA BASLAMADAN once
                // hata verir, yarim bir oturum birakmaz.
                setSize(update.size)
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
            config.lastInstallError = "kurulum: ${e.message}"
        } finally {
            // Commit edilmeyen oturum kendiliginden GITMEZ; sistemde birikir ve
            // uygulama basina oturum siniri dolunca sonraki guncellemeler de
            // basarisiz olur. Basarisizlikta acikca birakiyoruz.
            if (sessionId >= 0) {
                // PackageInstaller.abandonSession(id) - Session.abandon() ise oturum
                // nesnesi uzerindedir; installer uzerinde "abandon" diye bir metot yok.
                runCatching { installer.abandonSession(sessionId) }
                    .onFailure { Log.w(TAG, "oturum birakilamadi: ${it.message}") }
            }
        }
    }

    /**
     * OKSUZ OTURUMLARI BIRAK.
     *
     * finally blogu yalnizca SUREC YASADIGI SURECE calisir. Bu cihazlarda kontak
     * kapanmasi normal hayat: guc tam kurulum sirasinda giderse oturum commit
     * edilmemis olarak sistemde kalir ve her biri TAM BIR APK KOPYASINI /data'da
     * tutar. Birikince iki sey birden olur - disk dolar ve uygulama basina oturum
     * siniri asilir, yani sonraki TUM guncellemeler basarisiz olur. Hicbir yol
     * bunlari temizlemiyordu.
     *
     * Kendi oturumlarimizi (mySessions) her kurulum oncesi tariyoruz.
     */
    private fun birakOksuzOturumlari() {
        val installer = context.packageManager.packageInstaller
        runCatching {
            for (oturum in installer.mySessions) {
                runCatching { installer.abandonSession(oturum.sessionId) }
                    .onSuccess { Log.w(TAG, "oksuz kurulum oturumu birakildi: ${oturum.sessionId}") }
            }
        }.onFailure { Log.w(TAG, "oturumlar taranamadi: ${it.message}") }
    }

    /**
     * Calisan APK'yi sakla.
     *
     * NE ICIN DEGIL: otomatik geri donus icin degil. PackageInstaller surum
     * DUSURMEYI reddeder (INSTALL_FAILED_VERSION_DOWNGRADE); bunu asan
     * setRequestDowngrade @SystemApi'dir ve bize acik degildir. Yani "bozuk surumu
     * kendi kendine geri al" diye bir mekanizma stok Android'de KURULAMAZ.
     *
     * NE ICIN: elle kurtarma icin. DURUST SINIR: dosya /data/data altindadir ve
     * release derlemesinde adb shell kullanicisi ORAYI OKUYAMAZ (run-as yalnizca
     * debuggable uygulamalarda calisir). Yani "teknisyen adb ile geri kurar" sozu
     * gercekci DEGIL; gercek kurtarma yolu DUZELTILMIS SURUM YAYINLAMAKTIR
     * (critical=1). Kopya, cihaza root/kurtarma erisimi olan durumlar ve olasi
     * ileriki bir kurtarma araci icin duruyor - maliyeti bir dosya kadar.
     *
     * "CALISTIGI KANITLANMIS" OLMAK ZORUNDA:
     * Onceden kosulsuz kopyalaniyordu, yani BOZUK bir surum calisirken guncelleme
     * denenirse saglam yedegin uzerine bozugu yaziyordu - geri donus kopyasi tam da
     * ihtiyac duyuldugu anda degersiz hale geliyordu. Artik yalnizca calisan surum
     * saglikliysa (guvenli modda degil, acilis hatasi yok) kopyalaniyor.
     */
    private fun saveKnownGood() {
        if (config.safeMode || config.startupFailures > 1) {
            Log.w(TAG, "calisan surum saglikli degil - geri donus kopyasi GUNCELLENMIYOR")
            return
        }
        runCatching {
            val running = File(context.applicationInfo.sourceDir)
            val backup = File(store.apkDir, FileStore.KNOWN_GOOD_APK)
            if (!running.exists()) return@runCatching
            if (backup.exists() && backup.length() == running.length()) return@runCatching

            /*
             * ATOMIK: gecici dosyaya yaz, sonra yerine tasi.
             *
             * copyTo(overwrite = true) once hedefi SIFIRLAR. Guc kesintisi veya ENOSPC
             * tam o anda gelirse gecerli yedek yok olur ve yerinde YARIM bir dosya kalir -
             * yani "yedek var" gorunur, kurtarma aninda ise ise yaramaz. Tam da korumak
             * icin var oldugu seyi bozan bir yol.
             */
            val tmp = File(store.apkDir, FileStore.KNOWN_GOOD_APK + ".tmp")
            runCatching { tmp.delete() }
            running.copyTo(tmp, overwrite = true)
            if (tmp.length() != running.length()) {
                tmp.delete()
                Log.w(TAG, "geri donus kopyasi eksik yazildi, atlandi")
                return@runCatching
            }
            if (!tmp.renameTo(backup)) {
                backup.delete()
                if (!tmp.renameTo(backup)) { tmp.delete(); return@runCatching }
            }
            config.knownGoodApk = backup.absolutePath
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
     *  2. EKRANI AYAKTA TUT  - zorunlu olmayan isleri atla (bkz. asagidaki liste)
     *  3. SENKRONU ACIK TUT  - duzeltilmis surum ancak boyle ulasabilir
     *
     * GUVENLI MODDA ATLANAN ISLER (somut liste - bu yorumun dogru kalmasi icin
     * degistirirken buraya da yazin):
     *  - kanit karesi uretimi   (en agir is: video cozme + bitmap, bkz. SyncService)
     *  - gece kontrollu yeniden baslatma (cokuyor olabilecek bir surumu yeniden
     *    baslatmak durumu iyilestirmez, kotulestirir - bkz. PlayerActivity)
     *  - geri donus kopyasinin guncellenmesi (bozuk surumu yedek yapmayalim)
     * ATLANMAYANLAR ve sebebi: oynatma, oynatma kaydi, senkron, log yukleme,
     * heartbeat ve UYGULAMA GUNCELLEMESI - duzeltilmis surumun gelis yolu budur.
     *
     * @return true ise cihaz GUVENLI MODDA.
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
        /** Ayni surum icin en fazla kac kurulum denemesi. */
        const val MAX_TRIES = 3
        /** Oturum + dex/oat uretimi icin biraktigimiz pay. */
        const val KURULUM_PAYI = 150L * 1024 * 1024
    }
}
