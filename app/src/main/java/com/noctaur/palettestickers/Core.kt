package com.noctaur.palettestickers

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.widget.ImageView
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Library.init(this)
    }
}

/** Background work + hopping back to the UI thread. */
object Bg {
    val io: ExecutorService = Executors.newFixedThreadPool(3)
    val main = Handler(Looper.getMainLooper())
}

const val MAX_STICKER_BYTES = 25 * 1024 * 1024        // a single image/GIF
const val MAX_PACK_BYTES = 400L * 1024 * 1024         // a whole .stickerpack

object Hash {
    fun sha256(data: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(data))

    fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return hex(md.digest())
    }

    private fun hex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) sb.append(String.format(Locale.ROOT, "%02x", b.toInt() and 0xff))
        return sb.toString()
    }
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

/** Reads an input stream fully, refusing anything over [max] bytes. */
fun readCapped(input: InputStream, max: Long): ByteArray {
    val out = ByteArrayOutputStream()
    val buf = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
        val n = input.read(buf)
        if (n < 0) break
        total += n
        if (total > max) throw IOException("File is too large")
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}

/** Copies the bytes behind a content:// URI right away (keyboard/share grants can be short-lived). */
fun readUri(ctx: Context, uri: Uri, max: Long = MAX_STICKER_BYTES.toLong()): ByteArray? = try {
    ctx.contentResolver.openInputStream(uri)?.use { readCapped(it, max) }
} catch (e: Throwable) { // includes OutOfMemoryError on a huge file — skip it, don't crash
    null
}

fun slugify(name: String): String {
    val s = name.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40).trim('-')
    return s.ifEmpty { "pack" }
}

fun safeFolderName(name: String): String =
    name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().take(60).ifEmpty { "Sticker pack" }

fun humanBytes(n: Long): String = when {
    n >= 1024 * 1024 -> String.format(Locale.ROOT, "%.1f MB", n / (1024.0 * 1024.0))
    n >= 1024 -> "${n / 1024} KB"
    else -> "$n B"
}

/** An ImageView that is always as tall as it is wide — keeps grid cells square. */
class SquareImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ImageView(context, attrs) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, widthMeasureSpec)
        setMeasuredDimension(measuredWidth, measuredWidth)
    }
}
