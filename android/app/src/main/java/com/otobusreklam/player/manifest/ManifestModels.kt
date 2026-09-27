package com.otobusreklam.player.manifest

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

data class ChunkSpec(val index: Int, val offset: Long, val len: Int, val sha256: String)

data class ManifestItem(
    val id: String,
    val remotePath: String,
    val size: Long,
    val sha256: String,
    val durationMs: Long,
    val weight: Int,
    val validFrom: Long?,
    val validUntil: Long?,
    val dayparts: List<String>,
    val evergreen: Boolean,
    val chunks: List<ChunkSpec>
)

data class AppUpdate(
    val versionCode: Int,
    val versionName: String,
    val remotePath: String,
    val size: Long,
    val sha256: String,
    val rolloutGroup: Int,
    val critical: Boolean,
    val chunks: List<ChunkSpec>
)

/**
 * Cihaz davranis parametreleri sunucudan gelir: APK yayimlamadan ayarlanabilsin.
 * Sunucu gondermezse buradaki varsayilanlar kullanilir.
 */
data class Policy(
    val staggerMaxMs: Long = 15_000,
    val parallelChunks: Int = 2,
    val connectTimeoutMs: Long = 8_000,
    val readTimeoutMs: Long = 15_000,
    val heartbeatEveryMs: Long = 3_600_000,
    val maxStalenessHours: Int = 72,
    val cellularAllowed: Boolean = false
)

data class PlayManifest(
    val schema: Int,
    val playlistVersion: Int,
    val deviceId: String,
    val deviceGroup: String,
    val serverTimeMs: Long?,
    /**
     * Daypart'in yorumlanacagi saat dilimi (IANA adi, orn. "Europe/Istanbul").
     *
     * SUNUCUDAN GELIR, cihazdan okunmaz: ZoneId.systemDefault() ucuz stick'lerde sik
     * sik UTC'dir ve Turkiye icin bu 3 SAATLIK kaymadir - sabah kusagi icin satilan
     * reklam ogleden sonra doner. Bos gelirse cihaz kendi dilimine duser (eski davranis).
     */
    val timezone: String,
    val items: List<ManifestItem>,
    val app: AppUpdate?,
    val policy: Policy,
    /** Kademeli yayimda kac grup var - SUNUCUDAN gelir, cihazda sabit degildir. */
    val rolloutGroups: Int
)

object ManifestParser {

    fun parse(json: String): PlayManifest {
        val o = JSONObject(json)
        return PlayManifest(
            schema = o.optInt("schema", 1),
            playlistVersion = o.optInt("playlistVersion", 0),
            deviceId = o.optString("deviceId", ""),
            deviceGroup = o.optString("deviceGroup", "default"),
            serverTimeMs = parseInstant(o.optString("serverTime", "")),
            timezone = o.optString("timezone", ""),
            items = parseItems(o.optJSONArray("items")),
            app = o.optJSONObject("app")?.let(::parseApp),
            policy = parsePolicy(o.optJSONObject("policy")),
            rolloutGroups = o.optInt("rolloutGroups", 4).coerceAtLeast(1)
        )
    }

    private fun parseItems(arr: JSONArray?): List<ManifestItem> {
        if (arr == null) return emptyList()
        val out = ArrayList<ManifestItem>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out += ManifestItem(
                id = o.getString("id"),
                remotePath = o.getString("file"),
                size = o.getLong("size"),
                sha256 = o.getString("sha256"),
                durationMs = o.optLong("durationMs", 0),
                weight = o.optInt("weight", 1).coerceAtLeast(1),
                validFrom = parseInstant(o.optString("validFrom", "")),
                validUntil = parseInstant(o.optString("validUntil", "")),
                dayparts = o.optJSONArray("dayparts").toStringList(),
                evergreen = o.optBoolean("evergreen", false),
                chunks = parseChunks(o.optJSONArray("chunks"))
            )
        }
        return out
    }

    private fun parseApp(o: JSONObject): AppUpdate? {
        val path = o.optString("url", "")
        if (path.isBlank()) return null
        return AppUpdate(
            versionCode = o.optInt("versionCode", 0),
            versionName = o.optString("versionName", ""),
            remotePath = path,
            size = o.optLong("size", 0),
            sha256 = o.optString("sha256", ""),
            rolloutGroup = o.optInt("rolloutGroup", 0),
            critical = o.optBoolean("critical", false),
            chunks = parseChunks(o.optJSONArray("chunks"))
        )
    }

    private fun parsePolicy(o: JSONObject?): Policy {
        val d = Policy()
        if (o == null) return d
        /*
         * DEGERLER SINIRLANIYOR.
         *
         * Bu alanlar sunucudan geliyor. OkHttp'de zaman asimi 0 demek "ZAMAN ASIMI YOK"
         * demektir: bir yapilandirma hatasi (veya bozuk bir onbellek yaniti) on plan
         * servisini sonsuza kadar askida birakir, cihaz bir daha senkron olmaz ve bunu
         * kimse gormez. Alt sinirlar bu sinifi tamamen kapatiyor.
         *
         * staggerMaxMs de sinirli: asiri buyuk bir deger tum 3 dakikalik pencereyi
         * beklemeyle gecirirdi.
         */
        return Policy(
            staggerMaxMs = o.optLong("staggerMaxMs", d.staggerMaxMs).coerceIn(0, 60_000),
            parallelChunks = o.optInt("parallelChunks", d.parallelChunks).coerceIn(1, 4),
            connectTimeoutMs = o.optLong("connectTimeoutMs", d.connectTimeoutMs).coerceIn(1_000, 60_000),
            readTimeoutMs = o.optLong("readTimeoutMs", d.readTimeoutMs).coerceIn(1_000, 120_000),
            heartbeatEveryMs = o.optLong("heartbeatEveryMs", d.heartbeatEveryMs).coerceAtLeast(60_000),
            maxStalenessHours = o.optInt("maxStalenessHours", d.maxStalenessHours).coerceIn(1, 24 * 30),
            cellularAllowed = o.optBoolean("cellularAllowed", d.cellularAllowed)
        )
    }

    private fun parseChunks(arr: JSONArray?): List<ChunkSpec> {
        if (arr == null) return emptyList()
        val out = ArrayList<ChunkSpec>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out += ChunkSpec(
                index = o.optInt("i", i),
                offset = o.getLong("offset"),
                len = o.getInt("len"),
                sha256 = o.getString("sha256")
            )
        }
        return out
    }

    private fun parseInstant(value: String?): Long? {
        if (value.isNullOrBlank() || value == "null") return null
        return try { Instant.parse(value).toEpochMilli() } catch (_: Exception) { null }
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).map { optString(it, "") }.filter { it.isNotBlank() }
    }
}
