package com.noctaur.palettestickers

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * The sticker library on the phone:  filesDir/library/packs/<packId>/pack.json + stickers/.
 * Each pack folder is already in the export format (only file names differ), so exporting is
 * mostly zipping. All mutations happen on the main thread; background work uses [Pack.snapshot].
 */
object Library {
    private lateinit var packsDir: File
    private lateinit var prefs: SharedPreferences
    private val packs = LinkedHashMap<String, Pack>()
    private val SAFE_ID = Regex("[A-Za-z0-9-]{1,64}")

    enum class AddResult { ADDED, DUPLICATE }

    fun init(ctx: Context) {
        packsDir = File(ctx.filesDir, "library/packs").apply { mkdirs() }
        prefs = ctx.getSharedPreferences("library", Context.MODE_PRIVATE)
        load()
    }

    private fun load() {
        packs.clear()
        val loaded = HashMap<String, Pack>()
        packsDir.listFiles()?.forEach { dir ->
            val f = File(dir, "pack.json")
            if (f.isFile) {
                runCatching { Pack.fromJson(JSONObject(f.readText())) }
                    .onSuccess { if (it.id == dir.name) loaded[it.id] = it }
            }
        }
        val order = prefs.getString("order", "").orEmpty().split(',').filter { it.isNotBlank() }
        for (id in order) loaded.remove(id)?.let { packs[id] = it }
        loaded.values.sortedBy { it.createdAt }.forEach { packs[it.id] = it }
    }

    fun all(): List<Pack> = packs.values.toList()
    fun get(id: String?): Pack? = id?.let { packs[it] }
    fun dir(p: Pack): File = File(packsDir, p.id)
    fun file(p: Pack, s: Sticker): File = File(dir(p), s.file)

    var lastUsedId: String?
        get() = prefs.getString("lastUsed", null)
        set(v) = prefs.edit().putString("lastUsed", v).apply()

    fun create(name: String, id: String = UUID.randomUUID().toString()): Pack {
        val p = Pack(id = id, name = name.ifBlank { "Untitled" })
        packs[p.id] = p
        write(p)
        saveOrder()
        return p
    }

    /** Records a change: bumps the revision so Palette can tell this is a newer version. */
    fun touch(p: Pack) {
        p.revision += 1
        p.updatedAt = nowIso()
        write(p)
    }

    private fun write(p: Pack) {
        val d = dir(p)
        File(d, "stickers").mkdirs()
        val tmp = File(d, "pack.json.tmp")
        tmp.writeText(p.toJson().toString(2))
        val target = File(d, "pack.json")
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
    }

    fun delete(p: Pack) {
        packs.remove(p.id)
        dir(p).deleteRecursively()
        saveOrder()
    }

    fun reorder(ids: List<String>) {
        val old = LinkedHashMap(packs)
        packs.clear()
        for (id in ids) old.remove(id)?.let { packs[id] = it }
        packs.putAll(old)
        saveOrder()
    }

    private fun saveOrder() = prefs.edit().putString("order", packs.keys.joinToString(",")).apply()

    fun addSticker(p: Pack, data: ByteArray, info: ImageSniffer.Info, sha: String, save: Boolean = true): AddResult {
        val id = sha.take(16)
        if (p.stickers.any { it.id == id }) return AddResult.DUPLICATE
        val rel = "stickers/$id.${info.ext}"
        File(dir(p), rel).apply {
            parentFile?.mkdirs()
            writeBytes(data)
        }
        p.stickers.add(Sticker(id, rel, info.mime, info.animated, info.width, info.height, data.size.toLong(), sha))
        if (save) touch(p)
        return AddResult.ADDED
    }

    fun removeStickers(p: Pack, ids: Set<String>) {
        val gone = p.stickers.filter { it.id in ids }
        if (gone.isEmpty()) return
        gone.forEach { file(p, it).delete() }
        p.stickers.removeAll { it.id in ids }
        if (p.cover in ids) p.cover = null
        touch(p)
    }

    /** Moves stickers between packs. Ones already in the target are just removed from the source. */
    fun moveStickers(from: Pack, to: Pack, ids: Set<String>): Int {
        if (from.id == to.id) return 0
        var moved = 0
        for (s in from.stickers.filter { it.id in ids }) {
            if (to.stickers.none { it.id == s.id }) {
                val src = file(from, s)
                if (!src.isFile) continue
                val dst = File(dir(to), s.file)
                dst.parentFile?.mkdirs()
                src.copyTo(dst, overwrite = true)
                to.stickers.add(s)
                moved++
            }
        }
        removeStickers(from, ids)
        if (moved > 0) touch(to)
        return moved
    }

    fun setCover(p: Pack, stickerId: String) {
        p.cover = stickerId
        touch(p)
    }

    fun updateDetails(p: Pack, name: String, author: String, description: String) {
        p.name = name.ifBlank { p.name }
        p.author = author
        p.description = description
        touch(p)
    }

    /**
     * Installs a pack read from a .stickerpack. Same pack id as one already here → merges new
     * stickers into it (nothing is lost). Returns the pack and how many stickers were added.
     */
    fun importPack(src: Pack, files: Map<String, ByteArray>): Pair<Pack, Int> {
        val id = if (SAFE_ID.matches(src.id)) src.id else UUID.randomUUID().toString()
        val existing = packs[id]
        val target = existing ?: create(src.name, id).also {
            it.author = src.author
            it.description = src.description
        }
        var added = 0
        val idMap = HashMap<String, String>()
        for (s in src.stickers) {
            val data = files[s.id] ?: continue
            val info = ImageSniffer.sniff(data) ?: continue
            val sha = Hash.sha256(data)
            idMap[s.id] = sha.take(16)
            if (addSticker(target, data, info, sha, save = false) == AddResult.ADDED) added++
        }
        if (existing == null) {
            target.cover = src.cover?.let { idMap[it] }?.takeIf { c -> target.stickers.any { it.id == c } }
        }
        touch(target)
        return target to added
    }
}
