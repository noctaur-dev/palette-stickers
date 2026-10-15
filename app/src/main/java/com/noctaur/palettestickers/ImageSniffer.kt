package com.noctaur.palettestickers

import android.graphics.BitmapFactory

/**
 * Identifies an image from its bytes (never trusts the sender's MIME type) and works out
 * whether it's animated, without decoding the whole thing.
 */
object ImageSniffer {

    class Info(val mime: String, val ext: String, val animated: Boolean, val width: Int, val height: Int)

    fun sniff(b: ByteArray): Info? {
        val (mime, ext) = kind(b) ?: return null
        val animated = when (mime) {
            "image/png" -> isApng(b)
            "image/gif" -> gifFrameCount(b) > 1
            "image/webp" -> isAnimatedWebp(b)
            "image/avif" -> brand(b) == "avis"
            else -> false
        }
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(b, 0, b.size, opts)
        return Info(mime, ext, animated, maxOf(0, opts.outWidth), maxOf(0, opts.outHeight))
    }

    private fun kind(b: ByteArray): Pair<String, String>? {
        if (b.size < 12) return null
        fun at(i: Int, s: String) = s.indices.all { b[i + it] == s[it].code.toByte() }
        return when {
            b[0] == 0x89.toByte() && at(1, "PNG") -> "image/png" to "png"
            at(0, "GIF8") -> "image/gif" to "gif"
            at(0, "RIFF") && at(8, "WEBP") -> "image/webp" to "webp"
            b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte() -> "image/jpeg" to "jpg"
            at(4, "ftyp") -> when (brand(b)) {
                "avif", "avis" -> "image/avif" to "avif"
                "heic", "heix", "hevc", "hevx", "mif1", "msf1" -> "image/heic" to "heic"
                else -> null
            }
            else -> null
        }
    }

    private fun brand(b: ByteArray): String =
        if (b.size >= 12) String(b, 8, 4, Charsets.US_ASCII) else ""

    private fun u32(b: ByteArray, i: Int): Long =
        ((b[i].toLong() and 0xff) shl 24) or ((b[i + 1].toLong() and 0xff) shl 16) or
            ((b[i + 2].toLong() and 0xff) shl 8) or (b[i + 3].toLong() and 0xff)

    /** APNG = a PNG with an acTL chunk before the first IDAT. */
    private fun isApng(b: ByteArray): Boolean {
        var p = 8
        while (p + 8 <= b.size) {
            val len = u32(b, p)
            val type = String(b, p + 4, 4, Charsets.US_ASCII)
            if (type == "acTL") return true
            if (type == "IDAT" || type == "IEND") return false
            val next = p.toLong() + 12 + len
            if (next > b.size) return false
            p = next.toInt()
        }
        return false
    }

    /** Extended WebP (VP8X) with the animation flag set. */
    private fun isAnimatedWebp(b: ByteArray): Boolean {
        if (b.size < 21) return false
        val isVp8x = String(b, 12, 4, Charsets.US_ASCII) == "VP8X"
        return isVp8x && (b[20].toInt() and 0x02) != 0
    }

    /** Counts image frames in a GIF (stops once it's sure there's more than one). */
    private fun gifFrameCount(b: ByteArray): Int {
        if (b.size < 13) return 0
        var p = 13
        val flags = b[10].toInt() and 0xff
        if (flags and 0x80 != 0) p += 3 * (1 shl ((flags and 0x07) + 1))
        var frames = 0
        while (p < b.size) {
            when (b[p].toInt() and 0xff) {
                0x2C -> { // image descriptor
                    frames++
                    if (frames > 1 || p + 10 > b.size) return frames
                    val lf = b[p + 9].toInt() and 0xff
                    p += 10
                    if (lf and 0x80 != 0) p += 3 * (1 shl ((lf and 0x07) + 1))
                    p += 1 // LZW minimum code size
                    p = skipSubBlocks(b, p) ?: return frames
                }
                0x21 -> { // extension: introducer + label, then data sub-blocks
                    p += 2
                    p = skipSubBlocks(b, p) ?: return frames
                }
                else -> return frames // 0x3B trailer or garbage
            }
        }
        return frames
    }

    private fun skipSubBlocks(b: ByteArray, start: Int): Int? {
        var p = start
        while (p < b.size) {
            val n = b[p].toInt() and 0xff
            p += 1
            if (n == 0) return p
            p += n
        }
        return null
    }
}
