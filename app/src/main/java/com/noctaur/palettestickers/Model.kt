package com.noctaur.palettestickers

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.temporal.ChronoUnit

// See FORMAT.md — this is the on-disk and exported manifest format.
const val FORMAT_PACK = "palette.stickerpack"
const val FORMAT_CATALOG = "palette.stickercatalog"
const val FORMAT_VERSION = 1

fun nowIso(): String = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString()

data class Sticker(
    val id: String,          // first 16 hex chars of sha256 — content-addressed
    val file: String,        // path relative to the pack folder, under stickers/
    val mime: String,
    val animated: Boolean,
    val width: Int,
    val height: Int,
    val bytes: Long,
    val sha256: String,
) {
    val ext: String get() = file.substringAfterLast('.', "png")

    fun toJson(fileOverride: String? = null): JSONObject = JSONObject()
        .put("id", id)
        .put("file", fileOverride ?: file)
        .put("mime", mime)
        .put("animated", animated)
        .put("width", width)
        .put("height", height)
        .put("bytes", bytes)
        .put("sha256", sha256)

    companion object {
        fun fromJson(o: JSONObject) = Sticker(
            id = o.getString("id"),
            file = o.getString("file"),
            mime = o.optString("mime", "image/png"),
            animated = o.optBoolean("animated", false),
            width = o.optInt("width", 0),
            height = o.optInt("height", 0),
            bytes = o.optLong("bytes", 0L),
            sha256 = o.optString("sha256", ""),
        )
    }
}

class Pack(
    val id: String,
    var name: String,
    var author: String = "",
    var description: String = "",
    var revision: Int = 1,
    val createdAt: String = nowIso(),
    var updatedAt: String = nowIso(),
    var cover: String? = null,
    val stickers: MutableList<Sticker> = mutableListOf(),
) {
    val coverSticker: Sticker? get() = stickers.firstOrNull { it.id == cover } ?: stickers.firstOrNull()
    val animatedCount: Int get() = stickers.count { it.animated }

    /** Snapshot for background work (export) so the UI can keep editing the live pack. */
    fun snapshot(): Pack = Pack(id, name, author, description, revision, createdAt, updatedAt, cover, stickers.toMutableList())

    fun summary(): String {
        val n = stickers.size
        val base = if (n == 1) "1 sticker" else "$n stickers"
        val a = animatedCount
        return if (a > 0) "$base · $a animated" else base
    }

    fun toJson(fileNames: ((Int, Sticker) -> String)? = null): JSONObject {
        val arr = JSONArray()
        stickers.forEachIndexed { i, s -> arr.put(s.toJson(fileNames?.invoke(i, s))) }
        return JSONObject()
            .put("format", FORMAT_PACK)
            .put("version", FORMAT_VERSION)
            .put("id", id)
            .put("name", name)
            .put("author", author)
            .put("description", description)
            .put("revision", revision)
            .put("createdAt", createdAt)
            .put("updatedAt", updatedAt)
            .put("cover", cover ?: JSONObject.NULL)
            .put("stickers", arr)
    }

    companion object {
        fun fromJson(o: JSONObject): Pack {
            require(o.optString("format") == FORMAT_PACK) { "Not a Palette sticker pack" }
            val v = o.optInt("version", 0)
            require(v in 1..FORMAT_VERSION) { "This pack uses format version $v, which this app doesn't know yet" }
            val arr = o.optJSONArray("stickers") ?: JSONArray()
            val list = ArrayList<Sticker>(arr.length())
            for (i in 0 until arr.length()) {
                val so = arr.optJSONObject(i) ?: continue
                runCatching { Sticker.fromJson(so) }.onSuccess { list.add(it) }
            }
            return Pack(
                id = o.getString("id"),
                name = o.optString("name", "Untitled"),
                author = o.optString("author", ""),
                description = o.optString("description", ""),
                revision = o.optInt("revision", 1),
                createdAt = o.optString("createdAt", nowIso()),
                updatedAt = o.optString("updatedAt", nowIso()),
                cover = if (o.isNull("cover")) null else o.optString("cover"),
                stickers = list,
            )
        }
    }
}
