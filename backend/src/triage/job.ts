import { parseSingleMailbox } from '../admission/addresses';
import { type ConcernRow, CONCERN_COLUMNS, applyEvidence } from '../concerns/model';
import { concernSourceIds, writeConcern } from '../concerns/store';
import type { Env } from '../env';
import { canonicalJson } from '../http/idempotency';
import { deterministicUuid, sha256Hex } from '../ids';
import { inferJobStatements } from '../inference/job';
import { CLAIM_GUARD, type ClaimedJob, type JobResult, guardParams } from '../jobs/runner';
import { REGISTRY_COLUMNS, REGISTRY_FROM, type RegistryRow, archiveFor } from '../sources/registry';
import { type Rule, type RuleDecision, decide, parseEffects, parsePredicate } from './rules';

export const TRIAGE_ENGINE = 'rules-v1';
const MAX_REFERENCES = 50;

/**
 * Enqueues triage for a source in the caller's batch, applying only when the registry row satisfies `condition`
 * (bound with `params`). Deduplicated per source revision, so release after quarantine triggers it again.
 */
export function triageJobStatements(db: D1Database, sourceId: string, now: number, condition: string, params: unknown[]): D1PreparedStatement[] {
  const dedupe = `(SELECT 'triage:' || id || ':' || revision FROM source_registry WHERE id = ?)`;
  return [
    db.prepare(
      `INSERT OR IGNORE INTO jobs(id, dedupe_key, kind, source_id, input_revision, state, next_at, input_json, created_at)
       SELECT ?, 'triage:' || id || ':' || revision, 'triage', id, revision, 'pending', ?, ?, ? FROM source_registry WHERE id = ? AND admission = 'normal' AND state = 'ready' AND ${condition}`,
    ).bind(crypto.randomUUID(), now, JSON.stringify({ sourceId }), now, sourceId, ...params),
    db.prepare(`INSERT OR IGNORE INTO outbox(id, dedupe_key, kind, reference_id, state, next_at) SELECT ?, 'job:' || id, 'job', id, 'pending', ? FROM jobs WHERE dedupe_key = ${dedupe}`)
      .bind(crypto.randomUUID(), now, sourceId),
  ];
}

/** Message IDs from In-Reply-To and References, nearest parent first. */
export function threadReferences(inReplyTo: string | null, references: string | null): string[] {
  const ids = (value: string | null) => (value ?? '').match(/<[^<>\s]{1,250}>/g) ?? [];
  const ordered = [...ids(inReplyTo), ...ids(references).reverse()];
  return [...new Set(ordered)].slice(0, MAX_REFERENCES);
}

export function messageIdOf(value: string | null): string | null {
  return (value ?? '').match(/<[^<>\s]{1,250}>/)?.[0] ?? null;
}

async function loadRules(db: D1Database): Promise<{ rules: Rule[]; versions: { id: string; revision: number }[] }> {
  const { results } = await db
    .prepare('SELECT id, revision, origin, predicate_json, effect_json, created_at FROM rules WHERE enabled = 1 ORDER BY created_at, id')
    .all<{ id: string; revision: number; origin: 'owner' | 'learned'; predicate_json: string; effect_json: string; created_at: number }>();
  return {
    rules: results.map((r) => ({ id: r.id, origin: r.origin, createdAt: r.created_at, predicate: parsePredicate(JSON.parse(r.predicate_json)), effects: parseEffects(JSON.parse(r.effect_json)) })),
    versions: results.map((r) => ({ id: r.id, revision: r.revision })),
  };
}

/** Concern already holding the nearest verified parent message in the same recipient mailbox, if any. */
async function threadConcern(db: D1Database, sourceId: string): Promise<string | null> {
  const thread = await db.prepare('SELECT mailbox_hash, references_json FROM source_threads WHERE source_id = ?').bind(sourceId).first<{ mailbox_hash: string; references_json: string }>();
  if (!thread) return null;
  const refs = JSON.parse(thread.references_json) as string[];
  if (refs.length === 0) return null;
  const { results } = await db
    .prepare(
      `SELECT t.message_id, cs.concern_id FROM source_threads t JOIN concern_sources cs ON cs.source_id = t.source_id JOIN source_registry r ON r.id = t.source_id
       WHERE t.mailbox_hash = ? AND t.source_id <> ? AND r.state = 'ready' AND r.admission = 'normal' AND t.message_id IN (${refs.map(() => '?').join(',')})
       ORDER BY r.received_at DESC, r.id DESC`,
    )
    .bind(thread.mailbox_hash, sourceId, ...refs)
    .all<{ message_id: string; concern_id: string }>();
  for (const ref of refs) {
    const hit = results.find((r) => r.message_id === ref);
    if (hit) return hit.concern_id;
  }
  return null;
}

/**
 * Deterministic triage (lld.md#triage, lld.md#concerns): evaluates owner and learned rules, groups the source into
 * a concern by verified thread reference or creates one, and records a triage result awaiting inference.
 */
export async function triageJob(job: ClaimedJob, env: Env, now: number): Promise<JobResult> {
  if (!job.sourceId) return { kind: 'fail', errorCode: 'missing_source' };
  const db = env.CONTROL_DB;
  const source = await db.prepare(`SELECT ${REGISTRY_COLUMNS} FROM ${REGISTRY_FROM} WHERE r.id = ?`).bind(job.sourceId).first<RegistryRow>();
  if (!source || source.state !== 'ready' || source.admission !== 'normal') return { kind: 'complete', output: { skipped: 'not_eligible' } };
  const grouped = await db.prepare('SELECT concern_id FROM concern_sources WHERE source_id = ?').bind(source.id).first<{ concern_id: string }>();
  if (grouped) return { kind: 'complete', output: { skipped: 'already_grouped', concernId: grouped.concern_id } };

  const archive = archiveFor(env, source);
  const stored = archive
    ? await archive.prepare('SELECT subject, headers_json, normalized_text, text_object_key, text_hash FROM sources WHERE id = ?').bind(source.id).first<{
        subject: string;
        headers_json: string;
        normalized_text: string | null;
        text_object_key: string | null;
        text_hash: string;
      }>()
    : null;
  if (!stored) return { kind: 'retry', errorCode: 'archive_unavailable' };
  let body = stored.normalized_text;
  if (body === null && stored.text_object_key) body = (await (await env.ARCHIVE_BUCKET.get(stored.text_object_key))?.text()) ?? null;
  if (body === null) return { kind: 'retry', errorCode: 'text_unavailable' };

  const headers = JSON.parse(stored.headers_json) as { name: string; value: string }[];
  const froms = headers.filter((h) => h.name.toLowerCase() === 'from');
  const sender = froms.length === 1 ? parseSingleMailbox(froms[0]?.value ?? null) : null;
  const { rules, versions } = await loadRules(db);
  const decision: RuleDecision = decide(rules, {
    sender: sender ? `${sender.local}@${sender.domain}` : null,
    subject: stored.subject,
    body,
    serviceIds: [],
    reportKind: null,
    authentication: 'unknown',
  });
  const confirmedCritical = decision.importance === 'critical';
  const importance = decision.importance ?? 'routine';
  const materialDigest = await sha256Hex(canonicalJson({ body: stored.text_hash, importance, due: decision.dueText }));
  const update = {
    importance,
    materialDigest,
    confirmedCritical,
    criticalBasis: confirmedCritical ? `rule:${decision.winningRuleId}` : null,
    signalId: `source:${source.id}`,
    dueText: decision.dueText,
    serviceId: decision.serviceIds[0] ?? null,
  };

  const guard = { sql: CLAIM_GUARD, params: guardParams(job) };
  const targetId = await threadConcern(db, source.id);
  let before: ConcernRow | null = null;
  let after: ConcernRow;
  let kind: string;
  let sourceIds: string[];
  if (targetId) {
    before = await db.prepare(`SELECT ${CONCERN_COLUMNS.join(', ')} FROM concerns WHERE id = ?`).bind(targetId).first<ConcernRow>();
  }
  if (before) {
    const applied = applyEvidence(before, update, now);
    after = { ...applied.next, revision: before.revision + 1 };
    kind = applied.material ? 'evidence_added' : 'evidence_linked';
    sourceIds = [...(await concernSourceIds(db, before.id)), source.id];
  } else {
    after = {
      id: await deterministicUuid(`concern:${source.id}`),
      revision: 1,
      title: stored.subject.trim().slice(0, 200) || '(no subject)',
      status: 'open',
      importance,
      critical_basis: update.criticalBasis,
      critical_signal_id: confirmedCritical ? update.signalId : null,
      material_digest: materialDigest,
      due_revision: 0,
      waiting_note: null,
      awaited_party: null,
      follow_up_at: null,
      read_at: null,
      acknowledged_at: null,
      resolved_at: null,
      snooze_until: null,
      due_at: null,
      due_zone: null,
      due_source_text: decision.dueText,
      service_id: update.serviceId,
      updated_at: now,
    };
    kind = 'created';
    sourceIds = [source.id];
  }

  const result = { status: 'awaiting_inference', concernId: after.id, rules: decision };
  return {
    kind: 'complete',
    output: { concernId: after.id, event: kind },
    statements: [
      ...writeConcern(db, {
        before,
        after,
        kind,
        eventId: await deterministicUuid(`concern-event:${source.id}:${after.id}:${after.revision}`),
        sourceIds,
        now,
        meaningful: false,
        eventDetail: { sourceId: source.id, ruleId: decision.winningRuleId },
        guard,
      }),
      db.prepare(`INSERT OR IGNORE INTO triage_results(id, source_id, source_revision, policy_revision, provenance_json, result_json, created_at) SELECT ?, ?, ?, 0, ?, ?, ? WHERE ${CLAIM_GUARD}`)
        .bind(
          await deterministicUuid(`triage:${source.id}:${source.revision}:${TRIAGE_ENGINE}`),
          source.id,
          source.revision,
          JSON.stringify({ engine: TRIAGE_ENGINE, rules: versions }),
          JSON.stringify(result),
          now,
          ...guardParams(job),
        ),
      // Model triage follows deterministic triage; routing decides whether it may run and where.
      ...inferJobStatements(db, source.id, now, guard),
    ],
  };
}
