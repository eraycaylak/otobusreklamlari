package com.otobusreklam.player.net

import android.net.Network
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * HTTP istemcisi.
 *
 * ONEMLI: istemci BELIRLI BIR AGA baglanir (network.socketFactory).
 * Sebep: cihazda birden fazla ag olabilir (ornegin eski bir WiFi profili hala
 * "baglanti" sayiliyor olabilir). Soketi noktanin agina sabitlemezsek istekler
 * yanlis arabirimden cikip pencereyi bos harcayabilir.
 */
object Http {

    fun client(
        network: Network?,
        connectTimeoutMs: Long,
        readTimeoutMs: Long
    ): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
            .writeTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)   // parca indirmeleri kendi zaman asimini yonetir
            .retryOnConnectionFailure(true)
            // Paylasimli AP'de tek cihazin cok baglanti acmasi TOPLAM verimi dusurur.
            .connectionPool(okhttp3.ConnectionPool(4, 30L, TimeUnit.SECONDS))

        if (network != null) {
            builder.socketFactory(network.socketFactory)
        }
        return builder.build()
    }

    fun authed(url: String, token: String): Request.Builder =
        Request.Builder().url(url).header("Authorization", "Bearer $token")

    /** Range istegi: kaldigi bayttan devam. Sunucu 206 Partial Content donmeli. */
    fun range(url: String, offset: Long, len: Int): Request =
        Request.Builder()
            .url(url)
            .header("Range", "bytes=$offset-${offset + len - 1}")
            .build()
}
