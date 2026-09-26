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
import com.otobusreklam.player.clock.ClockManager
import com.otobusreklam.player.data.AppDatabase
import com.otobusreklam.player.data.ContentState
import com.otobusreklam.player.manifest.PlayManifest
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

    /**
     * Oynatma loglari.
     * ACK ALINMADAN SATIR SILINMEZ; sunucu tekrarlari (cihaz, seq) ile eler.
     * Boylece pencere ortasinda kopan baglanti log kaybina yol acmaz.
     */
    suspend fun uploadLogs(client: OkHttpClient) {
        while (true) {
            val rows = db.playLog().pending(BATCH)
            if (rows.isEmpty()) return

            val ndjson = rows.joinToString("\n") { r ->
                JSONObject().apply {
                    put("seq", r.seq)
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

            val request = Http.authed("${config.apiUrl}/api/v1/logs", config.token)
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
                    db.playLog().markUploaded(ack)
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

        val body = JSONObject().apply {
            put("appVersion", BuildConfig.VERSION_CODE)
            put("appVersionName", BuildConfig.VERSION_NAME)
            put("playlistVersion", manifest.playlistVersion)
            put("readyItems", ready)
            put("badItems", bad)
            put("totalItems", manifest.items.size)
            put("freeBytes", store.freeBytes())
            put("rssi", wifiRssi())
            put("reboots", config.rebootCount)
            put("clockTrusted", clock.trusted())
            put("clockNote", clock.note)
            put("sessionBytes", sessionBytes)
            put("pendingLogs", db.playLog().pendingCount())
            put("lastError", config.lastError)
            put("model", "${Build.MANUFACTURER} ${Build.MODEL}")
            put("androidSdk", Build.VERSION.SDK_INT)
        }

        val request = Http.authed("${config.apiUrl}/api/v1/heartbeat", config.token)
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
        val now = clock.now()
        if (now - config.lastProofAt < PROOF_INTERVAL_MS) return

        val last = db.playLog().lastPlayed() ?: return
        val content = db.contents().get(last.sha256) ?: return
        val file = store.contentFile(last.sha256, content.remotePath)
        if (!file.exists()) return

        val jpeg = renderProof(file.absolutePath, last.itemId, now) ?: return

        val request = Http.authed("${config.apiUrl}/api/v1/proof", config.token)
            .post(jpeg.toRequestBody("image/jpeg".toMediaType()))
            .build()

        runCatching {
            client.newCall(request).execute().use { if (it.isSuccessful) config.lastProofAt = now }
        }.onFailure { Log.i(TAG, "kanit karesi gonderilemedi: ${it.message}") }
    }

    private fun renderProof(path: String, itemId: String, now: Long): ByteArray? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(path)
            val frame: Bitmap = retriever.getFrameAtTime(1_000_000L) ?: return null

            // Kucult: 640 piksel genislik kanit icin fazlasiyla yeterli, bant genisligini yemesin
            val scaled = Bitmap.createScaledBitmap(
                frame, PROOF_WIDTH, (frame.height * PROOF_WIDTH / frame.width).coerceAtLeast(1), true
            )
            val canvas = Canvas(scaled)
            val text = "${config.deviceId} | $itemId | " +
                Instant.ofEpochMilli(now).toString() +
                if (clock.trusted()) "" else " | SAAT SUPHELI"

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
            }.toByteArray()
        } catch (e: Exception) {
            Log.i(TAG, "kare cikarilamadi: ${e.message}")
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    @Suppress("DEPRECATION")
    private fun wifiRssi(): Int? = try {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        wm?.connectionInfo?.rssi
    } catch (_: Exception) {
        null
    }

    private companion object {
        const val TAG = "Telemetry"
        const val BATCH = 500
        const val PROOF_WIDTH = 640
        const val PROOF_INTERVAL_MS = 24L * 3600 * 1000   // gunde en fazla bir kare
    }
}
