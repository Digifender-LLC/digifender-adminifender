/**
 * Quote-based citations. Models copy the words they rely on (`{chunkId, quote}`); the server finds them in that
 * chunk and stores the usual `{chunkId, start, end}` offsets. A quote that does not occur in its chunk is an error,
 * so citations stay checked against the exact text the model was given. Offset citations are left as they are.
 */

export const MIN_QUOTE = 3;
export const MAX_QUOTE = 1000;

type Citation = { chunkId?: unknown; quote?: unknown; start?: unknown; end?: unknown };

/** Lower-cased text with whitespace runs collapsed to one space, plus each kept character's original index. */
function folded(text: string): { text: string; index: number[] } {
  let out = '';
  const index: number[] = [];
  let space = false;
  for (let i = 0; i < text.length; i++) {
    const c = text[i]!;
    if (/\s/.test(c)) {
      space = out.length > 0;
      continue;
    }
    if (space) {
      out += ' ';
      index.push(i - 1);
      space = false;
    }
    out += c.toLowerCase();
    index.push(i);
  }
  return { text: out, index };
}

/** Surrounding quote marks and ellipses a model may add around a copied phrase. */
const TRIM = /^[\s"'“”‘’«»`.…]+|[\s"'“”‘’«»`.…]+$/g;

/** Offsets of `quote` in `text`: exact first, then ignoring case and whitespace differences. */
export function locateQuote(text: string, quote: string): { start: number; end: number } | null {
  const wanted = quote.replace(TRIM, '');
  if (wanted.length < MIN_QUOTE) return null;
  const exact = text.indexOf(wanted);
  if (exact >= 0) return { start: exact, end: exact + wanted.length };
  const hay = folded(text);
  const needle = folded(wanted).text;
  const at = hay.text.indexOf(needle);
  if (at < 0 || needle.length === 0) return null;
  return { start: hay.index[at]!, end: hay.index[at + needle.length - 1]! + 1 };
}

function resolve(citation: unknown, texts: ReadonlyMap<string, string>, at: string, errors: string[]): unknown {
  if (citation === null || typeof citation !== 'object' || Array.isArray(citation)) return citation;
  const c = citation as Citation;
  if (!('quote' in c) || 'start' in c || 'end' in c) return citation;
  if (typeof c.quote !== 'string' || c.quote.length > MAX_QUOTE) {
    errors.push(`${at}.quote:invalid`);
    return citation;
  }
  const text = typeof c.chunkId === 'string' ? texts.get(c.chunkId) : undefined;
  if (text === undefined) {
    errors.push(`${at}.chunkId:not_in_context`);
    return citation;
  }
  const found = locateQuote(text, c.quote);
  if (!found) {
    errors.push(`${at}.quote:not_in_chunk`);
    return citation;
  }
  const { quote: _quote, ...rest } = c;
  return { ...rest, ...found };
}

const list = (value: unknown): unknown[] | null => (Array.isArray(value) ? value : null);
const record = (value: unknown): Record<string, unknown> | null => (value !== null && typeof value === 'object' && !Array.isArray(value) ? (value as Record<string, unknown>) : null);

/** Triage output: resolves `evidence[]` quotes. */
export function resolveTriageQuotes(value: unknown, texts: ReadonlyMap<string, string>): { value: unknown; errors: string[] } {
  const errors: string[] = [];
  const root = record(value);
  const evidence = root && list(root.evidence);
  if (!root || !evidence) return { value, errors };
  return { value: { ...root, evidence: evidence.map((c, i) => resolve(c, texts, `$.evidence[${i}]`, errors)) }, errors };
}

/** Chat answers: resolves `claims[].citations[]` quotes. */
export function resolveAnswerQuotes(value: unknown, texts: ReadonlyMap<string, string>): { value: unknown; errors: string[] } {
  const errors: string[] = [];
  const root = record(value);
  const claims = root && list(root.claims);
  if (!root || !claims) return { value, errors };
  const resolved = claims.map((claim, i) => {
    const c = record(claim);
    const citations = c && list(c.citations);
    if (!c || !citations) return claim;
    return { ...c, citations: citations.map((x, j) => resolve(x, texts, `$.claims[${i}].citations[${j}]`, errors)) };
  });
  return { value: { ...root, claims: resolved }, errors };
}
