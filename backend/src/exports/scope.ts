import { HttpError } from '../http/errors';
import { canonicalJson } from '../http/idempotency';
import { type Validator, v } from '../http/validate';

/**
 * Immutable export scope (lld.md#exports). Quarantine is never implied: `admission` is explicit in every stored
 * scope and defaults to `normal` only when a request omits it.
 */
export type Admission = 'normal' | 'quarantine' | 'all';
export interface ExportFilter {
  from?: string;
  to?: string;
  sender?: string;
  recipient?: string;
}
export type ExportScope =
  | { kind: 'archive'; admission: Admission }
  | { kind: 'filter'; admission: Admission; filter: ExportFilter }
  | { kind: 'sources'; admission: Admission; sourceIds: string[] };

export const MAX_EXPORT_SOURCE_IDS = 1000;
const ADMISSIONS = ['normal', 'quarantine', 'all'] as const;

const invalid = (path: string, reason: string) => new HttpError(400, 'invalid_request', 'The request is invalid.', { path, reason });
const admission = v.optional(v.oneOf(ADMISSIONS));
const address = v.string({ max: 320 });

/** Sorted and duplicate-free, so equivalent requests hash alike for idempotency. */
const sourceIdList: Validator<string[]> = (value, path) => {
  if (!Array.isArray(value) || value.length === 0 || value.length > MAX_EXPORT_SOURCE_IDS) throw invalid(path, 'expected_list');
  const ids = value.map((item, i) => v.uuid()(item, `${path}[${i}]`));
  if (new Set(ids).size !== ids.length) throw invalid(path, 'duplicate');
  return [...ids].sort();
};

const lowerAddress: Validator<string> = (value, path) => {
  const text = address(value, path).trim().toLowerCase();
  if (!text) throw invalid(path, 'length');
  return text;
};

const filterShape = v.object({ from: v.optional(v.instant()), to: v.optional(v.instant()), sender: v.optional(lowerAddress), recipient: v.optional(lowerAddress) });

/** Same criteria as `GET /search`: received time (from inclusive, to exclusive) and exact envelope addresses. */
const filterValidator: Validator<ExportFilter> = (value, path) => {
  const parsed = filterShape(value, path);
  const out: ExportFilter = {};
  if (parsed.from !== undefined) out.from = new Date(parsed.from).toISOString();
  if (parsed.to !== undefined) out.to = new Date(parsed.to).toISOString();
  if (parsed.sender !== undefined) out.sender = parsed.sender;
  if (parsed.recipient !== undefined) out.recipient = parsed.recipient;
  if (Object.keys(out).length === 0) throw invalid(path, 'empty_filter');
  if (parsed.from !== undefined && parsed.to !== undefined && parsed.from >= parsed.to) throw invalid(path, 'empty_range');
  return out;
};

export const exportScope: Validator<ExportScope> = (value, path) => {
  // `kind` first, so each shape rejects the fields that belong to the others.
  const head = v.record()(value, path);
  const selected = v.oneOf(['archive', 'filter', 'sources'] as const)(head.kind, `${path}.kind`);
  if (selected === 'archive') {
    const parsed = v.object({ kind: v.oneOf(['archive'] as const), admission })(value, path);
    return { kind: 'archive', admission: parsed.admission ?? 'normal' };
  }
  if (selected === 'filter') {
    const parsed = v.object({ kind: v.oneOf(['filter'] as const), admission, filter: filterValidator })(value, path);
    return { kind: 'filter', admission: parsed.admission ?? 'normal', filter: parsed.filter };
  }
  const parsed = v.object({ kind: v.oneOf(['sources'] as const), admission, sourceIds: sourceIdList })(value, path);
  return { kind: 'sources', admission: parsed.admission ?? 'normal', sourceIds: parsed.sourceIds };
};

export const DEFAULT_SCOPE: ExportScope = { kind: 'archive', admission: 'normal' };

/** `{}` is the whole normal archive. The result always carries a fully explicit scope, which is what the idempotency hash covers. */
export const exportBody: Validator<{ scope: ExportScope }> = (value, path) => {
  const parsed = v.object({ scope: v.optional(exportScope) })(value, path);
  return { scope: parsed.scope ?? DEFAULT_SCOPE };
};

export const scopeJson = (scope: ExportScope): string => canonicalJson(scope);

/** Re-validates a stored scope; anything unreadable is refused instead of being widened. */
export function parseStoredScope(json: string | null): ExportScope | null {
  if (json === null) return null;
  try {
    const parsed = JSON.parse(json) as unknown;
    const scope = exportScope(parsed, '$.scope');
    return canonicalJson(scope) === canonicalJson(parsed) ? scope : null;
  } catch {
    return null;
  }
}

/** Snapshot admissions a scope selects. */
export const selectsAdmission = (scope: ExportScope, snapshotAdmission: string): boolean => scope.admission === 'all' || scope.admission === snapshotAdmission;
