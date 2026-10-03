import PostalMime, { type Address, type Email } from 'postal-mime';
import { sha256Hex } from '../ids';
import { htmlToText } from './html-text';

export const PARSER_VERSION = 'mime-v1';
/** Archive chunk bound (lld.md#storage). */
export const MAX_CHUNK_CHARS = 6000;
/** Header JSON bound per source row (lld.md#storage). */
export const MAX_HEADERS_JSON = 64 * 1024;
const MAX_NESTING = 32;
const MAX_HEADER_BYTES = 256 * 1024;

export interface NormalizedAttachment {
  name: string;
  declaredType: string | null;
  disposition: 'attachment' | 'inline';
  sizeBytes: number;
  sha256: string;
  bytes: Uint8Array;
}

export interface NormalizedEmail {
  subject: string;
  envelope: {
    from: string[];
    to: string[];
    cc: string[];
    date: string | null;
    messageId: string | null;
    inReplyTo: string | null;
    references: string | null;
  };
  headers: { name: string; value: string }[];
  headersTruncated: boolean;
  text: string;
  textHash: string;
  chunks: { ordinal: number; startOffset: number; text: string; textHash: string }[];
  attachments: NormalizedAttachment[];
  parserVersion: string;
  /** Raw HTML body for admission link features only; never stored. */
  html: string | undefined;
}


function addresses(value: Address | Address[] | undefined): string[] {
  const list = value === undefined ? [] : Array.isArray(value) ? value : [value];
  return list.flatMap((entry) => (entry.group ? entry.group.map((m) => m.address) : [entry.address])).filter((a): a is string => !!a);
}

function plain(text: string): string {
  return text.replace(/\r\n?/g, '\n').replace(/[ \t]+$/gm, '').replace(/\n{3,}/g, '\n\n').trim();
}

const comparable = (text: string) => text.replace(/\s+/g, ' ').trim().toLowerCase();

/**
 * Text body selection for FR-006: plain text when present; the HTML projection when it is the only body or
 * when it materially differs from the plain alternative, in which case both are retained.
 */
export function bodyText(email: Pick<Email, 'text' | 'html'>): string {
  const text = email.text ? plain(email.text) : '';
  const html = email.html ? htmlToText(email.html) : '';
  if (!html) return text;
  if (!text) return html;
  const a = comparable(text);
  const b = comparable(html);
  if (a === b || a.includes(b)) return text;
  if (b.includes(a)) return html;
  return `${text}\n\n[HTML alternative]\n${html}`;
}

/** Deterministic chunks of at most MAX_CHUNK_CHARS, preferring paragraph, then line, then word boundaries. */
export function chunkText(text: string, max = MAX_CHUNK_CHARS): { ordinal: number; startOffset: number; text: string }[] {
  const chunks: { ordinal: number; startOffset: number; text: string }[] = [];
  let start = 0;
  while (start < text.length) {
    let end = Math.min(start + max, text.length);
    if (end < text.length) {
      const window = text.slice(start, end);
      const cut = Math.max(window.lastIndexOf('\n\n'), -1);
      const line = window.lastIndexOf('\n');
      const space = window.lastIndexOf(' ');
      const boundary = cut > max / 2 ? cut + 2 : line > max / 2 ? line + 1 : space > max / 2 ? space + 1 : window.length;
      end = start + boundary;
      // Never split a UTF-16 surrogate pair.
      const code = text.charCodeAt(end - 1);
      if (code >= 0xd800 && code <= 0xdbff) end -= 1;
    }
    chunks.push({ ordinal: chunks.length, startOffset: start, text: text.slice(start, end) });
    start = end;
  }
  return chunks;
}

function boundedHeaders(headers: Email['headers']): { headers: { name: string; value: string }[]; truncated: boolean } {
  const kept: { name: string; value: string }[] = [];
  let size = 2;
  for (const header of headers) {
    const entry = { name: header.originalKey, value: header.value };
    size += JSON.stringify(entry).length + 1;
    if (size > MAX_HEADERS_JSON) return { headers: kept, truncated: true };
    kept.push(entry);
  }
  return { headers: kept, truncated: false };
}

/** Parses staged raw MIME into normalized text, bounded headers and retained attachment bytes. Throws on invalid MIME. */
export async function normalizeEmail(raw: ArrayBuffer | Uint8Array): Promise<NormalizedEmail> {
  const email = await PostalMime.parse(raw, {
    attachmentEncoding: 'arraybuffer',
    maxNestingDepth: MAX_NESTING,
    maxHeadersSize: MAX_HEADER_BYTES,
    maxRfc822NestingDepth: 3,
  });
  const text = bodyText(email);
  const { headers, truncated } = boundedHeaders(email.headers);
  const attachments: NormalizedAttachment[] = [];
  for (const [index, attachment] of email.attachments.entries()) {
    const bytes = attachment.content instanceof ArrayBuffer ? new Uint8Array(attachment.content) : typeof attachment.content === 'string' ? new TextEncoder().encode(attachment.content) : attachment.content;
    attachments.push({
      name: (attachment.filename ?? '').trim() || `attachment-${index + 1}`,
      declaredType: attachment.mimeType || null,
      disposition: attachment.disposition === 'inline' || attachment.related ? 'inline' : 'attachment',
      sizeBytes: bytes.byteLength,
      sha256: await sha256Hex(bytes),
      bytes,
    });
  }
  return {
    subject: (email.subject ?? '').trim(),
    envelope: {
      from: addresses(email.from),
      to: addresses(email.to),
      cc: addresses(email.cc),
      date: email.date ?? null,
      messageId: email.messageId ?? null,
      inReplyTo: email.inReplyTo ?? null,
      references: email.references ?? null,
    },
    headers,
    headersTruncated: truncated,
    text,
    textHash: await sha256Hex(text),
    chunks: await Promise.all(chunkText(text).map(async (chunk) => ({ ...chunk, textHash: await sha256Hex(chunk.text) }))),
    attachments,
    parserVersion: PARSER_VERSION,
    html: email.html,
  };
}
