package com.otobusreklam.player.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.otobusreklam.player.Config
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

        // Yeniden baslatma sayaci: guc problemini panelde gormenin en hizli yolu.
        // Sayaci SADECE burada artiriyoruz - watchdog'un kendi reboot'u da buraya
        // dusecegi icin iki yerde artirmak mukerrer sayima yol acardi.
        config.rebootCount = config.rebootCount + 1

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

        val admin = DeviceAdmin(context)
        admin.applyPolicies()
        admin.ensureWifi(config.ssid, config.psk)
        // Guvenilir capa varsa sistem saatini de onar: ClockManager'dan GECMEYEN
        // tum damgalar (Room, dosya tarihleri, logcat, TLS) boylece anlamli olur.
        ClockManager(context).repairSystemClock(admin::setTime)

        SyncWorker.schedule(context)
    }

    private companion object { const val TAG = "BootReceiver" }
}
