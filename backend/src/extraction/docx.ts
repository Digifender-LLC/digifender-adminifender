import { ExtractionError, MAX_EXPANDED_BYTES, inflateEntry, readCentralDirectory } from './zip';

/** Body, then notes; headers, footers, comments and embedded objects are not indexed. Macros are never run. */
const PARTS = ['word/document.xml', 'word/footnotes.xml', 'word/endnotes.xml'];

const ENTITIES: Readonly<Record<string, string>> = { amp: '&', lt: '<', gt: '>', quot: '"', apos: "'" };

function decodeEntities(text: string): string {
  return text.replace(/&(#x[0-9a-fA-F]{1,6}|#[0-9]{1,7}|[a-z]{2,4});/g, (whole, ref: string) => {
    if (ref.startsWith('#')) {
      const code = ref[1] === 'x' ? parseInt(ref.slice(2), 16) : parseInt(ref.slice(1), 10);
      return code > 0 && code <= 0x10ffff && !(code >= 0xd800 && code <= 0xdfff) ? String.fromCodePoint(code) : '�';
    }
    return ENTITIES[ref] ?? whole;
  });
}

/**
 * WordprocessingML text with paragraph, line, tab and table-cell structure. Document type declarations are refused,
 * so no entity is ever expanded and no external reference is resolved.
 */
export function wordXmlText(xml: string): string {
  if (/<!DOCTYPE|<!ENTITY/i.test(xml)) throw new ExtractionError('parser_error', 'xml_doctype');
  let out = '';
  let inText = 0;
  let inCell = 0;
  const token = /<(\/?)([A-Za-z_][\w.:-]*)[^>]*?(\/?)>|<[!?][^>]*>|([^<]+)/g;
  for (let match = token.exec(xml); match; match = token.exec(xml)) {
    const [, closing, name, selfClosing, text] = match;
    if (text !== undefined) {
      if (inText > 0) out += decodeEntities(text);
      continue;
    }
    if (!name) continue;
    if (name === 'w:t') {
      if (!closing && !selfClosing) inText += 1;
      else if (closing) inText = Math.max(0, inText - 1);
    } else if (name === 'w:tab' && !closing) out += '\t';
    else if ((name === 'w:br' || name === 'w:cr') && !closing) out += '\n';
    else if (name === 'w:p' && closing) out += inCell > 0 ? ' ' : '\n';
    else if (name === 'w:tc' && !closing && !selfClosing) inCell += 1;
    else if (name === 'w:tc' && closing) {
      inCell = Math.max(0, inCell - 1);
      out = `${out.replace(/ +$/, '')}\t`;
    } else if (name === 'w:tr' && closing) out = `${out.replace(/\t$/, '')}\n`;
  }
  return out;
}

export async function docxText(bytes: Uint8Array): Promise<string> {
  const entries = readCentralDirectory(bytes);
  const byName = new Map(entries.map((e) => [e.name, e]));
  if (!byName.has('[Content_Types].xml') || !byName.has('word/document.xml')) throw new ExtractionError('parser_error', 'not_docx');
  const decoder = new TextDecoder('utf-8', { fatal: true, ignoreBOM: false });
  let budget = MAX_EXPANDED_BYTES;
  const texts: string[] = [];
  for (const part of PARTS) {
    const entry = byName.get(part);
    if (!entry) continue;
    const xml = await inflateEntry(bytes, entry, budget);
    budget -= xml.byteLength;
    let decoded: string;
    try {
      decoded = decoder.decode(xml);
    } catch {
      throw new ExtractionError('parser_error', 'xml_encoding');
    }
    texts.push(wordXmlText(decoded));
  }
  return texts.join('\n');
}
