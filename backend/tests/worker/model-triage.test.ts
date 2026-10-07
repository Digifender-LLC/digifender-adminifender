import { env } from 'cloudflare:workers';
import { beforeEach, describe, expect, it } from 'vitest';
import { applyModelTriageJob } from '../../src/triage/apply-model';
import { JOB_HANDLERS } from '../../src/jobs/handlers';
import { LEASE_MS, runJob } from '../../src/jobs/runner';
import { DEFAULT_INFERENCE, loadSettings } from '../../src/inference/settings';
import { T0, api, enrollOwner, ingest, quietEnv, runPending } from './support';

const db = () => env.CONTROL_DB;
let token: string;
const key = () => crypto.randomUUID();
const BODY = 'Synthetic hosting invoice is due on 2026-10-13 at 09:00 UTC.';
const output = (sourceId: string, overrides: Record<string, unknown> = {}) => ({
  summary: 'Synthetic hosting payment is due.', category: 'billing', importance: 'critical', uncertainty: [],
  deadline: { text: '2026-10-13 at 09:00 UTC', instant: '2026-10-13T09:00:00Z', zone: 'UTC', ambiguous: false },
  serviceIds: [], evidence: [{ chunkId: `${sourceId}:body-v1:0`, start: 0, end: BODY.length }], suggestedActions: [], ...overrides,
});
async function prepared(overrides: Record<string, unknown> = {}, options: { beforeInference?: (id: string, concernId: string) => Promise<void>; headers?: string[]; beforeModelReturns?: (id: string) => Promise<void> } = {}) {
  const sourceId = await ingest(['From: billing@vendor.example', 'To: inbox@adminifender.example', 'Subject: Synthetic invoice', ...(options.headers ?? []), 'Content-Type: text/plain', '', BODY].join('\r\n'));
  await runPending('triage');
  const concernId = (await db().prepare('SELECT concern_id FROM concern_sources WHERE source_id = ?').bind(sourceId).first<{ concern_id: string }>())!.concern_id;
  await options.beforeInference?.(sourceId, concernId);
  const ai = { run: async () => {
    await options.beforeModelReturns?.(sourceId);
    return { response: JSON.stringify(output(sourceId, overrides)), usage: { prompt_tokens: 100, completion_tokens: 100 } };
  } } as unknown as Ai;
  expect(await runPending('infer', T0, { AI: ai })).toEqual(['complete']);
  return { sourceId, concernId };
}
const concern = async (id: string) => (await api('GET', `/concerns/${id}`, { token })).body.data;
async function modelResult(sourceId: string) {
  const row = await db().prepare("SELECT result_json FROM triage_results WHERE source_id = ? AND json_extract(provenance_json, '$.engine') = 'inference'").bind(sourceId).first<{ result_json: string }>();
  return JSON.parse(row!.result_json);
}
async function projectionJob() {
  return (await db().prepare("SELECT id FROM jobs WHERE kind = 'apply_model_triage'").first<{ id: string }>())!.id;
}
async function service(name: string) {
  const id = crypto.randomUUID();
  await db().prepare("INSERT INTO service_catalog(id, revision, name, aliases_json, targets_json, created_at) VALUES (?, 1, ?, '[]', '[]', ?)").bind(id, name, T0).run();
  return id;
}
async function rule(effects: object[], origin = 'owner') {
  const id = crypto.randomUUID();
  await db().prepare('INSERT INTO rules(id, revision, origin, enabled, predicate_json, effect_json, created_at) VALUES (?, 1, ?, 1, ?, ?, ?)')
    .bind(id, origin, JSON.stringify({ sender_equals: 'billing@vendor.example' }), JSON.stringify(effects), T0).run();
  return id;
}
beforeEach(async () => {
  token = (await enrollOwner()).token;
  const res = await api('PATCH', '/settings', { token, key: key(), body: { expectedRevision: 0, inference: { ...DEFAULT_INFERENCE, cloud: { ...DEFAULT_INFERENCE.cloud, modelId: '@cf/test/synthetic', account: 'dedicated', includedNeuronsPerDay: 10000, neuronsPerMillionInputTokens: 10000, neuronsPerMillionOutputTokens: 20000 } } } });
  expect(res.status).toBe(200);
});

describe('applying model triage', () => {
  it('atomically queues projection, exposes inference on concern detail, and applies urgent/service/due once without a critical notification', async () => {
    const serviceId = await service('Synthetic hosting');
    const { sourceId, concernId } = await prepared({ serviceIds: [serviceId] });
    const before = await api('GET', `/concerns/${concernId}`, { token });
    expect(before.status).toBe(200);
    expect(before.body.data.triage).toContainEqual(expect.objectContaining({ sourceId, output: expect.objectContaining({ importance: 'urgent' }), provenance: expect.objectContaining({ route: 'cloud' }) }));
    expect(await runPending('apply_model_triage')).toEqual(['complete']);
    const view = await concern(concernId);
    expect(view.concern).toMatchObject({ revision: 2, title: 'Synthetic invoice', importance: 'urgent', serviceId, due: { at: '2026-10-13T09:00:00.000Z', zone: 'UTC', sourceText: '2026-10-13 at 09:00 UTC' } });
    expect(view.events.filter((e: any) => e.kind === 'model_triaged')).toHaveLength(1);
    expect((await modelResult(sourceId)).application).toMatchObject({ status: 'applied', concernId, concernRevision: 2 });
    expect((await db().prepare('SELECT kind FROM notification_events').all()).results).toEqual([{ kind: 'due' }]);
    expect((await runJob(quietEnv(), JOB_HANDLERS, await projectionJob(), T0)).status).toBe('skipped');
    expect((await concern(concernId)).concern.revision).toBe(2);
    expect((await db().prepare('SELECT COUNT(*) AS n FROM notification_events').first<{ n: number }>())!.n).toBe(1);
  });

  it('uses the same durable projection for a locally computed result, including duplicate submission recovery', async () => {
    const ready = { foreground: false, charging: true, batteryPercent: 80, thermal: 'none', modelId: 'synthetic-local', modelManifestHash: 'a'.repeat(64) };
    expect((await api('POST', '/device-work/readiness', { token, key: key(), body: ready })).status).toBe(200);
    const { sourceId, concernId } = await prepared();
    expect(await runPending('apply_model_triage')).toEqual([]);
    const { work } = (await api('POST', '/device-work/claim', { token, key: key(), body: ready })).body.data;
    expect(work.task).toBe('triage');
    const body = { leaseToken: work.leaseToken, inputDigest: work.inputDigest, policyRevision: work.policyRevision, outputJson: JSON.stringify(output(sourceId)), modelManifestHash: ready.modelManifestHash };
    expect((await api('POST', `/device-work/${work.attemptId}/result`, { token, key: key(), body })).body.data.state).toBe('succeeded');
    expect(await runPending('apply_model_triage')).toEqual(['complete']);
    expect((await concern(concernId)).concern).toMatchObject({ revision: 2, importance: 'urgent' });
    expect((await concern(concernId)).triage).toContainEqual(expect.objectContaining({ sourceId, provenance: expect.objectContaining({ route: 'local', modelId: 'synthetic-local' }), application: expect.objectContaining({ status: 'applied' }) }));
    expect((await api('POST', `/device-work/${work.attemptId}/result`, { token, key: key(), body })).body.error?.code).toBe('lease_lost');
    expect(await runPending('apply_model_triage')).toEqual([]);
    expect((await concern(concernId)).events.filter((event: any) => event.kind === 'model_triaged')).toHaveLength(1);
    expect((await db().prepare('SELECT kind FROM notification_events').all()).results).toEqual([{ kind: 'due' }]);
  });

  it.each(['owner', 'learned'])('preserves the captured %s rule decision even if the rule is edited after routing', async (origin) => {
    const assigned = await service('Rule service');
    const suggested = await service('Model service');
    const ruleId = await rule([{ less_important: true }, { assign_service: assigned }, { suggest_due: { text: 'Owner rule wording' } }], origin);
    const { concernId } = await prepared({ serviceIds: [suggested] }, { beforeInference: async () => {
      await db().prepare("UPDATE rules SET revision = 2, effect_json = '[{\"critical\":true}]' WHERE id = ?").bind(ruleId).run();
    } });
    await runPending('apply_model_triage');
    expect((await concern(concernId)).concern).toMatchObject({ importance: 'routine', serviceId: assigned, due: { at: null, sourceText: 'Owner rule wording' } });
    expect((await db().prepare('SELECT COUNT(*) AS n FROM notification_events').first<{ n: number }>())!.n).toBe(0);
  });

  it('preserves an existing confirmed critical signal without duplicating its notification', async () => {
    await rule([{ critical: true }]);
    const { concernId } = await prepared({ importance: 'routine', deadline: null });
    const before = (await concern(concernId)).concern;
    expect(before.importance).toBe('critical');
    expect((await db().prepare("SELECT COUNT(*) AS n FROM notification_events WHERE kind = 'critical'").first<{ n: number }>())!.n).toBe(1);
    await runPending('apply_model_triage');
    expect((await concern(concernId)).concern).toMatchObject({ importance: 'critical' });
    expect((await db().prepare("SELECT COUNT(*) AS n FROM notification_events WHERE kind = 'critical'").first<{ n: number }>())!.n).toBe(1);
  });

  it('does not apply a newly created rule retroactively', async () => {
    const { concernId } = await prepared({}, { beforeInference: async () => { await rule([{ critical: true }]); } });
    await runPending('apply_model_triage');
    expect((await concern(concernId)).concern.importance).toBe('urgent');
    expect((await db().prepare("SELECT COUNT(*) AS n FROM notification_events WHERE kind = 'critical'").first<{ n: number }>())!.n).toBe(0);
  });

  it.each([
    { text: 'October 13', instant: '2026-10-13T09:00:00Z', zone: 'UTC', ambiguous: false },
    { text: '2026-10-13', instant: '2026-10-13T09:00:00Z', ambiguous: false },
    { text: '2026-10-13', instant: '2026-10-13T09:00:00Z', zone: 'UT', ambiguous: false },
    { text: '2026-10-13', instant: '2026-10-13T09:00:00Z', zone: 'UTC', ambiguous: true },
  ])('retains an uncertain deadline as wording only: %j', async (deadline) => {
    const { sourceId, concernId } = await prepared({ deadline });
    await runPending('apply_model_triage');
    expect((await concern(concernId)).concern.due).toMatchObject({ at: null, sourceText: deadline.text });
    expect((await modelResult(sourceId)).application.deadline).toBe('proposed');
    expect((await db().prepare('SELECT COUNT(*) AS n FROM notification_events').first<{ n: number }>())!.n).toBe(0);
  });

  it.each(['resolve', 'set_due', 'wait'])('keeps intervening owner %s authoritative and makes the skip inspectable', async (kind) => {
    const { sourceId, concernId } = await prepared();
    const args = kind === 'set_due' ? { at: '2027-01-01T09:00:00Z', zone: 'UTC' } : kind === 'wait' ? { awaitedParty: 'Owner choice' } : {};
    expect((await api('POST', `/concerns/${concernId}/actions`, { token, key: key(), body: { expectedRevision: 1, kind, args } })).status).toBe(200);
    const before = (await concern(concernId)).concern;
    await runPending('apply_model_triage');
    expect((await concern(concernId)).concern).toEqual(before);
    expect((await modelResult(sourceId)).application).toMatchObject({ status: 'skipped', reason: 'concern_changed' });
  });

  it('preserves a later read timestamp while applying a result and does not count model work as owner review', async () => {
    const { concernId } = await prepared();
    await api('POST', `/concerns/${concernId}/actions`, { token, key: key(), now: T0 + 100, body: { expectedRevision: 1, kind: 'mark_read' } });
    await runPending('apply_model_triage', T0 + 101);
    expect((await concern(concernId)).concern).toMatchObject({ revision: 3, readAt: new Date(T0 + 100).toISOString(), importance: 'urgent' });
    expect(await db().prepare("SELECT last_meaningful_at FROM review_state WHERE id = 'owner'").first()).toBeNull();
  });

  it.each(['revision', 'privacy', 'restricted', 'cloud_local_only', 'admission', 'deletion', 'policy'])('discards projection after the %s fence changes', async (fence) => {
    const { sourceId, concernId } = await prepared();
    if (fence === 'policy') {
      const settings = await loadSettings(db());
      await api('PATCH', '/settings', { token, key: key(), body: { expectedRevision: settings.revision, inference: { ...settings.inference, mode: 'no_ai' } } });
    } else {
      const sql = { revision: 'revision = revision + 1', privacy: "privacy = 'no_ai'", restricted: "privacy = 'restricted_metadata'", cloud_local_only: "privacy = 'local_only'", admission: "admission = 'quarantine'", deletion: "state = 'deleting'" }[fence as 'revision' | 'privacy' | 'restricted' | 'cloud_local_only' | 'admission' | 'deletion'];
      await db().prepare(`UPDATE source_registry SET ${sql} WHERE id = ?`).bind(sourceId).run();
    }
    await runPending('apply_model_triage');
    expect((await db().prepare('SELECT revision, importance FROM concerns WHERE id = ?').bind(concernId).first())).toEqual({ revision: 1, importance: 'routine' });
    expect((await modelResult(sourceId)).application.status).toBe('skipped');
    expect((await db().prepare('SELECT COUNT(*) AS n FROM notification_events').first<{ n: number }>())!.n).toBe(0);
  });

  it('never queues projection for an inference result discarded during provider I/O', async () => {
    const { sourceId, concernId } = await prepared({}, { beforeModelReturns: async (id) => {
      await db().prepare("UPDATE source_registry SET revision = revision + 1, privacy = 'no_ai' WHERE id = ?").bind(id).run();
    } });
    expect((await db().prepare("SELECT COUNT(*) AS n FROM triage_results WHERE source_id = ? AND json_extract(provenance_json, '$.engine') = 'inference'").bind(sourceId).first<{ n: number }>())!.n).toBe(0);
    expect(await runPending('apply_model_triage')).toEqual([]);
    expect((await concern(concernId)).concern.revision).toBe(1);
  });

  it('rechecks a privacy change between projection reads and the final transaction', async () => {
    const { sourceId, concernId } = await prepared();
    const id = await projectionJob();
    const raced = { ...JOB_HANDLERS, apply_model_triage: async (...args: Parameters<typeof applyModelTriageJob>) => {
      const result = await applyModelTriageJob(...args);
      await db().prepare("UPDATE source_registry SET privacy = 'no_ai', revision = revision + 1 WHERE id = ?").bind(sourceId).run();
      return result;
    } };
    expect((await runJob(quietEnv(), raced, id, T0)).status).toBe('retry');
    expect((await runJob(quietEnv(), JOB_HANDLERS, id, T0 + 30000)).status).toBe('complete');
    expect((await db().prepare('SELECT revision FROM concerns WHERE id = ?').bind(concernId).first())).toEqual({ revision: 1 });
    expect((await modelResult(sourceId)).application.status).toBe('skipped');
  });

  it('keeps an owner correction committed between projection reads and writes', async () => {
    const { sourceId, concernId } = await prepared();
    const id = await projectionJob();
    const raced = { ...JOB_HANDLERS, apply_model_triage: async (...args: Parameters<typeof applyModelTriageJob>) => {
      const result = await applyModelTriageJob(...args);
      expect((await api('POST', `/concerns/${concernId}/actions`, { token, key: key(), body: { expectedRevision: 1, kind: 'resolve' } })).status).toBe(200);
      return result;
    } };
    expect((await runJob(quietEnv(), raced, id, T0)).status).toBe('retry');
    expect((await runJob(quietEnv(), JOB_HANDLERS, id, T0 + 30000)).status).toBe('complete');
    expect((await concern(concernId)).concern).toMatchObject({ revision: 2, status: 'resolved', importance: 'routine' });
    expect((await modelResult(sourceId)).application).toMatchObject({ status: 'skipped', reason: 'concern_changed' });
    expect((await db().prepare('SELECT COUNT(*) AS n FROM notification_events').first<{ n: number }>())!.n).toBe(0);
  });

  it('keeps an older interpretation from overwriting newer source evidence', async () => {
    const { sourceId, concernId } = await prepared({}, { headers: ['Message-ID: <parent@vendor.example>'] });
    await ingest(['From: billing@vendor.example', 'To: inbox@adminifender.example', 'Subject: Updated synthetic invoice', 'In-Reply-To: <parent@vendor.example>', 'Content-Type: text/plain', '', 'Synthetic invoice cancelled.'].join('\r\n'));
    await runPending('triage');
    const before = (await concern(concernId)).concern;
    expect(before.sourceIds).toHaveLength(2);
    await runPending('apply_model_triage');
    expect((await concern(concernId)).concern).toEqual(before);
    expect((await modelResult(sourceId)).application).toMatchObject({ status: 'skipped', reason: 'concern_changed' });
  });

  it('does not turn a later identical source body into new material merely because a model interpreted it', async () => {
    const { concernId } = await prepared({}, { headers: ['Message-ID: <repeat-parent@vendor.example>'] });
    await runPending('apply_model_triage');
    await api('POST', `/concerns/${concernId}/actions`, { token, key: key(), body: { expectedRevision: 2, kind: 'resolve' } });
    await ingest(['From: billing@vendor.example', 'To: inbox@adminifender.example', 'Subject: Repeated synthetic invoice', 'In-Reply-To: <repeat-parent@vendor.example>', 'Content-Type: text/plain', '', BODY].join('\r\n'));
    await runPending('triage');
    const view = await concern(concernId);
    expect(view.concern.status).toBe('resolved');
    expect(view.events[0].kind).toBe('evidence_linked');
  });

  it('applies historical rules receipts using the source-linked event revision', async () => {
    const { sourceId, concernId } = await prepared();
    await db().prepare("UPDATE triage_results SET result_json = json_remove(result_json, '$.concernRevision') WHERE source_id = ? AND json_extract(provenance_json, '$.engine') = 'rules-v1'").bind(sourceId).run();
    await runPending('apply_model_triage');
    expect((await concern(concernId)).concern.importance).toBe('urgent');
  });

  it.each([
    { deadline: { text: '2027-10-13 at 09:00 UTC', instant: '2027-10-13T09:00:00Z', zone: 'UTC', ambiguous: false } },
    { deadline: { text: '2026-10-13 at 09:00 Europe/London', instant: '2026-10-13T08:00:00Z', zone: 'Europe/London', ambiguous: false } },
    { uncertainty: ['Timezone is unclear'] },
  ])('keeps a model-filled gap as a proposal: %j', async (overrides) => {
    const { sourceId, concernId } = await prepared(overrides);
    await runPending('apply_model_triage');
    expect((await concern(concernId)).concern.due.at).toBeNull();
    expect((await modelResult(sourceId)).application.deadline).toBe('proposed');
  });

  it.each([
    { text: '2026-11-13 at 09:00 UTC', instant: '2026-11-13T09:00:00Z' },
    { text: '2026-10-14 at 09:00 UTC', instant: '2026-10-14T09:00:00Z' },
    { text: '2026-10-13 at 10:00 UTC', instant: '2026-10-13T10:00:00Z' },
    { text: '2026-10-13 at 09:00 UTC', instant: '2026-10-14T09:00:00Z' },
    { text: '2026 UTC', instant: '2026-10-13T09:00:00Z' },
    { text: '2026-10-13 at 09:00 UTC+01:00', instant: '2026-10-13T09:00:00Z' },
  ])('never promotes invented date parts or a mismatched instant: %j', async (date) => {
    const { sourceId, concernId } = await prepared({ deadline: { ...date, zone: 'UTC', ambiguous: false } });
    await runPending('apply_model_triage');
    expect((await concern(concernId)).concern.due.at).toBeNull();
    expect((await modelResult(sourceId)).application.deadline).toBe('proposed');
    expect((await db().prepare('SELECT COUNT(*) AS n FROM notification_events').first<{ n: number }>())!.n).toBe(0);
  });

  it('refuses an ungrounded result and a service deleted before projection', async () => {
    const missingEvidence = await prepared({ evidence: [] });
    await runPending('apply_model_triage');
    expect((await modelResult(missingEvidence.sourceId)).application).toMatchObject({ status: 'skipped', reason: 'evidence_missing' });
    const id = await service('Transient synthetic service');
    // Distinct MIME identity keeps this an independent source.
    const missingService = await prepared({ serviceIds: [id] }, { headers: ['Message-ID: <other@vendor.example>'] });
    await db().prepare('DELETE FROM service_catalog WHERE id = ?').bind(id).run();
    await runPending('apply_model_triage');
    expect((await modelResult(missingService.sourceId)).application).toMatchObject({ status: 'skipped', reason: 'output_no_longer_valid' });
    expect((await concern(missingService.concernId)).concern.revision).toBe(1);
  });

  it('cannot finish a partial projection transaction and can safely replay after a crash', async () => {
    const { sourceId, concernId } = await prepared();
    const id = await projectionJob();
    // A database error after the concern statements rolls back job settlement, event, receipt and notification.
    const broken = { ...JOB_HANDLERS, apply_model_triage: async (...args: Parameters<typeof applyModelTriageJob>) => {
      const result = await applyModelTriageJob(...args);
      if (result.kind !== 'complete') throw new Error('expected projection');
      return { ...result, statements: [...(result.statements ?? []), db().prepare('INSERT INTO batch_assertions(ok) VALUES (NULL)')] };
    } };
    expect((await runJob(quietEnv(), broken, id, T0)).status).toBe('retry');
    expect((await modelResult(sourceId)).application).toBeUndefined();
    expect((await concern(concernId)).concern.revision).toBe(1);
    expect((await db().prepare('SELECT COUNT(*) AS n FROM notification_events').first<{ n: number }>())!.n).toBe(0);
    expect((await runJob(quietEnv(), JOB_HANDLERS, id, T0 + 30000)).status).toBe('complete');
    expect((await concern(concernId)).events.filter((e: any) => e.kind === 'model_triaged')).toHaveLength(1);
    expect((await db().prepare('SELECT COUNT(*) AS n FROM notification_events').first<{ n: number }>())!.n).toBe(1);
  });

  it('rejects an expired worker after another lease commits without duplicating a concern event', async () => {
    const { concernId } = await prepared();
    const id = await projectionJob();
    const raced = { ...JOB_HANDLERS, apply_model_triage: async (...args: Parameters<typeof applyModelTriageJob>) => {
      const result = await applyModelTriageJob(...args);
      expect((await runJob(quietEnv(), JOB_HANDLERS, id, T0 + LEASE_MS + 1)).status).toBe('complete');
      return result;
    } };
    expect((await runJob(quietEnv(), raced, id, T0)).status).toBe('stale');
    expect((await concern(concernId)).events.filter((e: any) => e.kind === 'model_triaged')).toHaveLength(1);
  });
});
