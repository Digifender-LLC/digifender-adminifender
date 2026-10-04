import { htmlToText } from '../archive/html-text';

export type ExtractKind = 'text' | 'html' | 'docx' | 'pdf' | 'image' | 'unsupported';

const TEXT_TYPES = new Set(['text/plain', 'text/csv', 'text/markdown', 'text/x-markdown', 'application/json', 'text/json', 'text/tab-separated-values']);
const TEXT_EXTENSIONS = new Set(['txt', 'csv', 'tsv', 'md', 'markdown', 'json', 'log']);
const DOCX_TYPE = 'application/vnd.openxmlformats-officedocument.wordprocessingml.document';

const startsWith = (bytes: Uint8Array, magic: number[]) => magic.every((b, i) => bytes[i] === b);

/**
 * Chooses an extractor from content signatures first, then the declared type and file extension. Binary content
 * never goes to a text extractor whatever its label says.
 */
export function detectKind(name: string, declaredType: string | null, bytes: Uint8Array): ExtractKind {
  const type = (declaredType ?? '').split(';')[0]?.trim().toLowerCase() ?? '';
  const extension = name.includes('.') ? (name.split('.').pop() ?? '').toLowerCase() : '';
  if (startsWith(bytes, [0x25, 0x50, 0x44, 0x46, 0x2d])) return 'pdf';
  if (startsWith(bytes, [0x89, 0x50, 0x4e, 0x47]) || startsWith(bytes, [0xff, 0xd8, 0xff]) || (startsWith(bytes, [0x52, 0x49, 0x46, 0x46]) && startsWith(bytes.subarray(8), [0x57, 0x45, 0x42, 0x50]))) {
    return 'image';
  }
  if (startsWith(bytes, [0x50, 0x4b, 0x03, 0x04])) return type === DOCX_TYPE || extension === 'docx' ? 'docx' : 'unsupported';
  const textual = TEXT_TYPES.has(type) || TEXT_EXTENSIONS.has(extension);
  const html = type === 'text/html' || extension === 'html' || extension === 'htm';
  if (!textual && !html) return 'unsupported';
  const utf16 = startsWith(bytes, [0xff, 0xfe]) || startsWith(bytes, [0xfe, 0xff]);
  if (!utf16 && bytes.subarray(0, 8192).includes(0)) return 'unsupported';
  return html ? 'html' : 'text';
}

/** UTF-8 (with or without BOM), UTF-16 by BOM, otherwise windows-1252 so every byte maps to a character. */
export function decodeText(bytes: Uint8Array): string {
  if (startsWith(bytes, [0xff, 0xfe])) return new TextDecoder('utf-16le').decode(bytes.subarray(2));
  if (startsWith(bytes, [0xfe, 0xff])) return new TextDecoder('utf-16be').decode(bytes.subarray(2));
  try {
    return new TextDecoder('utf-8', { fatal: true, ignoreBOM: false }).decode(bytes);
  } catch {
    return new TextDecoder('windows-1252').decode(bytes);
  }
}

export function plainText(kind: 'text' | 'html', bytes: Uint8Array): string {
  const decoded = decodeText(bytes);
  const text = kind === 'html' ? htmlToText(decoded) : decoded;
  return text.replace(/\r\n?/g, '\n').replace(/[ \t]+$/gm, '').replace(/\n{3,}/g, '\n\n').trim();
}
