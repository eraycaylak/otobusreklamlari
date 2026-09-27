package com.otobusreklam.player.data

import android.content.Context
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
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

    companion object {
        private const val TAG = "AppDatabase"
        private const val NAME = "reklam.db"

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
         * Boylece uygulama sahada calismaya devam eder (kiosk ekrani kararmaz) ama
         * veri de kaybolmaz: bozuk dosya cihazda durur ve elle kurtarilabilir.
         */
        private fun open(context: Context): AppDatabase {
            return try {
                build(context).also {
                    // Semayi HEMEN dogrula: sorun varsa burada patlasin, ilk
                    // sorguda beklenmedik bir anda degil.
                    it.openHelper.writableDatabase
                }
            } catch (e: Exception) {
                Log.e(TAG, "veritabani acilamadi, eski dosya saklanip yenisi olusturuluyor", e)
                arsivle(context)
                build(context)
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
        }
    }
}
