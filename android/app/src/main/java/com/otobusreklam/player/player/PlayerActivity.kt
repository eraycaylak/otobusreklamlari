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
import com.otobusreklam.player.R
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
    /* Fatura butunlugu: bu ikisi OYNATMANIN BASLADIGI anda yakalanir, yazma aninda
       degil. Yazma asenkron oldugu icin arada bir senkron gerceklesirse 1970 damgali
       bir kayit "saati guvenilir" olarak faturaya girebilirdi. */
    private var currentClockTrusted: Boolean = false
    private var currentPlaylistVersion: Int = 0

    /** Hazir bekleyen yeni liste. VIDEONUN ORTASINDA DEGIL, icerik sinirinda uygulanir. */
    private var pendingPlaylist: List<PlaylistBuilder.Entry>? = null
    private var lastReason: String = ""

    private val handler = Handler(Looper.getMainLooper())
    private var lastPosition = -1L
    private var stallCount = 0

    /** Teshis ekrani icin tus dizisi (bkz. onKeyDown). */
    private var tusSayaci = 0
    private var sonTusAni = 0L
    private val teshisKapat = Runnable {
        binding.diag.visibility = View.GONE
        Log.i(TAG, "teshis ekrani zaman asimiyla kapatildi")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        config = Config(this)
        store = FileStore(this)
        db = AppDatabase.get(this)
        clock = ClockManager(this)
        builder = PlaylistBuilder(store, db, clock, config)
        logger = PlaybackLogger(db, clock, config)
        admin = DeviceAdmin(this)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUi()

        /*
         * ACILIS SAGLIK SAYACI.
         *
         * Amaci tek: "yeni surum acilir acilmaz cokuyor mu?" Bunun icin cokme
         * DONGUSUNU olcmek gerekir, tek bir yeniden olusturmayi degil.
         *
         * Onceden sayac her onCreate'te artiyor ve yalnizca 2 dakika sonra calisan bir
         * Handler ile sifirlaniyordu - ama onDestroy tum callback'leri iptal ediyordu.
         * Yani aktivitenin normal bir sebeple yeniden olusmasi (HDMI cozunurluk
         * degisimi, dil/yapilandirma degisikligi, ROM'un aktiviteyi geri donusturmesi)
         * sayaci artirip sifirlama sansini yok ediyordu. Ucuncusunde cihaz kendini
         * "bozuk surum" sanip GUVENLI MODA geciyordu - ortada hicbir cokme yokken.
         *
         * Cozum: sayaci ONCEKI ACILISLA ARASINDAKI SUREYE gore degerlendiriyoruz.
         * Monotonik saat kullaniyoruz; sistem saati bu cihazlarda guvenilmez ve negatif
         * fark (yeniden baslatma) da dogru bicimde sifirlama sayilir.
         */
        val simdi = android.os.SystemClock.elapsedRealtime()
        val oncekiAcilis = config.lastStartElapsed
        val fark = simdi - oncekiAcilis
        if (oncekiAcilis <= 0L || fark < 0L || fark > HEALTHY_AFTER_MS) {
            // Ya ilk acilis, ya yeniden baslatma, ya da onceki calisma SAGLIKLIYDI.
            config.startupFailures = 0
        }
        config.lastStartElapsed = simdi
        config.startupFailures = config.startupFailures + 1

        handler.postDelayed({
            config.startupFailures = 0
            if (config.safeMode) {
                // 2 dakika sorunsuz calistik: guvenli moddan cikilabilir.
                Log.i(TAG, "guvenli mod kapatiliyor, surum saglikli calisiyor")
                config.safeMode = false
            }
        }, HEALTHY_AFTER_MS)

        val guvenliMod = Updater(this, config, store).startupHealthCheck()
        config.safeMode = guvenliMod
        if (guvenliMod) Log.w(TAG, "GUVENLI MOD: sadece oynatma ve senkron calisiyor")

        /*
         * Zorunlu olmayan her is AYRI AYRI korunuyor.
         *
         * Bu bir kiosk: ekranin kararmasi isin kendisinin durmasi demek. Cihaz
         * sahibi politikalarinda veya kiosk kilidinde beklenmedik bir istisna
         * (ROM farki, kaldirilmis yetki) tum aktiviteyi dusurup ekrani karartirdi.
         * Reklam oynatmak, kiosk kilidinden daha onceliklidir.
         */
        runCatching { admin.applyPolicies() }
            .onFailure { Log.e(TAG, "cihaz politikalari uygulanamadi, devam ediliyor", it) }
        runCatching { admin.enterKiosk(this) }
            .onFailure { Log.e(TAG, "kiosk moduna girilemedi, devam ediliyor", it) }

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
        flushCurrentPlay(completed = false, bekleyerek = true)
        player?.release()
        player = null
        super.onDestroy()
    }

    /**
     * Kiosk: kumanda ve tuslar hicbir sey yapmasin. Tek istisna teshis ekrani.
     *
     * TESHIS EKRANI UC BASISLA ACILIR.
     *
     * Tek basista aciliyordu ve bir kez acildiginda EKRANDA KALIYORDU. Otobusteki
     * televizyonun kumandasi yolcularin erisebilecegi bir yerde olabilir; tek bir
     * INFO/MENU basisi reklamin uzerine cihaz kimligi, sunucu adresi ve son hata
     * metnini yaziyor ve orada birakiyordu - yani reklamveren parasini odedigi
     * ekranda teknik dokum goruyordu. Ustelik ekran bir daha kapanmiyordu.
     *
     * Kasitli bir hareket gerekiyor (2 saniyede uc basis) ve ekran 60 saniye sonra
     * KENDILIGINDEN kapaniyor: teknisyene yetiyor, kazara acilmiyor, kalici olmuyor.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_INFO || keyCode == KeyEvent.KEYCODE_MENU) {
            val simdi = android.os.SystemClock.elapsedRealtime()
            if (simdi - sonTusAni > TESHIS_PENCERE_MS) tusSayaci = 0
            sonTusAni = simdi
            tusSayaci++
            if (tusSayaci >= TESHIS_BASIS) {
                tusSayaci = 0
                toggleDiagnostics()
            }
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
                /*
                 * REPEAT de TAM OYNATMADIR.
                 *
                 * Listede TEK oge varsa ExoPlayer her tur icin AUTO degil REPEAT sebebini
                 * bildirir. Sadece AUTO'yu saymak, tek reklamli bir otobuste her oynatmayi
                 * "yarim kalmis" olarak kaydediyordu - yani o otobus fatura raporunda HIC
                 * gorunmuyordu. Ikisi de dogal bitistir.
                 */
                val tamOynatildi = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
                    reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT
                flushCurrentPlay(completed = tamOynatildi)

                val saat = clock.snapshot()
                currentEntry = mediaItem?.localConfiguration?.tag as? PlaylistBuilder.Entry
                currentStartedAt = saat.nowMs
                currentClockTrusted = saat.trusted
                currentPlaylistVersion = config.playlistVersion

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
                // immediate=false ONEMLI: immediate=true listeyi 0. ogeden yeniden
                // kurup az once yaptigimiz "sonraki ogeye gec" hamlesini GERI ALIR ve
                // bozuk ogeye donerdi - sonsuz hata dongusu. Yeni liste bir sonraki
                // icerik sinirinda devreye girsin.
                refreshPlaylist(immediate = false)
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
                /*
                 * EKRANIN BOS KALMASI bu sistemin en kotu sonucudur: reklamveren para
                 * odedi, ekran siyah. Onceden bu durum SADECE cihaz loguna yaziliyordu -
                 * yani kimse ogrenmiyordu. Artik lastError'a yaziliyor ve ilk heartbeat'te
                 * panele dusuyor.
                 */
                Log.e(TAG, "liste bos: ${result.reason}")
                config.lastError = "EKRAN BOS: ${result.reason}"
                /*
                 * EKRANDA DA GORUNSUN.
                 *
                 * Sebep heartbeat ile panele gidiyor ama panel ancak cihaz senkron
                 * OLABILDIGINDE guncellenir - oysa ekranin bos kalmasinin en yaygin
                 * sebebi tam olarak senkron olamamaktir. O durumda siyah ekran hicbir
                 * sey soylemez: otobuse cikan kisi cihazin bozuk mu, kablonun cikmis mi,
                 * yoksa icerigin mi inmedigini anlayamaz. Bir satir metin bu ziyareti
                 * tek basina cozer. Gecerli bir liste geldiginde yaziyi kaldiriyoruz,
                 * yani reklamin uzerinde asla kalmaz.
                 */
                binding.bos.visibility = View.VISIBLE
                binding.bos.text = getString(
                    R.string.screen_empty, config.deviceId, result.reason,
                    if (config.lastSyncAt > 0) DateTimeFormatter.ofPattern("dd.MM HH:mm")
                        .withZone(config.zoneId).format(Instant.ofEpochMilli(config.lastSyncAt)) else "hic"
                )
                return@launch
            }
            binding.bos.visibility = View.GONE
            // Ekran yeniden doldu: onceki "bos" uyarisini temizle
            if (config.lastError.startsWith("EKRAN BOS")) config.lastError = ""

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

        /*
         * SIRA: once mevcut oynatmayi KAPAT, sonra listeyi degistir.
         *
         * setMediaItems, gecis geri cagrisini (MEDIA_ITEM_TRANSITION) AYNI CAGRI ICINDE
         * tetikleyebilir. Onceden izleme alanlari (currentEntry/currentStartedAt) bu
         * cagridan SONRA yaziliyordu: geri cagri yeni ogeyi kurar, hemen ardindan biz
         * ustune yazardik. Iki yerin ayni durumu farkli sirayla kurmasi, kaydin hangi
         * ogeye ait oldugunu belirsiz kilar - faturanin dayanagi olan veri icin
         * kabul edilemez bir belirsizlik.
         *
         * Artik tek kural var: kaydi BURADA kapatiyoruz (currentEntry null olur), yeni
         * ogenin izlenmesini ISE YALNIZCA gecis geri cagrisi kurar. Geri cagri hic
         * tetiklenmezse asagidaki yedek devreye girer.
         */
        flushCurrentPlay(completed = false)

        exo.setMediaItems(mediaItems, 0, 0L)
        exo.prepare()
        exo.play()

        if (currentEntry == null) {
            // Gecis geri cagrisi tetiklenmedi (ayni oge / surum farki): elle kur.
            val saat = clock.snapshot()
            currentEntry = entries.firstOrNull()
            currentStartedAt = saat.nowMs
            currentClockTrusted = saat.trusted
            currentPlaylistVersion = config.playlistVersion
        }
        Log.i(TAG, "liste uygulandi: ${entries.size} oge ($lastReason)")
        updateDiagnostics()
    }

    /** @param bekleyerek yeniden baslatma/kapanis oncesi: yazmanin diske inmesini bekle */
    private fun flushCurrentPlay(completed: Boolean, bekleyerek: Boolean = false) {
        val entry = currentEntry ?: return
        val started = currentStartedAt
        if (started <= 0L) return
        val elapsed = (clock.now() - started).coerceAtLeast(0L)
        val saatGuveni = currentClockTrusted
        val listeSurumu = currentPlaylistVersion
        currentEntry = null
        currentStartedAt = 0L

        // Tam oynatildiysa dosyanin gercek suresini yaz, yarim kaldiysa gecen sure
        val sure = if (completed && entry.durationMs > 0) entry.durationMs else elapsed

        // logger.kaydet(): kendi kapsamindan yazar. lifecycleScope kullansaydik
        // onDestroy'daki son kayit hic yazilmazdi (kapsam o an iptal edilmis olur).
        if (bekleyerek) {
            logger.bekleyerekKaydet(entry.itemId, entry.sha256, started, sure, completed, saatGuveni, listeSurumu)
        } else {
            logger.kaydet(entry.itemId, entry.sha256, started, sure, completed, saatGuveni, listeSurumu)
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
            yenidenDegerlendir()
            checkNightlyReboot()
            updateDiagnostics()
            handler.postDelayed(this, WATCHDOG_MS)
        }
    }

    /**
     * Listeyi PERIYODIK olarak yeniden degerlendir.
     *
     * BU OLMADAN daypart ozelligi FIILEN CALISMIYORDU: liste yalnizca senkron veya
     * hata aninda kuruluyordu. Otobus gun icinde senkron olmazsa 07:00-10:00 araligi
     * icin tanimlanmis bir reklam aksama kadar donmeye devam ediyordu - ve daha kotusu,
     * suresi dolan (validUntil) bir reklam bir sonraki senkrona kadar yayinda kaliyordu.
     * Ikisi de dogrudan faturalanabilir hata.
     *
     * Pahali degil: iki kucuk veritabani sorgusu. Liste GERCEKTEN degismediyse
     * refreshPlaylist zaten hicbir sey yapmaz (sameAsCurrent kontrolu).
     */
    private fun yenidenDegerlendir() {
        refreshPlaylist(immediate = player?.mediaItemCount == 0)
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
                // Kuyruktaki oynatma kaydi yeniden baslatmayla kaybolmasin
                flushCurrentPlay(completed = false, bekleyerek = true)
                // Sayac BootReceiver'da artiyor; burada artirmak mukerrer sayim olurdu.
                config.lastError = "watchdog reboot"
                if (!admin.reboot()) kendiniYenidenBaslat()
            }
        }
    }

    /**
     * SURECI OLDUR AMA GERI GELMEYI GARANTIYE AL.
     *
     * Eski kod `finishAffinity(); exit(0)` yapiyor ve "HOME oldugumuz icin Android bizi
     * geri acar" diyordu. Bu tam da CALISMADIGI durumda devreye giriyor: buraya ancak
     * admin.reboot() basarisiz olunca geliyoruz, yani cihaz sahibi DEGILIZ - ve cihaz
     * sahibi olmadan kalici HOME'u da devralamamisizdir (addPersistentPreferredActivity
     * cihaz sahibi yetkisi ister). Yani surec olur, ROM'un kendi launcher'i one gelir ve
     * ekranda reklam yerine ANDROID ANA EKRANI kalir - hem de kimsenin bilmedigi bicimde,
     * cihaza fiziksel olarak gidilene kadar.
     *
     * Once kendimizi yeniden baslatmak icin alarm kuruyoruz, sonra sureci olduruyoruz.
     * Alarm tetiklenince aktivite yeniden acilir - HOME olup olmadigimizdan bagimsiz.
     */
    private fun kendiniYenidenBaslat() {
        runCatching {
            val niyet = android.content.Intent(this, PlayerActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val bayrak = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M)
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
            else
                android.app.PendingIntent.FLAG_UPDATE_CURRENT
            val bekleyen = android.app.PendingIntent.getActivity(this, 42, niyet, bayrak)
            val alarm = getSystemService(android.app.AlarmManager::class.java)
            alarm?.set(
                android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,
                android.os.SystemClock.elapsedRealtime() + YENIDEN_ACILIS_MS,
                bekleyen
            )
            Log.w(TAG, "surec olduruluyor, ${YENIDEN_ACILIS_MS / 1000} sn sonra yeniden acilacak")
        }.onFailure { Log.e(TAG, "yeniden acilis alarmi kurulamadi", it) }

        finishAffinity()
        Runtime.getRuntime().exit(0)
    }

    /**
     * Gece kontrollu yeniden baslatma - garajda, yayin saatinde degil.
     * Uzun sureli bellek sizintilari ve takilmalar icin ucuz bir sigorta.
     */
    private fun checkNightlyReboot() {
        /*
         * GUVENLI MODDA YENIDEN BASLATMA YOK.
         *
         * Guvenli mod "bu surum acilista cokuyor olabilir" demektir. Boyle bir cihazi
         * her gece yeniden baslatmak durumu iyilestirmez: her acilis yeni bir cokme
         * sansi ve o gece boyunca ekran bos kalabilir. Bu modda tek isimiz duzeltilmis
         * surumu indirebilecek kadar ayakta kalmak.
         */
        if (config.safeMode) return
        val saat = clock.snapshot()
        if (!saat.trusted) return
        // Dilim sunucudan: "gece 03:25" isletmenin gecesi olmali, ROM varsayilaninin degil.
        val local = Instant.ofEpochMilli(saat.nowMs).atZone(config.zoneId)
        val today = local.toLocalDate().toString()
        // KALICI kontrol: cihaz yeniden baslayip hala pencere icindeyken
        // tekrar yeniden baslatmasin (yeniden baslatma dongusu).
        if (today == config.lastRebootDay) return
        if (local.hour != REBOOT_HOUR) return
        if (local.minute !in REBOOT_MINUTE_FROM..REBOOT_MINUTE_TO) return

        config.lastRebootDay = today
        Log.i(TAG, "gece kontrollu yeniden baslatma")
        flushCurrentPlay(completed = false, bekleyerek = true)
        admin.reboot()
    }

    // ------------------------------------------------------------- teshis

    private fun toggleDiagnostics() {
        val acilacak = binding.diag.visibility != View.VISIBLE
        binding.diag.visibility = if (acilacak) View.VISIBLE else View.GONE
        // Teknisyen kapatmayi unutursa ekran reklamin uzerinde kalmasin.
        handler.removeCallbacks(teshisKapat)
        if (acilacak) handler.postDelayed(teshisKapat, TESHIS_SURE_MS)
        updateDiagnostics()
    }

    private fun updateDiagnostics() {
        if (binding.diag.visibility != View.VISIBLE) return
        lifecycleScope.launch {
            val contents = db.contents().all()
            val ready = contents.count { it.state == ContentState.READY }
            val saat = clock.snapshot()
            val fmt = DateTimeFormatter.ofPattern("dd.MM HH:mm").withZone(config.zoneId)
            binding.diag.text = buildString {
                appendLine("cihaz    : ${config.deviceId}")
                appendLine("surum    : ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                appendLine("liste    : ${config.playlistVersion} - $lastReason")
                appendLine("icerik   : $ready/${contents.size} hazir")
                appendLine("son senk : " + if (config.lastSyncAt > 0) fmt.format(Instant.ofEpochMilli(config.lastSyncAt)) else "hic")
                appendLine("saat     : " + if (saat.trusted) "guvenilir" else "SUPHELI (${saat.reason} ${clock.note})")
                appendLine("dilim    : " + config.zoneId + if (config.timezone.isBlank()) " (cihaz)" else " (sunucu)")
                appendLine("sahip    : " + if (admin.isDeviceOwner) "evet" else "HAYIR")
                appendLine("sunucu   : ${config.baseUrl}")
                if (config.lastError.isNotBlank()) appendLine("hata     : ${config.lastError}")
                if (config.lastInstallError.isNotBlank()) appendLine("kurulum  : ${config.lastInstallError}")
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
        /** Teshis ekrani: kac basis, hangi pencerede, ne kadar acik kalir. */
        const val TESHIS_BASIS = 3
        const val TESHIS_PENCERE_MS = 2_000L
        const val TESHIS_SURE_MS = 60_000L
        /** Surec oldurulduktan sonra kendini yeniden acma gecikmesi. */
        const val YENIDEN_ACILIS_MS = 3_000L
        const val REBOOT_HOUR = 3
        const val REBOOT_MINUTE_FROM = 25
        const val REBOOT_MINUTE_TO = 35
    }
}
