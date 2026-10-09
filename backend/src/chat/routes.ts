import { authenticate } from '../auth/sessions';
import { assertion, isAssertionFailure } from '../db/assert';
import type { Env } from '../env';
import { canonicalJson } from '../http/idempotency';
import { HttpError, notFound } from '../http/errors';
import { WithStatus } from '../http/status';
import { v } from '../http/validate';
import { sha256Hex } from '../ids';
import { visibleSource } from '../sources/registry';
import type { ChatAnswer } from './answer';
import { CHAT_TASK } from './job';
import { type CitedRef, type ContextSource, type RegistryState, answerFitsContext, contextHolds, parseStoredCitations, registryStates } from './provenance';
import { type Evidence, type Scope, loadEvidence } from './retrieval';

export const MAX_QUESTION_CHARS = 4000;
const TURNS_PER_PAGE = 50;

const scopeShape = (value: unknown, path: string): Scope => {
  const scope = v.object({ kind: v.oneOf(['all', 'concern', 'source', 'service'] as const), id: v.optional(v.uuid()) })(value, path);
  if ((scope.kind === 'all') !== (scope.id === undefined)) throw new HttpError(400, 'invalid_request', 'The request is invalid.', { path: `${path}.id`, reason: scope.kind === 'all' ? 'unexpected' : 'required' });
  return scope.id === undefined ? { kind: scope.kind } : { kind: scope.kind, id: scope.id };
};

export const threadBody = v.object({ scope: scopeShape });

// POST /chat/threads — the scope is fixed for the thread and shown before any question is sent.
export async function createThread(env: Env, now: number, body: ReturnType<typeof threadBody>) {
  const db = env.CONTROL_DB;
  const { scope } = body;
  if (scope.kind === 'source') {
    const source = await visibleSource(env, scope.id!);
    if (source.admission !== 'normal') throw new HttpError(422, 'scope_unavailable', 'Quarantined email is not available to chat.');
  } else if (scope.kind === 'concern') {
    if (!(await db.prepare('SELECT 1 AS hit FROM concerns WHERE id = ?').bind(scope.id).first())) throw notFound();
  } else if (scope.kind === 'service') {
    if (!(await db.prepare('SELECT 1 AS hit FROM service_catalog WHERE id = ?').bind(scope.id).first())) throw notFound();
  }
  const id = crypto.randomUUID();
  await db.prepare('INSERT INTO chat_threads(id, scope_json, created_at) VALUES (?, ?, ?)').bind(id, JSON.stringify(scope), now).run();
  return { id, scope, createdAt: new Date(now).toISOString() };
}

export const turnBody = v.object({ turnId: v.uuid(), content: v.string({ max: MAX_QUESTION_CHARS }) });

interface TurnRow {
  id: string;
  thread_id: string;
  request_hash: string;
  content: string;
  state: string;
  reason: string | null;
  error_code: string | null;
  answer: string | null;
  output_json: string | null;
  citations_json: string | null;
  created_at: number;
  completed_at: number | null;
  invalidated_at: number | null;
}

/**
 * POST /chat/threads/{id}/turns — the client turn ID is the request identity (FR-027). The turn, its job and
 * outbox commit together before anything is dispatched. Resending the same ID and content returns the
 * existing turn; different content under that ID conflicts.
 */
export async function postTurn(env: Env, now: number, body: ReturnType<typeof turnBody>, threadId: string) {
  const db = env.CONTROL_DB;
  if (!(await db.prepare('SELECT 1 AS hit FROM chat_threads WHERE id = ?').bind(threadId).first())) throw notFound();
  const requestHash = await sha256Hex(canonicalJson({ threadId, content: body.content }));
  const existing = async () => {
    const row = await db.prepare('SELECT request_hash, state FROM chat_turns WHERE id = ?').bind(body.turnId).first<{ request_hash: string; state: string }>();
    if (row && row.request_hash !== requestHash) throw new HttpError(409, 'idempotency_conflict', 'This turn ID was used for a different question.');
    return row;
  };
  const found = await existing();
  if (found) return new WithStatus(202, { turnId: body.turnId, state: found.state });

  const jobId = crypto.randomUUID();
  try {
    await db.batch([
      db.prepare("INSERT INTO chat_turns(id, thread_id, request_hash, content, state, job_id, created_at) VALUES (?, ?, ?, ?, 'pending', ?, ?)").bind(body.turnId, threadId, requestHash, body.content, jobId, now),
      db.prepare("INSERT INTO jobs(id, dedupe_key, kind, source_id, input_revision, state, next_at, input_json, created_at) VALUES (?, ?, ?, NULL, NULL, 'pending', ?, ?, ?)")
        .bind(jobId, `${CHAT_TASK}:${body.turnId}`, CHAT_TASK, now, JSON.stringify({ turnId: body.turnId }), now),
      db.prepare("INSERT INTO outbox(id, dedupe_key, kind, reference_id, state, next_at) VALUES (?, ?, 'job', ?, 'pending', ?)").bind(crypto.randomUUID(), `job:${jobId}`, jobId, now),
    ]);
  } catch (error) {
    // A concurrent request with the same turn ID won; report its turn.
    if (!(error instanceof Error && /UNIQUE constraint failed/.test(error.message))) throw error;
    const raced = await existing();
    if (!raced) throw error;
    return new WithStatus(202, { turnId: body.turnId, state: raced.state });
  }
  return new WithStatus(202, { turnId: body.turnId, state: 'pending' });
}

/** A stored turn with what its answer rests on, read once. */
interface Parsed {
  row: TurnRow;
  cited: CitedRef[];
  context: ContextSource[] | null;
}

/**
 * A turn as the owner sees it. The answer is shown only while everything it rests on still holds, judged now:
 * every cited passage is readable, and every email the model was given (not only the cited ones) is still ready,
 * admitted, model-eligible and no stricter than when it was used. Otherwise it is withheld rather than shown without
 * its evidence. A row whose provenance is unknown (stored before it was recorded) fails closed the same way; a
 * no-evidence answer has a known empty context and is unaffected. A deletion removes the stored answer.
 * `context` is present exactly when an answer is: the emails the phone must be able to check it against.
 * [current] and [live] were read for the whole page at once; each turn is still judged only against its own
 * context and its own cited passages.
 */
function turnView(parsed: Parsed, scope: Scope, current: ReadonlyMap<string, RegistryState>, live: ReadonlyMap<string, Evidence>) {
  const { row, cited: refs, context } = parsed;
  const output = row.output_json ? (JSON.parse(row.output_json) as ChatAnswer) : null;
  const intact = output !== null && context !== null && answerFitsContext(output, refs, context) && contextHolds(context, current) && refs.every((r) => live.has(r.chunkId));
  const show = output !== null && row.invalidated_at === null && intact;
  return {
    turnId: row.id,
    threadId: row.thread_id,
    scope,
    content: row.content,
    state: row.state,
    reason: row.reason,
    errorCode: row.error_code,
    answer: show ? output.answer : null,
    claims: show
      ? output.claims.map((c) => ({
          text: c.text,
          basis: c.basis,
          citations: c.citations.map((x) => {
            const ref = refs.find((r) => r.chunkId === x.chunkId);
            const evidence = live.get(x.chunkId);
            return { chunkId: x.chunkId, sourceId: ref?.sourceId ?? null, attachmentId: ref?.attachmentId ?? null, page: ref?.page ?? null, start: x.start, end: x.end, excerpt: evidence ? evidence.text.slice(x.start, x.end) : null };
          }),
        }))
      : [],
    gaps: show ? output.gaps : [],
    // Proposals have no dispatch authority; acting on one needs its own exact review.
    proposals: show ? output.proposals : [],
    context: show ? context : null,
    invalidated: row.invalidated_at !== null,
    withheld: output !== null && row.invalidated_at === null && !intact,
    createdAt: new Date(row.created_at).toISOString(),
    completedAt: row.completed_at === null ? null : new Date(row.completed_at).toISOString(),
  };
}

/**
 * The turn views for these rows. The current state of every email any of them rests on is read once, and so are all
 * the cited passages, so a page costs a bounded number of reads (each list is cut into slices of at most 90 values)
 * instead of several per turn. Turns with no answer, or whose answer was removed, need neither.
 */
async function turnViews(env: Env, rows: TurnRow[], scope: Scope) {
  const parsed: Parsed[] = rows.map((row) => ({ row, ...parseStoredCitations(row.citations_json) }));
  const answered = parsed.filter((p) => p.row.output_json !== null && p.row.invalidated_at === null);
  const current = await registryStates(env.CONTROL_DB, answered.flatMap((p) => p.context?.map((c) => c.sourceId) ?? []));
  const live = new Map((await loadEvidence(env, answered.flatMap((p) => p.cited.map((r) => r.chunkId)), { modelOnly: false })).map((e) => [e.chunkId, e]));
  return parsed.map((p) => turnView(p, scope, current, live));
}

const TURN_COLUMNS = 'id, thread_id, request_hash, content, state, reason, error_code, answer, output_json, citations_json, created_at, completed_at, invalidated_at';

// GET /chat/turns/{turnId} — durable state; recovering after a lost response never dispatches again.
export async function getTurn(request: Request, env: Env, now: number, params: string[]) {
  await authenticate(request, env, now);
  const row = await env.CONTROL_DB.prepare(`SELECT ${TURN_COLUMNS} FROM chat_turns WHERE id = ?`).bind(params[0]).first<TurnRow>();
  if (!row) throw notFound();
  const thread = await env.CONTROL_DB.prepare('SELECT scope_json FROM chat_threads WHERE id = ?').bind(row.thread_id).first<{ scope_json: string }>();
  return (await turnViews(env, [row], JSON.parse(thread!.scope_json) as Scope))[0]!;
}

// GET /chat/threads/{id}/turns — saved history, oldest first. `before` pages back from a turn ID in the Worker's own
// order (insertion order, not any clock). `nextBefore` is the ID to send next: the oldest turn of this page while earlier
// turns exist, otherwise null, so the phone never has to work out an anchor from its own record.
export async function listTurns(request: Request, env: Env, now: number, params: string[]) {
  await authenticate(request, env, now);
  const db = env.CONTROL_DB;
  const thread = await db.prepare('SELECT id, scope_json, created_at FROM chat_threads WHERE id = ?').bind(params[0]).first<{ id: string; scope_json: string; created_at: number }>();
  if (!thread) throw notFound();
  const before = new URL(request.url).searchParams.get('before');
  const anchor = before ? await db.prepare('SELECT rowid AS r FROM chat_turns WHERE id = ? AND thread_id = ?').bind(v.uuid()(before, '$.before'), thread.id).first<{ r: number }>() : null;
  if (before && !anchor) throw notFound();
  const { results } = await db
    .prepare(`SELECT ${TURN_COLUMNS} FROM chat_turns WHERE thread_id = ? AND rowid < ? ORDER BY rowid DESC LIMIT ?`)
    .bind(thread.id, anchor?.r ?? Number.MAX_SAFE_INTEGER, TURNS_PER_PAGE + 1)
    .all<TurnRow>();
  const page = results.slice(0, TURNS_PER_PAGE).reverse();
  const scope = JSON.parse(thread.scope_json) as Scope;
  const hasMore = results.length > TURNS_PER_PAGE;
  return {
    thread: { id: thread.id, scope, createdAt: new Date(thread.created_at).toISOString() },
    turns: await turnViews(env, page, scope),
    hasMore,
    nextBefore: hasMore ? page[0]!.id : null,
  };
}

export const cancelBody = v.object({});

/**
 * POST /chat/turns/{id}/cancel — stops undispatched work only. A turn already being answered cannot be
 * cancelled: the processing may still complete and incur its cost.
 */
export async function cancelTurn(env: Env, now: number, _body: ReturnType<typeof cancelBody>, turnId: string) {
  const db = env.CONTROL_DB;
  const row = await db.prepare('SELECT state, job_id FROM chat_turns WHERE id = ?').bind(turnId).first<{ state: string; job_id: string | null }>();
  if (!row) throw notFound();
  try {
    await db.batch([
      assertion(db, "EXISTS (SELECT 1 FROM chat_turns WHERE id = ? AND state = 'pending')", turnId),
      db.prepare("UPDATE chat_turns SET state = 'cancelled', reason = 'owner_cancelled', completed_at = ? WHERE id = ?").bind(now, turnId),
      db.prepare("UPDATE jobs SET state = 'cancelled', error_code = 'owner_cancelled' WHERE id = ? AND state = 'pending'").bind(row.job_id),
      // Phone work for the turn is withdrawn; a late device result then fails its lease check.
      db.prepare("UPDATE inference_attempts SET state = 'cancelled', error_code = 'owner_cancelled', completed_at = ? WHERE job_id = ? AND state IN ('queued', 'leased')").bind(now, row.job_id),
    ]);
  } catch (error) {
    if (!isAssertionFailure(error)) throw error;
    const current = await db.prepare('SELECT state FROM chat_turns WHERE id = ?').bind(turnId).first<{ state: string }>();
    if (current?.state === 'running') throw new HttpError(409, 'already_dispatched', 'This question is already being answered. Cancelling cannot undo processing that has started.');
    throw new HttpError(409, 'not_cancellable', 'This question is no longer waiting.', { state: current?.state ?? null });
  }
  return { turnId, state: 'cancelled' };
}
