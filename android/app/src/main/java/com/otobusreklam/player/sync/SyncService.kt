package com.otobusreklam.player.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Network
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.otobusreklam.player.BuildConfig
import com.otobusreklam.player.Config
import com.otobusreklam.player.R
import com.otobusreklam.player.clock.ClockManager
import com.otobusreklam.player.data.AppDatabase
import com.otobusreklam.player.data.ContentEntity
import com.otobusreklam.player.data.ContentState
import com.otobusreklam.player.data.ItemEntity
import com.otobusreklam.player.manifest.AppUpdate
import com.otobusreklam.player.manifest.ManifestItem
import com.otobusreklam.player.manifest.ManifestParser
import com.otobusreklam.player.manifest.PlayManifest
import com.otobusreklam.player.manifest.SignatureVerifier
import com.otobusreklam.player.net.Http
import com.otobusreklam.player.store.FileStore
import com.otobusreklam.player.telemetry.Telemetry
import com.otobusreklam.player.update.Updater
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import kotlin.random.Random

/**
 * Senkron servisi - pencereyi yoneten bilesen.
 *
 * Neden foreground service: 3 dakikalik pencerede sistem bizi oldurmemeli.
 * Neden WorkManager'in periyodik isi DEGIL: PeriodicWorkRequest'in minimum
 * periyodu 15 DAKIKA; 3 dakikalik pencere rahatlikla kacar. Tetikleyici
 * NetworkWatcher (ConnectivityManager.NetworkCallback), bu servis de isi yapar.
 */
class SyncService : Service() {

    /**
     * Kapsam YENIDEN OLUSTURULABILIR olmak zorunda.
     *
     * onDestroy `scope.cancel()` yapiyor. Servis nesnesi hemen yok olmadigi icin
     * (Android kendi zamanlamasiyla siler) arada gelen yeni bir WiFi olayi ayni nesne
     * uzerinde onStartCommand tetikleyebilir: is IPTAL EDILMIS bir kapsamda baslatilir,
     * yani hic calismaz ve o pencere sessizce kaybolur. Her baslatmada kapsamin canli
     * oldugunu dogruluyoruz.
     */
    private var scope = CoroutineScope(SupervisorJob())
    private var job: Job? = null

    @Volatile private var network: Network? = null
    @Volatile private var running = false
    /** stopSelf(startId): araya giren ikinci bir WiFi olayinin baslattigi senkronu oldurmemek icin. */
    @Volatile private var sonStartId = -1

    private lateinit var config: Config
    private lateinit var store: FileStore
    private lateinit var db: AppDatabase
    private lateinit var clock: ClockManager
    private lateinit var downloader: Downloader

    override fun onCreate() {
        super.onCreate()
        config = Config(this)
        store = FileStore(this)
        db = AppDatabase.get(this)
        clock = ClockManager(this)
        downloader = Downloader(store, db)
        createChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, notification())
        sonStartId = startId

        when (intent?.action) {
            ACTION_STOP -> {
                stopSync("ag koptu")
                return START_NOT_STICKY
            }
        }

        @Suppress("DEPRECATION")
        val net: Network? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                intent?.getParcelableExtra(EXTRA_NETWORK, Network::class.java)
            else
                intent?.getParcelableExtra(EXTRA_NETWORK)

        if (!config.configured) {
            Log.w(TAG, "cihaz provizyonlanmamis, senkron atlandi")
            stopSelf(startId)
            return START_NOT_STICKY
        }

        // Zaten calisan bir oturum varsa ikincisini baslatma.
        if (running) return START_STICKY

        if (!scope.isActive) {
            Log.i(TAG, "kapsam iptal edilmisti, yenisi olusturuluyor")
            scope = CoroutineScope(SupervisorJob())
        }

        network = net
        running = true

        // LAZY + explicit start: `job = scope.launch { ... }` yazilsaydi coroutine
        // atama tamamlanmadan baska bir is parcaciginda calismaya baslayabilirdi.
        // O anda job hala null oldugu icin stillRunning() false doner ve indirme
        // hic baslamazdi. Bu yaris kosulunu tamamen kapatiyoruz.
        val newJob = scope.launch(start = CoroutineStart.LAZY) {
            try {
                runSync()
            } catch (e: CancellationException) {
                /*
                 * IPTAL BIR HATA DEGILDIR - PENCERENIN NORMAL SONUDUR.
                 *
                 * Otobus noktadan ayrilinca WiFi kopar ve senkron iptal edilir; bu her
                 * gun, her otobuste olan beklenen durum. Onceden bu da lastError'a
                 * yaziliyordu: panelde her cihaz surekli "hata" gosteriyor ve GERCEK
                 * hatalar bu gurultunun icinde kayboluyordu.
                 */
                Log.i(TAG, "senkron iptal edildi (pencere bitti) - ilerleme korundu")
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "senkron hatasi", e)
                config.lastError = "${e.javaClass.simpleName}: ${e.message}"
            } finally {
                running = false
                stopForegroundCompat()
                // startId ile: bu cagriyi baslatan istek icin duruyoruz. Araya giren
                // yeni bir WiFi olayi varsa onun baslattigi senkron yasamaya devam eder.
                stopSelf(sonStartId)
            }
        }
        job = newJob
        newJob.start()
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun stopSync(reason: String) {
        Log.i(TAG, "senkron durduruluyor: $reason")
        job?.cancel()
        running = false
        stopForegroundCompat()
        // startId ile: bu durdurma istegi icin duruyoruz. Parametresiz stopSelf()
        // BEKLEYEN start isteklerini yok sayar ve araya giren yeni bir WiFi olayinin
        // baslattigi senkronu daha dogmadan oldururdu.
        stopSelf(sonStartId)
    }

    // ------------------------------------------------------------------ akis

    private suspend fun runSync() {
        /*
         * PENCERENIN HATA DURUMUNU BASTA TEMIZLE.
         *
         * lastError heartbeat ile panele gidiyor. Onceden yalnizca pencerenin SONUNDA
         * yazilyordu, yani manifest alinamayip erken donen bir turda panel ONCEKI
         * pencerenin hatasini "guncel durum" olarak gosteriyordu. Operator duzeltilmis
         * bir sorunu gunlerce kovalayabilirdi.
         */
        config.lastError = ""

        val policyClient = Http.client(network, 8_000, 15_000)

        // 1) MANIFEST ONCE, GECIKMESIZ.
        // Kademeli baslatma (stagger) manifestten SONRA yapiliyor: manifest ~2 KB,
        // 20 cihaz ayni anda cekse bile 40 KB - AP'yi bogmaz. Agir indirmeden once
        // beklemek, pencereyi bosa harcamadan AP'yi korur.
        val cekilen = fetchManifest(policyClient) ?: return
        val manifest = cekilen.manifest

        val client = Http.client(network, manifest.policy.connectTimeoutMs, manifest.policy.readTimeoutMs)

        // 2) Manifesti diske yaz ve oynaticiyi uyar.
        // Indirmeden ONCE yayimlaniyor ki suresi dolan / iptal edilen reklam
        // dosyasi hala diskte olsa bile ANINDA yayindan dussun.
        persist(manifest)
        publish(cekilen)

        /*
         * 3) TEMIZLIK INDIRMEDEN ONCE.
         *
         * Onceden temizlik pencerenin SONUNDAYDI. Sonuc kisir bir dongu: disk dolu
         * oldugu icin indirmeler basarisiz olur, indirme basarisiz oldugu icin (eski
         * hata yolunda) senkron erken doner ve temizlige HIC sira gelmez. Disk kendi
         * kendini bir daha bosaltamaz ve cihaz kalici olarak durur.
         *
         * Dogru sira: once yerini artik hak etmeyen dosyalari sil, sonra indir.
         * Silinecek sey manifestten belli; indirme sonucuna bagli degil.
         */
        val keep = manifest.items.map { it.sha256 }.toMutableSet()
        manifest.app?.sha256?.let { keep += it }
        runCatching { store.cleanup(keep) }   // known-good.apk FileStore tarafinda korunuyor
            .onFailure { Log.w(TAG, "dosya temizligi basarisiz: ${it.message}") }
        runCatching { db.bakim(keep, clock.now() - LOG_SAKLAMA_MS) }
            .onFailure { Log.w(TAG, "veritabani bakimi basarisiz: ${it.message}") }

        // Temizlikten SONRA bakiyoruz: hala yer yoksa bu pencerede indirme sansi yok
        // ve sebebi panelde gorunmeli (yoksa cihaz "sessizce guncellenmiyor" olur).
        val bos = store.freeBytes()
        if (bos in 0 until DISK_ALT_SINIR) {
            Log.e(TAG, "temizlikten sonra da disk dolu: ${bos / 1_000_000} MB")
            config.lastError = "DISK DOLU: ${bos / 1_000_000} MB bos - icerik guncellenemiyor"
        }

        // 4) Kademeli baslatma: 20 cihaz ayni milisaniyede AP'ye yuklenmesin
        val stagger = Random.nextLong(0, manifest.policy.staggerMaxMs.coerceAtLeast(1))
        Log.i(TAG, "kademeli baslatma: ${stagger}ms")
        delay(stagger)

        // 5) Oncelik sirasiyla indir
        downloader.resetSessionStats()
        val bekleyenGuncelleme = downloadInPriorityOrder(manifest, client)

        // 6) Oynaticiyi tekrar uyar (yeni hazir olanlar devreye girsin)
        publish(cekilen)

        // 7) Telemetri: loglar + heartbeat. Pencerenin SONUNDA, indirmeyi calmasin.
        val telemetry = Telemetry(this, config, store, db, clock)
        telemetry.uploadLogs(client)
        telemetry.sendHeartbeat(client, manifest, downloader.sessionBytes)
        // Kanit karesi zorunlu degil ve en agir islem (video cozme + bitmap).
        // Guvenli modda atlaniyor: oncelik duzeltilmis surumu indirebilmek.
        if (!config.safeMode) {
            telemetry.sendProofFrame(client)   // gunde en fazla bir kez, en sona birakilir
        }

        config.lastSyncAt = clock.now()
        // Indirme sirasinda anlasilir bir sorun olduysa (disk dolu, bozuk manifest)
        // onu KORU; yoksa temizle. Aksi halde tespit edilebilir tek ipucu kayboluyordu.
        downloader.sonHata?.let { config.lastError = it }
        Log.i(TAG, "senkron tamam, oturumda inen: ${downloader.sessionBytes / 1024} KB")

        /*
         * 8) UYGULAMA GUNCELLEMESI EN SONDA KURULUR.
         *
         * PackageInstaller.commit() basarili olursa sistem SUREci OLDURUR (kendi
         * paketimizi degistiriyoruz). Kurulum pencerenin ortasindayken yapildiginda
         * bundan sonrasi HIC CALISMIYORDU: oynatma loglari yuklenmiyor, heartbeat
         * gitmiyor, temizlik yapilmiyor, lastSyncAt yazilmiyordu. Sonuc: guncelleme
         * alan cihaz panelde "gunlerdir senkron olmadi" gorunuyor ve o pencerenin
         * fatura kayitlari bir sonraki ziyarete kaliyordu.
         *
         * APK zaten diskte ve dogrulanmis; kurulumu pencerenin en sonuna almanin
         * hicbir maliyeti yok.
         */
        bekleyenGuncelleme?.let {
            Log.i(TAG, "uygulama guncellemesi kuruluyor (pencerenin sonu): ${it.versionCode}")
            Updater(this, config, store).install(it)
        }
    }

    /**
     * Manifesti cek, IMZAYI DOGRULA, ayristir.
     *
     * Imza tutmazsa null doneriz ve HICBIR SEY yapilmaz: mevcut liste calmaya
     * devam eder. Bu kasitlidir - onbellek kutusu ele gecse bile cihaza sahte
     * reklam sokulamaz.
     */
    /** Ayristirilmis manifest + onu tasiyan IMZALI HAM ZARF. */
    private data class CekilenManifest(val manifest: PlayManifest, val zarf: String)

    private fun fetchManifest(client: OkHttpClient): CekilenManifest? {
        val url = "${config.apiUrl}/api/v1/manifest"
        return try {
            client.newCall(Http.authed(url, config.token).build()).execute().use { response ->
                /*
                 * ZAMANI HER DURUMDA OKU - BASARISIZ YANITTA DA.
                 *
                 * Onceden `Date` yalnizca basarili yanitta okunuyordu. Saati bozulmus
                 * bir cihazda (guc kesintisi -> 1970) manifest herhangi bir sebeple
                 * gelmezse - 401, 404, bozuk imza - saat de duzelmiyordu. Oysa Date
                 * basligi 500 yanitinda bile gelir ve o cihazin tek zaman kaynagidir.
                 * Saatin bozuk kalmasi tum oynatma kayitlarina 1970 damgasi atar.
                 */
                zayifSaatDuzelt(response)

                if (!response.isSuccessful) {
                    config.lastError = "manifest HTTP ${response.code}"
                    Log.w(TAG, "manifest alinamadi: ${response.code}")
                    return null
                }

                val envelope = response.body?.string().orEmpty()
                val json = SignatureVerifier.open(envelope, BuildConfig.MANIFEST_PUBLIC_KEY)
                val manifest = ManifestParser.parse(json)

                if (manifest.deviceId.isNotBlank() && manifest.deviceId != config.deviceId) {
                    config.lastError = "manifest baska cihaza ait: ${manifest.deviceId}"
                    return null
                }

                /*
                 * GUVENILIR SAAT KAYNAGI: IMZALI GOVDEDEKI serverTime.
                 *
                 * Onceden burada tam TERSI yapiliyordu: `Date` basligi HER ZAMAN
                 * oncelikliydi. Gerekcesi "nokta onbellegi bayat manifest servis
                 * edebilir, Date ise tazedir" idi - ve bu gerekce, tehdit modelini
                 * atliyordu:
                 *   - manifest govdesi Ed25519 ile IMZALI, dolayisiyla serverTime'i
                 *     yalnizca ozel anahtara sahip olan yazabilir
                 *   - `Date` basligi imzanin DISINDA, duz metin bir basliktir; ag duz
                 *     HTTP oldugu icin AP agina erisebilen HERKES onu degistirebilir
                 * Yani kod, tek guvenilir kaynagi birakip tek guvenilmez olani
                 * seciyordu. Saati geriye almak, SURESI DOLMUS veya IPTAL EDILMIS
                 * reklamlari yeniden yayina sokar: dogrudan ticari ve hukuki zarar.
                 *
                 * Bayat manifest sorunu ise hala cozuluyor, saatle degil dogru araci
                 * kullanarak: imzali govdedeki serverTime zaten bayat manifestin
                 * KENDI uretim zamanidir ve ClockManager geriye giden zamani reddeder,
                 * yani bayat bir kopya saati geri alamaz - yalnizca guncelleyemez.
                 */
                manifest.serverTimeMs?.let { clock.onServerTime(it, signed = true) }
                CekilenManifest(manifest, envelope)
            }
        } catch (e: SignatureVerifier.InvalidSignature) {
            // Guvenlik olayi: panelde gorunmeli.
            config.lastError = "IMZA GECERSIZ: ${e.message}"
            Log.e(TAG, "manifest imzasi gecersiz - hicbir sey indirilmiyor", e)
            null
        } catch (e: Exception) {
            config.lastError = "manifest: ${e.message}"
            Log.w(TAG, "manifest hatasi: ${e.message}")
            null
        }
    }

    /**
     * ZAYIF saat duzeltmesi: imzasiz `Date` basligindan.
     *
     * Bu cagri saati GUVENILIR YAPMAZ (bkz. ClockManager: imzasiz kaynak imzali capayi
     * ezemez ve guven vermez). Amaci tek: guc kesintisinden sonra 1970'te kalan cihazin
     * damgalarini makul hale getirmek. Bitis tarihi zorlamasi buna dayanmaz.
     */
    private fun zayifSaatDuzelt(response: okhttp3.Response) {
        val dateMs = runCatching { response.headers.getDate("Date")?.time }.getOrNull() ?: return
        clock.onServerTime(dateMs, signed = false)
    }

    private suspend fun persist(manifest: PlayManifest) {
        db.items().upsertAll(
            manifest.items.map {
                ItemEntity(
                    itemId = it.id,
                    sha256 = it.sha256,
                    remotePath = it.remotePath,
                    durationMs = it.durationMs,
                    weight = it.weight,
                    validFrom = it.validFrom,
                    validUntil = it.validUntil,
                    dayparts = it.dayparts.joinToString(","),
                    evergreen = it.evergreen,
                    playlistVersion = manifest.playlistVersion
                )
            }
        )
        val ids = manifest.items.map { it.id }
        if (ids.isEmpty()) db.items().deleteAll() else db.items().deleteNotIn(ids)

        for (item in manifest.items) {
            if (db.contents().get(item.sha256) == null) {
                db.contents().upsert(
                    ContentEntity(item.sha256, item.remotePath, item.size, ContentState.MISSING, System.currentTimeMillis())
                )
            }
        }
        config.playlistVersion = manifest.playlistVersion
        // Daypart'in yorumlanacagi dilim: cihazin kendi dilimine GUVENMIYORUZ.
        if (manifest.timezone.isNotBlank()) config.timezone = manifest.timezone
    }

    /**
     * Son dogrulanmis manifesti diske yaz ve oynaticiyi uyar.
     *
     * OYNATMA LISTESININ KAYNAGI ROOM'DUR, bu dosya degil. Buradaki dosya TESHIS
     * ve KANIT icindir: teknisyen cihazi alip "bu cihaz hangi listeye inaniyordu?"
     * sorusunu cevaplayabilsin diye.
     *
     * Bu yuzden ozet bir JSON URETMIYORUZ, sunucudan gelen IMZALI ZARFI oldugu gibi
     * yaziyoruz. Iki kazanc:
     *  - Elle JSON kacisi yazma ihtiyaci (ve onun hata sinifi) tamamen ortadan kalkar.
     *    Satir sonu / tirnak / unicode iceren bir kampanya basligi dosyayi bozamaz.
     *  - Dosya cevrimdisi olarak YENIDEN DOGRULANABILIR: imza hala gecerlidir.
     */
    private fun publish(cekilen: CekilenManifest) {
        val yeni = cekilen.zarf.toByteArray(Charsets.UTF_8)
        runCatching {
            val mevcut = if (store.currentManifest.exists()) store.currentManifest.readBytes() else null
            // Icerik gercekten degistiyse oncekini sakla; ayni manifesti iki kez
            // yayimlamak (senkron basi/sonu) gecmisi bosuna silmesin.
            if (mevcut != null && !mevcut.contentEquals(yeni)) {
                store.atomicWrite(store.previousManifest, mevcut)
            }
        }
        runCatching { store.atomicWrite(store.currentManifest, yeni) }
            .onFailure { Log.w(TAG, "manifest diske yazilamadi: ${it.message}") }
        PlaylistBus.notifyChanged()
    }

    /**
     * Oncelik kurallari ve gerekceleri icin bkz. [SyncPlan].
     * @return indirilip kurulmayi bekleyen uygulama guncellemesi (varsa)
     */
    private suspend fun downloadInPriorityOrder(manifest: PlayManifest, client: OkHttpClient): AppUpdate? {
        val update = manifest.app?.takeIf { shouldTakeUpdate(it, manifest.rolloutGroups) }
        var hazirGuncelleme: AppUpdate? = null

        val byId = HashMap<String, ManifestItem>()
        val needs = ArrayList<SyncPlan.Need>()

        for (item in manifest.items) {
            if (isReady(item)) continue
            /*
             * "Kalan bayt" hesabinda HIC BASLANMAMIS ile BITMIS ayirt edilmek zorunda.
             *
             * remainingBytes() ikisinde de 0 doner (satir yok / hepsi done=1). Onceden
             * 0 gorunce tum dosya boyutu kalan sayiliyordu: tamami inmis ama henuz
             * dogrulanip READY yapilmamis bir dosya, siralamada EN BUYUK is gibi
             * gorunup EN SONA atiliyordu. Yani pencere, bitmesine tek bir dogrulama
             * kalan dosya yerine bastan indirilecek kocaman dosyaya harcaniyordu.
             */
            val parcaSayisi = db.chunks().count(item.sha256)
            val remaining = db.chunks().remainingBytes(item.sha256)
            needs += SyncPlan.Need(
                id = item.id,
                evergreen = item.evergreen,
                validFrom = item.validFrom,
                remainingBytes = if (parcaSayisi > 0) remaining else item.size
            )
            byId[item.id] = item
        }

        for (id in SyncPlan.order(needs, appUpdate = update != null, appCritical = update?.critical == true)) {
            if (!stillRunning()) return hazirGuncelleme
            try {
                if (id == SyncPlan.APP_UPDATE) {
                    update?.let { if (downloadUpdate(it, manifest, client)) hazirGuncelleme = it }
                } else {
                    byId[id]?.let { fetch(it, manifest, client) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                /*
                 * TEK BIR OGENIN HATASI PENCEREYI BITIRMEZ.
                 *
                 * Onceden buradan kacan bir istisna (veritabani kilidi, disk hatasi)
                 * tum senkronu iptal ediyordu: sonraki icerikler hic denenmiyor,
                 * telemetri gonderilmiyor ve DISK TEMIZLIGI hic calismiyordu - yani
                 * disk dolmasi kendi kendini besleyen bir kisir donguye giriyordu.
                 */
                Log.e(TAG, "oge indirilemedi: $id", e)
                config.lastError = "indirme hatasi ($id): ${e.message}"
            }
        }
        return hazirGuncelleme
    }

    private suspend fun isReady(item: ManifestItem): Boolean =
        db.contents().get(item.sha256)?.state == ContentState.READY &&
            store.contentFile(item.sha256, item.remotePath).exists()

    private suspend fun fetch(item: ManifestItem, manifest: PlayManifest, client: OkHttpClient) {
        val result = downloader.ensure(
            sha = item.sha256,
            remotePath = item.remotePath,
            size = item.size,
            chunks = item.chunks,
            baseUrl = config.baseUrl,
            client = client,
            parallel = manifest.policy.parallelChunks,
            stillRunning = ::stillRunning
        )
        if (result == Downloader.Result.READY) {
            // Yeni icerik hazir - oynatici bir sonraki ICERIK SINIRINDA devralsin
            PlaylistBus.notifyChanged()
        }
    }

    /**
     * Kademeli yayim karari.
     * Grup sayisi MANIFESTTEN gelir; cihazda sabitlemek, sunucu grup sayisini
     * degistirdiginde iki tarafin farkli hesap yapmasina yol acardi.
     */
    private fun shouldTakeUpdate(update: AppUpdate, groups: Int): Boolean {
        if (update.versionCode <= BuildConfig.VERSION_CODE) return false
        return RolloutGroup.of(config.deviceId, groups) <= update.rolloutGroup
    }

    /**
     * APK'yi indir - KURMA.
     * Kurulum sureci oldurdugu icin pencerenin en sonuna birakiliyor (bkz. runSync).
     * @return APK diskte ve dogrulanmis mi
     */
    private suspend fun downloadUpdate(update: AppUpdate, manifest: PlayManifest, client: OkHttpClient): Boolean {
        val result = downloader.ensure(
            sha = update.sha256,
            remotePath = update.remotePath,
            size = update.size,
            chunks = update.chunks,
            baseUrl = config.baseUrl,
            client = client,
            parallel = manifest.policy.parallelChunks,
            stillRunning = ::stillRunning
        )
        return result == Downloader.Result.READY
    }

    private fun stillRunning(): Boolean = running && (job?.isActive ?: false) && scope.isActive

    // ------------------------------------------------------------- bildirim

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(CHANNEL, getString(R.string.sync_channel), NotificationManager.IMPORTANCE_MIN)
        channel.setShowBadge(false)
        manager.createNotificationChannel(channel)
    }

    private fun notification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this)
        return builder
            .setContentTitle(getString(R.string.sync_running))
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .build()
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION") stopForeground(true)
        }
    }

    companion object {
        private const val TAG = "SyncService"
        /** Yuklenmis oynatma kayitlarinin cihazda tutuldugu sure. */
        private const val LOG_SAKLAMA_MS = 30L * 24 * 3600 * 1000
        /** Bunun altinda indirme pratikte imkansiz - panelde gorunsun. */
        private const val DISK_ALT_SINIR = 200L * 1024 * 1024
        private const val CHANNEL = "senkron"
        private const val NOTIF_ID = 1001
        const val EXTRA_NETWORK = "network"
        const val ACTION_STOP = "com.otobusreklam.player.SYNC_STOP"

        /**
         * Senkronu baslat.
         *
         * Android 12+ arka plandan on plan servisi baslatmayi KISITLAR
         * (ForegroundServiceStartNotAllowedException). Normalde bu bizi etkilemez:
         * oynatici aktivitesi HOME oldugu ve surekli ekranda durdugu icin uygulama
         * pratikte her zaman on plandadir.
         *
         * Yine de acilis aninda (HOME henuz baslamadan ag gelirse) istisna
         * firlayabilir; bu senkronu kacirmamiza yol acar ama UYGULAMAYI COKERTMEMELI.
         * Emniyet kemeri olan periyodik is bir sonraki turda tekrar dener.
         */
        fun startNow(context: Context, network: Network?) {
            val intent = Intent(context, SyncService::class.java)
            if (network != null) intent.putExtra(EXTRA_NETWORK, network)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.w(TAG, "senkron servisi baslatilamadi (arka plan kisiti?): ${e.message}")
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, SyncService::class.java).setAction(ACTION_STOP)
            runCatching { context.startService(intent) }
        }
    }
}
