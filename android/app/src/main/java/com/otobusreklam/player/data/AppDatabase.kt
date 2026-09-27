package com.otobusreklam.player.data

import android.content.Context
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.otobusreklam.player.Config
import java.io.File

@Database(
    entities = [ItemEntity::class, ContentEntity::class, ChunkEntity::class, PlayLogEntity::class],
    version = 1,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun items(): ItemDao
    abstract fun contents(): ContentDao
    abstract fun chunks(): ChunkDao
    abstract fun playLog(): PlayLogDao

    /**
     * Bakim turu - her senkronun sonunda calisir.
     *
     * @param keepShas manifestte HALA duran icerik sha'lari (+ APK)
     * @param logsBefore bu andan eski YUKLENMIS loglar silinir
     */
    suspend fun bakim(keepShas: Collection<String>, logsBefore: Long) {
        /*
         * OKSUZ SATIRLARI BIRAK.
         *
         * Dosyalari FileStore.cleanup() siliyordu ama contents/chunks SATIRLARINI
         * silen hicbir yol yoktu. Sonuc: manifestten dusen bir icerigin .part dosyasi
         * silinir, geride done=1 isaretli parca satirlari kalir. O icerik daha sonra
         * geri gelirse (kampanya yeniden yayina alindi - ayni sha) Downloader
         * "bekleyen parca yok" gorur, dosya ise diskte olmadigi icin tam dosya hash'i
         * tutmaz ve icerik her pencerede bastan indirilip her seferinde ayni yerde
         * tikanir. Satirlar da suresiz birikerek veritabanini sisirir.
         */
        val keep = keepShas.toList()
        if (keep.isEmpty()) {
            contents().deleteAll()
            chunks().deleteAll()
        } else {
            contents().deleteNotIn(keep)
            chunks().deleteNotIn(keep)
        }

        playLog().purgeOlderThan(logsBefore)

        // Yuklenemeyen loglar icin son care tavani (bkz. PlayLogDao.dropOldest).
        val toplam = playLog().totalCount()
        if (toplam > LOG_TAVANI) {
            val fazla = toplam - LOG_TAVANI
            Log.e(
                TAG,
                "play_log tavani asildi ($toplam > $LOG_TAVANI): EN ESKI $fazla satir " +
                    "siliniyor. Bu cihaz uzun suredir log yukleyemiyor - panelde pendingLogs'a bakin."
            )
            playLog().dropOldest(fazla)
        }

        /*
         * VACUUM: SQLite silinen alani isletim sistemine GERI VERMEZ, dosya icinde
         * "bos sayfa" olarak tutar. Diski dolduran bir cihazda temizlik yaptigimiz
         * halde bos alan artmazsa hicbir sey cozulmemis olur - indirme yine durur.
         * Pahali bir islem oldugu icin yalnizca gercekten bir sey sildiysek.
         */
        if (toplam > LOG_TAVANI) {
            runCatching { openHelper.writableDatabase.execSQL("VACUUM") }
                .onFailure { Log.w(TAG, "VACUUM basarisiz: ${it.message}") }
        }
    }

    companion object {
        private const val TAG = "AppDatabase"
        private const val NAME = "reklam.db"

        /**
         * Bekleyen + yuklenmis toplam log satiri tavani.
         *
         * Buyuk tutuluyor: satir ~200 bayt, 200.000 satir ~40 MB. Bir otobus gunde
         * ~3.000 oynatma yapar, yani bu tavan 60 GUNluk tamamen yuklenememis kaydi
         * tasir. Fatura verisini silmek son caredir; tavanin amaci diski doldurup
         * EKRANI karartmasini onlemek.
         */
        const val LOG_TAVANI = 200_000

        /** Saklanan bozuk veritabani kopyasi sayisi. */
        private const val ARSIV_SINIRI = 2

        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: open(context.applicationContext).also { instance = it }
        }

        /**
         * Veritabanini ac; acilamazsa ESKI DOSYAYI SAKLAYARAK yenisini olustur.
         *
         * NEDEN fallbackToDestructiveMigration KULLANILMIYOR:
         * Bu veritabani play_log tablosunu tutuyor ve o tablo REKLAMVEREN FATURASININ
         * DAYANAGI. Destructive fallback, sema surumu her degistiginde henuz sunucuya
         * yuklenmemis oynatma kayitlarini SESSIZCE siler. Bir gelistirici surumu
         * artirip migration yazmayi unutursa, guncelleme tum filoda fatura verisi
         * kaybina yol acardi ve kimse fark etmezdi.
         *
         * Bunun yerine:
         *   - Sema degisiklikleri icin GERCEK migration yazilir (addMigrations)
         *   - Yine de acilamazsa (bozulma, eksik migration) eski dosya SILINMEZ,
         *     yeniden adlandirilarak saklanir ve temiz bir veritabani acilir
         *
         * DIKKAT: cihazlar sahaya cikttiktan sonra HER sema degisikligi migration
         * gerektirir; aksi halde bu yol devreye girer ve o cihazin yuklenmemis
         * kayitlari (silinmese de) arsiv dosyasinda kalir.
         */
        private fun open(context: Context): AppDatabase {
            // Ornek DISARIDA tutuluyor: catch icinde onu KAPATMAMIZ gerekiyor.
            // Onceden `build(context).also { ... }` yaziliyordu ve istisna firladiginda
            // o ornege giden tek referans kayboluyordu - acilamayan veritabani yine de
            // bir dosya tanitici ve WAL kilidi tutar, dolayisiyla arsivleme (rename)
            // ve ardindan gelen temiz acilis ayni hatayi alabilir.
            var aday: AppDatabase? = null
            return try {
                aday = build(context)
                // Semayi HEMEN dogrula: sorun varsa burada patlasin, ilk sorguda
                // beklenmedik bir anda degil.
                aday.openHelper.writableDatabase
                aday
            } catch (e: Exception) {
                Log.e(TAG, "veritabani acilamadi, eski dosya saklanip yenisi olusturuluyor", e)
                runCatching { aday?.close() }
                arsivle(context)
                try {
                    // Yedek yol da DOGRULANIYOR: yalnizca build() cagirmak hicbir sey
                    // acmaz (Room tembel acar), yani "yenisi olustu" sozu ilk sorguya
                    // kadar sinanmamis kalirdi.
                    val temiz = build(context)
                    temiz.openHelper.writableDatabase
                    temiz
                } catch (e2: Exception) {
                    // Temiz dosya da acilamiyorsa (disk dolu, izin, bozuk eMMC) burada
                    // patlamak dogrudur: ilk sorguya kadar gizlemek teshisi imkansiz kilar.
                    Log.e(TAG, "temiz veritabani da acilamadi", e2)
                    throw e2
                }
            }
        }

        private fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, NAME)
                // Ani guc kesintisi bu cihazin normal hayati.
                .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                // Sema degisirse buraya Migration ekleyin:
                //   .addMigrations(MIGRATION_1_2)
                // Destructive fallback BILEREK kullanilmiyor (yukaridaki aciklama).
                .fallbackToDestructiveMigrationOnDowngrade()
                .addCallback(object : RoomDatabase.Callback() {
                    /**
                     * VERITABANI SIFIRDAN OLUSTU -> KURULUM KIMLIGINI YENILE.
                     *
                     * play_log.seq bu dosyadan gelir ve dosya yeniyse 1'DEN BASLAR.
                     * Sunucu tekrarlari (device, epoch, seq) ile eler; epoch AYNI
                     * kalirsa yeni 1,2,3... satirlarini "zaten gordum" diye atar,
                     * ustelik yuksek bir ackSeq dondugu icin cihaz onlari SILER.
                     * Yani o otobusun fatura verisi sessizce kaybolur.
                     *
                     * Bu tam olarak sunun oldugu durum: fabrika ayarlarina donus,
                     * uygulama verisinin temizlenmesi ya da yukaridaki arsivleme yolu.
                     * Config.logEpoch tek basina bunu yakalayamiyordu cunku
                     * SharedPreferences ayakta kalirken veritabani gitmis olabilir.
                     */
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        val yeni = Config(context).newLogEpoch()
                        Log.w(TAG, "veritabani sifirdan olustu -> yeni kurulum kimligi: $yeni")
                    }
                })
                .build()

        /** Acilamayan veritabanini silmek yerine kenara al. */
        private fun arsivle(context: Context) {
            val damga = System.currentTimeMillis()
            for (ek in listOf("", "-wal", "-shm")) {
                val dosya = context.getDatabasePath(NAME + ek)
                if (!dosya.exists()) continue
                val hedef = File(dosya.parentFile, "${dosya.name}.bozuk-$damga")
                val tasindi = runCatching { dosya.renameTo(hedef) }.getOrDefault(false)
                if (!tasindi) {
                    // Yeniden adlandirilamiyorsa silmek zorundayiz, yoksa uygulama hic acilmaz.
                    Log.e(TAG, "bozuk veritabani saklanamadi, siliniyor: ${dosya.name}")
                    runCatching { dosya.delete() }
                } else {
                    Log.w(TAG, "bozuk veritabani saklandi: ${hedef.name}")
                }
            }
            arsivleriBudula(context)
        }

        /**
         * Eski arsivleri budа.
         *
         * Tekrarlayan bir acilis hatasinda (bozuk eMMC sektoru) bu yol her onyuklemede
         * bir kopya birakir. Kopyalar hicbir zaman silinmedigi icin disk dolar - yani
         * veri kaybini onlemek icin konan mekanizma ekrani karartan sebebe donusur.
         * En yeni [ARSIV_SINIRI] kopya yeter: elle kurtarma icin lazim olan sondur.
         */
        private fun arsivleriBudula(context: Context) {
            runCatching {
                val dizin = context.getDatabasePath(NAME).parentFile ?: return
                dizin.listFiles { f -> f.name.startsWith("$NAME") && f.name.contains(".bozuk-") }
                    ?.groupBy { it.name.substringAfter(".bozuk-") }   // ayni damga = ayni kopya seti
                    ?.entries
                    ?.sortedByDescending { it.key }
                    ?.drop(ARSIV_SINIRI)
                    ?.forEach { (damga, dosyalar) ->
                        Log.w(TAG, "eski veritabani arsivi siliniyor: $damga")
                        dosyalar.forEach { runCatching { it.delete() } }
                    }
            }.onFailure { Log.w(TAG, "arsivler budanamadi: ${it.message}") }
        }
    }
}
