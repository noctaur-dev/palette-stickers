package com.noctaur.palettestickers

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.text.InputType
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.util.Collections

/** Off-thread thumbnail decoding. Animated stickers come back as running AnimatedImageDrawables. */
object Thumbs {
    private val cache = object : LruCache<String, Bitmap>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    fun load(view: ImageView, file: File, animated: Boolean, sizePx: Int) {
        val key = "${file.path}@$sizePx@${file.lastModified()}"
        if (view.tag == key && view.drawable != null) return
        view.tag = key
        if (!animated) {
            cache.get(key)?.let { view.setImageBitmap(it); return }
        }
        view.setImageDrawable(null)
        Bg.io.execute {
            val drawable: Drawable? = try {
                val src = ImageDecoder.createSource(file)
                val listener = ImageDecoder.OnHeaderDecodedListener { decoder, info, _ ->
                    val w = info.size.width
                    val h = info.size.height
                    val scale = sizePx.toFloat() / maxOf(w, h, 1)
                    if (scale < 1f) decoder.setTargetSize(maxOf(1, (w * scale).toInt()), maxOf(1, (h * scale).toInt()))
                }
                if (animated) {
                    ImageDecoder.decodeDrawable(src, listener)
                } else {
                    val bmp = ImageDecoder.decodeBitmap(src, listener)
                    cache.put(key, bmp)
                    BitmapDrawable(view.resources, bmp)
                }
            } catch (e: Exception) {
                null
            }
            Bg.main.post {
                if (view.tag == key) {
                    view.setImageDrawable(drawable)
                    if (drawable is AnimatedImageDrawable) {
                        drawable.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                        drawable.start()
                    }
                }
            }
        }
    }
}

object Dialogs {
    fun toast(a: Activity, msg: String) = Toast.makeText(a.applicationContext, msg, Toast.LENGTH_LONG).show()

    private fun field(a: Activity, value: String, hint: String, multi: Boolean = false) = EditText(a).apply {
        setText(value)
        this.hint = hint
        if (multi) {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 2
        } else {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            isSingleLine = true
        }
    }

    private fun column(a: Activity, vararg views: View) = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(a.dp(22), a.dp(8), a.dp(22), 0)
        views.forEach { addView(it) }
    }

    fun prompt(
        a: Activity,
        title: String,
        initial: String,
        hint: String,
        onCancel: (() -> Unit)? = null,
        onOk: (String) -> Unit,
    ) {
        val et = field(a, initial, hint)
        var ok = false
        val d = AlertDialog.Builder(a)
            .setTitle(title)
            .setView(column(a, et))
            .setPositiveButton("OK") { _, _ -> ok = true; onOk(et.text.toString().trim()) }
            .setNegativeButton("Cancel", null)
            .setOnDismissListener { if (!ok) onCancel?.invoke() }
            .create()
        d.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        d.show()
        et.requestFocus()
        et.selectAll()
    }

    fun details(a: Activity, p: Pack, onSaved: () -> Unit) {
        val name = field(a, p.name, "Pack name")
        val author = field(a, p.author, "Author (optional)")
        val desc = field(a, p.description, "Description (optional)", multi = true)
        AlertDialog.Builder(a)
            .setTitle("Pack details")
            .setView(column(a, name, author, desc))
            .setPositiveButton("Save") { _, _ ->
                Library.updateDetails(p, name.text.toString().trim(), author.text.toString().trim(), desc.text.toString().trim())
                onSaved()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun confirm(a: Activity, title: String, message: String, action: String, onYes: () -> Unit) {
        AlertDialog.Builder(a)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(action) { _, _ -> onYes() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Picks a pack, with "New pack…" at the top and the last-used pack first. */
    fun pickPack(
        a: Activity,
        title: String,
        excludeId: String? = null,
        onCancel: (() -> Unit)? = null,
        onPick: (Pack) -> Unit,
    ) {
        val last = Library.lastUsedId
        val packs = Library.all().filter { it.id != excludeId }.sortedByDescending { it.id == last }
        val labels = (listOf("＋  New pack…") + packs.map { "${it.name}   ·   ${it.stickers.size}" }).toTypedArray()
        var picked = false
        AlertDialog.Builder(a)
            .setTitle(title)
            .setItems(labels) { _, which ->
                picked = true
                if (which == 0) {
                    prompt(a, "New pack", "", "Pack name", onCancel) { name -> onPick(Library.create(name)) }
                } else {
                    onPick(packs[which - 1])
                }
            }
            .setOnDismissListener { if (!picked) onCancel?.invoke() }
            .show()
    }

    /** Hands a file to the system share sheet. */
    fun shareFile(a: Activity, file: File, mime: String, chooserTitle: String) {
        val uri = FileProvider.getUriForFile(a, "${a.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType(mime)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        a.startActivity(Intent.createChooser(send, chooserTitle))
    }
}

// ---- Grid adapters ---------------------------------------------------------------------------

class PackAdapter(private val coverPx: Int, private val onOpen: (Pack) -> Unit) :
    RecyclerView.Adapter<PackAdapter.VH>() {

    val items = mutableListOf<Pack>()

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val cover: ImageView = v.findViewById(R.id.cover)
        val name: TextView = v.findViewById(R.id.name)
        val meta: TextView = v.findViewById(R.id.meta)
    }

    fun submit(list: List<Pack>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun move(from: Int, to: Int) {
        if (from < to) {
            for (i in from until to) Collections.swap(items, i, i + 1)
        } else {
            for (i in from downTo to + 1) Collections.swap(items, i, i - 1)
        }
        notifyItemMoved(from, to)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_pack, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val p = items[position]
        h.name.text = p.name
        h.meta.text = if (p.author.isNotBlank()) "${p.summary()} · ${p.author}" else p.summary()
        val c = p.coverSticker
        if (c != null) {
            Thumbs.load(h.cover, Library.file(p, c), c.animated, coverPx)
        } else {
            h.cover.tag = null
            h.cover.setImageResource(R.drawable.ic_empty_pack)
        }
        h.itemView.setOnClickListener {
            val pos = h.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) onOpen(items[pos])
        }
    }
}

class StickerAdapter(
    private val pack: Pack,
    private val cellPx: Int,
    private val onTap: (Sticker) -> Unit,
) : RecyclerView.Adapter<StickerAdapter.VH>() {

    var selecting = false
        private set
    val selected = LinkedHashSet<String>()

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val img: ImageView = v.findViewById(R.id.img)
        val badge: TextView = v.findViewById(R.id.badge)
        val check: ImageView = v.findViewById(R.id.check)
    }

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long {
        val id = pack.stickers[position].id
        return id.take(15).toLongOrNull(16) ?: id.hashCode().toLong()
    }

    override fun getItemCount() = pack.stickers.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_sticker, parent, false))

    override fun onBindViewHolder(h: VH, position: Int) {
        val s = pack.stickers[position]
        Thumbs.load(h.img, Library.file(pack, s), s.animated, cellPx)
        h.badge.visibility = if (s.animated) View.VISIBLE else View.GONE
        h.badge.text = if (s.mime == "image/gif") "GIF" else "ANIM"
        bindSelection(h, s)
        h.itemView.setOnClickListener {
            val pos = h.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) onTap(pack.stickers[pos])
        }
    }

    override fun onBindViewHolder(h: VH, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(SELECTION)) {
            bindSelection(h, pack.stickers[position])
        } else {
            onBindViewHolder(h, position)
        }
    }

    private fun bindSelection(h: VH, s: Sticker) {
        val on = s.id in selected
        h.check.visibility = if (selecting && on) View.VISIBLE else View.GONE
        h.img.alpha = if (selecting && !on) 0.45f else 1f
    }

    fun setSelectionMode(on: Boolean) {
        selecting = on
        if (!on) selected.clear()
        notifyItemRangeChanged(0, itemCount, SELECTION)
    }

    fun toggle(id: String) {
        if (!selected.remove(id)) selected.add(id)
        val pos = pack.stickers.indexOfFirst { it.id == id }
        if (pos >= 0) notifyItemChanged(pos, SELECTION)
    }

    fun selectAll() {
        pack.stickers.forEach { selected.add(it.id) }
        notifyItemRangeChanged(0, itemCount, SELECTION)
    }

    fun move(from: Int, to: Int) {
        if (from < to) {
            for (i in from until to) Collections.swap(pack.stickers, i, i + 1)
        } else {
            for (i in from downTo to + 1) Collections.swap(pack.stickers, i, i - 1)
        }
        notifyItemMoved(from, to)
    }

    companion object {
        private const val SELECTION = "selection"
    }
}
