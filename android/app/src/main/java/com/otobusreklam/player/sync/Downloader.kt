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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

/**
 * Parcali, devam ettirilebilir indirme motoru.
 *
 * Cozdugu problem: otobus noktada sadece ~3 dakika duruyor. Pencere yetmezse
 * indirme YARIDA kalir. Bu durumda:
 *   - yarim dosya SILINMEZ
 *   - hangi parcalarin indigi veritabanina yazilir (fsync ile)
 *   - sonraki ziyarette SADECE eksik parcalar, kaldigi bayttan istenir
 *
 * Dogrulama uc katmanli:
 *   1) HTTP yaniti: 206 + Content-Range/Content-Length beklenen araligi gostermeli
 *   2) her parca kendi SHA-256'si ile (bozuk parca sessizce yazilmaz)
 *   3) tamamlaninca tum dosyanin SHA-256'si ile
 * Hepsi gecmeden dosya content/ dizinine (yani oynatma listesine) GIRMEZ.
 */
class Downloader(
    private val store: FileStore,
    private val db: AppDatabase
) {

    enum class Result { READY, PARTIAL, FAILED }

    /**
     * Bu senkron oturumunda inen toplam bayt - heartbeat ve teshis icin.
     *
     * AtomicLong: parcalar paralel indigi icin `sessionBytes += len` seklinde bir
     * oku-degistir-yaz islemi sayim kaybederdi. @Volatile bunu COZMEZ, sadece
     * gorunurlugu garanti eder.
     */
    private val indirilen = AtomicLong(0L)

    val sessionBytes: Long get() = indirilen.get()

    fun resetSessionStats() { indirilen.set(0L); sonHata = null }

    /**
     * Son indirme hatasinin ANLASILIR aciklamasi.
     *
     * Onceden her basarisizlik ayni Log.i satirina iniyordu ve panele HIC yansimiyordu:
     * disk dolmus, sunucu Range desteklemiyor, manifest bozuk - hepsi sahada ayni
     * sekilde goruluyordu, yani hicbir sekilde. SyncService bunu lastError'a tasiyor.
     */
    @Volatile var sonHata: String? = null
        private set

    suspend fun ensure(
        sha: String,
        remotePath: String,
        size: Long,
        chunks: List<ChunkSpec>,
        baseUrl: String,
        token: String,
        client: OkHttpClient,
        parallel: Int,
        stillRunning: () -> Boolean
    ): Result = withContext(Dispatchers.IO) {

        val target = store.contentFile(sha, remotePath)

        /*
         * PARCA LISTESI DOSYAYI TAM KAPSIYOR MU? PARCALAR MAKUL BOYUTTA MI?
         *
         * Kapsamiyorsa (sunucu hatasi, kirpilmis manifest) tum parcalar "indi" olur,
         * tam dosya hash'i TUTMAZ, dosya bastan indirilir - ve bu SONSUZA KADAR
         * tekrarlanir. Her pencerede tum bant genisligi ayni dosyaya harcanir ve
         * hicbir icerik guncellenemez.
         *
         * Boyut ust siniri da ZORUNLU: her parca tamamen BELLEGE aliniyor (hash'i
         * yazmadan once dogrulanmali). Manifestteki bir `len` degeri 500 MB olsa
         * ByteArray(len) OutOfMemoryError firlatir - ve OOM bir Error'dur, alttaki
         * `catch (e: Exception)` bloklarinin HICBIRI onu tutmaz: kiosk uygulamasi
         * ekrani karartarak coker. Bu yuzden basta reddediyoruz.
         */
        val kapsam = chunks.sumOf { it.len.toLong() }
        val buyukParca = chunks.firstOrNull { it.len <= 0 || it.len > MAX_PARCA }
        if (chunks.isEmpty() || kapsam != size || buyukParca != null) {
            sonHata = when {
                buyukParca != null ->
                    "manifest parca boyutu kabul edilemez: idx=${buyukParca.index} len=${buyukParca.len} (sinir $MAX_PARCA)"
                else ->
                    "manifest parca listesi bozuk: ${chunks.size} parca $kapsam bayt, beklenen $size"
            }
            Log.e(TAG, sonHata!!)
            db.contents().upsert(
                ContentEntity(sha, remotePath, size, ContentState.BAD, System.currentTimeMillis())
            )
            return@withContext Result.FAILED
        }

        val known = db.contents().get(sha)

        // Zaten hazir mi? (dosya var + boyut tutuyor)
        if (target.exists() && target.length() == size) {
            // BAD durumunu KORU: dosya saglam indi ama oynatilamadi (kodek sorunu).
            // Yeniden indirmek bunu duzeltmez; yeni bir kodlama yuklenip sha degisene
            // kadar bu icerik donguye girmemeli. Aksi halde her senkronda dirilir.
            if (known?.state != ContentState.BAD) {
                // upsert (setState degil): setState bir UPDATE'tir ve satir yoksa
                // sessizce 0 satir gunceller. O durumda dosya diskte hazir olur ama
                // contents tablosunda READY satiri olmadigi icin PlaylistBuilder
                // icerigi HIC OYNATMAZ. Satirin varligina guvenmek yerine yaziyoruz.
                db.contents().upsert(
                    ContentEntity(sha, remotePath, size, ContentState.READY, System.currentTimeMillis())
                )
                return@withContext Result.READY
            }
            return@withContext Result.FAILED
        }

        /*
         * YARIM DOSYANIN UZUNLUGU, RAF ACILMADAN ONCE OKUNUYOR.
         *
         * Asagida RandomAccessFile setLength(size) ile dosyayi tam boyuta genisletiyor.
         * Onceden tutarsizlik kontrolu (parcalar "indi" ama dosya yok) RAF'tan SONRA
         * `part.length() != size` diye yapiliyordu - o noktada uzunluk HER ZAMAN size'a
         * esit oldugu icin kosul asla saglanamiyordu: OLU KOD. Yani "diski birisi
         * temizledi ama veritabani parcalari indi saniyor" durumu hic yakalanmiyor,
         * dosya sessizce SIFIRLARLA doluyor ve ancak tam dosya hash'inde patliyordu.
         */
        val part = store.partFile(sha)
        val oncekiUzunluk = runCatching { if (part.exists()) part.length() else 0L }.getOrDefault(0L)

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

        /*
         * DISK ON KONTROLU.
         *
         * Disk doluyken indirmeye baslamak 3 dakikalik pencereyi bosa harcar: her parca
         * yazma hatasi verir, hepsi ayni sessiz log satirina duser ve cihaz her ziyarette
         * bunu tekrarlar. Onceden bu durum kalici ve GORUNMEZ bir kilitlenmeydi.
         */
        val bosAlan = store.freeBytes()
        val gereken = size - oncekiUzunluk
        if (bosAlan in 0 until (gereken + DISK_MARJI)) {
            sonHata = "disk dolu: ${bosAlan / 1_000_000} MB bos, ${gereken / 1_000_000} MB gerekiyor"
            Log.e(TAG, sonHata!!)
            return@withContext Result.FAILED
        }

        val raf = try {
            RandomAccessFile(part, "rw").apply { if (length() != size) setLength(size) }
        } catch (e: Exception) {
            sonHata = "parca dosyasi acilamadi: ${e.message}"
            Log.e(TAG, sonHata!!)
            return@withContext Result.FAILED
        }

        try {
            var pending = db.chunks().pending(sha)

            // Tutarsiz durum: tum parcalar "indi" isaretli ama yarim dosya yok veya
            // kirpilmis (ornegin diski temizleyen biri, ya da bozuk bir kapanma).
            // RAF ACILMADAN ONCE okunan uzunluga bakiyoruz - bkz. yukaridaki not.
            if (pending.isEmpty() && oncekiUzunluk != size) {
                Log.w(TAG, "parcalar indi isaretli ama dosya $oncekiUzunluk/$size - bastan indirilecek")
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
                                if (fetchChunk(url, chunk, chunks.size, raf, client, token)) {
                                    db.chunks().markDone(sha, chunk.idx)
                                    indirilen.addAndGet(chunk.len.toLong())
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                // Pencere bitti / ag koptu. Bu NORMAL bir son.
                                // Ilerleme diskte, bir sonraki ziyarette devam edilir.
                                Log.i(TAG, "parca ${chunk.idx} yarim kaldi: ${e.message}")
                                // Disk hatasi NORMAL degil: ayirt edilebilir olmali
                                val m = e.message.orEmpty()
                                if (m.contains("No space", true) || m.contains("ENOSPC", true)) {
                                    sonHata = "disk dolu (yazma hatasi): $m"
                                }
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

        // Pencere kapandiysa BURADA DUR: asagisi tum dosyayi okuyup hash'liyor
        // (50 MB'da saniyeler) ve ardindan dosyayi tasiyor. Iptal edilmis bir oturumun
        // yayin listesini degistirmesi dogru degil; ilerleme diskte, sonraki ziyarette
        // dogrulama bir sonraki turda saniyeler icinde yapilir.
        if (!stillRunning()) return@withContext Result.PARTIAL

        // Tum parcalar indi -> tam dosya dogrulamasi
        val actual = store.sha256(part)
        if (!actual.equals(sha, ignoreCase = true)) {
            // Parcalar tek tek dogrulandigi halde butun tutmuyorsa yazma sirasinda
            // bir sey bozulmus demektir. Bastan indir.
            sonHata = "tam dosya hash tutmadi ($remotePath) - bastan indirilecek"
            Log.e(TAG, "TAM DOSYA HASH TUTMADI, bastan indirilecek: $sha != $actual")
            part.delete()
            db.chunks().reset(sha, chunks.map {
                ChunkEntity(sha, it.index, it.offset, it.len, it.sha256, done = false)
            })
            db.contents().setState(sha, ContentState.MISSING, System.currentTimeMillis())
            return@withContext Result.FAILED
        }

        if (!store.promote(part, target)) {
            sonHata = "dosya nihai konuma tasinamadi: $remotePath"
            Log.e(TAG, "dosya tasinamadi: $sha")
            return@withContext Result.FAILED
        }
        /*
         * SIRA ONEMLI: promote() ICINDE dizin fsync'i yapiliyor, yani buraya
         * geldigimizde yeni ad DISKTE. Ancak ondan sonra devam bilgisini silebiliriz;
         * ters sirada bir guc kesintisi "parca kayitlari yok + dosya da yok" durumu
         * yaratir ve %100 inmis dosya bastan indirilir.
         */
        db.chunks().clear(sha)
        db.contents().setState(sha, ContentState.READY, System.currentTimeMillis())
        // Yeni bir kodlama indi: oynatma hatasi sayaci bu dosya icin anlamsiz.
        db.contents().clearFail(sha)
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
    private suspend fun fetchChunk(
        url: String,
        chunk: ChunkEntity,
        totalChunks: Int,
        raf: RandomAccessFile,
        client: OkHttpClient,
        token: String
    ): Boolean {
        val call = client.newCall(Http.range(url, chunk.offset, chunk.len, token))

        /*
         * IPTAL EDILEN PENCERE, ACIK SOKETI DE KAPATMALI.
         *
         * OkHttp'nin execute()'u BLOKLAYICIDIR ve coroutine iptaline duyarli degildir:
         * otobus noktadan ayrildiginda senkron isi iptal edilir ama bu is parcacigi
         * okuma zaman asimi (15 sn) dolana kadar olu bir baglantida bekler. parallel=2
         * ile bu, pencerenin son saniyelerinde iki is parcaciginin bosa harcanmasi ve
         * on plan servisinin gereksiz uzun yasamasi demek. Is iptal edildigi anda
         * cagriyi da iptal ediyoruz.
         */
        val kanca = currentCoroutineContext().job.invokeOnCompletion { if (it != null) call.cancel() }
        try {
            call.execute().use {
                val singleChunkWholeFile = totalChunks == 1 && chunk.offset == 0L
                if (it.code != 206 && !(it.code == 200 && singleChunkWholeFile)) {
                    Log.w(TAG, "beklenmeyen kod ${it.code} (Range destegi yok mu?) $url")
                    sonHata = when (it.code) {
                        200 -> "sunucu Range desteklemiyor (200 dondu): $url"
                        // Onbellek kutusu Authorization basligini yukari gecirmiyor olabilir.
                        401, 403 -> "icerik indirme yetkisi reddedildi (HTTP ${it.code}) - onbellek kutusu Authorization basligini geciriyor mu?"
                        404 -> "icerik sunucuda yok (404): $url"
                        else -> "icerik indirme HTTP ${it.code}: $url"
                    }
                    return false
                }

                /*
                 * YANIT GERCEKTEN ISTEDIGIMIZ ARALIK MI?
                 *
                 * Onceden yalnizca govdenin uzunluguna bakiliyordu. Yanlis araligi
                 * (ornegin arada duran bir onbellegin kendi yorumu, ya da `Range`'i
                 * kismen destekleyen bir proxy) dondurdugunde bu ancak parca hash'inde
                 * anlasiliyordu - ve teshis edilemez bir "parca hash tutmadi" satiri
                 * olarak goruluyordu. Sunucu ne dediyse ONCE onu kontrol ediyoruz;
                 * hata mesaji da artik sebebi soyluyor.
                 */
                if (it.code == 206) {
                    val cr = it.header("Content-Range").orEmpty()
                    val beklenen = "bytes ${chunk.offset}-${chunk.offset + chunk.len - 1}/"
                    if (cr.isNotBlank() && !cr.startsWith(beklenen)) {
                        sonHata = "sunucu yanlis aralik dondu: istenen $beklenen, gelen $cr"
                        Log.w(TAG, sonHata!!)
                        return false
                    }
                }
                val cl = it.body?.contentLength() ?: -1L
                if (cl >= 0 && cl != chunk.len.toLong()) {
                    sonHata = "govde uzunlugu beklenenden farkli: $cl != ${chunk.len} ($url)"
                    Log.w(TAG, sonHata!!)
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
                //
                // DIKKAT: write() TAM yazmayi GARANTI ETMEZ; sozlesmeye gore daha az bayt
                // yazabilir. Tek cagri yapsaydik parca "indi" isaretlenirken dosyada DELIK
                // kalirdi; tam dosya hash'i bunu yakalar ama bedeli dosyanin bastan
                // indirilmesidir - ve 3 dakikalik pencerede bu cok pahali.
                val buf = ByteBuffer.wrap(bytes)
                var yazilan = 0
                while (buf.hasRemaining()) {
                    val n = raf.channel.write(buf, chunk.offset + yazilan)
                    if (n <= 0) {
                        Log.w(TAG, "parca ${chunk.idx} diske yazilamadi (yazilan=$yazilan/${chunk.len})")
                        return false
                    }
                    yazilan += n
                }
                // Her parcadan sonra diske zorla: ani guc kesintisinde ilerleme kaybolmasin.
                raf.channel.force(false)
                return true
            }
        } finally {
            kanca.dispose()
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

    private companion object {
        const val TAG = "Downloader"
        /** Dosya boyutunun ustune biraktigimiz emniyet payi (veritabani, loglar, gecici dosyalar). */
        const val DISK_MARJI = 50L * 1024 * 1024
        /**
         * Tek parca icin kabul edilen en buyuk boyut.
         * Parca tamamen bellege alindigi icin bu dogrudan bir BELLEK sinirdir.
         * Sunucu 4 MB parca uretiyor; 16 MB bol pay birakir.
         */
        const val MAX_PARCA = 16 * 1024 * 1024
    }
}
