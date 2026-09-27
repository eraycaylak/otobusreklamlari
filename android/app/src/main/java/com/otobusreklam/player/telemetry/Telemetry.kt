package com.otobusreklam.player.telemetry

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaMetadataRetriever
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import com.otobusreklam.player.BuildConfig
import com.otobusreklam.player.Config
import com.otobusreklam.player.HataKaydi
import com.otobusreklam.player.admin.DeviceAdmin
import com.otobusreklam.player.clock.ClockManager
import com.otobusreklam.player.data.AppDatabase
import com.otobusreklam.player.data.ContentState
import com.otobusreklam.player.manifest.PlayManifest
import com.otobusreklam.player.player.PlaylistBuilder
import com.otobusreklam.player.net.Http
import com.otobusreklam.player.store.FileStore
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.zip.GZIPOutputStream

/**
 * Cihazdan merkeze giden veri: oynatma loglari + saglik bilgisi.
 *
 * Pencerenin SONUNDA calisir - once icerik insin, telemetri artakalan saniyeleri
 * kullansin. Loglar gzip'li NDJSON olarak toplu gider (satir satir gondermek
 * pencereyi bosa harcardi).
 */
class Telemetry(
    private val context: Context,
    private val config: Config,
    private val store: FileStore,
    private val db: AppDatabase,
    private val clock: ClockManager
) {

    /** Cihaz sahibi durumu heartbeat'e girsin diye (ucuz bir DPM sorgusu). */
    private val admin by lazy { DeviceAdmin(context) }

    /**
     * Oynatma loglari.
     * ACK ALINMADAN SATIR SILINMEZ; sunucu tekrarlari (cihaz, seq) ile eler.
     * Boylece pencere ortasinda kopan baglanti log kaybina yol acmaz.
     *
     * ZAMAN BUTCESI VAR - HEARTBEAT'IN ONUNU KESMEMESI ICIN.
     *
     * Dongu eskiden `while (true)` idi ve yalnizca "satir kalmadi" ya da "ack
     * ilerlemiyor" durumunda duruyordu. Uzun sure senkron olamamis bir cihazda birikmis
     * binlerce satir, 3 dakikalik pencerenin TAMAMINI yiyebiliyordu - ve heartbeat
     * ONDAN SONRA gonderildigi icin hic gitmiyordu: yani panelde "en cok sorunlu"
     * cihaz "hic konusmuyor" olarak gorunuyordu, oysa her pencerede konusmaya
     * CALISIYORDU. Tam da teshise en cok ihtiyac duyulan cihazda sinyal kayboluyordu.
     *
     * Butce asilirsa kalan satirlar CIHAZDA KALIR (kaybolmaz, ACK almadilar) ve bir
     * sonraki pencerede devam eder; kalan sayi heartbeat'teki `pendingLogs` ile
     * panele gider.
     */
    suspend fun uploadLogs(client: OkHttpClient, butceMs: Long = LOG_BUTCE_MS) {
        val bitis = android.os.SystemClock.elapsedRealtime() + butceMs
        while (true) {
            if (android.os.SystemClock.elapsedRealtime() > bitis) {
                val kalan = db.playLog().pendingCount()
                Log.w(TAG, "log yukleme butcesi doldu (${butceMs / 1000} sn), $kalan satir sonraki pencereye kaldi")
                return
            }
            val rows = db.playLog().pending(BATCH)
            if (rows.isEmpty()) return

            val epoch = config.logEpoch
            val ndjson = rows.joinToString("\n") { r ->
                JSONObject().apply {
                    put("seq", r.seq)
                    // Kurulum kimligi: fabrika ayarindan sonra seq 1'den baslayinca
                    // sunucunun sayaci sifirlayabilmesi icin (bkz. Config.logEpoch)
                    put("epoch", epoch)
                    put("itemId", r.itemId)
                    put("sha256", r.sha256)
                    put("startedAt", Instant.ofEpochMilli(r.startedAt).toString())
                    put("durationMs", r.durationMs)
                    put("completed", r.completed)
                    put("playlistVersion", r.playlistVersion)
                    put("clockTrusted", r.clockTrusted)
                }.toString()
            }

            val gz = ByteArrayOutputStream().also { out ->
                GZIPOutputStream(out).use { it.write(ndjson.toByteArray(Charsets.UTF_8)) }
            }.toByteArray()

            val request = Http.authed("${config.apiUrl}/api/v1/logs", config.token, config.deviceId)
                .header("Content-Encoding", "gzip")
                .post(gz.toRequestBody("application/x-ndjson".toMediaType()))
                .build()

            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w(TAG, "log yukleme HTTP ${response.code}")
                        return
                    }
                    val ack = JSONObject(response.body?.string().orEmpty()).optLong("ackSeq", -1)
                    if (ack < 0) return

                    /*
                     * ILERLEME KONTROLU.
                     *
                     * markUploaded(ack) yalnizca seq <= ack satirlari isaretler. Sunucu
                     * bir hata sonucu bu partinin EN KUCUK seq'inden dusuk bir ack
                     * donerse hicbir satir isaretlenmez; bir sonraki tur ayni satirlari
                     * ceker ve dongu 3 dakikalik pencereyi tamamen yakar - ustelik
                     * sessizce, cunku HTTP 200 doner.
                     *
                     * Ilerleme yoksa duruyoruz: loglar cihazda kaliyor (kaybolmuyor) ve
                     * durum lastError uzerinden panele dusuyor.
                     */
                    if (ack < rows.first().seq) {
                        Log.e(TAG, "sunucu ilerlemeyen ack dondu (ack=$ack < ilk=${rows.first().seq}), log yukleme durduruldu")
                        config.hataEkle("logack", "log yukleme ilerlemiyor (ack=$ack)")
                        return
                    }

                    /*
                     * ack, GONDERDIGIMIZ PARTININ USTUNE TASAMAZ.
                     *
                     * ackSeq imzasiz bir HTTP govdesinden geliyor. Sinirsiz
                     * uygulandiginda tek bir buyuk deger (sunucu hatasi, bozuk onbellek
                     * yaniti veya kotu niyet) henuz GONDERILMEMIS satirlari da
                     * "yuklendi" yapar; temizlik turu onlari sildigi icin o otobusun
                     * faturasi geri donusu olmayan bicimde kaybolur.
                     */
                    db.playLog().markUploaded(ackSeq = ack, batchMaxSeq = rows.last().seq)
                    if (ack > rows.last().seq) {
                        Log.w(TAG, "sunucu parti disi ack dondu (ack=$ack > son=${rows.last().seq}) - sinirlandi")
                    }
                    Log.i(TAG, "${rows.size} log satiri yuklendi (ack=$ack)")
                }
            } catch (e: Exception) {
                Log.i(TAG, "log yukleme yarida kaldi: ${e.message}")
                return
            }
            if (rows.size < BATCH) return
        }
    }

    /** Panelin "bu otobus ne durumda?" sorusuna cevabi. */
    suspend fun sendHeartbeat(client: OkHttpClient, manifest: PlayManifest, sessionBytes: Long) {
        val contents = db.contents().all()
        val ready = contents.count { it.state == ContentState.READY }
        val bad = contents.count { it.state == ContentState.BAD }

        // Zaman ve guven TEK OKUMADA (bkz. ClockMath.Snapshot).
        val saat = clock.snapshot()

        /*
         * OYNATILABILIR OGE SAYISI, OYNATMA LISTESINI KURAN KODUN KENDISINDEN GELIR.
         *
         * Eskiden burada `db.items().all().count { it.sha256 in hazirShalar }` yaziyordu:
         * yani yalnizca DOSYASI INMIS oge sayisi. Uygunluk (validUntil, validFrom,
         * daypart, saat guveni) HIC uygulanmiyordu - oysa gercek liste PlaylistBuilder'dan
         * cikiyor ve o suzgeci uyguluyor. Iki farkli tanim, tek isim.
         *
         * Somut sonuc: bir otobuste 5 kampanya var, hepsinin dosyasi inmis, hicbiri
         * evergreen degil ve dun hepsinin suresi bitti. PlaylistBuilder BOS liste doner,
         * ekran SIYAH kalir. Heartbeat ise playableItems=5 gonderir; panel
         * (`ekranBos = playableItems === 0`) cihazi YESIL gosterir ve "en yuksek
         * oncelikli alarm" diye tanimlanan sinyal TAM GEREKTIGI ANDA yanmaz.
         * Reklamveren parayi odedi, ekran gunlerce siyah kalir.
         *
         * Cozum, sayiyi ayni yerden uretmek: artik ayrisma MUMKUN DEGIL, cunku tek
         * kaynak var. Listeyi kurmak ucuz (DB okumasi + dosya varlik kontrolu).
         * `reason` de gonderiliyor: "0" tek basina ne yapilacagini soylemiyor.
         */
        val liste = PlaylistBuilder(store, db, clock, config).build()
        val oynatilabilir = liste.entries.map { it.sha256 }.distinct().size

        val body = JSONObject().apply {
            put("epoch", config.logEpoch)
            put("appVersion", BuildConfig.VERSION_CODE)
            put("appVersionName", BuildConfig.VERSION_NAME)
            put("playlistVersion", manifest.playlistVersion)
            put("readyItems", ready)
            put("badItems", bad)
            // Oynatilabilir oge sayisi: 0 ise ekran BOS demektir, panelde kirmizi yansin.
            // DEGER LISTEYI KURAN KODUN KENDISINDEN GELIR (bkz. yukaridaki not).
            put("playableItems", oynatilabilir)
            /*
             * LISTE NEDEN BOYLE - "0" tek basina mudahaleye yol gostermiyor.
             *
             * Ayrica bu alan lastError'dan AYRI: "EKRAN BOS" metni eskiden lastError
             * uzerinden gidiyordu ve runSync her pencerenin BASINDA lastError'u
             * siliyordu, yani sinyal bir yaris kosuluna bagliydi.
             */
            put("playlistReason", liste.reason)
            put("safeMode", config.safeMode)
            put("totalItems", manifest.items.size)
            put("freeBytes", store.freeBytes())
            put("rssi", wifiRssi())
            put("reboots", config.rebootCount)
            /*
             * UPTIME + GUNLUK REBOOT: ASIL "GUC PROBLEMI" SINYALI.
             *
             * Kumulatif `reboots` tek basina alarm uretemez: 200 gun sahada calisan
             * saglikli bir otobus ile gunde 6 kez gucu kesilen komsusu panelde ayni
             * gorunur (planli gece reboot'u da sayiliyordu). Bu iki alan farki
             * dogrudan gosterir - kisa uptime + yuksek gunluk sayi = besleme problemi.
             */
            put("uptimeMs", android.os.SystemClock.elapsedRealtime())
            put("rebootsSince24h", config.rebootsToday)
            /*
             * CIHAZ SAHIBI DURUMU VE POLITIKA HATALARI.
             *
             * Projenin temel varsayimi "device owner'iz": sessiz kurulum, kiosk, WiFi
             * profili ve planli reboot hepsi buna bagli. Buna ragmen bu bilgi hicbir
             * uzaktan kanalda YOKTU - yalnizca cihazin yanina gidip teshis ekranini
             * acarak gorulebiliyordu. Bir ROM degisikligi DPM politikalarini
             * dusurdugunde 50 cihaz kiosk'suz calisiyor ve heartbeat YESIL gonderiyordu.
             */
            put("deviceOwner", admin.isDeviceOwner)
            put("policyErrors", config.policyErrors)
            put("clockTrusted", saat.trusted)
            // Sebep + not: "saat supheli" tek basina hicbir mudahaleye yol gostermiyor.
            put("clockNote", listOf(saat.reason, clock.note).filter { it.isNotBlank() }.joinToString(" / "))
            put("timezone", config.timezone)
            put("sessionBytes", sessionBytes)
            put("pendingLogs", db.playLog().pendingCount())
            put("lastError", config.lastError)
            /*
             * SON ARIZALARIN LISTESI - "son yazan kazanir" problemi icin.
             *
             * lastError tek dizgi oldugu icin ayni pencerede olan birden fazla ariza
             * birbirini eziyordu: disk dolu -> indirme hatasi -> log tasmasi
             * zincirinde panelde yalnizca son gorunuyor, operator ASIL SEBEBI
             * (disk dolu) hic gormuyordu. Yas MONOTONIK saatten: duvar saati
             * guvenilmez oldugu icin "2 dakika once" 1970'te bile dogru kalir.
             */
            val simdiUptime = android.os.SystemClock.elapsedRealtime()
            put("errors", org.json.JSONArray().apply {
                for (n in config.hatalar) {
                    put(JSONObject().apply {
                        put("kod", n.kod)
                        put("metin", n.metin)
                        put("yasSn", HataKaydi.yasSaniye(n, simdiUptime))
                    })
                }
            })
            // Kurulum hatasi AYRI alan: asenkron geldigi icin lastError temizligine
            // yakalaniyordu ve "guncelleme neden gelmedi" sorusu cevapsiz kaliyordu.
            put("lastInstallError", config.lastInstallError)
            put("model", "${Build.MANUFACTURER} ${Build.MODEL}")
            put("androidSdk", Build.VERSION.SDK_INT)
        }

        val request = Http.authed("${config.apiUrl}/api/v1/heartbeat", config.token, config.deviceId)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        runCatching { client.newCall(request).execute().close() }
            .onFailure { Log.i(TAG, "heartbeat gonderilemedi: ${it.message}") }
    }

    /**
     * KANIT KARESI.
     *
     * DURUST SINIR: Normal bir Android uygulamasi ekranin GERCEK goruntusunu
     * sessizce alamaz. MediaProjection kullanici onay diyalogu acar (sahada kimse
     * basmaz), CAPTURE_VIDEO_OUTPUT ise imza seviyesi bir izindir ve uygulamalara
     * verilmez. Cihaz sahibi olmak da bunu degistirmez.
     *
     * Bu yuzden ekran goruntusu yerine OYNATILAN DOSYADAN kare cikarip uzerine
     * cihaz kimligi ve zaman damgasi yaziyoruz.
     *   Kanitladigi   : o icerigin o cihazda o saatte oynatildigi
     *   Kanitlamadigi : TV'nin acik oldugu ve goruntunun ekrana dustugu
     *
     * TV'nin acikligini yazilimdan guvenilir sekilde olcmek mumkun degil (cogu TV
     * kapaliyken de HDMI +5V'u surer). Reklamverene verilen raporda bu sinir acikca
     * belirtilmeli; ekranin gercekten calistigi saha denetimiyle dogrulanir.
     */
    suspend fun sendProofFrame(client: OkHttpClient) {
        val saat = clock.snapshot()
        val now = saat.nowMs
        if (now - config.lastProofAt < PROOF_INTERVAL_MS) return

        /*
         * Kanit karesi yalnizca YAKIN GECMISTEKI bir oynatma icin uretilir.
         *
         * Zaman siniri olmadan, ekran gunlerdir bos olsa bile (tum icerik BAD, disk
         * dolu, liste bos) en son kayit bulunuyor ve uzerine GUNCEL zaman damgasi
         * basiliyordu. Reklamverene "bu icerik su an oynuyor" izlenimi veren bir kanit,
         * kanit olmamasindan daha kotudur: denetimde tum raporun guvenilirligini gotur.
         */
        val last = db.playLog().lastPlayed(since = now - KANIT_TAZELIK_MS) ?: run {
            Log.i(TAG, "son ${KANIT_TAZELIK_MS / 3_600_000} saatte oynatma kaydi yok - kanit karesi uretilmedi")
            return
        }
        val content = db.contents().get(last.sha256) ?: return
        val file = store.contentFile(last.sha256, content.remotePath)
        if (!file.exists()) return

        val jpeg = renderProof(file.absolutePath, last.itemId, now, saat.trusted) ?: return

        val request = Http.authed("${config.apiUrl}/api/v1/proof", config.token, config.deviceId)
            .post(jpeg.toRequestBody("image/jpeg".toMediaType()))
            .build()

        runCatching {
            client.newCall(request).execute().use { if (it.isSuccessful) config.lastProofAt = now }
        }.onFailure { Log.i(TAG, "kanit karesi gonderilemedi: ${it.message}") }
    }

    private fun renderProof(path: String, itemId: String, now: Long, clockTrusted: Boolean): ByteArray? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(path)
            val frame: Bitmap = retriever.getFrameAtTime(1_000_000L) ?: return null
            // Bozuk/sifir boyutlu kare: olcekleme sifira bolme hatasi verirdi
            if (frame.width <= 0 || frame.height <= 0) {
                frame.recycle()
                return null
            }

            // Kucult: 640 piksel genislik kanit icin fazlasiyla yeterli, bant genisligini yemesin
            val scaled = Bitmap.createScaledBitmap(
                frame, PROOF_WIDTH, (frame.height * PROOF_WIDTH / frame.width).coerceAtLeast(1), true
            )
            // Kaynak kareyi hemen birak: 1080p bir kare ~8 MB ve bu cihazlarda bellek dar
            if (scaled !== frame) frame.recycle()
            val canvas = Canvas(scaled)
            val text = "${config.deviceId} | $itemId | " +
                Instant.ofEpochMilli(now).toString() +
                if (clockTrusted) "" else " | SAAT SUPHELI"

            val paint = Paint().apply {
                color = Color.WHITE; textSize = 18f; isAntiAlias = true
                setShadowLayer(3f, 0f, 0f, Color.BLACK)
            }
            canvas.drawRect(0f, (scaled.height - 28).toFloat(),
                scaled.width.toFloat(), scaled.height.toFloat(),
                Paint().apply { color = Color.argb(150, 0, 0, 0) })
            canvas.drawText(text, 8f, (scaled.height - 8).toFloat(), paint)

            java.io.ByteArrayOutputStream().also {
                scaled.compress(Bitmap.CompressFormat.JPEG, 60, it)
            }.toByteArray().also { scaled.recycle() }
        } catch (e: Exception) {
            Log.i(TAG, "kare cikarilamadi: ${e.message}")
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    /**
     * WiFi sinyal seviyesi - panelde "bu otobus noktada zayif mi cekiyor?" sorusu icin.
     *
     * Izin ACIKCA kontrol ediliyor: connectionInfo, ACCESS_FINE_LOCATION olmadan
     * SecurityException firlatir. Yakalamak yeterli degil - sinyal olmayan bir alan,
     * "sinyal yok" ile "izin yok" arasinda ayrim yapmadigi icin yaniltici olur.
     * SecurityException'i da ayrica yakaliyoruz: her ROM ayni davranmiyor.
     */
    @Suppress("DEPRECATION")
    private fun wifiRssi(): Int? {
        val izin = context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
        if (izin != android.content.pm.PackageManager.PERMISSION_GRANTED) return null
        return try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            wm?.connectionInfo?.rssi
        } catch (_: SecurityException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    private companion object {
        /**
         * Log yuklemesine ayrilan en fazla sure.
         *
         * Pencere ~3 dakika; heartbeat ve kanit karesi icin mutlaka yer kalmali.
         * 45 sn, binlerce satirlik bir birikimi birkac pencerede eritmeye yeter ve
         * hicbir pencerede teshis sinyalini kurban etmez.
         */
        const val LOG_BUTCE_MS = 45_000L

        const val TAG = "Telemetry"
        const val BATCH = 500
        const val PROOF_WIDTH = 640
        const val PROOF_INTERVAL_MS = 24L * 3600 * 1000   // gunde en fazla bir kare
        /** Kanit karesi icin kabul edilen en eski oynatma kaydi. */
        const val KANIT_TAZELIK_MS = 26L * 3600 * 1000
    }
}
