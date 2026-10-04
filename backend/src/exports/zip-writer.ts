/** Streaming ZIP writer (stored entries, no ZIP64) for exports; entries are already compressed or small text. */
const CRC_TABLE = Array.from({ length: 256 }, (_, n) => {
  let c = n;
  for (let k = 0; k < 8; k += 1) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
  return c >>> 0;
});

export function crc32(data: Uint8Array): number {
  let c = 0xffffffff;
  for (const byte of data) c = (CRC_TABLE[(c ^ byte) & 0xff] as number) ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}

export const MAX_ZIP_ENTRIES = 65_535;
export const MAX_ZIP_BYTES = 0xffff_fffe;

export class ExportTooLarge extends Error {}

function dosTime(date: Date): { time: number; date: number } {
  return {
    time: (date.getUTCHours() << 11) | (date.getUTCMinutes() << 5) | Math.floor(date.getUTCSeconds() / 2),
    date: ((Math.max(1980, date.getUTCFullYear()) - 1980) << 9) | ((date.getUTCMonth() + 1) << 5) | date.getUTCDate(),
  };
}

export class ZipWriter {
  private offset = 0;
  private readonly central: Uint8Array[] = [];
  private readonly encoder = new TextEncoder();

  constructor(
    private readonly sink: (chunk: Uint8Array) => Promise<void>,
    private readonly modified: Date,
  ) {}

  get entries(): number {
    return this.central.length;
  }

  async add(name: string, data: Uint8Array): Promise<void> {
    if (this.central.length + 1 > MAX_ZIP_ENTRIES || this.offset + data.length + 1024 > MAX_ZIP_BYTES) throw new ExportTooLarge();
    const nameBytes = this.encoder.encode(name);
    const crc = crc32(data);
    const { time, date } = dosTime(this.modified);
    const local = new Uint8Array(30 + nameBytes.length);
    const lv = new DataView(local.buffer);
    lv.setUint32(0, 0x04034b50, true);
    lv.setUint16(4, 20, true);
    lv.setUint16(6, 0x0800, true); // UTF-8 names
    lv.setUint16(8, 0, true);
    lv.setUint16(10, time, true);
    lv.setUint16(12, date, true);
    lv.setUint32(14, crc, true);
    lv.setUint32(18, data.length, true);
    lv.setUint32(22, data.length, true);
    lv.setUint16(26, nameBytes.length, true);
    local.set(nameBytes, 30);
    const central = new Uint8Array(46 + nameBytes.length);
    const cv = new DataView(central.buffer);
    cv.setUint32(0, 0x02014b50, true);
    cv.setUint16(4, 20, true);
    cv.setUint16(6, 20, true);
    cv.setUint16(8, 0x0800, true);
    cv.setUint16(10, 0, true);
    cv.setUint16(12, time, true);
    cv.setUint16(14, date, true);
    cv.setUint32(16, crc, true);
    cv.setUint32(20, data.length, true);
    cv.setUint32(24, data.length, true);
    cv.setUint16(28, nameBytes.length, true);
    cv.setUint32(42, this.offset, true);
    central.set(nameBytes, 46);
    this.central.push(central);
    await this.sink(local);
    await this.sink(data);
    this.offset += local.length + data.length;
  }

  async finish(): Promise<void> {
    const size = this.central.reduce((n, c) => n + c.length, 0);
    for (const entry of this.central) await this.sink(entry);
    const end = new Uint8Array(22);
    const ev = new DataView(end.buffer);
    ev.setUint32(0, 0x06054b50, true);
    ev.setUint16(8, this.central.length, true);
    ev.setUint16(10, this.central.length, true);
    ev.setUint32(12, size, true);
    ev.setUint32(16, this.offset, true);
    await this.sink(end);
  }
}

/** Buffers ZIP output into R2 multipart parts of at least 5 MiB (except the last). */
export class MultipartSink {
  private parts: R2UploadedPart[] = [];
  private buffer: Uint8Array[] = [];
  private buffered = 0;
  static readonly PART_BYTES = 8 * 1024 * 1024;

  constructor(private readonly upload: R2MultipartUpload) {}

  readonly write = async (chunk: Uint8Array): Promise<void> => {
    this.buffer.push(chunk);
    this.buffered += chunk.length;
    if (this.buffered >= MultipartSink.PART_BYTES) await this.flush();
  };

  private async flush(): Promise<void> {
    if (this.buffered === 0) return;
    const part = new Uint8Array(this.buffered);
    let at = 0;
    for (const chunk of this.buffer) {
      part.set(chunk, at);
      at += chunk.length;
    }
    this.buffer = [];
    this.buffered = 0;
    this.parts.push(await this.upload.uploadPart(this.parts.length + 1, part));
  }

  async complete(): Promise<R2Object> {
    await this.flush();
    return this.upload.complete(this.parts);
  }
}
