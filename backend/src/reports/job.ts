import type { Env } from '../env';
import { sha256Hex } from '../ids';
import { openToken } from '../notifications/push';
import { archiveFor, REGISTRY_COLUMNS, REGISTRY_FROM, type RegistryRow, sourceChange, sourceProjection } from '../sources/registry';
import { CLAIM_GUARD, type ClaimedJob, type JobResult, guardParams } from '../jobs/runner';
import { REPORT_FILENAME, parseWrapper, validateReport, verifySignature } from './verify';

export const REPORT_TASK = 'report';

export const reportsEnabled = (env: Env) => env.REPORTS_ENABLED === 'true';

/**
 * Whether a parsed email is report mail: it carries the report attachment or arrived at a registered report alias.
 * Such mail is restricted before anything else runs, whatever its signature turns out to be (lld.md#reports).
 */
export async function isReportMail(env: Env, attachmentNames: string[], recipientHash: string | null): Promise<boolean> {
  if (!reportsEnabled(env)) return false;
  if (attachmentNames.includes(REPORT_FILENAME)) return true;
  if (!recipientHash) return false;
  const { results } = await env.CONTROL_DB.prepare('SELECT DISTINCT alias FROM report_keys WHERE alias IS NOT NULL').all<{ alias: string }>();
  for (const row of results) if ((await sha256Hex(row.alias)) === recipientHash) return true;
  return false;
}

/** Queues verification for a source in the parse claim's batch. */
export function reportJobStatements(db: D1Database, sourceId: string, recipientHash: string | null, now: number, condition: string, params: unknown[]): D1PreparedStatement[] {
  const dedupe = `report:${sourceId}`;
  const jobId = crypto.randomUUID();
  return [
    db.prepare(`INSERT OR IGNORE INTO jobs(id, dedupe_key, kind, source_id, input_revision, state, next_at, input_json, created_at) SELECT ?, ?, ?, ?, NULL, 'pending', ?, ?, ? WHERE ${condition}`)
      .bind(jobId, dedupe, REPORT_TASK, sourceId, now, JSON.stringify({ sourceId, recipientHash }), now, ...params),
    db.prepare("INSERT OR IGNORE INTO outbox(id, dedupe_key, kind, reference_id, state, next_at) SELECT ?, 'job:' || id, 'job', id, 'pending', ? FROM jobs WHERE dedupe_key = ?")
      .bind(crypto.randomUUID(), now, dedupe),
  ];
}

/**
 * Verifies one report email. A trusted report is stored once per (service, event) and advances the service's head
 * only with a higher sequence. Anything else quarantines the email (it stays archived and restricted) and records one
 * verification issue. A valid signature still grants no operation authority.
 */
export async function reportJob(job: ClaimedJob, env: Env, now: number): Promise<JobResult> {
  const input = job.input as { sourceId?: string; recipientHash?: string | null };
  if (typeof input.sourceId !== 'string') return { kind: 'fail', errorCode: 'invalid_input' };
  const db = env.CONTROL_DB;
  const [guardId, guardToken] = guardParams(job);
  const source = await db.prepare(`SELECT ${REGISTRY_COLUMNS} FROM ${REGISTRY_FROM} WHERE r.id = ?`).bind(input.sourceId).first<RegistryRow>();
  if (!source || source.state !== 'ready') return { kind: 'complete', output: { skipped: 'not_ready' } };
  const archive = archiveFor(env, source);
  if (!archive) return { kind: 'retry', errorCode: 'archive_unavailable' };

  const stored = await archive.prepare('SELECT subject, envelope_json FROM sources WHERE id = ?').bind(source.id).first<{ subject: string; envelope_json: string }>();
  const quarantined = sourceProjection({ ...source, admission: 'quarantine', revision: source.revision + 1 }, stored);
  const reject = (code: string, serviceId: string | null = null): JobResult => ({
    kind: 'complete',
    output: { trusted: false, code },
    statements: [
      db.prepare(`INSERT OR IGNORE INTO report_issues(source_id, service_id, code, created_at) SELECT ?, ?, ?, ? WHERE ${CLAIM_GUARD}`).bind(source.id, serviceId, code, now, guardId, guardToken),
      db.prepare(`UPDATE source_registry SET admission = 'quarantine', revision = revision + 1 WHERE id = ? AND revision = ? AND admission = 'normal' AND ${CLAIM_GUARD}`)
        .bind(source.id, source.revision, guardId, guardToken),
      sourceChange(db, source.id, quarantined, now, { sql: `revision = ? AND admission = 'quarantine' AND ${CLAIM_GUARD}`, params: [source.revision + 1, guardId, guardToken] }),
    ],
  });

  const attachments = (await archive.prepare('SELECT name, object_key, sha256 FROM attachments WHERE source_id = ? AND name = ?').bind(source.id, REPORT_FILENAME).all<{ name: string; object_key: string; sha256: string }>()).results;
  if (attachments.length !== 1) return reject(attachments.length === 0 ? 'missing_report' : 'multiple_reports');
  const object = await env.ARCHIVE_BUCKET.get(attachments[0]!.object_key);
  if (!object) return { kind: 'retry', errorCode: 'attachment_unavailable' };
  const bytes = new Uint8Array(await object.arrayBuffer());
  if ((await sha256Hex(bytes)) !== attachments[0]!.sha256) return { kind: 'fail', errorCode: 'attachment_hash_mismatch' };

  const wrapper = parseWrapper(bytes);
  if (!wrapper.ok) return reject(wrapper.code);
  const key = await db.prepare('SELECT id, service_id, alias, secret_cipher, revoked_at FROM report_keys WHERE id = ?').bind(wrapper.value.keyId).first<{
    id: string;
    service_id: string;
    alias: string | null;
    secret_cipher: ArrayBuffer | number[] | null;
    revoked_at: number | null;
  }>();
  if (!key || key.revoked_at !== null || !key.secret_cipher || !env.REPORT_KEY_WRAP) return reject('unknown_key');
  // The key belongs to one receiving alias: a report delivered anywhere else is not trusted.
  if (!key.alias || !input.recipientHash || (await sha256Hex(key.alias)) !== input.recipientHash) return reject('wrong_alias', key.service_id);
  const secret = await openToken(env.REPORT_KEY_WRAP, key.id, key.secret_cipher);
  if (!secret) return reject('unknown_key', key.service_id);
  const raw = Uint8Array.from(atob(secret), (c) => c.charCodeAt(0));
  if (!(await verifySignature(raw, wrapper.value.payload, wrapper.value.signature))) return reject('bad_signature', key.service_id);

  const checked = validateReport(wrapper.value.payload, now);
  if (!checked.ok) return reject(checked.code, key.service_id);
  const report = checked.value;
  if (report.serviceId !== key.service_id) return reject('service_mismatch', key.service_id);
  if (report.kind === 'readiness') {
    const service = await db.prepare('SELECT targets_json FROM service_catalog WHERE id = ?').bind(report.serviceId).first<{ targets_json: string }>();
    const targets = service ? (JSON.parse(service.targets_json) as string[]) : [];
    if (!targets.includes(report.facts.resourceRef as string)) return reject('unknown_resource', key.service_id);
  }

  const payloadHash = await sha256Hex(wrapper.value.payload);
  const existing = await db.prepare('SELECT payload_hash, source_id FROM service_reports WHERE service_id = ? AND event_id = ?').bind(report.serviceId, report.eventId).first<{ payload_hash: string; source_id: string }>();
  if (existing && existing.payload_hash !== payloadHash) return reject('conflicting_event', key.service_id);
  if (existing) return { kind: 'complete', output: { trusted: true, duplicate: true } };

  return {
    kind: 'complete',
    output: { trusted: true, serviceId: report.serviceId, kind: report.kind, sequence: report.sequence },
    statements: [
      db.prepare(
        `INSERT OR IGNORE INTO service_reports(service_id, event_id, payload_hash, source_id, key_id, kind, sequence, concern_key, occurred_at, received_at, report_json)
         SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ? WHERE ${CLAIM_GUARD}`,
      ).bind(report.serviceId, report.eventId, payloadHash, source.id, key.id, report.kind, report.sequence, report.concernKey, report.occurredAt, source.received_at, JSON.stringify(report), guardId, guardToken),
      // Only a higher sequence advances what the operations view treats as current; late or replayed reports stay history.
      db.prepare(
        `INSERT INTO service_report_heads(service_id, kind, concern_key, sequence, event_id) SELECT ?, ?, ?, ?, ? WHERE ${CLAIM_GUARD}
         ON CONFLICT(service_id, kind, concern_key) DO UPDATE SET sequence = excluded.sequence, event_id = excluded.event_id WHERE excluded.sequence > service_report_heads.sequence`,
      ).bind(report.serviceId, report.kind, report.concernKey, report.sequence, report.eventId, guardId, guardToken),
    ],
  };
}
