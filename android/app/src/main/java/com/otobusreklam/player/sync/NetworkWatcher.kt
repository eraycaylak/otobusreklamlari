package com.otobusreklam.player.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log

/**
 * Senkronun TETIKLEYICISI.
 *
 * Neden WorkManager'in periyodik isi degil: PeriodicWorkRequest'in minimum
 * periyodu 15 DAKIKA. Otobus noktada 3 dakika duruyor - periyodik is bu pencereyi
 * rahatlikla kacirir. NetworkCallback ise WiFi ilisklendirildigi ANDA tetikleniyor.
 *
 * NET_CAPABILITY_INTERNET ARANMIYOR (bilincli):
 * Noktadaki ag PtMP linki koptugunda "internetsiz" gorunur, ama onbellek kutusu
 * hala yerel icerigi servis ediyordur. Internet sartI koysaydik tam da en cok
 * ihtiyac duyulan anda senkron hic baslamazdi.
 */
class NetworkWatcher(private val context: Context) {

    private val cm: ConnectivityManager? =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    /** Senkronu baslattigimiz ag - yalnizca BU agin kopmasi onu durdurmali. */
    @Volatile private var aktifAg: Network? = null

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            Log.i(TAG, "WiFi geldi -> senkron hemen basliyor")
            aktifAg = network
            SyncService.startNow(context, network)
        }

        override fun onLost(network: Network) {
            /*
             * SADECE KENDI AGIMIZ.
             *
             * Cihazda birden fazla WiFi profili olabilir (servis sirasinda eklenen bir
             * ag, komsu bir AP). Onceden HERHANGI bir agin kopmasi calisan senkronu
             * olduruyordu: otobus noktada dururken, alakasiz bir agin dusmesi yuzunden
             * pencere yariliyordu. Ustelik ters durum da vardi - senkron olmus bir aga
             * bagli kalmaya devam ediyordu.
             */
            if (network != aktifAg) {
                Log.i(TAG, "baska bir ag koptu, calisan senkron etkilenmiyor")
                return
            }
            // Pencere bitti. Yarim dosya SILINMEZ, ilerleme diskte kalir.
            Log.i(TAG, "WiFi gitti -> senkron duruyor (ilerleme korunuyor)")
            aktifAg = null
            SyncService.stop(context)
        }
    }

    fun start() {
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        runCatching { cm?.registerNetworkCallback(request, callback) }
            .onFailure { Log.e(TAG, "ag dinleyicisi kaydedilemedi", it) }
    }

    /** Uygulama acilirken WiFi zaten baglIysa pencereyi kacirmayalim. */
    fun triggerIfAlreadyConnected() {
        val manager = cm ?: return
        val active = manager.activeNetwork ?: return
        val caps = manager.getNetworkCapabilities(active) ?: return
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            aktifAg = active
            SyncService.startNow(context, active)
        }
    }

    private companion object { const val TAG = "NetworkWatcher" }
}
