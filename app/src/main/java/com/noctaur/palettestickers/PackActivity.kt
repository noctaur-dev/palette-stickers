package com.noctaur.palettestickers

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputFilter
import android.view.ActionMode
import android.view.ContentInfo
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import java.io.File

/**
 * One pack. The dashed box at the top accepts stickers straight from the keyboard (Samsung
 * Keyboard's sticker tab, Gboard, etc.) and pasted images. Long-press a sticker to drag it.
 */
class PackActivity : Activity() {

    private lateinit var pack: Pack
    private lateinit var adapter: StickerAdapter
    private lateinit var status: TextView
    private lateinit var empty: TextView
    private var actionMode: ActionMode? = null
    private var reordered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pack = Library.get(intent.getStringExtra(EXTRA_ID)) ?: run { finish(); return }
        setContentView(R.layout.activity_pack)
        title = pack.name
        actionBar?.setDisplayHomeAsUpEnabled(true)

        status = findViewById(R.id.status)
        empty = findViewById(R.id.empty)

        val width = resources.displayMetrics.widthPixels
        val cols = maxOf(3, width / dp(105))
        adapter = StickerAdapter(pack, width / cols) { onTap(it) }
        val grid = findViewById<RecyclerView>(R.id.grid)
        grid.layoutManager = GridLayoutManager(this, cols)
        grid.adapter = adapter
        ItemTouchHelper(dragCallback).attachToRecyclerView(grid)

        setupPasteBox(findViewById(R.id.paste))
        updateEmpty()
    }

    override fun onResume() {
        super.onResume()
        if (Library.get(pack.id) == null) finish()
    }

    // ---- Receiving stickers ------------------------------------------------------------------

    private fun setupPasteBox(box: EditText) {
        // The box is only a landing pad: typed text is discarded, rich content is captured.
        box.filters = arrayOf(InputFilter { _, _, _, _, _, _ -> "" })
        // Declaring these MIME types is what makes keyboards offer stickers/GIFs for this field.
        box.setOnReceiveContentListener(ACCEPTED_MIME) { _, payload ->
            val clip = payload.clip
            val uris = ArrayList<Uri>()
            for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { uris.add(it) }
            if (uris.isEmpty()) {
                if (payload.source == ContentInfo.SOURCE_CLIPBOARD) showStatus("The clipboard doesn't contain an image")
                return@setOnReceiveContentListener null
            }
            // Read now: the keyboard's permission to these URIs can end as soon as we return.
            val datas = uris.mapNotNull { readUri(this, it) }
            ingest(datas, uris.size - datas.size)
            null
        }
    }

    private fun ingest(datas: List<ByteArray>, unreadable: Int) {
        if (datas.isEmpty()) {
            showStatus("Couldn't read that — try sharing it to the app instead")
            return
        }
        showStatus("Adding…")
        Bg.io.execute {
            val prepared = Ingest.prepare(datas)
            Bg.main.post {
                val report = Ingest.commit(pack, prepared, unreadable)
                Library.lastUsedId = pack.id
                if (!isDestroyed) {
                    adapter.notifyDataSetChanged()
                    updateEmpty()
                    showStatus(report.describe())
                }
            }
        }
    }

    private fun addFromUris(uris: List<Uri>) {
        showStatus("Adding…")
        Bg.io.execute {
            val datas = uris.mapNotNull { readUri(this, it) }
            Bg.main.post { ingest(datas, uris.size - datas.size) }
        }
    }

    private fun showStatus(msg: String) {
        status.text = msg
        status.visibility = View.VISIBLE
    }

    private fun updateEmpty() {
        empty.visibility = if (pack.stickers.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun refresh() {
        adapter.notifyDataSetChanged()
        updateEmpty()
        title = pack.name
    }

    // ---- Reorder -----------------------------------------------------------------------------

    private val dragCallback = object : ItemTouchHelper.Callback() {
        override fun getMovementFlags(rv: RecyclerView, vh: RecyclerView.ViewHolder): Int =
            if (adapter.selecting) {
                0
            } else {
                makeMovementFlags(
                    ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT,
                    0,
                )
            }

        override fun onMove(rv: RecyclerView, from: RecyclerView.ViewHolder, to: RecyclerView.ViewHolder): Boolean {
            val f = from.bindingAdapterPosition
            val t = to.bindingAdapterPosition
            if (f == RecyclerView.NO_POSITION || t == RecyclerView.NO_POSITION) return false
            adapter.move(f, t)
            reordered = true
            return true
        }

        override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) = Unit

        override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
            super.clearView(rv, vh)
            if (reordered) {
                reordered = false
                Library.touch(pack)
            }
        }
    }

    // ---- Tapping a sticker -------------------------------------------------------------------

    private fun onTap(s: Sticker) {
        if (adapter.selecting) {
            adapter.toggle(s.id)
            updateModeTitle()
        } else {
            preview(s)
        }
    }

    private fun preview(s: Sticker) {
        val img = ImageView(this).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        val info = TextView(this).apply {
            alpha = 0.7f
            textSize = 13f
            val dims = if (s.width > 0) " · ${s.width}×${s.height}" else ""
            val anim = if (s.animated) " · animated" else ""
            text = "${s.ext.uppercase()}$dims · ${humanBytes(s.bytes)}$anim"
            setPadding(0, dp(10), 0, 0)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), 0)
            addView(img, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(240)))
            addView(info)
        }
        Thumbs.load(img, Library.file(pack, s), s.animated, dp(480))
        AlertDialog.Builder(this)
            .setView(box)
            .setPositiveButton("Close", null)
            .setNegativeButton("Delete") { _, _ ->
                Library.removeStickers(pack, setOf(s.id))
                refresh()
            }
            .setNeutralButton("More…") { _, _ -> more(s) }
            .show()
    }

    private fun more(s: Sticker) {
        val options = arrayOf("Copy", "Move to another pack…", "Use as pack cover", "Share as image", "Select multiple")
        AlertDialog.Builder(this)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> copySticker(s)
                    1 -> moveToPack(setOf(s.id))
                    2 -> {
                        Library.setCover(pack, s.id)
                        showStatus("Cover updated")
                    }
                    3 -> shareSticker(s)
                    4 -> startSelection(s.id)
                }
            }
            .show()
    }

    private fun copySticker(s: Sticker) {
        val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.files", Library.file(pack, s))
        val cm = getSystemService(android.content.ClipboardManager::class.java)
        cm.setPrimaryClip(android.content.ClipData.newUri(contentResolver, "Sticker", uri))
        showStatus("Copied — paste it anywhere that accepts images")
    }

    private fun shareSticker(s: Sticker) {
        val dir = File(cacheDir, "exports").apply { mkdirs() }
        val out = File(dir, "sticker-${s.id}.${s.ext}")
        Library.file(pack, s).copyTo(out, overwrite = true)
        Dialogs.shareFile(this, out, s.mime, "Share sticker")
    }

    private fun moveToPack(ids: Set<String>) {
        Dialogs.pickPack(this, "Move ${ids.size} to…", excludeId = pack.id) { target ->
            val moved = Library.moveStickers(pack, target, ids)
            val dupes = ids.size - moved
            actionMode?.finish()
            refresh()
            showStatus(
                "Moved $moved to ${target.name}" + if (dupes > 0) " · $dupes were already there" else "",
            )
        }
    }

    // ---- Multi-select ------------------------------------------------------------------------

    private fun startSelection(initial: String? = null) {
        if (actionMode != null) return
        adapter.setSelectionMode(true)
        initial?.let { adapter.toggle(it) }
        actionMode = startActionMode(object : ActionMode.Callback {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                menu.add(0, A_ALL, 0, "Select all")
                menu.add(0, A_MOVE, 1, "Move to…")
                menu.add(0, A_COVER, 2, "Use as cover")
                menu.add(0, A_DELETE, 3, "Delete")
                return true
            }

            override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false

            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                val ids = adapter.selected.toSet()
                when (item.itemId) {
                    A_ALL -> {
                        adapter.selectAll()
                        updateModeTitle()
                    }
                    A_MOVE -> if (ids.isNotEmpty()) moveToPack(ids)
                    A_COVER -> if (ids.isNotEmpty()) {
                        Library.setCover(pack, ids.first())
                        showStatus("Cover updated")
                        mode.finish()
                    }
                    A_DELETE -> if (ids.isNotEmpty()) {
                        Dialogs.confirm(this@PackActivity, "Delete ${ids.size}?", "They'll be removed from this pack.", "Delete") {
                            Library.removeStickers(pack, ids)
                            mode.finish()
                            refresh()
                        }
                    }
                }
                return true
            }

            override fun onDestroyActionMode(mode: ActionMode) {
                adapter.setSelectionMode(false)
                actionMode = null
            }
        })
        updateModeTitle()
    }

    private fun updateModeTitle() {
        actionMode?.title = "${adapter.selected.size} selected"
    }

    // ---- Menu --------------------------------------------------------------------------------

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, M_ADD, 0, "Add files").setIcon(R.drawable.ic_add)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        menu.add(0, M_SELECT, 1, "Select")
        menu.add(0, M_DETAILS, 2, "Name & details")
        menu.add(0, M_SHARE, 3, "Share pack")
        menu.add(0, M_EXPORT_FILE, 4, "Save .stickerpack…")
        menu.add(0, M_EXPORT_FOLDER, 5, "Export to folder…")
        menu.add(0, M_DELETE, 6, "Delete pack")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> finish()
            M_ADD -> startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("image/*")
                    .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true),
                RC_ADD,
            )
            M_SELECT -> startSelection()
            M_DETAILS -> Dialogs.details(this, pack) { refresh() }
            M_SHARE -> whenNotEmpty {
                val snap = pack.snapshot()
                Bg.io.execute {
                    val f = runCatching { PackIO.buildZipFile(applicationContext, snap) }
                    Bg.main.post {
                        f.fold(
                            { Dialogs.shareFile(this, it, PackIO.PACK_MIME, "Share ${snap.name}") },
                            { Dialogs.toast(this, "Couldn't build pack: ${it.message}") },
                        )
                    }
                }
            }
            M_EXPORT_FILE -> whenNotEmpty {
                startActivityForResult(
                    Intent(Intent.ACTION_CREATE_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .setType(PackIO.PACK_MIME)
                        .putExtra(Intent.EXTRA_TITLE, PackIO.fileName(pack)),
                    RC_EXPORT_FILE,
                )
            }
            M_EXPORT_FOLDER -> whenNotEmpty {
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), RC_EXPORT_FOLDER)
            }
            M_DELETE -> Dialogs.confirm(
                this,
                "Delete \"${pack.name}\"?",
                "All ${pack.stickers.size} stickers in it will be deleted from this app.",
                "Delete",
            ) {
                Library.delete(pack)
                finish()
            }
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun whenNotEmpty(block: () -> Unit) {
        if (pack.stickers.isEmpty()) Dialogs.toast(this, "This pack is empty") else block()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        when (requestCode) {
            RC_ADD -> {
                val uris = buildList {
                    data.clipData?.let { c -> for (i in 0 until c.itemCount) c.getItemAt(i).uri?.let { add(it) } }
                    if (isEmpty()) data.data?.let { add(it) }
                }
                if (uris.isNotEmpty()) addFromUris(uris)
            }
            RC_EXPORT_FILE -> {
                val uri = data.data ?: return
                runExport("Saved ${PackIO.fileName(pack)}") { snap -> PackIO.exportToDocument(applicationContext, snap, uri) }
            }
            RC_EXPORT_FOLDER -> {
                val tree = data.data ?: return
                runExport("Exported to folder") { snap -> PackIO.exportToFolder(applicationContext, snap, tree) }
            }
        }
    }

    private fun runExport(doneMsg: String, work: (Pack) -> Unit) {
        val snap = pack.snapshot()
        showStatus("Exporting…")
        Bg.io.execute {
            val r = runCatching { work(snap) }
            Bg.main.post { showStatus(r.fold({ doneMsg }, { "Export failed: ${it.message}" })) }
        }
    }

    companion object {
        const val EXTRA_ID = "pack_id"

        private val ACCEPTED_MIME = arrayOf(
            "image/*", "image/png", "image/gif", "image/webp", "image/jpeg", "image/heic", "image/avif",
        )

        private const val M_ADD = 1
        private const val M_SELECT = 2
        private const val M_DETAILS = 3
        private const val M_SHARE = 4
        private const val M_EXPORT_FILE = 5
        private const val M_EXPORT_FOLDER = 6
        private const val M_DELETE = 7

        private const val A_ALL = 21
        private const val A_MOVE = 22
        private const val A_COVER = 23
        private const val A_DELETE = 24

        private const val RC_ADD = 31
        private const val RC_EXPORT_FILE = 32
        private const val RC_EXPORT_FOLDER = 33
    }
}
