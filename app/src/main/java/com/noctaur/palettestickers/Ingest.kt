package com.noctaur.palettestickers

/** Turning raw bytes (from the keyboard, share sheet or file picker) into stickers. */
object Ingest {

    class Item(val data: ByteArray, val info: ImageSniffer.Info, val sha: String)

    class Prepared(val items: List<Item>, val unsupported: Int)

    class Report(var added: Int = 0, var duplicates: Int = 0, var skipped: Int = 0) {
        fun describe(): String {
            val parts = mutableListOf(if (added == 1) "Added 1 sticker" else "Added $added stickers")
            if (duplicates > 0) parts.add("$duplicates already in this pack")
            if (skipped > 0) parts.add("$skipped couldn't be read")
            return parts.joinToString(" · ")
        }
    }

    /** Background thread: identify and hash. */
    fun prepare(datas: List<ByteArray>): Prepared {
        val items = ArrayList<Item>()
        var bad = 0
        for (d in datas) {
            val info = ImageSniffer.sniff(d)
            if (info == null) bad++ else items.add(Item(d, info, Hash.sha256(d)))
        }
        return Prepared(items, bad)
    }

    /** Main thread: write into the pack. */
    fun commit(pack: Pack, prepared: Prepared, unreadable: Int = 0): Report {
        val r = Report(skipped = prepared.unsupported + unreadable)
        for (item in prepared.items) {
            when (Library.addSticker(pack, item.data, item.info, item.sha, save = false)) {
                Library.AddResult.ADDED -> r.added++
                Library.AddResult.DUPLICATE -> r.duplicates++
            }
        }
        if (r.added > 0) Library.touch(pack)
        return r
    }
}
