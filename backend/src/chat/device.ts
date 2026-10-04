import { HttpError } from '../http/errors';
import type { AttemptRow, DeviceTask } from '../inference/device';
import { answerChecker, chatDigest, completeTurnStatement, evidenceStillEligible } from './job';
import { loadEvidence } from './retrieval';

interface TurnRef {
  id: string;
  request_hash: string;
  state: string;
}

async function turnFor(db: D1Database, row: AttemptRow): Promise<TurnRef | null> {
  return db
    .prepare("SELECT t.id, t.request_hash, t.state FROM chat_turns t JOIN jobs j ON j.id = ? AND t.id = json_extract(j.input_json, '$.turnId')")
    .bind(row.job_id)
    .first<TurnRef>();
}

const chunkIds = (row: AttemptRow) => (row.chunk_ids_json ? (JSON.parse(row.chunk_ids_json) as string[]) : []);

/** Chat on the phone: the same excerpts, rechecked under current policy, and the same answer validation. */
export const chatDeviceTask: DeviceTask = {
  async verify(env, row) {
    const turn = await turnFor(env.CONTROL_DB, row);
    if (!turn || turn.state !== 'pending') return null;
    const ids = chunkIds(row);
    const evidence = await loadEvidence(env, ids);
    if (evidence.length !== ids.length) return null;
    return { chunkIds: ids, digest: await chatDigest(turn.id, turn.request_hash, evidence), extra: { turnId: turn.id } };
  },
  async accept(env, row, outputJson, policyRevision, now) {
    const turn = await turnFor(env.CONTROL_DB, row);
    const ids = chunkIds(row);
    const evidence = await loadEvidence(env, ids);
    if (!turn || evidence.length !== ids.length) throw new HttpError(409, 'input_changed', 'The cited email changed after this task was assigned.');
    const checked = answerChecker(evidence)(outputJson);
    if (!checked.ok) return checked;
    return {
      ok: true,
      store: [completeTurnStatement(env.CONTROL_DB, turn.id, 'pending', checked.value, evidence, { policyRevision, reason: 'local' }, now, evidenceStillEligible(evidence, policyRevision))],
    };
  },
  async failed(db, row, now) {
    const turn = await turnFor(db, row);
    return turn ? [db.prepare("UPDATE chat_turns SET state = 'failed', error_code = 'invalid_output', completed_at = ? WHERE id = ? AND state = 'pending'").bind(now, turn.id)] : [];
  },
};
