# Palette Sticker Pack Format — v1

This is the contract between **Palette Stickers** (the Android app that makes packs) and
**Palette** (the Obsidian plugin that installs them). Anything that reads or writes packs
should follow this document; `palette-import/stickerpack.ts` is a reference reader.

## 1. A pack: `*.stickerpack`

A `.stickerpack` file is a plain ZIP archive:

```
my-pack.stickerpack
├── pack.json
└── stickers/
    ├── 001.png
    ├── 002.webp
    └── 003.gif
```

The same layout unzipped into a folder (`pack.json` + `stickers/`) is also a valid pack —
that's what the app's "Export to folder" writes. Readers should accept both.

### `pack.json`

```json
{
  "format": "palette.stickerpack",
  "version": 1,
  "id": "6f1c1e8e-3c1f-4a0b-9d0e-2a51b0b4d7c2",
  "name": "Reaction Faces",
  "author": "noctaur",
  "description": "",
  "revision": 7,
  "createdAt": "2026-10-15T06:30:00Z",
  "updatedAt": "2026-10-15T07:02:11Z",
  "cover": "a3f09c1d22e4b7aa",
  "stickers": [
    {
      "id": "a3f09c1d22e4b7aa",
      "file": "stickers/001.webp",
      "mime": "image/webp",
      "animated": true,
      "width": 512,
      "height": 512,
      "bytes": 48213,
      "sha256": "a3f09c1d22e4b7aa…(64 hex chars)"
    }
  ]
}
```

| Field | Type | Notes |
|---|---|---|
| `format` | string | Always `"palette.stickerpack"`. Reject anything else. |
| `version` | int | Format version. Readers must reject versions they don't know (> 1). |
| `id` | string | UUID, generated once when the pack is created and **never changes**. Palette uses it to recognise "the same pack, newer version". |
| `name` | string | Display name. Not unique. |
| `author` | string | May be empty. |
| `description` | string | May be empty. |
| `revision` | int | Starts at 1, increases on every change. Higher revision of the same `id` = update. |
| `createdAt`, `updatedAt` | string | ISO-8601 UTC. Informational. |
| `cover` | string \| null | A sticker `id` to use as the pack's thumbnail. If null or missing, use the first sticker. |
| `stickers` | array | **Array order is the display order.** |

Sticker entry:

| Field | Type | Notes |
|---|---|---|
| `id` | string | First 16 hex chars of the file's SHA-256. Content-addressed: the same image always has the same id, across packs and devices. Unique within a pack. |
| `file` | string | Path inside the pack, always under `stickers/`. Readers must refuse paths containing `..`, a leading `/`, or a backslash. |
| `mime` | string | One of `image/png`, `image/webp`, `image/gif`, `image/jpeg`, `image/heic`, `image/avif`. |
| `animated` | bool | `true` for animated GIF, animated WebP, APNG and animated AVIF. A static GIF is `false`. |
| `width`, `height` | int | Pixel size of the first frame. `0` if unknown. |
| `bytes` | int | File size. |
| `sha256` | string | Full lowercase hex SHA-256 of the file, for integrity checks. |

Files are stored **exactly as received** — no re-encoding — so animation, transparency and
quality are preserved. Readers should ignore unknown fields and ignore files in the archive
that `pack.json` doesn't list.

## 2. A library: `catalog.json`

"Export all" in the app writes a folder you can upload anywhere that serves static files
(a GitHub repo, GitHub Pages, a Cloudflare R2 bucket, a synced folder):

```
stickers/
├── catalog.json
├── reaction-faces-6f1c1e8e.stickerpack
└── cats-91be22d0.stickerpack
```

```json
{
  "format": "palette.stickercatalog",
  "version": 1,
  "generatedAt": "2026-10-15T07:05:00Z",
  "packs": [
    {
      "id": "6f1c1e8e-3c1f-4a0b-9d0e-2a51b0b4d7c2",
      "name": "Reaction Faces",
      "author": "noctaur",
      "revision": 7,
      "count": 24,
      "animated": 6,
      "file": "reaction-faces-6f1c1e8e.stickerpack",
      "bytes": 1203321,
      "sha256": "…"
    }
  ]
}
```

`file` is relative to `catalog.json`. To install from a hosted catalog, Palette fetches
`catalog.json`, shows the packs, then fetches the chosen `file`s (e.g. with Obsidian's
`requestUrl`, which works on iPad). Compare `id` + `revision` against installed packs to
offer updates. `sha256` is of the whole `.stickerpack` file.

## 3. Rules of thumb for Palette's importer

- Key installed packs by `id`. Same `id` with a higher `revision` → replace.
- Key stored images by sticker `id` (content hash) so a sticker that appears in two packs is
  stored once.
- Verify each file's SHA-256 against `sha256` when convenient; skip (don't fail the whole
  pack) on a mismatch.
- Never write archive paths straight to disk — only the paths you resolved from `pack.json`.
- Treat everything in a pack as untrusted data: it's just images and JSON.
