package com.otobusreklam.player.sync

import android.util.Log
import com.otobusreklam.player.data.AppDatabase
import com.otobusreklam.player.data.ChunkEntity
import com.otobusreklam.player.data.ContentEntity
import com.otobusreklam.player.data.ContentState
import com.otobusreklam.player.manifest.ChunkSpec
import com.otobusreklam.player.net.Http
import com.otobusreklam.player.store.FileStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * Parcali, devam ettirilebilir indirme motoru.
 *
 * Cozdugu problem: otobus noktada sadece ~3 dakika duruyor. Pencere yetmezse
 * indirme YARIDA kalir. Bu durumda:
 *   - yarim dosya SILINMEZ
 *   - hangi parcalarin indigi veritabanina yazilir (fsync ile)
 *   - sonraki ziyarette SADECE eksik parcalar, kaldigi bayttan istenir
 *
 * Dogrulama iki katmanli:
 *   1) her parca kendi SHA-256'si ile (bozuk parca sessizce yazilmaz)
 *   2) tamamlaninca tum dosyanin SHA-256'si ile
 * Ikisi de gecmeden dosya content/ dizinine (yani oynatma listesine) GIRMEZ.
 */
class Downloader(
    private val store: FileStore,
    private val db: AppDatabase
) {

    enum class Result { READY, PARTIAL, FAILED }

    /** Bu senkron oturumunda inen toplam bayt - heartbeat ve teshis icin. */
    @Volatile var sessionBytes: Long = 0L
        private set

    fun resetSessionStats() { sessionBytes = 0L }

    suspend fun ensure(
        sha: String,
        remotePath: String,
        size: Long,
        chunks: List<ChunkSpec>,
        baseUrl: String,
        client: OkHttpClient,
        parallel: Int,
        stillRunning: () -> Boolean
    ): Result = withContext(Dispatchers.IO) {

        val target = store.contentFile(sha, remotePath)

        val known = db.contents().get(sha)

        // Zaten hazir mi? (dosya var + boyut tutuyor)
        if (target.exists() && target.length() == size) {
            // BAD durumunu KORU: dosya saglam indi ama oynatilamadi (kodek sorunu).
            // Yeniden indirmek bunu duzeltmez; yeni bir kodlama yuklenip sha degisene
            // kadar bu icerik donguye girmemeli. Aksi halde her senkronda dirilir.
            if (known?.state != ContentState.BAD) {
                db.contents().setState(sha, ContentState.READY, System.currentTimeMillis())
                return@withContext Result.READY
            }
            return@withContext Result.FAILED
        }

        // Parca kayitlarini hazirla. Manifest degistiyse (ayni sha farkli parcalama)
        // kayitlari sifirla - sha ayni oldugu surece bu pratikte olmaz ama savunmaci davranalim.
        val existing = db.chunks().forContent(sha)
        if (existing.size != chunks.size) {
            db.chunks().reset(sha, chunks.map {
                ChunkEntity(sha, it.index, it.offset, it.len, it.sha256, done = false)
            })
        }
        db.contents().upsert(
            ContentEntity(sha, remotePath, size, ContentState.PARTIAL, System.currentTimeMillis())
        )

        val part = store.partFile(sha)
        val raf = try {
            RandomAccessFile(part, "rw").apply { if (length() != size) setLength(size) }
        } catch (e: Exception) {
            Log.e(TAG, "parca dosyasi acilamadi: ${e.message}")
            return@withContext Result.FAILED
        }

        try {
            var pending = db.chunks().pending(sha)

            // Tutarsiz durum: tum parcalar "indi" isaretli ama yarim dosya yok
            // (ornegin diski temizleyen biri). Bastan indirmek icin sifirla.
            if (pending.isEmpty() && part.length() != size) {
                db.chunks().reset(sha, chunks.map {
                    ChunkEntity(sha, it.index, it.offset, it.len, it.sha256, done = false)
                })
                pending = db.chunks().pending(sha)
            }

            val gate = Semaphore(parallel.coerceIn(1, 4))
            val url = "$baseUrl/$remotePath"

            coroutineScope {
                pending.map { chunk ->
                    async {
                        if (!stillRunning()) return@async
                        gate.withPermit {
                            if (!stillRunning()) return@withPermit
                            try {
                                if (fetchChunk(url, chunk, chunks.size, raf, client)) {
                                    db.chunks().markDone(sha, chunk.idx)
                                    sessionBytes += chunk.len
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                // Pencere bitti / ag koptu. Bu NORMAL bir son.
                                // Ilerleme diskte, bir sonraki ziyarette devam edilir.
                                Log.i(TAG, "parca ${chunk.idx} yarim kaldi: ${e.message}")
                            }
                        }
                    }
                }.awaitAll()
            }

            val remaining = db.chunks().remainingBytes(sha)
            if (remaining > 0L) {
                db.contents().setState(sha, ContentState.PARTIAL, System.currentTimeMillis())
                return@withContext Result.PARTIAL
            }
        } finally {
            runCatching { raf.channel.force(true) }
            runCatching { raf.close() }
        }

        // Tum parcalar indi -> tam dosya dogrulamasi
        val actual = store.sha256(part)
        if (!actual.equals(sha, ignoreCase = true)) {
            // Parcalar tek tek dogrulandigi halde butun tutmuyorsa yazma sirasinda
            // bir sey bozulmus demektir. Bastan indir.
            Log.e(TAG, "TAM DOSYA HASH TUTMADI, bastan indirilecek: $sha != $actual")
            part.delete()
            db.chunks().reset(sha, chunks.map {
                ChunkEntity(sha, it.index, it.offset, it.len, it.sha256, done = false)
            })
            db.contents().setState(sha, ContentState.MISSING, System.currentTimeMillis())
            return@withContext Result.FAILED
        }

        if (!store.promote(part, target)) {
            Log.e(TAG, "dosya tasinamadi: $sha")
            return@withContext Result.FAILED
        }
        db.chunks().clear(sha)
        db.contents().setState(sha, ContentState.READY, System.currentTimeMillis())
        Log.i(TAG, "hazir: $remotePath")
        Result.READY
    }

    /**
     * Tek parca. Sunucudan 206 Partial Content bekliyoruz.
     *
     * 200 gelirse sunucu Range'i YOK SAYMIS demektir (yanlis yapilandirilmis proxy
     * veya onbellek kutusu). O govdeyi parcanin offset'ine yazmak dosyayi BOZARDI;
     * bu yuzden cok parcali dosyalarda 200 kabul edilmez.
     */
    private fun fetchChunk(
        url: String,
        chunk: ChunkEntity,
        totalChunks: Int,
        raf: RandomAccessFile,
        client: OkHttpClient
    ): Boolean {
        val response = client.newCall(Http.range(url, chunk.offset, chunk.len)).execute()
        response.use {
            val singleChunkWholeFile = totalChunks == 1 && chunk.offset == 0L
            if (it.code != 206 && !(it.code == 200 && singleChunkWholeFile)) {
                Log.w(TAG, "beklenmeyen kod ${it.code} (Range destegi yok mu?) $url")
                return false
            }
            val body = it.body ?: return false
            val bytes = readExactly(body.byteStream(), chunk.len) ?: return false

            val digest = MessageDigest.getInstance("SHA-256")
                .digest(bytes)
                .joinToString("") { b -> "%02x".format(b) }
            if (!digest.equals(chunk.chunkSha, ignoreCase = true)) {
                Log.w(TAG, "parca hash tutmadi idx=${chunk.idx}")
                return false
            }

            // Mutlak konumlu yazma: FileChannel.write(buffer, position) kanal konumunu
            // degistirmez, bu yuzden farkli offset'lere paralel yazmak guvenlidir.
            raf.channel.write(ByteBuffer.wrap(bytes), chunk.offset)
            // Her parcadan sonra diske zorla: ani guc kesintisinde ilerleme kaybolmasin.
            raf.channel.force(false)
            return true
        }
    }

    private fun readExactly(input: InputStream, len: Int): ByteArray? {
        val out = ByteArray(len)
        var read = 0
        while (read < len) {
            val n = input.read(out, read, len - read)
            if (n <= 0) return null   // govde beklenenden kisa -> parcayi kabul etme
            read += n
        }
        return out
    }

    private companion object { const val TAG = "Downloader" }
}
