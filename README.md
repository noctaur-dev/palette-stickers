# Palette Stickers

A small Android app for curating sticker packs on your phone — including your Samsung custom
stickers — and exporting them in a format the Palette Obsidian plugin can install.

- **Grab stickers straight from the keyboard.** Open a pack, tap the dashed box, switch the
  Samsung Keyboard to its sticker tab and tap stickers. Each one lands in the pack as the
  original file (PNG, GIF, WebP…), animation and transparency intact.
- **Share sheet + file picker.** Share images/GIFs from any app to *Add to sticker pack*, or use
  *Add files* inside a pack.
- **Packs.** Long-press and drag to reorder stickers (and packs). Select to move between packs,
  delete, or set the cover. Duplicates are detected by content hash.
- **Animated + static.** Animated GIF, animated WebP and APNG are detected and play in the grid.
- **Export.** Per pack: *Share pack*, *Save .stickerpack…*, or *Export to folder…* (a plain
  folder of images + `pack.json`). For everything: *Export all + catalog…* writes every pack plus
  a `catalog.json` into one folder you can host for Palette to install from.
- **Import** `.stickerpack` files back in (backup/restore, or moving to another phone).
- Local only, no accounts, no ads, nothing locked.

## Install the app

The APK is built by GitHub Actions on every push to `main` (this workspace couldn't reach
Google's Android SDK servers to build it locally).

1. Create an empty GitHub repo (e.g. `palette-stickers`) and push this folder to it.
2. Wait for the **Build APK** action to finish (~3–4 min) — Actions tab.
3. On the phone, open `https://github.com/<you>/palette-stickers/releases/latest`, download
   the `.apk`, and open it. Allow "install unknown apps" for your browser/Files when asked.
4. Later builds install over the top (same signing key, rising version number).

Building locally instead: open the folder in Android Studio and run, or `gradle :app:assembleRelease`
with Gradle 8.9+ and JDK 17.

## Pack format

See [`FORMAT.md`](FORMAT.md). Short version: a `.stickerpack` is a zip with `pack.json` and a
`stickers/` folder; files are stored untouched; stickers are identified by content hash; each
pack has a permanent `id` and an increasing `revision` so Palette can offer updates.

## Palette side

[`palette-import/stickerpack.ts`](palette-import/stickerpack.ts) is a drop-in reader for Palette
(depends only on `fflate`): `readStickerPack()` for a `.stickerpack`, `readStickerPackFiles()`
for an unzipped folder, `parseCatalog()` / `catalogFileUrl()` / `isUpdate()` for installing
from a hosted catalog. `npm test` in that folder runs its checks.

## Notes

- `keystore/palette-stickers.jks` is checked in on purpose so every build can update the
  installed app. It's fine for a personal sideloaded app; use a private key if you ever publish.
- If a keyboard ever sends a sticker as a web link instead of a file, share it to the app instead.
