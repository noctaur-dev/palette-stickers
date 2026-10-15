package com.noctaur.palettestickers

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Reading and writing .stickerpack files, pack folders and catalogs (see FORMAT.md). */
object PackIO {

    const val EXT = ".stickerpack"
    const val PACK_MIME = "application/octet-stream" // keeps providers from appending ".zip"

    fun fileName(p: Pack) = "${slugify(p.name)}-${p.id.take(8)}$EXT"

    /** Exported file names follow display order, so a plain folder sorts correctly too. */
    private fun exportName(i: Int, s: Sticker) = String.format(Locale.ROOT, "stickers/%03d.%s", i + 1, s.ext)

    /** The pack as it will be exported: only stickers whose files exist, ordered names. */
    private fun exportable(p: Pack): List<Pair<String, Sticker>> =
        p.stickers.filter { Library.file(p, it).isFile }.mapIndexed { i, s -> exportName(i, s) to s }

    private fun manifest(p: Pack, entries: List<Pair<String, Sticker>>): ByteArray {
        val view = p.snapshot().also { v ->
            v.stickers.clear()
            v.stickers.addAll(entries.map { it.second })
            if (v.cover != null && v.stickers.none { it.id == v.cover }) v.cover = null
        }
        val names = entries.associate { it.second.id to it.first }
        return view.toJson { _, s -> names.getValue(s.id) }.toString(2).toByteArray(Charsets.UTF_8)
    }

    fun writeZip(p: Pack, out: OutputStream) {
        val entries = exportable(p)
        ZipOutputStream(BufferedOutputStream(out)).use { z ->
            z.setLevel(Deflater.BEST_SPEED) // images are already compressed
            z.putNextEntry(ZipEntry("pack.json"))
            z.write(manifest(p, entries))
            z.closeEntry()
            for ((name, s) in entries) {
                z.putNextEntry(ZipEntry(name))
                Library.file(p, s).inputStream().use { it.copyTo(z) }
                z.closeEntry()
            }
        }
    }

    /** Builds the .stickerpack in cache (for sharing, or to hash before copying out). */
    fun buildZipFile(ctx: Context, p: Pack): File {
        val dir = File(ctx.cacheDir, "exports").apply { mkdirs() }
        val f = File(dir, fileName(p))
        f.outputStream().use { writeZip(p, it) }
        return f
    }

    fun isZip(b: ByteArray) = b.size > 4 && b[0] == 'P'.code.toByte() && b[1] == 'K'.code.toByte() &&
        b[2] == 3.toByte() && b[3] == 4.toByte()

    private fun safePath(p: String) = p.startsWith("stickers/") && !p.contains("..") && !p.contains('\\')

    /** Reads a .stickerpack. Tolerates the pack being nested one folder deep inside the zip. */
    fun readZip(input: InputStream): Pair<Pack, Map<String, ByteArray>> {
        val entries = HashMap<String, ByteArray>()
        var total = 0L
        ZipInputStream(input.buffered()).use { zin ->
            while (true) {
                val e = zin.nextEntry ?: break
                if (e.isDirectory) continue
                if (entries.size >= 5000) throw IOException("Too many files in this pack")
                val bytes = readCapped(zin, MAX_STICKER_BYTES.toLong())
                total += bytes.size
                if (total > MAX_PACK_BYTES) throw IOException("Pack is too large")
                entries[e.name] = bytes
            }
        }
        val manifestKey = entries.keys.filter { it == "pack.json" || it.endsWith("/pack.json") }
            .minByOrNull { it.length } ?: throw IOException("No pack.json — this isn't a sticker pack")
        val prefix = manifestKey.removeSuffix("pack.json")
        val pack = Pack.fromJson(JSONObject(String(entries.getValue(manifestKey), Charsets.UTF_8)))
        val files = HashMap<String, ByteArray>()
        for (s in pack.stickers) {
            if (!safePath(s.file)) continue
            entries[prefix + s.file]?.let { files[s.id] = it }
        }
        return pack to files
    }

    // ---- Storage Access Framework (user-picked folders) -------------------------------------

    private fun rootDoc(tree: Uri): Uri =
        DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))

    private fun children(cr: ContentResolver, tree: Uri, parent: Uri): List<Pair<String, Uri>> {
        val kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(parent))
        val out = ArrayList<Pair<String, Uri>>()
        cr.query(
            kids,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                out.add(c.getString(1) to DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0)))
            }
        }
        return out
    }

    private fun findOrCreate(cr: ContentResolver, tree: Uri, parent: Uri, name: String, mime: String): Uri =
        children(cr, tree, parent).firstOrNull { it.first == name }?.second
            ?: DocumentsContract.createDocument(cr, parent, mime, name)
            ?: throw IOException("Couldn't create $name")

    private fun writeDoc(cr: ContentResolver, uri: Uri, block: (OutputStream) -> Unit) {
        val out = try {
            cr.openOutputStream(uri, "wt")
        } catch (e: Exception) {
            cr.openOutputStream(uri, "w")
        } ?: throw IOException("Couldn't write file")
        out.use(block)
    }

    /** Writes the pack to a .stickerpack chosen with the system "Save as" screen. */
    fun exportToDocument(ctx: Context, p: Pack, uri: Uri) {
        writeDoc(ctx.contentResolver, uri) { writeZip(p, it) }
    }

    /**
     * Writes a plain folder:  <Pack name>/pack.json + stickers/001.png …  into the picked folder.
     * Re-exporting to the same place updates it and removes stickers that are no longer in the pack.
     */
    fun exportToFolder(ctx: Context, p: Pack, tree: Uri): String {
        val cr = ctx.contentResolver
        val folderName = safeFolderName(p.name)
        val packDir = findOrCreate(cr, tree, rootDoc(tree), folderName, DocumentsContract.Document.MIME_TYPE_DIR)
        val stickersDir = findOrCreate(cr, tree, packDir, "stickers", DocumentsContract.Document.MIME_TYPE_DIR)
        val entries = exportable(p)
        val keep = HashSet<String>()
        for ((name, s) in entries) {
            val leaf = name.removePrefix("stickers/")
            keep.add(leaf)
            val doc = findOrCreate(cr, tree, stickersDir, leaf, s.mime)
            writeDoc(cr, doc) { out -> Library.file(p, s).inputStream().use { it.copyTo(out) } }
        }
        for ((name, uri) in children(cr, tree, stickersDir)) {
            if (name !in keep) runCatching { DocumentsContract.deleteDocument(cr, uri) }
        }
        val manifestDoc = findOrCreate(cr, tree, packDir, "pack.json", "application/json")
        writeDoc(cr, manifestDoc) { it.write(manifest(p, entries)) }
        return folderName
    }

    /**
     * "Export all": every pack as a .stickerpack plus catalog.json, into one folder you can host
     * anywhere static (GitHub, a synced folder…) for Palette to install from.
     */
    fun exportLibrary(ctx: Context, packs: List<Pack>, tree: Uri): Int {
        val cr = ctx.contentResolver
        val root = rootDoc(tree)
        val list = JSONArray()
        for (p in packs) {
            if (p.stickers.isEmpty()) continue
            val tmp = buildZipFile(ctx, p)
            try {
                val doc = findOrCreate(cr, tree, root, tmp.name, PACK_MIME)
                writeDoc(cr, doc) { out -> tmp.inputStream().use { it.copyTo(out) } }
                list.put(
                    JSONObject()
                        .put("id", p.id)
                        .put("name", p.name)
                        .put("author", p.author)
                        .put("revision", p.revision)
                        .put("count", p.stickers.size)
                        .put("animated", p.animatedCount)
                        .put("file", tmp.name)
                        .put("bytes", tmp.length())
                        .put("sha256", Hash.sha256(tmp)),
                )
            } finally {
                tmp.delete()
            }
        }
        val catalog = JSONObject()
            .put("format", FORMAT_CATALOG)
            .put("version", FORMAT_VERSION)
            .put("generatedAt", nowIso())
            .put("packs", list)
        val doc = findOrCreate(cr, tree, root, "catalog.json", "application/json")
        writeDoc(cr, doc) { it.write(catalog.toString(2).toByteArray(Charsets.UTF_8)) }
        return list.length()
    }
}
