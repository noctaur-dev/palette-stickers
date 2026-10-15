// Round-trip checks for the reader: npm test
import { zipSync, strToU8 } from "fflate";
import { createHash } from "node:crypto";
import assert from "node:assert/strict";
import { readStickerPack, parseCatalog, catalogFileUrl, isUpdate, StickerPackError } from "./stickerpack.ts";

const sha = (b: Uint8Array) => createHash("sha256").update(b).digest("hex");
const png = new Uint8Array([0x89, 0x50, 0x4e, 0x47, 1, 2, 3, 4, 5, 6, 7, 8]);
const gif = strToU8("GIF89a-fake-animated");

function manifest(extra: object = {}) {
	return {
		format: "palette.stickerpack",
		version: 1,
		id: "6f1c1e8e-3c1f-4a0b-9d0e-2a51b0b4d7c2",
		name: "Test",
		author: "noctaur",
		description: "",
		revision: 3,
		createdAt: "2026-10-15T06:30:00Z",
		updatedAt: "2026-10-15T07:02:11Z",
		cover: sha(gif).slice(0, 16),
		stickers: [
			{ id: sha(png).slice(0, 16), file: "stickers/001.png", mime: "image/png", animated: false, width: 1, height: 1, bytes: png.length, sha256: sha(png) },
			{ id: sha(gif).slice(0, 16), file: "stickers/002.gif", mime: "image/gif", animated: true, width: 1, height: 1, bytes: gif.length, sha256: sha(gif) },
			{ id: "evil", file: "stickers/../../etc/passwd", mime: "image/png", animated: false, width: 0, height: 0, bytes: 0, sha256: "" },
		],
		...extra,
	};
}

// 1. Normal pack
let pack = await readStickerPack(
	zipSync({ "pack.json": strToU8(JSON.stringify(manifest())), "stickers/001.png": png, "stickers/002.gif": gif }),
);
assert.equal(pack.stickers.length, 2, "unsafe path dropped");
assert.equal(pack.stickers[1].animated, true);
assert.equal(pack.cover?.file, "stickers/002.gif");
assert.ok(pack.stickers.every((s) => s.verified));

// 2. Nested one folder deep + tampered file
pack = await readStickerPack(
	zipSync({
		"My Pack/pack.json": strToU8(JSON.stringify(manifest())),
		"My Pack/stickers/001.png": png,
		"My Pack/stickers/002.gif": strToU8("tampered"),
	}),
);
assert.equal(pack.stickers.length, 2);
assert.equal(pack.stickers[1].verified, false);
assert.equal(pack.warnings.length, 1);

// 3. Rejections
for (const bad of [manifest({ format: "whatsapp" }), manifest({ version: 2 }), manifest({ id: "../x" })]) {
	await assert.rejects(readStickerPack(zipSync({ "pack.json": strToU8(JSON.stringify(bad)) })), StickerPackError);
}
await assert.rejects(readStickerPack(strToU8("not a zip")), StickerPackError);

// 4. Catalog
const cat = parseCatalog({
	format: "palette.stickercatalog",
	version: 1,
	generatedAt: "x",
	packs: [
		{ id: "a", name: "A", revision: 2, count: 3, animated: 1, file: "a-12345678.stickerpack", bytes: 10, sha256: "AB" },
		{ id: "b", name: "B", file: "../escape.stickerpack" },
	],
});
assert.equal(cat.packs.length, 1);
assert.equal(
	catalogFileUrl("https://raw.githubusercontent.com/u/r/main/stickers/catalog.json", cat.packs[0]),
	"https://raw.githubusercontent.com/u/r/main/stickers/a-12345678.stickerpack",
);
assert.ok(isUpdate({ id: "a", revision: 1 }, cat.packs[0]));
assert.ok(!isUpdate({ id: "a", revision: 2 }, cat.packs[0]));

console.log("all reader checks passed");
