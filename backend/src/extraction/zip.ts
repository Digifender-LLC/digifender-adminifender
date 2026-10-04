/** Bounds for DOCX containers (lld.md#text-extraction-and-ocr). */
export const MAX_ENTRIES = 1000;
export const MAX_EXPANDED_BYTES = 20 * 1024 * 1024;
export const MAX_RATIO = 100;

export type ExtractionDetail = 'limit' | 'encrypted' | 'parser_error';

/** A deterministic, content-free extraction refusal; the detail is safe to store and show. */
export class ExtractionError extends Error {
  constructor(
    readonly detail: ExtractionDetail,
    readonly reason: string,
  ) {
    super(reason);
  }
}

export interface ZipEntry {
  name: string;
  method: number;
  flags: number;
  compressedSize: number;
  size: number;
  localOffset: number;
}

const u16 = (b: Uint8Array, at: number) => (b[at] ?? 0) | ((b[at + 1] ?? 0) << 8);
const u32 = (b: Uint8Array, at: number) => (u16(b, at) | (u16(b, at + 2) << 16)) >>> 0;

/**
 * Reads the central directory and enforces entry-count, declared-size and compression-ratio limits before any
 * byte is inflated. ZIP64, multi-disk and encrypted archives are refused. Nothing is unpacked recursively.
 */
export function readCentralDirectory(bytes: Uint8Array): ZipEntry[] {
  let eocd = -1;
  for (let at = bytes.length - 22; at >= Math.max(0, bytes.length - 22 - 0xffff); at -= 1) {
    if (u32(bytes, at) === 0x06054b50) {
      eocd = at;
      break;
    }
  }
  if (eocd < 0) throw new ExtractionError('parser_error', 'zip_directory');
  const count = u16(bytes, eocd + 10);
  const dirSize = u32(bytes, eocd + 12);
  const dirOffset = u32(bytes, eocd + 16);
  if (u16(bytes, eocd + 4) !== 0 || u16(bytes, eocd + 6) !== 0 || count !== u16(bytes, eocd + 8)) throw new ExtractionError('parser_error', 'zip_multidisk');
  if (count === 0xffff || dirOffset === 0xffffffff) throw new ExtractionError('limit', 'zip64');
  if (count > MAX_ENTRIES) throw new ExtractionError('limit', 'entries');
  if (dirOffset + dirSize > eocd) throw new ExtractionError('parser_error', 'zip_directory');

  const entries: ZipEntry[] = [];
  let at = dirOffset;
  let expanded = 0;
  const decoder = new TextDecoder('utf-8', { fatal: false, ignoreBOM: false });
  for (let i = 0; i < count; i += 1) {
    if (u32(bytes, at) !== 0x02014b50) throw new ExtractionError('parser_error', 'zip_entry');
    const nameLength = u16(bytes, at + 28);
    const entry: ZipEntry = {
      flags: u16(bytes, at + 8),
      method: u16(bytes, at + 10),
      compressedSize: u32(bytes, at + 20),
      size: u32(bytes, at + 24),
      localOffset: u32(bytes, at + 42),
      name: decoder.decode(bytes.subarray(at + 46, at + 46 + nameLength)),
    };
    if (entry.flags & 0x1) throw new ExtractionError('encrypted', 'zip_encrypted');
    if (entry.size === 0xffffffff || entry.compressedSize === 0xffffffff) throw new ExtractionError('limit', 'zip64');
    expanded += entry.size;
    if (expanded > MAX_EXPANDED_BYTES) throw new ExtractionError('limit', 'expanded_size');
    if (entry.size > 0 && entry.size > Math.max(entry.compressedSize, 1) * MAX_RATIO) throw new ExtractionError('limit', 'ratio');
    entries.push(entry);
    at += 46 + nameLength + u16(bytes, at + 30) + u16(bytes, at + 32);
  }
  return entries;
}

/**
 * Inflates one entry, stopping as soon as output exceeds its declared size or the remaining budget, so a lying
 * header cannot expand past the limits.
 */
export async function inflateEntry(bytes: Uint8Array, entry: ZipEntry, budget: number): Promise<Uint8Array> {
  const at = entry.localOffset;
  if (u32(bytes, at) !== 0x04034b50) throw new ExtractionError('parser_error', 'zip_local_header');
  const start = at + 30 + u16(bytes, at + 26) + u16(bytes, at + 28);
  const data = bytes.subarray(start, start + entry.compressedSize);
  if (data.length !== entry.compressedSize) throw new ExtractionError('parser_error', 'zip_truncated');
  const cap = Math.min(entry.size, budget);
  if (entry.method === 0) {
    if (entry.size !== entry.compressedSize) throw new ExtractionError('parser_error', 'zip_stored_size');
    if (entry.size > budget) throw new ExtractionError('limit', 'expanded_size');
    return data.slice();
  }
  if (entry.method !== 8) throw new ExtractionError('parser_error', 'zip_method');

  const stream = new DecompressionStream('deflate-raw');
  const writer = stream.writable.getWriter();
  writer.write(new Uint8Array(data)).catch(() => undefined);
  writer.close().catch(() => undefined);
  const reader = stream.readable.getReader();
  const parts: Uint8Array[] = [];
  let total = 0;
  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      total += value.byteLength;
      if (total > cap) {
        await reader.cancel().catch(() => undefined);
        throw new ExtractionError('limit', total > budget ? 'expanded_size' : 'declared_size');
      }
      parts.push(value);
    }
  } catch (error) {
    if (error instanceof ExtractionError) throw error;
    throw new ExtractionError('parser_error', 'zip_inflate');
  }
  if (total !== entry.size) throw new ExtractionError('parser_error', 'zip_size_mismatch');
  const out = new Uint8Array(total);
  let offset = 0;
  for (const part of parts) {
    out.set(part, offset);
    offset += part.byteLength;
  }
  return out;
}
