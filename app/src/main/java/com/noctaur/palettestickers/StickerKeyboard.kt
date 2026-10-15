package com.noctaur.palettestickers

import android.content.ClipDescription
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputContentInfo
import android.view.inputmethod.InputMethodManager
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File

/**
 * "Palette Stickers" keyboard. Apps like Messenger only treat an image as a *sticker*
 * (transparent, no bubble) when a keyboard sends it — sharing an image always makes it a photo.
 * So this keyboard sends your stickers exactly the way the Samsung Keyboard does.
 */
class StickerKeyboard : InputMethodService() {

    private lateinit var ui: Context
    private lateinit var tabs: LinearLayout
    private lateinit var grid: RecyclerView
    private lateinit var empty: TextView
    private val adapter = KeyAdapter { s -> send(s) }
    private var currentId: String? = null

    override fun onCreateInputView(): View {
        ui = ContextThemeWrapper(this, R.style.Theme_Stickers)
        val bg = themeColor(android.R.attr.colorBackground)

        val switchBtn = ImageView(ui).apply {
            setImageResource(R.drawable.ic_keyboard)
            contentDescription = "Switch keyboard"
            setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10))
            setOnClickListener { if (!switchToPreviousInputMethod()) showPicker() }
            setOnLongClickListener { showPicker(); true }
        }
        tabs = LinearLayout(ui).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(ui.dp(4), 0, ui.dp(8), 0)
        }
        val tabScroll = HorizontalScrollView(ui).apply {
            isHorizontalScrollBarEnabled = false
            addView(tabs)
        }
        val top = LinearLayout(ui).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(switchBtn, LinearLayout.LayoutParams(ui.dp(48), ui.dp(44)))
            addView(tabScroll, LinearLayout.LayoutParams(0, ui.dp(44), 1f))
        }

        grid = RecyclerView(ui).apply {
            layoutManager = GridLayoutManager(ui, 5)
            adapter = this@StickerKeyboard.adapter
            setPadding(ui.dp(4), 0, ui.dp(4), ui.dp(4))
            clipToPadding = false
        }
        empty = TextView(ui).apply {
            gravity = Gravity.CENTER
            alpha = 0.7f
            setPadding(ui.dp(24), 0, ui.dp(24), 0)
            text = "No stickers yet — open Palette Stickers and add some."
            visibility = View.GONE
        }
        val body = android.widget.FrameLayout(ui).apply {
            addView(grid, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(empty, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }

        return LinearLayout(ui).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            addView(top, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(44)))
            addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(270)))
        }
    }

    override fun onStartInputView(info: android.view.inputmethod.EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        val packs = Library.all().filter { it.stickers.isNotEmpty() }
        if (packs.none { it.id == currentId }) {
            currentId = (packs.firstOrNull { it.id == Library.lastUsedId } ?: packs.firstOrNull())?.id
        }
        buildTabs(packs)
        show(Library.get(currentId))
    }

    private fun buildTabs(packs: List<Pack>) {
        tabs.removeAllViews()
        val accent = themeColor(android.R.attr.colorAccent)
        for (p in packs) {
            val selected = p.id == currentId
            val chip = TextView(ui).apply {
                text = p.name
                textSize = 14f
                setPadding(ui.dp(14), ui.dp(6), ui.dp(14), ui.dp(6))
                background = GradientDrawable().apply {
                    cornerRadius = ui.dp(16).toFloat()
                    if (selected) setColor(accent) else setStroke(ui.dp(1), 0x66808080)
                }
                if (selected) setTextColor(0xFFFFFFFF.toInt())
                setOnClickListener {
                    currentId = p.id
                    buildTabs(packs)
                    show(p)
                }
            }
            tabs.addView(
                chip,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = ui.dp(4)
                    gravity = Gravity.CENTER_VERTICAL
                },
            )
        }
    }

    private fun show(p: Pack?) {
        adapter.cellPx = resources.displayMetrics.widthPixels / 5
        adapter.pack = p
        adapter.notifyDataSetChanged()
        grid.scrollToPosition(0)
        empty.visibility = if (p == null || p.stickers.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun send(s: Sticker) {
        val pack = adapter.pack ?: return
        val ic = currentInputConnection ?: return
        val accepted = currentInputEditorInfo?.contentMimeTypes ?: emptyArray()
        fun accepts(m: String) = accepted.any { ClipDescription.compareMimeTypes(m, it) }

        var file = Library.file(pack, s)
        var mime = s.mime
        if (!accepts(mime)) {
            if (!s.animated && accepts("image/png")) {
                file = asPng(file, s.id) ?: return toast("Couldn't convert that sticker")
                mime = "image/png"
            } else {
                return toast(
                    if (accepted.isEmpty()) "This text box doesn't accept stickers"
                    else "This app doesn't accept ${s.ext.uppercase()} stickers",
                )
            }
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        val content = InputContentInfo(uri, ClipDescription("sticker", arrayOf(mime)), null)
        if (!ic.commitContent(content, InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION, null)) {
            toast("The app didn't take the sticker")
        } else {
            Library.lastUsedId = pack.id
        }
    }

    /** For apps that only take PNG: re-encode a static sticker, keeping transparency. */
    private fun asPng(src: File, id: String): File? = try {
        val dir = File(cacheDir, "exports").apply { mkdirs() }
        val out = File(dir, "kb-$id.png")
        if (!out.isFile) {
            val bmp = ImageDecoder.decodeBitmap(ImageDecoder.createSource(src)) { d, _, _ ->
                d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
            out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        out
    } catch (e: Exception) {
        null
    }

    private fun showPicker() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
    }

    private fun toast(msg: String) = Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()

    private fun themeColor(attr: Int): Int {
        val tv = TypedValue()
        ui.theme.resolveAttribute(attr, tv, true)
        return tv.data
    }

    private class KeyAdapter(private val onTap: (Sticker) -> Unit) : RecyclerView.Adapter<KeyAdapter.VH>() {
        var pack: Pack? = null
        var cellPx = 200

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val img: ImageView = v.findViewById(R.id.img)
            val badge: View = v.findViewById(R.id.badge)
        }

        override fun getItemCount() = pack?.stickers?.size ?: 0

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_sticker, parent, false))

        override fun onBindViewHolder(h: VH, position: Int) {
            val p = pack ?: return
            val s = p.stickers[position]
            Thumbs.load(h.img, Library.file(p, s), s.animated, cellPx)
            h.badge.visibility = View.GONE
            h.itemView.setOnClickListener { onTap(s) }
        }
    }
}
