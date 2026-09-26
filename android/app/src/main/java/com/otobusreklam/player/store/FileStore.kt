package com.otobusreklam.player.store

import android.content.Context
import android.os.StatFs
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Disk duzeni ve atomik yazma.
 *
 *   files/content/<sha>.mp4     -> SADECE dogrulanmis dosyalar
 *   files/tmp/<sha>.part        -> yarim inen (parca durumu veritabaninda)
 *   files/manifest/current.json -> oynaticinin okudugu liste
 *   files/manifest/previous.json-> geri donus
 *   files/app/<sha>.apk         -> uygulama guncellemesi
 *
 * KURAL: content/ altina SADECE tam inip SHA-256'si dogrulanmis dosya girer.
 * Boylece oynatici oradaki her dosyaya kosulsuz guvenebilir.
 */
class FileStore(context: Context) {

    val root: File = context.filesDir
    val contentDir = File(root, "content").apply { mkdirs() }
    val tmpDir = File(root, "tmp").apply { mkdirs() }
    val manifestDir = File(root, "manifest").apply { mkdirs() }
    val apkDir = File(root, "app").apply { mkdirs() }

    fun contentFile(sha: String, remotePath: String): File =
        File(dirFor(remotePath), sha + extensionOf(remotePath))

    fun partFile(sha: String): File = File(tmpDir, "$sha.part")

    val currentManifest: File get() = File(manifestDir, "current.json")
    val previousManifest: File get() = File(manifestDir, "previous.json")

    private fun dirFor(remotePath: String): File =
        if (remotePath.startsWith("app/")) apkDir else contentDir

    private fun extensionOf(remotePath: String): String {
        val dot = remotePath.lastIndexOf('.')
        val slash = remotePath.lastIndexOf('/')
        return if (dot > slash && dot >= 0) remotePath.substring(dot) else ""
    }

    /**
     * Atomik yazma: gecici dosyaya yaz -> fsync -> rename.
     * Yarim yazilmis manifest diye bir durum olusamaz.
     */
    fun atomicWrite(target: File, bytes: ByteArray) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(bytes)
            out.flush()
            out.fd.sync()
        }
        if (!tmp.renameTo(target)) {
            target.delete()
            check(tmp.renameTo(target)) { "atomik yazma basarisiz: ${target.name}" }
        }
    }

    /** Yarim dosyayi nihai konuma atomik tasi. */
    fun promote(part: File, target: File): Boolean {
        target.parentFile?.mkdirs()
        if (target.exists()) target.delete()
        return part.renameTo(target)
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val KNOWN_GOOD_APK = "known-good.apk"
    }

    fun freeBytes(): Long = try {
        val stat = StatFs(root.absolutePath)
        stat.availableBlocksLong * stat.blockSizeLong
    } catch (_: Exception) {
        -1L
    }

    /**
     * Artik hicbir manifestte gecmeyen dosyalari sil.
     * EVERGREEN dosyalar bu listeye asla girmez - ekranin son guvencesi onlar.
     */
    fun cleanup(keepShas: Set<String>) {
        for (dir in listOf(contentDir, apkDir)) {
            dir.listFiles()?.forEach { f ->
                // Geri donus APK'si icerik adresli DEGIL, sabit adli.
                // Silinirse bozuk bir guncellemeden donus imkani kaybolur.
                if (f.name == KNOWN_GOOD_APK) return@forEach
                val sha = f.name.substringBefore('.')
                if (sha !in keepShas) f.delete()
            }
        }
        // Artik takip edilmeyen yarim dosyalar
        tmpDir.listFiles()?.forEach { f ->
            val sha = f.name.removeSuffix(".part")
            if (sha !in keepShas) f.delete()
        }
    }
}
