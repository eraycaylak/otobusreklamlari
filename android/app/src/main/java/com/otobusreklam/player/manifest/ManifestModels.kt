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
    val items: List<ManifestItem>,
    val app: AppUpdate?,
    val policy: Policy
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
            items = parseItems(o.optJSONArray("items")),
            app = o.optJSONObject("app")?.let(::parseApp),
            policy = parsePolicy(o.optJSONObject("policy"))
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
        return Policy(
            staggerMaxMs = o.optLong("staggerMaxMs", d.staggerMaxMs),
            parallelChunks = o.optInt("parallelChunks", d.parallelChunks).coerceIn(1, 4),
            connectTimeoutMs = o.optLong("connectTimeoutMs", d.connectTimeoutMs),
            readTimeoutMs = o.optLong("readTimeoutMs", d.readTimeoutMs),
            heartbeatEveryMs = o.optLong("heartbeatEveryMs", d.heartbeatEveryMs),
            maxStalenessHours = o.optInt("maxStalenessHours", d.maxStalenessHours),
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
