package com.noctaur.palettestickers

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import java.io.ByteArrayInputStream

/**
 * Share-sheet target ("Add to sticker pack"). Images/GIFs → pick a pack. A shared
 * .stickerpack (zip) → imported as a pack. Shows only dialogs over whatever app you came from.
 */
class ShareActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) { finish(); return }
        val uris = collectUris(intent)
        if (uris.isEmpty()) {
            Dialogs.toast(this, "Nothing to add")
            finish()
            return
        }
        Bg.io.execute {
            // Only a .stickerpack gets the big size cap; images are capped like stickers.
            val datas = uris.mapNotNull {
                val isImage = contentResolver.getType(it)?.startsWith("image/") == true
                readUri(this, it, if (isImage) MAX_STICKER_BYTES.toLong() else MAX_PACK_BYTES)
            }
            val (zips, images) = datas.partition { PackIO.isZip(it) }
            val packs = zips.map { z -> runCatching { PackIO.readZip(ByteArrayInputStream(z)) } }
            val prepared = Ingest.prepare(images.filter { it.size <= MAX_STICKER_BYTES })
            val unreadable = (uris.size - datas.size) + images.count { it.size > MAX_STICKER_BYTES }
            Bg.main.post { onReady(packs, prepared, unreadable) }
        }
    }

    private fun onReady(
        packs: List<Result<Pair<Pack, Map<String, ByteArray>>>>,
        prepared: Ingest.Prepared,
        unreadable: Int,
    ) {
        if (packs.isNotEmpty()) {
            val msgs = packs.map { r ->
                r.fold(
                    { (pack, files) ->
                        if (Licensing.canImport(this, pack.id)) {
                            val (p, added) = Library.importPack(pack, files)
                            "Imported ${p.name} ($added new)"
                        } else {
                            "Skipped ${pack.name} — unlock Peelbox Unlimited for more than ${Licensing.FREE_PACKS} packs"
                        }
                    },
                    { "Couldn't import pack: ${it.message}" },
                )
            }
            Dialogs.toast(this, msgs.joinToString("\n"))
        }
        if (isFinishing || isDestroyed) return // backed out while reading
        if (prepared.items.isEmpty()) {
            if (packs.isEmpty()) Dialogs.toast(this, "That isn't an image this app can use")
            finish()
            return
        }
        val n = prepared.items.size
        Dialogs.pickPack(
            this,
            title = if (n == 1) "Add sticker to…" else "Add $n stickers to…",
            onCancel = { finish() },
        ) { pack ->
            val report = Ingest.commit(pack, prepared, unreadable)
            Library.lastUsedId = pack.id
            Dialogs.toast(this, "${report.describe()} → ${pack.name}")
            finish()
        }
    }

    @Suppress("DEPRECATION") // the typed overloads need API 33; minSdk is 31
    private fun collectUris(i: Intent): List<Uri> {
        val out = LinkedHashSet<Uri>()
        when (i.action) {
            Intent.ACTION_SEND -> i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { out.add(it) }
            Intent.ACTION_SEND_MULTIPLE ->
                i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { out.addAll(it) }
        }
        i.clipData?.let { c -> for (k in 0 until c.itemCount) c.getItemAt(k).uri?.let { out.add(it) } }
        return out.toList()
    }
}
