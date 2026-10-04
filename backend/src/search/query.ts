import { HttpError } from '../http/errors';

export const MAX_QUERY_CHARS = 200;
export const MAX_TERMS = 20;

/**
 * Turns user text into escaped literal FTS5 phrases joined by AND (lld.md#search). No raw FTS syntax,
 * column filters or operators from the caller ever reach SQLite.
 */
export function ftsQuery(raw: string): string {
  const text = raw.trim();
  if (!text || text.length > MAX_QUERY_CHARS) throw new HttpError(400, 'invalid_request', 'Enter 1 to 200 characters.', { path: '$.q', reason: 'length' });
  const terms = text.split(/\s+/u).filter(Boolean);
  if (terms.length > MAX_TERMS) throw new HttpError(400, 'invalid_request', 'Use at most 20 words.', { path: '$.q', reason: 'terms' });
  return terms.map((term) => `"${term.replace(/"/g, '""')}"`).join(' AND ');
}
