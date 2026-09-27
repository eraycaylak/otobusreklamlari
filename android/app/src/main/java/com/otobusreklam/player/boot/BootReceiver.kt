package com.otobusreklam.player.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import com.otobusreklam.player.Config
import com.otobusreklam.player.admin.AcilisIsleri
import com.otobusreklam.player.admin.DeviceAdmin
import com.otobusreklam.player.clock.ClockManager
import com.otobusreklam.player.sync.SyncWorker

/**
 * Acilista calisir.
 *
 * OynatIcIyI burada ACMIYORUZ: uygulama HOME oldugu icin Android onu zaten
 * kendisi baslatir. Burada yapilan is durum tazeleme.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Log.i(TAG, "acilis: ${intent.action}")

        val config = Config(context)

        rebootSayacinaIsle(context, config)

        /*
         * Acilis saglik sayacini SIFIRLA.
         *
         * Sayac "yeni surum acilista cokuyor mu?" sorusunu olcmek icin var: oynatici
         * acilirken artiyor, 2 dakika saglikli calisinca sifirlaniyor.
         *
         * Ama otobusun kontagi 2 dakika icinde birkac kez kapanip acilirsa (manevra,
         * marş, gevsek baglanti) sayac artar ve sifirlanmaya firsat bulamaz. Ucuncu
         * seferde uygulama kendini BOZUK sanip saglam surumu geri kurardi - oysa
         * ortada yazilim hatasi yok, sadece guc titremesi var.
         *
         * Yeniden baslatma bir COKME DEGILDIR; burada sifirliyoruz. Boylece sayac
         * yalnizca gercek cokme dongusunu (acilir-coker-HOME yeniden baslatir,
         * cihaz hic yeniden baslamadan) olcer.
         */
        config.startupFailures = 0

        /*
         * Saat capasi: KOSULSUZ SILINMIYOR.
         *
         * BOOT_COMPLETED yayini HOME aktivitesinden SONRA gelir. Yani bu alici
         * calistiginda oynatici acilmis, WiFi gelmis ve senkron TAZE bir capa almis
         * olabilir. Eskiden burada kosulsuz invalidate ediliyordu ve o taze capa cope
         * atiliyordu: cihaz, senkronu basariyla tamamlamisken saatini "supheli"
         * sayip yalnizca evergreen oynamaya baslardi.
         *
         * onBoot() artik capanin BU acilista alinip alinmadigina bakiyor
         * (Settings.Global.BOOT_COUNT); yeniden baslatma zaten oradan tespit ediliyor.
         */
        ClockManager(context).onBoot()

        /*
         * POLITIKA VE WIFI ISLERI BURADA TEKRAR EDILMIYOR.
         *
         * App.onCreate bu surec icin AcilisIsleri.birKez'i zaten cagirdi (bu alici
         * sureci baslattiysa da oyle). Eskiden burada applyPolicies() + ensureWifi()
         * ayni ana is parcaciginda IKINCI kez kosuyordu - hem de acilis
         * contention'inin en yogun aninda, PlayerActivity.onCreate'i bekleterek.
         * birKez cagrisi surec basina tek is garantisi verir; burada cagirmak yalnizca
         * "acilis isi baslatildi" garantisidir.
         */
        AcilisIsleri.birKez(context)

        // Guvenilir capa varsa sistem saatini de onar: ClockManager'dan GECMEYEN
        // tum damgalar (Room, dosya tarihleri, logcat, TLS) boylece anlamli olur.
        // Bu ucuz bir DPM cagrisi (bekleme yok), ana is parcaciginda kalabilir.
        ClockManager(context).repairSystemClock(DeviceAdmin(context)::setTime)

        SyncWorker.schedule(context)
    }

    /**
     * YENIDEN BASLATMA SAYACI - "GUC PROBLEMI" SINYALI GERCEKTEN CALISSIN.
     *
     * Eski hali her acilista `rebootCount++` yapiyordu ve sayaci sifirlayan hicbir kod
     * yoktu. Uc ayri sebeple panelde kullanilamaz bir sayiydi:
     *
     *  1. PLANLI REBOOT SAYILIYORDU. Her gece 03:25-03:35 arasinda kasitli bir reboot
     *     var, yani sayac gunde en az 1 artiyor. 200 gun calisan SAGLIKLI bir otobus
     *     "206" derken gunde 6 kez gucu kesilen komsusu "200" diyebiliyordu - iki
     *     deger ayirt edilemiyordu.
     *  2. QUICKBOOT CIFT SAYIYORDU. Manifest ayni alicida BOOT_COMPLETED ve
     *     QUICKBOOT_POWERON dinliyor; ikisini de yayinlayan ucuz ROM'larda tek acilis
     *     iki kez sayiliyordu (ve startupFailures iki kez sifirlaniyordu).
     *  3. KUMULATIF SAYI ALARM URETEMEZ. "206" ile "200" arasindaki fark, cihazlarin
     *     sahada gecirdigi gun sayisi farkindan ayirt edilemez.
     *
     * Cozum: BOOT_COUNT ile de-dup (acilis basina bir kez artar - tek dogru anahtar),
     * planli reboot'un isaretini atlama, ve GUNLUK pencere sayaci. Panel asil alarmi
     * `rebootsSince24h` ve `uptimeMs` uzerine kuruyor.
     */
    private fun rebootSayacinaIsle(context: Context, config: Config) {
        val bootCount = runCatching {
            Settings.Global.getInt(context.contentResolver, "boot_count", -1)
        }.getOrDefault(-1)

        /*
         * BOOT_COUNT okunamiyorsa (bazi ROM'larda alan yok) geri dusme: acilistan bu
         * yana 60 saniyeden az gectiyse ve zaten saymissak tekrar saymayiz.
         * Kesin degil ama cift sayimin BUYUK kismini engeller.
         */
        val anahtar = if (bootCount >= 0) bootCount else -(SystemClock.elapsedRealtime() / 60_000L).toInt() - 1
        if (config.lastCountedBoot == anahtar) {
            Log.i(TAG, "bu acilis zaten sayildi (anahtar=$anahtar) - QUICKBOOT cift yayini")
            return
        }
        config.lastCountedBoot = anahtar

        if (config.plannedReboot) {
            // Gece planli reboot: sayilmaz, isaret temizlenir.
            config.plannedReboot = false
            Log.i(TAG, "planli gece reboot'u - sayaca islenmedi")
            return
        }

        config.rebootCount = config.rebootCount + 1

        // Gunluk pencere: gun degistiyse sifirdan basla.
        val gun = java.time.LocalDate.now().toString()
        if (config.rebootDay != gun) {
            config.rebootDay = gun
            config.rebootsToday = 0
        }
        config.rebootsToday = config.rebootsToday + 1
        Log.w(TAG, "BEKLENMEYEN yeniden baslatma: bugun ${config.rebootsToday}, toplam ${config.rebootCount}")
    }

    private companion object { const val TAG = "BootReceiver" }
}
