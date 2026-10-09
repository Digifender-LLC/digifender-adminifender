/**
 * Signed Whumpo report verification (lld.md#reports). Pure functions: the wrapper is parsed, the HMAC is checked over
 * the exact payload bytes before the payload is parsed, and the payload must match a closed schema per kind.
 */

export const REPORT_FILENAME = 'adminifender-report.json';
export const MAX_PAYLOAD_BYTES = 64 * 1024;
const MAX_WRAPPER_BYTES = 96 * 1024;
/** Timestamps further ahead than this are not trusted as current state. */
export const FUTURE_SKEW_MS = 5 * 60 * 1000;

export const REPORT_KINDS = ['maintenance', 'uncertain_operation', 'release', 'operator_decision', 'usage', 'readiness', 'heartbeat'] as const;
export type ReportKind = (typeof REPORT_KINDS)[number];

export interface Coverage {
  scope: 'single_observation' | 'service_kind_snapshot';
  status: 'complete' | 'partial' | 'unknown';
  omittedCount: number | null;
}

export interface Report {
  eventId: string;
  serviceId: string;
  kind: ReportKind;
  occurredAt: number;
  sequence: number;
  concernKey: string;
  classification: 'metadata_only';
  coverage: Coverage;
  summary: string;
  facts: Record<string, unknown>;
  links: { label: string; url: string }[];
}

export type Failure = { ok: false; code: string };
type Ok<T> = { ok: true; value: T };
const fail = (code: string): Failure => ({ ok: false, code });

export interface Wrapper {
  keyId: string;
  payload: Uint8Array;
  signature: Uint8Array;
}

function fromBase64url(text: string): Uint8Array | null {
  if (!/^[A-Za-z0-9_-]*$/.test(text)) return null;
  try {
    const binary = atob(text.replace(/-/g, '+').replace(/_/g, '/') + '='.repeat((4 - (text.length % 4)) % 4));
    return Uint8Array.from(binary, (c) => c.charCodeAt(0));
  } catch {
    return null;
  }
}

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const isObject = (v: unknown): v is Record<string, unknown> => typeof v === 'object' && v !== null && !Array.isArray(v);
const onlyKeys = (o: Record<string, unknown>, allowed: readonly string[]) => Object.keys(o).every((k) => allowed.includes(k));

/** `{schemaVersion:1,keyId,payloadBase64url,signature}`; nothing in the payload is read before the signature is checked. */
export function parseWrapper(bytes: Uint8Array): Ok<Wrapper> | Failure {
  if (bytes.byteLength > MAX_WRAPPER_BYTES) return fail('too_large');
  let raw: unknown;
  try {
    raw = JSON.parse(new TextDecoder('utf-8', { fatal: true, ignoreBOM: false }).decode(bytes));
  } catch {
    return fail('malformed_wrapper');
  }
  if (!isObject(raw) || !onlyKeys(raw, ['schemaVersion', 'keyId', 'payloadBase64url', 'signature'])) return fail('malformed_wrapper');
  if (raw.schemaVersion !== 1) return fail('unsupported_version');
  if (typeof raw.keyId !== 'string' || !UUID.test(raw.keyId)) return fail('malformed_wrapper');
  if (typeof raw.payloadBase64url !== 'string' || typeof raw.signature !== 'string') return fail('malformed_wrapper');
  const payload = fromBase64url(raw.payloadBase64url);
  const signature = fromBase64url(raw.signature);
  if (!payload || !signature || signature.byteLength !== 32) return fail('malformed_wrapper');
  if (payload.byteLength > MAX_PAYLOAD_BYTES) return fail('too_large');
  return { ok: true, value: { keyId: raw.keyId, payload, signature } };
}

/** HMAC-SHA256 over the exact payload bytes; WebCrypto's verify compares in constant time. */
export async function verifySignature(key: Uint8Array, payload: Uint8Array, signature: Uint8Array): Promise<boolean> {
  const hmac = await crypto.subtle.importKey('raw', key, { name: 'HMAC', hash: 'SHA-256' }, false, ['verify']);
  return crypto.subtle.verify('HMAC', hmac, signature, payload);
}

// --- Closed fact schemas ---------------------------------------------------------------------------------------

type Check = (value: unknown) => boolean;
const state: Check = (v) => typeof v === 'string' && /^[a-z][a-z_]{0,39}$/.test(v);
const ident: Check = (v) => typeof v === 'string' && /^[A-Za-z0-9._:/-]{1,100}$/.test(v);
const count: Check = (v) => Number.isSafeInteger(v) && (v as number) >= 0;
const instant: Check = (v) => typeof v === 'string' && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{1,3})?Z$/.test(v) && !Number.isNaN(Date.parse(v));
const nullable = (c: Check): Check => (v) => v === null || c(v);
const oneOf = (values: readonly string[]): Check => (v) => typeof v === 'string' && values.includes(v);
const hex64: Check = (v) => typeof v === 'string' && /^[0-9a-f]{64}$/.test(v);
const quantity: Check = (v) => typeof v === 'number' && Number.isFinite(v) && v >= 0;
const versions: Check = (v) => isObject(v) && Object.keys(v).length <= 50 && Object.entries(v).every(([k, x]) => ident(k) && ident(x));

/** Allowed facts per kind; unknown fields are rejected, so exception text, logs or statements cannot ride along. */
export const FACTS: Record<ReportKind, Record<string, Check>> = {
  maintenance: { job: ident, state, oldestAgeSeconds: count, backlogCount: count, lastSuccessAt: nullable(instant) },
  uncertain_operation: { operationId: ident, family: ident, state, dispatchedAt: instant, recoveryGuideId: nullable(ident) },
  release: { releaseId: ident, manifestHash: hex64, serviceVersions: versions, schemaRevision: count, deploymentState: state, acceptanceState: state },
  // Fixed categories only; operator-decision reports never carry a statement or response text (FR-039).
  operator_decision: { requestId: ident, category: oneOf(['billing', 'access', 'data_request', 'abuse', 'account', 'other']), state, createdAt: instant },
  usage: { periodStart: instant, periodEnd: instant, metric: ident, quantity, unit: ident, evidenceKind: oneOf(['invoice', 'estimate', 'internal_usage', 'unsettled']) },
  readiness: {
    resourceRef: ident,
    kind: oneOf(['domain_expiry', 'credential_reconnect', 'credential_revocation', 'backup_verification', 'recovery_drill']),
    state,
    evidenceAt: instant,
    dueAt: nullable(instant),
  },
  heartbeat: { state: oneOf(['ok', 'degraded', 'failing']), expectedIntervalSeconds: (v) => Number.isSafeInteger(v) && (v as number) > 0 },
};

const TOP_LEVEL = ['eventId', 'serviceId', 'kind', 'occurredAt', 'sequence', 'concernKey', 'classification', 'coverage', 'summary', 'facts', 'links'];

/** Validates a verified payload. `now` bounds future timestamps; the caller still checks service, alias and key. */
export function validateReport(payload: Uint8Array, now: number): Ok<Report> | Failure {
  let raw: unknown;
  try {
    raw = JSON.parse(new TextDecoder('utf-8', { fatal: true, ignoreBOM: false }).decode(payload));
  } catch {
    return fail('malformed_payload');
  }
  if (!isObject(raw) || !onlyKeys(raw, TOP_LEVEL)) return fail('unknown_field');
  const { eventId, serviceId, kind, occurredAt, sequence, concernKey, classification, coverage, summary, facts, links } = raw;
  if (typeof eventId !== 'string' || !UUID.test(eventId) || typeof serviceId !== 'string' || !UUID.test(serviceId)) return fail('invalid_identity');
  if (typeof kind !== 'string' || !(REPORT_KINDS as readonly string[]).includes(kind)) return fail('unknown_kind');
  if (!instant(occurredAt)) return fail('invalid_time');
  const occurred = Date.parse(occurredAt as string);
  if (occurred > now + FUTURE_SKEW_MS) return fail('future_timestamp');
  if (!count(sequence)) return fail('invalid_sequence');
  if (typeof concernKey !== 'string' || !/^[a-z0-9_]{1,40}(:[A-Za-z0-9._-]{1,80}){0,3}$/.test(concernKey)) return fail('invalid_concern_key');
  if (classification !== 'metadata_only') return fail('invalid_classification');
  if (!isObject(coverage) || !onlyKeys(coverage, ['scope', 'status', 'omittedCount'])) return fail('invalid_coverage');
  if (!oneOf(['single_observation', 'service_kind_snapshot'])(coverage.scope) || !oneOf(['complete', 'partial', 'unknown'])(coverage.status)) return fail('invalid_coverage');
  if (!(coverage.omittedCount === null || count(coverage.omittedCount))) return fail('invalid_coverage');
  // Complete means nothing was omitted; truncated output must say partial (lld.md#reports).
  if (coverage.status === 'complete' && coverage.omittedCount !== 0) return fail('invalid_coverage');
  const summaryMax = kind === 'operator_decision' ? 120 : 500;
  if (typeof summary !== 'string' || summary.length === 0 || summary.length > summaryMax || /[\u0000-\u001f]/.test(summary)) return fail('invalid_summary');
  if (!isObject(facts)) return fail('invalid_facts');
  const allowed = FACTS[kind as ReportKind];
  for (const [name, value] of Object.entries(facts)) {
    const check = allowed[name];
    if (!check) return fail('unknown_fact');
    if (!check(value)) return fail('invalid_fact');
  }
  if (!Array.isArray(links) || links.length > 5) return fail('invalid_links');
  for (const link of links) {
    if (!isObject(link) || !onlyKeys(link, ['label', 'url'])) return fail('invalid_links');
    if (typeof link.label !== 'string' || link.label.length === 0 || link.label.length > 100) return fail('invalid_links');
    if (typeof link.url !== 'string' || link.url.length > 500 || !/^https:\/\/[^\s]+$/.test(link.url)) return fail('invalid_links');
  }
  return {
    ok: true,
    value: {
      eventId,
      serviceId,
      kind: kind as ReportKind,
      occurredAt: occurred,
      sequence: sequence as number,
      concernKey,
      classification: 'metadata_only',
      coverage: { scope: coverage.scope as Coverage['scope'], status: coverage.status as Coverage['status'], omittedCount: coverage.omittedCount as number | null },
      summary,
      facts,
      links: links as { label: string; url: string }[],
    },
  };
}
