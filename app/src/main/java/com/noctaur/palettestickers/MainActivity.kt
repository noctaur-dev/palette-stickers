package com.noctaur.palettestickers

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView

/** Library screen: all packs as cards. Long-press and drag to reorder. */
class MainActivity : Activity() {

    private lateinit var adapter: PackAdapter
    private lateinit var empty: TextView
    private var reordered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        empty = findViewById(R.id.empty)

        val width = resources.displayMetrics.widthPixels
        val cols = maxOf(2, width / dp(170))
        adapter = PackAdapter(coverPx = width / cols) { open(it) }

        val grid = findViewById<RecyclerView>(R.id.packs)
        grid.layoutManager = GridLayoutManager(this, cols)
        grid.adapter = adapter

        ItemTouchHelper(object : ItemTouchHelper.Callback() {
            override fun getMovementFlags(rv: RecyclerView, vh: RecyclerView.ViewHolder): Int =
                makeMovementFlags(
                    ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT,
                    0,
                )

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
                    Library.reorder(adapter.items.map { it.id })
                }
            }
        }).attachToRecyclerView(grid)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val packs = Library.all()
        adapter.submit(packs)
        empty.visibility = if (packs.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun open(p: Pack) {
        startActivity(Intent(this, PackActivity::class.java).putExtra(PackActivity.EXTRA_ID, p.id))
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, M_NEW, 0, "New pack").setIcon(R.drawable.ic_add)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        menu.add(0, M_IMPORT, 1, "Import .stickerpack")
        menu.add(0, M_EXPORT_ALL, 2, "Export all + catalog…")
        if (!Licensing.isUnlocked(this)) menu.add(0, M_UNLOCK, 0, "Peelbox Unlimited…")
        menu.add(0, M_KEYBOARD, 3, "Sticker keyboard setup")
        menu.add(0, M_ABOUT, 4, "How to add stickers")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            M_NEW -> Licensing.requestNewPack(this) {
                Dialogs.prompt(this, "New pack", "", "Pack name") { name -> open(Library.create(name)) }
            }
            M_IMPORT -> startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("*/*")
                    .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true),
                RC_IMPORT,
            )
            M_EXPORT_ALL -> {
                if (Library.all().none { it.stickers.isNotEmpty() }) {
                    Dialogs.toast(this, "Nothing to export yet")
                } else {
                    startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), RC_EXPORT_ALL)
                }
            }
            M_KEYBOARD -> keyboardSetup()
            M_UNLOCK -> Licensing.offerUnlock(this, onUnlocked = { invalidateOptionsMenu() })
            M_ABOUT -> android.app.AlertDialog.Builder(this)
                .setTitle("Adding stickers")
                .setMessage(
                    "• Keyboard: open a pack, tap the dashed box, open the Samsung Keyboard's sticker " +
                        "tab and tap stickers — each one lands in the pack.\n\n" +
                        "• Paste: long-press the box and Paste if an image is on the clipboard.\n\n" +
                        "• Share: share images or GIFs from any app to \"Add to sticker pack\".\n\n" +
                        "• Files: Add files inside a pack.\n\n" +
                        "Export all writes every pack as a .stickerpack plus catalog.json into one " +
                        "folder — upload that folder anywhere static for the Palette plugin for " +
                        "Obsidian to install from.",
                )
                .setPositiveButton("OK", null)
                .show()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun keyboardEnabled(): Boolean {
        val imm = getSystemService(android.view.inputmethod.InputMethodManager::class.java)
        return imm.enabledInputMethodList.any { it.packageName == packageName }
    }

    /** Messenger & co. only show an image as a sticker when a keyboard sends it — hence the keyboard. */
    private fun keyboardSetup() {
        val enabled = keyboardEnabled()
        android.app.AlertDialog.Builder(this)
            .setTitle("Sticker keyboard")
            .setMessage(
                "Apps like Messenger turn shared images into photos (transparency becomes black). " +
                    "Stickers sent from a keyboard stay stickers — so this app includes one.\n\n" +
                    (if (enabled) "It's turned on. " else "1. Turn on \"${getString(R.string.app_name)}\" on the next screen.\n2. ") +
                    "To switch: pull down Quick Settings, tap the pencil/edit button and add the " +
                    "\"Peelbox keyboard\" tile. In any chat, tap the tile and pick ${getString(R.string.app_name)} " +
                    "(Gboard users can also use Gboard's switch button). Tap a sticker to send it; " +
                    "tap the keyboard icon at its top-left to switch back.",
            )
            .setPositiveButton(if (enabled) "Switch keyboard now" else "Turn it on") { _, _ ->
                if (enabled) {
                    getSystemService(android.view.inputmethod.InputMethodManager::class.java).showInputMethodPicker()
                } else {
                    startActivity(Intent(android.provider.Settings.ACTION_INPUT_METHOD_SETTINGS))
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        when (requestCode) {
            RC_IMPORT -> {
                val uris = buildList {
                    data.clipData?.let { c -> for (i in 0 until c.itemCount) c.getItemAt(i).uri?.let { add(it) } }
                    if (isEmpty()) data.data?.let { add(it) }
                }
                Dialogs.toast(this, "Importing…")
                Bg.io.execute {
                    val parsed = uris.map { uri ->
                        runCatching {
                            contentResolver.openInputStream(uri)!!.use { PackIO.readZip(it) }
                        }
                    }
                    Bg.main.post {
                        val msgs = parsed.map { r ->
                            r.fold(
                                onSuccess = { (pack, files) ->
                                    if (Licensing.canImport(this, pack.id)) {
                                        val (p, added) = Library.importPack(pack, files)
                                        "${p.name}: $added new"
                                    } else {
                                        "${pack.name}: skipped — unlock Peelbox Unlimited for more than ${Licensing.FREE_PACKS} packs"
                                    }
                                },
                                onFailure = { "Couldn't import: ${it.message}" },
                            )
                        }
                        refresh()
                        Dialogs.toast(this, msgs.joinToString("\n"))
                    }
                }
            }
            RC_EXPORT_ALL -> {
                val tree = data.data ?: return
                val snapshot = Library.all().map { it.snapshot() }
                Dialogs.toast(this, "Exporting…")
                Bg.io.execute {
                    val result = runCatching { PackIO.exportLibrary(applicationContext, snapshot, tree) }
                    Bg.main.post {
                        Dialogs.toast(
                            this,
                            result.fold(
                                { "Exported $it packs + catalog.json" },
                                { "Export failed: ${it.message}" },
                            ),
                        )
                    }
                }
            }
        }
    }

    companion object {
        private const val M_NEW = 1
        private const val M_IMPORT = 2
        private const val M_EXPORT_ALL = 3
        private const val M_ABOUT = 4
        private const val M_KEYBOARD = 5
        private const val M_UNLOCK = 6
        private const val RC_IMPORT = 10
        private const val RC_EXPORT_ALL = 11
    }
}
