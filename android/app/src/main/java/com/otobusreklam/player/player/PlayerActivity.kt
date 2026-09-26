package com.otobusreklam.player.player

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.otobusreklam.player.BuildConfig
import com.otobusreklam.player.Config
import com.otobusreklam.player.admin.DeviceAdmin
import com.otobusreklam.player.clock.ClockManager
import com.otobusreklam.player.data.AppDatabase
import com.otobusreklam.player.data.ContentState
import com.otobusreklam.player.databinding.ActivityPlayerBinding
import com.otobusreklam.player.store.FileStore
import com.otobusreklam.player.sync.PlaylistBus
import com.otobusreklam.player.update.Updater
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Ekranda gorunen tek sey.
 *
 * Bu aktivite ayni zamanda HOME'dur. Bu kasitli: uygulama cokerse Android HOME'u
 * yeniden baslatir, yani isletim sistemi bize bedava bir watchdog saglar.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlayerBinding
    private lateinit var config: Config
    private lateinit var store: FileStore
    private lateinit var db: AppDatabase
    private lateinit var clock: ClockManager
    private lateinit var builder: PlaylistBuilder
    private lateinit var logger: PlaybackLogger
    private lateinit var admin: DeviceAdmin

    private var player: ExoPlayer? = null

    /** Su an oynayan ogenin kaydi - gecis aninda log yazmak icin tutuluyor. */
    private var currentEntry: PlaylistBuilder.Entry? = null
    private var currentStartedAt: Long = 0L

    /** Hazir bekleyen yeni liste. VIDEONUN ORTASINDA DEGIL, icerik sinirinda uygulanir. */
    private var pendingPlaylist: List<PlaylistBuilder.Entry>? = null
    private var lastReason: String = ""

    private val handler = Handler(Looper.getMainLooper())
    private var lastPosition = -1L
    private var stallCount = 0
    private var lastRebootDay = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        config = Config(this)
        store = FileStore(this)
        db = AppDatabase.get(this)
        clock = ClockManager(this)
        builder = PlaylistBuilder(store, db, clock)
        logger = PlaybackLogger(db, clock, config)
        admin = DeviceAdmin(this)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUi()

        // ACILIS SAGLIK SAYACI:
        // Simdi artiriyoruz, 2 dakika saglikli calisirsak sifirliyoruz.
        // Uygulama acilista cokerse sayac artmis kalir; esik asilinca onceki
        // surume donulur. (Surec hic baslamadan cokerse bu kod calismaz -
        // asil koruma kademeli yayimdir.)
        config.startupFailures = config.startupFailures + 1
        handler.postDelayed({ config.startupFailures = 0 }, HEALTHY_AFTER_MS)

        if (config.startupFailures >= 3) {
            Updater(this, config, store).checkStartupHealth()
        }

        admin.applyPolicies()
        admin.enterKiosk(this)

        initPlayer()
        observePlaylist()
        handler.postDelayed(watchdog, WATCHDOG_MS)
    }

    override fun onResume() {
        super.onResume()
        hideSystemUi()
        admin.enterKiosk(this)
        player?.play()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        // Yarim kalan oynatma da kayda gecsin (completed = false)
        flushCurrentPlay(completed = false)
        player?.release()
        player = null
        super.onDestroy()
    }

    /** Kiosk: kumanda ve tuslar hicbir sey yapmasin. Tek istisna teshis ekrani. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_INFO || keyCode == KeyEvent.KEYCODE_MENU) {
            toggleDiagnostics()
            return true
        }
        return true   // diger tum tuslar yutulur
    }

    override fun onBackPressed() { /* kiosk: geri tusu yok */ }

    // --------------------------------------------------------------- oynatici

    private fun initPlayer() {
        val exo = ExoPlayer.Builder(this).build()
        exo.repeatMode = Player.REPEAT_MODE_ALL
        exo.playWhenReady = true
        // Otobus ici ses politikasi: varsayilan sessiz.
        // Isletmeci ses istiyorsa burayi manifest policy'sine baglayin.
        exo.volume = 0f

        exo.addListener(object : Player.Listener {

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                // Onceki oge bitti -> oynatma kanitina yaz
                flushCurrentPlay(completed = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)

                currentEntry = mediaItem?.localConfiguration?.tag as? PlaylistBuilder.Entry
                currentStartedAt = clock.now()

                // Bekleyen liste varsa TAM BURADA uygula: icerik siniri,
                // yani izleyici acisindan en az rahatsiz edici an.
                pendingPlaylist?.let { next ->
                    pendingPlaylist = null
                    applyPlaylist(next)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                val entry = currentEntry
                Log.e(TAG, "oynatma hatasi: ${error.errorCodeName} / ${entry?.itemId}", error)
                if (entry != null) markContentFailed(entry)
                // Dongu OLMEMELI: sonraki ogeye gec ve yeniden hazirla
                player?.let {
                    if (it.mediaItemCount > 1) it.seekToNextMediaItem()
                    it.prepare()
                }
                refreshPlaylist(immediate = true)
            }
        })

        binding.playerView.player = exo
        binding.playerView.useController = false
        player = exo

        refreshPlaylist(immediate = true)
    }

    private fun observePlaylist() {
        lifecycleScope.launch {
            PlaylistBus.revision.collect {
                // Senkron yeni icerik indirdi veya liste degisti.
                // Hemen degil: siradaki icerik sinirinda devralinacak.
                refreshPlaylist(immediate = false)
            }
        }
    }

    /**
     * @param immediate true ise (ilk acilis / hata sonrasi) liste hemen uygulanir;
     *                  false ise bir sonraki icerik sinirini bekler.
     */
    private fun refreshPlaylist(immediate: Boolean) {
        lifecycleScope.launch {
            val result = builder.build()
            lastReason = result.reason

            if (result.entries.isEmpty()) {
                Log.e(TAG, "liste bos: ${result.reason}")
                return@launch
            }
            val playing = player?.mediaItemCount ?: 0
            if (immediate || playing == 0) {
                applyPlaylist(result.entries)
            } else if (!sameAsCurrent(result.entries)) {
                pendingPlaylist = result.entries
                Log.i(TAG, "yeni liste hazir (${result.entries.size} oge), icerik sinirinda uygulanacak")
            }
        }
    }

    private fun sameAsCurrent(entries: List<PlaylistBuilder.Entry>): Boolean {
        val exo = player ?: return false
        if (exo.mediaItemCount != entries.size) return false
        for (i in 0 until exo.mediaItemCount) {
            val tag = exo.getMediaItemAt(i).localConfiguration?.tag as? PlaylistBuilder.Entry
            if (tag?.sha256 != entries[i].sha256 || tag.itemId != entries[i].itemId) return false
        }
        return true
    }

    private fun applyPlaylist(entries: List<PlaylistBuilder.Entry>) {
        val exo = player ?: return
        val mediaItems = entries.map { entry ->
            MediaItem.Builder()
                .setUri(Uri.fromFile(entry.file))
                .setMediaId(entry.itemId)
                .setTag(entry)
                .build()
        }
        exo.setMediaItems(mediaItems, 0, 0L)
        exo.prepare()
        exo.play()
        currentEntry = entries.firstOrNull()
        currentStartedAt = clock.now()
        Log.i(TAG, "liste uygulandi: ${entries.size} oge (${lastReason})")
        updateDiagnostics()
    }

    private fun flushCurrentPlay(completed: Boolean) {
        val entry = currentEntry ?: return
        val started = currentStartedAt
        if (started <= 0L) return
        val elapsed = (clock.now() - started).coerceAtLeast(0L)
        currentEntry = null
        currentStartedAt = 0L

        lifecycleScope.launch {
            logger.record(
                itemId = entry.itemId,
                sha256 = entry.sha256,
                startedAt = started,
                // Tam oynatildiysa dosyanin gercek suresini yaz, yarim kaldiysa gecen sure
                durationMs = if (completed && entry.durationMs > 0) entry.durationMs else elapsed,
                completed = completed
            )
        }
    }

    /**
     * Ucuncu hatada icerik BAD isaretlenir: dosya saglam inmis olsa bile cihaz
     * onu cozemiyordur (kodek). Donguden cikarilir ve heartbeat ile panele bildirilir;
     * cozum sunucuda yeniden kodlamaktir, tekrar indirmek degil.
     */
    private fun markContentFailed(entry: PlaylistBuilder.Entry) {
        lifecycleScope.launch {
            db.contents().bumpFail(entry.sha256)
            val content = db.contents().get(entry.sha256)
            if ((content?.failCount ?: 0) >= MAX_PLAY_FAILURES) {
                db.contents().setState(entry.sha256, ContentState.BAD, clock.now())
                config.lastError = "oynatilamiyor: ${entry.itemId}"
                Log.e(TAG, "icerik BAD isaretlendi: ${entry.itemId}")
            }
        }
    }

    // --------------------------------------------------------------- watchdog

    private val watchdog = object : Runnable {
        override fun run() {
            checkStall()
            checkNightlyReboot()
            updateDiagnostics()
            handler.postDelayed(this, WATCHDOG_MS)
        }
    }

    private fun checkStall() {
        val exo = player ?: return
        val position = exo.currentPosition

        val stalled = when {
            exo.mediaItemCount == 0 -> false          // icerik yok, takilma degil
            !exo.playWhenReady -> false
            exo.isPlaying && position != lastPosition -> false
            else -> true
        }
        lastPosition = position

        if (!stalled) { stallCount = 0; return }

        stallCount++
        Log.w(TAG, "oynatma ilerlemiyor (sayac=$stallCount)")

        when {
            stallCount == STALL_REBUILD -> {
                Log.w(TAG, "oynatici yeniden kuruluyor")
                player?.release()
                player = null
                initPlayer()
            }
            stallCount >= STALL_REBOOT -> {
                Log.e(TAG, "takilma gecmedi -> cihaz yeniden baslatiliyor")
                // Sayac BootReceiver'da artiyor; burada artirmak mukerrer sayim olurdu.
                config.lastError = "watchdog reboot"
                if (!admin.reboot()) {
                    // Cihaz sahibi degilsek yeniden baslatamayiz; en azindan
                    // sureci oldur, HOME oldugumuz icin Android bizi geri actiracak.
                    finishAffinity()
                    Runtime.getRuntime().exit(0)
                }
            }
        }
    }

    /**
     * Gece kontrollu yeniden baslatma - garajda, yayin saatinde degil.
     * Uzun sureli bellek sizintilari ve takilmalar icin ucuz bir sigorta.
     */
    private fun checkNightlyReboot() {
        if (!clock.trusted()) return
        val local = Instant.ofEpochMilli(clock.now()).atZone(ZoneId.systemDefault())
        val today = local.toLocalDate().toString()
        if (today == lastRebootDay) return
        if (local.hour != REBOOT_HOUR) return
        if (local.minute !in REBOOT_MINUTE_FROM..REBOOT_MINUTE_TO) return

        lastRebootDay = today
        Log.i(TAG, "gece kontrollu yeniden baslatma")
        admin.reboot()
    }

    // ------------------------------------------------------------- teshis

    private fun toggleDiagnostics() {
        binding.diag.visibility =
            if (binding.diag.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        updateDiagnostics()
    }

    private fun updateDiagnostics() {
        if (binding.diag.visibility != View.VISIBLE) return
        lifecycleScope.launch {
            val contents = db.contents().all()
            val ready = contents.count { it.state == ContentState.READY }
            val fmt = DateTimeFormatter.ofPattern("dd.MM HH:mm").withZone(ZoneId.systemDefault())
            binding.diag.text = buildString {
                appendLine("cihaz    : ${config.deviceId}")
                appendLine("surum    : ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                appendLine("liste    : ${config.playlistVersion} - $lastReason")
                appendLine("icerik   : $ready/${contents.size} hazir")
                appendLine("son senk : " + if (config.lastSyncAt > 0) fmt.format(Instant.ofEpochMilli(config.lastSyncAt)) else "hic")
                appendLine("saat     : " + if (clock.trusted()) "guvenilir" else "SUPHELI (${clock.note})")
                appendLine("sahip    : " + if (admin.isDeviceOwner) "evet" else "HAYIR")
                appendLine("sunucu   : ${config.baseUrl}")
                if (config.lastError.isNotBlank()) appendLine("hata     : ${config.lastError}")
            }
        }
    }

    private fun hideSystemUi() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )
    }

    private companion object {
        const val TAG = "PlayerActivity"
        const val WATCHDOG_MS = 30_000L
        const val HEALTHY_AFTER_MS = 120_000L
        const val STALL_REBUILD = 2
        const val STALL_REBOOT = 6
        const val MAX_PLAY_FAILURES = 3
        const val REBOOT_HOUR = 3
        const val REBOOT_MINUTE_FROM = 25
        const val REBOOT_MINUTE_TO = 35
    }
}
