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

    /**
     * Kimlik dogrulamali istek.
     *
     * X-Cihaz BASLIGI - NOKTA ONBELLEGININ ANAHTARI ICIN.
     *
     * Onbellek kutusu manifesti CIHAZ BASINA onbelleklemek zorunda (her cihazin kendi
     * ogeleri ve kendi rollout grubu var). Anahtar eskiden `$http_authorization`
     * iceriyordu; nginx onbellek anahtarini her onbellek dosyasinin BASINA duz metin
     * olarak yazar, yani 50 cihazin TOKENI yol kenarindaki bir kabinede duran kutunun
     * diskinde acik metin birikiyordu. Cihaz kimligi ise sir DEGIL (otobusun ustunde
     * yazili, panelde gorunur): anahtar olarak onu kullanmak ayni bolumlemeyi sir
     * sizdirmadan sagliyor.
     *
     * Sunucu bu basligi tokenin sahibiyle KARSILASTIRIR ve uyusmazsa 400 doner; nginx
     * 400'u onbelleklemedigi icin baska bir cihazin onbellek girdisini zehirlemek
     * mumkun degil.
     */
    fun authed(url: String, token: String, deviceId: String = ""): Request.Builder =
        Request.Builder().url(url)
            .header("Authorization", "Bearer $token")
            .apply { if (deviceId.isNotBlank()) header("X-Cihaz", deviceId) }

    /**
     * Range istegi: kaldigi bayttan devam. Sunucu 206 Partial Content donmeli.
     *
     * TOKEN DA GIDIYOR. Sunucu /app (APK) icin kimlik dogrulamasi ISTIYOR - o dosya
     * PROVISION_SECRET'i gomulu tasiyor ve kimlik dogrulamasiz servis edilmesi,
     * sunucuya erisen herkesin filoya cihaz sokabilmesi demekti. /content icin de
     * varsayilan olarak isteniyor.
     *
     * Onbellek kutusu Authorization'i yukari gecirir ama onbellek ANAHTARINA katmaz;
     * boylece bir otobusun cektigi parca digerlerine de servis edilir.
     */
    fun range(url: String, offset: Long, len: Int, token: String): Request =
        Request.Builder()
            .url(url)
            .header("Range", "bytes=$offset-${offset + len - 1}")
            .apply { if (token.isNotBlank()) header("Authorization", "Bearer $token") }
            .build()
}
