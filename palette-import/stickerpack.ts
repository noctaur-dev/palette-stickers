/**
 * Palette — sticker pack reader (format v1, see ../FORMAT.md).
 *
 * Drop this file into the Palette plugin and add `fflate` as a dependency (≈8 KB gzipped,
 * pure JS, works on iPad). It only parses and validates; where Palette stores stickers is up
 * to Palette.
 *
 *   const pack = await readStickerPack(arrayBuffer);          // from a .stickerpack file
 *   const catalog = parseCatalog(await requestUrl(url).json);  // from a hosted catalog.json
 */
import { unzipSync } from "fflate";

export const PACK_FORMAT = "palette.stickerpack";
export const CATALOG_FORMAT = "palette.stickercatalog";
export const SUPPORTED_VERSION = 1;

const MIME_TYPES = new Set([
	"image/png",
	"image/webp",
	"image/gif",
	"image/jpeg",
	"image/heic",
	"image/avif",
]);

export interface StickerEntry {
	id: string;
	file: string;
	mime: string;
	animated: boolean;
	width: number;
	height: number;
	bytes: number;
	sha256: string;
}

export interface PackManifest {
	format: typeof PACK_FORMAT;
	version: number;
	id: string;
	name: string;
	author: string;
	description: string;
	revision: number;
	createdAt: string;
	updatedAt: string;
	cover: string | null;
	stickers: StickerEntry[];
}

export interface LoadedSticker extends StickerEntry {
	data: Uint8Array;
	/** false if sha256 was present and didn't match the file. */
	verified: boolean;
}

export interface LoadedPack {
	manifest: PackManifest;
	/** In display order. Stickers whose file was missing or unsafe are left out. */
	stickers: LoadedSticker[];
	/** The cover sticker (falls back to the first one). */
	cover: LoadedSticker | null;
	/** Human-readable notes about anything skipped. */
	warnings: string[];
}

export interface CatalogEntry {
	id: string;
	name: string;
	author: string;
	revision: number;
	count: number;
	animated: number;
	/** Relative to catalog.json. */
	file: string;
	bytes: number;
	sha256: string;
}

export interface Catalog {
	format: typeof CATALOG_FORMAT;
	version: number;
	generatedAt: string;
	packs: CatalogEntry[];
}

export class StickerPackError extends Error {}

// ---------------------------------------------------------------------------------------------

const str = (v: unknown, fallback = ""): string => (typeof v === "string" ? v : fallback);
const int = (v: unknown, fallback = 0): number =>
	typeof v === "number" && Number.isFinite(v) ? Math.trunc(v) : fallback;

function isSafePath(p: string): boolean {
	return (
		p.startsWith("stickers/") &&
		!p.includes("..") &&
		!p.includes("\\") &&
		!p.startsWith("/") &&
		p.length < 256
	);
}

/** Validates a pack.json object. Throws StickerPackError if it isn't a pack Palette can read. */
export function parseManifest(raw: unknown): PackManifest {
	if (!raw || typeof raw !== "object") throw new StickerPackError("pack.json is not an object");
	const o = raw as Record<string, unknown>;
	if (o.format !== PACK_FORMAT) throw new StickerPackError("Not a Palette sticker pack");
	const version = int(o.version, 0);
	if (version < 1 || version > SUPPORTED_VERSION) {
		throw new StickerPackError(`Pack format v${version} is newer than this Palette supports`);
	}
	const id = str(o.id);
	if (!/^[A-Za-z0-9-]{1,64}$/.test(id)) throw new StickerPackError("Pack has an invalid id");

	const seen = new Set<string>();
	const stickers: StickerEntry[] = [];
	for (const s of Array.isArray(o.stickers) ? o.stickers : []) {
		if (!s || typeof s !== "object") continue;
		const e = s as Record<string, unknown>;
		const sid = str(e.id);
		const file = str(e.file);
		const mime = str(e.mime);
		if (!sid || seen.has(sid) || !isSafePath(file) || !MIME_TYPES.has(mime)) continue;
		seen.add(sid);
		stickers.push({
			id: sid,
			file,
			mime,
			animated: e.animated === true,
			width: int(e.width),
			height: int(e.height),
			bytes: int(e.bytes),
			sha256: str(e.sha256).toLowerCase(),
		});
	}

	return {
		format: PACK_FORMAT,
		version,
		id,
		name: str(o.name, "Untitled") || "Untitled",
		author: str(o.author),
		description: str(o.description),
		revision: Math.max(1, int(o.revision, 1)),
		createdAt: str(o.createdAt),
		updatedAt: str(o.updatedAt),
		cover: typeof o.cover === "string" ? o.cover : null,
		stickers,
	};
}

async function sha256Hex(data: Uint8Array): Promise<string> {
	// Copy into a fresh ArrayBuffer-backed view so subtle.digest gets a plain BufferSource.
	const digest = await crypto.subtle.digest("SHA-256", new Uint8Array(data));
	return Array.from(new Uint8Array(digest), (b) => b.toString(16).padStart(2, "0")).join("");
}

/**
 * Reads a pack from its files — shared by the .stickerpack and unzipped-folder paths.
 * `files` maps archive paths (e.g. "stickers/001.png") to bytes; `pack.json` must be present.
 */
export async function readStickerPackFiles(
	files: Record<string, Uint8Array>,
	opts: { verify?: boolean } = {},
): Promise<LoadedPack> {
	// Tolerate a pack nested one folder deep (someone zipped the folder, not its contents).
	const manifestKey = Object.keys(files)
		.filter((k) => k === "pack.json" || k.endsWith("/pack.json"))
		.sort((a, b) => a.length - b.length)[0];
	if (!manifestKey) throw new StickerPackError("No pack.json found — this isn't a sticker pack");
	const prefix = manifestKey.slice(0, -"pack.json".length);

	let raw: unknown;
	try {
		raw = JSON.parse(new TextDecoder().decode(files[manifestKey]));
	} catch {
		throw new StickerPackError("pack.json isn't valid JSON");
	}
	const manifest = parseManifest(raw);

	const warnings: string[] = [];
	const stickers: LoadedSticker[] = [];
	for (const entry of manifest.stickers) {
		const data = files[prefix + entry.file];
		if (!data) {
			warnings.push(`Missing file ${entry.file}`);
			continue;
		}
		let verified = true;
		if (opts.verify !== false && entry.sha256.length === 64) {
			verified = (await sha256Hex(data)) === entry.sha256;
			if (!verified) warnings.push(`Checksum mismatch for ${entry.file}`);
		}
		stickers.push({ ...entry, bytes: data.byteLength, data, verified });
	}

	const cover = stickers.find((s) => s.id === manifest.cover) ?? stickers[0] ?? null;
	return { manifest, stickers, cover, warnings };
}

/** Reads a .stickerpack (zip). Pass `{ verify: false }` to skip checksum checks on huge packs. */
export async function readStickerPack(
	zip: ArrayBuffer | Uint8Array,
	opts: { verify?: boolean } = {},
): Promise<LoadedPack> {
	let files: Record<string, Uint8Array>;
	try {
		files = unzipSync(zip instanceof Uint8Array ? zip : new Uint8Array(zip), {
			// Only pull out what a pack can contain.
			filter: (f) => f.name.endsWith("pack.json") || f.name.includes("stickers/"),
		});
	} catch {
		throw new StickerPackError("Couldn't open the file — is it a .stickerpack?");
	}
	return readStickerPackFiles(files, opts);
}

/** Validates a catalog.json. Entries with unsafe paths are dropped. */
export function parseCatalog(raw: unknown): Catalog {
	if (!raw || typeof raw !== "object") throw new StickerPackError("catalog.json is not an object");
	const o = raw as Record<string, unknown>;
	if (o.format !== CATALOG_FORMAT) throw new StickerPackError("Not a Palette sticker catalog");
	const version = int(o.version, 0);
	if (version < 1 || version > SUPPORTED_VERSION) {
		throw new StickerPackError(`Catalog format v${version} is newer than this Palette supports`);
	}
	const packs: CatalogEntry[] = [];
	for (const p of Array.isArray(o.packs) ? o.packs : []) {
		if (!p || typeof p !== "object") continue;
		const e = p as Record<string, unknown>;
		const file = str(e.file);
		const id = str(e.id);
		if (!id || !file || file.includes("..") || file.includes("/") || file.includes("\\")) continue;
		packs.push({
			id,
			name: str(e.name, "Untitled"),
			author: str(e.author),
			revision: int(e.revision, 1),
			count: int(e.count),
			animated: int(e.animated),
			file,
			bytes: int(e.bytes),
			sha256: str(e.sha256).toLowerCase(),
		});
	}
	return { format: CATALOG_FORMAT, version, generatedAt: str(o.generatedAt), packs };
}

/** Resolves a catalog entry's file against the catalog URL. */
export function catalogFileUrl(catalogUrl: string, entry: CatalogEntry): string {
	return new URL(entry.file, catalogUrl).toString();
}

/** True if `incoming` should replace what's installed (same pack, newer revision). */
export function isUpdate(installed: { id: string; revision: number } | undefined, incoming: { id: string; revision: number }): boolean {
	return !installed || (installed.id === incoming.id && incoming.revision > installed.revision);
}
