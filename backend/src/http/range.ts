/**
 * Single byte ranges for downloads (RFC 9110 section 14). A range is honoured only in the plain `bytes=a-b`, `bytes=a-` and
 * `bytes=-n` forms; anything else (several ranges, another unit, bad syntax, an `If-Range` precondition we cannot check)
 * is ignored and the whole object is sent, which the RFC allows. A well-formed range that starts at or after the end of the
 * object is unsatisfiable (416).
 */
export type RangeDecision = { kind: 'full' } | { kind: 'partial'; offset: number; length: number } | { kind: 'unsatisfiable' };

export function decideRange(request: Request, size: number): RangeDecision {
  const header = request.headers.get('range');
  if (header === null || request.headers.has('if-range')) return { kind: 'full' };
  const match = /^bytes=(\d*)-(\d*)$/.exec(header.trim());
  if (!match) return { kind: 'full' };
  const [, first, last] = match as unknown as [string, string, string];
  if (first === '' && last === '') return { kind: 'full' };
  if (first === '') {
    // The last n bytes.
    const n = Number(last);
    if (!Number.isSafeInteger(n)) return { kind: 'full' };
    if (n === 0 || size === 0) return { kind: 'unsatisfiable' };
    const length = Math.min(n, size);
    return { kind: 'partial', offset: size - length, length };
  }
  const start = Number(first);
  if (!Number.isSafeInteger(start)) return { kind: 'full' };
  if (start >= size) return { kind: 'unsatisfiable' };
  const end = last === '' ? size - 1 : Math.min(Number(last), size - 1);
  // `a-b` with b before a is not a range; ignore it.
  if (!Number.isSafeInteger(end) || end < start) return { kind: 'full' };
  return { kind: 'partial', offset: start, length: end - start + 1 };
}

/** The headers a range answer adds: 206 with the slice's position, or 416 with the size. */
export function rangeHeaders(decision: RangeDecision, size: number): Record<string, string> {
  if (decision.kind === 'partial') {
    return { 'accept-ranges': 'bytes', 'content-range': `bytes ${decision.offset}-${decision.offset + decision.length - 1}/${size}`, 'content-length': String(decision.length) };
  }
  if (decision.kind === 'unsatisfiable') return { 'accept-ranges': 'bytes', 'content-range': `bytes */${size}` };
  return { 'accept-ranges': 'bytes', 'content-length': String(size) };
}
