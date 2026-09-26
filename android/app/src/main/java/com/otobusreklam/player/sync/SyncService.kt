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
import kotlinx.coroutines.CoroutineScope
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

    private val scope = CoroutineScope(SupervisorJob())
    private var job: Job? = null

    @Volatile private var network: Network? = null
    @Volatile private var running = false

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
            stopSelf()
            return START_NOT_STICKY
        }

        // Zaten calisan bir oturum varsa ikincisini baslatma.
        if (running) return START_STICKY

        network = net
        running = true
        job = scope.launch {
            try {
                runSync()
            } catch (e: Exception) {
                Log.e(TAG, "senkron hatasi", e)
                config.lastError = "${e.javaClass.simpleName}: ${e.message}"
            } finally {
                running = false
                stopForegroundCompat()
                stopSelf()
            }
        }
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
        stopSelf()
    }

    // ------------------------------------------------------------------ akis

    private suspend fun runSync() {
        val policyClient = Http.client(network, 8_000, 15_000)

        // 1) MANIFEST ONCE, GECIKMESIZ.
        // Kademeli baslatma (stagger) manifestten SONRA yapiliyor: manifest ~2 KB,
        // 20 cihaz ayni anda cekse bile 40 KB - AP'yi bogmaz. Agir indirmeden once
        // beklemek, pencereyi bosa harcamadan AP'yi korur.
        val manifest = fetchManifest(policyClient) ?: return

        val client = Http.client(network, manifest.policy.connectTimeoutMs, manifest.policy.readTimeoutMs)

        // 2) Manifesti diske yaz ve oynaticiyi uyar.
        // Indirmeden ONCE yayimlaniyor ki suresi dolan / iptal edilen reklam
        // dosyasi hala diskte olsa bile ANINDA yayindan dussun.
        persist(manifest)
        publish(manifest)

        // 3) Kademeli baslatma: 20 cihaz ayni milisaniyede AP'ye yuklenmesin
        val stagger = Random.nextLong(0, manifest.policy.staggerMaxMs.coerceAtLeast(1))
        Log.i(TAG, "kademeli baslatma: ${stagger}ms")
        delay(stagger)

        // 4) Oncelik sirasiyla indir
        downloader.resetSessionStats()
        downloadInPriorityOrder(manifest, client)

        // 5) Oynaticiyi tekrar uyar (yeni hazir olanlar devreye girsin)
        publish(manifest)

        // 6) Telemetri: loglar + heartbeat. Pencerenin SONUNDA, indirmeyi calmasin.
        val telemetry = Telemetry(this, config, store, db, clock)
        telemetry.uploadLogs(client)
        telemetry.sendHeartbeat(client, manifest, downloader.sessionBytes)
        telemetry.sendProofFrame(client)   // gunde en fazla bir kez, en sona birakilir

        // 7) Temizlik
        val keep = manifest.items.map { it.sha256 }.toMutableSet()
        manifest.app?.sha256?.let { keep += it }
        store.cleanup(keep)   // known-good.apk FileStore tarafinda korunuyor
        db.playLog().purgeOlderThan(clock.now() - 30L * 24 * 3600 * 1000)

        config.lastSyncAt = clock.now()
        config.lastError = ""
        Log.i(TAG, "senkron tamam, oturumda inen: ${downloader.sessionBytes / 1024} KB")
    }

    /**
     * Manifesti cek, IMZAYI DOGRULA, ayristir.
     *
     * Imza tutmazsa null doneriz ve HICBIR SEY yapilmaz: mevcut liste calmaya
     * devam eder. Bu kasitlidir - onbellek kutusu ele gecse bile cihaza sahte
     * reklam sokulamaz.
     */
    private fun fetchManifest(client: OkHttpClient): PlayManifest? {
        val url = "${config.apiUrl}/api/v1/manifest"
        return try {
            client.newCall(Http.authed(url, config.token).build()).execute().use { response ->
                if (!response.isSuccessful) {
                    config.lastError = "manifest HTTP ${response.code}"
                    Log.w(TAG, "manifest alinamadi: ${response.code}")
                    return null
                }

                // Saat duzeltmesi: ayri NTP portu acmaya gerek yok, Date basligi yeter.
                // Date basligi HER ZAMAN oncelikli - asagida sebebi aciklaniyor.
                val httpDateMs = response.headers.getDate("Date")?.time

                val envelope = response.body?.string().orEmpty()
                val json = SignatureVerifier.open(envelope, BuildConfig.MANIFEST_PUBLIC_KEY)
                val manifest = ManifestParser.parse(json)

                if (manifest.deviceId.isNotBlank() && manifest.deviceId != config.deviceId) {
                    config.lastError = "manifest baska cihaza ait: ${manifest.deviceId}"
                    return null
                }

                // SAAT KAYNAGI SIRASI ONEMLI:
                // Nokta onbellegi (nginx) PtMP linki koptugunda BAYAT manifest servis eder
                // (proxy_cache_use_stale). O manifestin govdesindeki serverTime saatlerce
                // eski olabilir; ona guvenirsek cihazin saati GERIYE gider ve suresi dolmus
                // reklamlar yeniden gecerli hale gelir.
                // Date basligini ise her zaman son atlayan sunucu (onbellek kutusu) uretir,
                // yani tazedir. Bu yuzden once Date, o yoksa govdedeki serverTime.
                val timeMs = httpDateMs ?: manifest.serverTimeMs
                timeMs?.let { clock.onServerTime(it) }
                manifest
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
    }

    private fun publish(manifest: PlayManifest) {
        // Atomik: once previous'a kopyala, sonra current'i tek hamlede degistir
        if (store.currentManifest.exists()) {
            runCatching { store.atomicWrite(store.previousManifest, store.currentManifest.readBytes()) }
        }
        store.atomicWrite(
            store.currentManifest,
            buildLocalPlaylistJson(manifest).toByteArray(Charsets.UTF_8)
        )
        PlaylistBus.notifyChanged()
    }

    /** Oynaticinin okudugu sadelestirilmis liste (parca bilgisi gerekmez). */
    private fun buildLocalPlaylistJson(manifest: PlayManifest): String {
        val items = manifest.items.joinToString(",") { i ->
            """{"id":${q(i.id)},"sha256":${q(i.sha256)},"remotePath":${q(i.remotePath)},""" +
                """"durationMs":${i.durationMs},"weight":${i.weight},""" +
                """"validFrom":${i.validFrom ?: "null"},"validUntil":${i.validUntil ?: "null"},""" +
                """"dayparts":${q(i.dayparts.joinToString(","))},"evergreen":${i.evergreen}}"""
        }
        return """{"playlistVersion":${manifest.playlistVersion},"items":[$items]}"""
    }

    private fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    /** Oncelik kurallari ve gerekceleri icin bkz. [SyncPlan]. */
    private suspend fun downloadInPriorityOrder(manifest: PlayManifest, client: OkHttpClient) {
        val update = manifest.app?.takeIf { shouldTakeUpdate(it, manifest.rolloutGroups) }

        val byId = HashMap<String, ManifestItem>()
        val needs = ArrayList<SyncPlan.Need>()

        for (item in manifest.items) {
            if (isReady(item)) continue
            val remaining = db.chunks().remainingBytes(item.sha256)
            needs += SyncPlan.Need(
                id = item.id,
                evergreen = item.evergreen,
                validFrom = item.validFrom,
                // Parca kaydi yoksa (hic baslanmamis) tum dosya kalmis demektir
                remainingBytes = if (remaining > 0L) remaining else item.size
            )
            byId[item.id] = item
        }

        for (id in SyncPlan.order(needs, appUpdate = update != null, appCritical = update?.critical == true)) {
            if (!stillRunning()) return
            if (id == SyncPlan.APP_UPDATE) {
                update?.let { applyUpdate(it, manifest, client) }
            } else {
                byId[id]?.let { fetch(it, manifest, client) }
            }
        }
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

    private suspend fun applyUpdate(update: AppUpdate, manifest: PlayManifest, client: OkHttpClient) {
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
        if (result != Downloader.Result.READY) return
        Updater(this, config, store).install(update)
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
        private const val CHANNEL = "senkron"
        private const val NOTIF_ID = 1001
        const val EXTRA_NETWORK = "network"
        const val ACTION_STOP = "com.otobusreklam.player.SYNC_STOP"

        fun startNow(context: Context, network: Network?) {
            val intent = Intent(context, SyncService::class.java)
            if (network != null) intent.putExtra(EXTRA_NETWORK, network)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, SyncService::class.java).setAction(ACTION_STOP)
            runCatching { context.startService(intent) }
        }
    }
}
