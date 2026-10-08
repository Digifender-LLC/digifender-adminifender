import type { ChatAnswer } from './answer';
import type { Evidence } from './retrieval';

/**
 * What a stored chat answer rests on (lld.md#chat). The answer is derived from every excerpt the model was given, not
 * only the ones it cited, so privacy has to be judged against all of those emails and against the privacy each had
 * when the answer was made. A source IDs list alone cannot say whether a policy has become stricter since.
 */
export interface ContextSource {
  sourceId: string;
  /** The privacy the email had when the model was given it. Only model-eligible values are ever recorded. */
  privacy: 'hybrid' | 'local_only';
}

export interface CitedRef {
  chunkId: string;
  sourceId: string;
  attachmentId: string | null;
  page: number | null;
}

/** Higher is stricter. A value this build does not know ranks strictest, so a new policy is never read as looser. */
const RANK: Record<string, number> = { hybrid: 0, local_only: 1, no_ai: 2, restricted_metadata: 2 };
export const privacyRank = (privacy: string): number => RANK[privacy] ?? 3;

const isEligiblePrivacy = (privacy: string): privacy is ContextSource['privacy'] => privacy === 'hybrid' || privacy === 'local_only';

/** The distinct emails in [evidence], with the privacy each had. Evidence from retrieval is always model-eligible. */
export function contextOf(evidence: Evidence[]): ContextSource[] {
  const seen = new Map<string, ContextSource>();
  for (const e of evidence) {
    if (!isEligiblePrivacy(e.privacy)) throw new Error('context_privacy');
    if (!seen.has(e.sourceId)) seen.set(e.sourceId, { sourceId: e.sourceId, privacy: e.privacy });
  }
  return [...seen.values()];
}

export interface StoredCitations {
  cited: CitedRef[];
  /**
   * Null means the provenance is unknown: the row predates it (`context` was a bare list of source IDs) or is
   * unreadable. An empty array is a known empty context, which is exactly what a no-evidence answer has.
   */
  context: ContextSource[] | null;
}

export function parseStoredCitations(json: string | null): StoredCitations {
  if (!json) return { cited: [], context: null };
  try {
    const value = JSON.parse(json) as { cited?: unknown; context?: unknown };
    const cited = Array.isArray(value.cited) ? (value.cited as CitedRef[]) : [];
    const raw = value.context;
    const context =
      Array.isArray(raw) && raw.every((c) => typeof c === 'object' && c !== null && typeof (c as ContextSource).sourceId === 'string' && isEligiblePrivacy(String((c as ContextSource).privacy)))
        ? (raw as ContextSource[])
        : null;
    return { cited, context };
  } catch {
    return { cited: [], context: null };
  }
}

export interface RegistryState {
  privacy: string;
  state: string;
  admission: string;
}

/** Current control state of these sources; one that is missing from the result no longer exists. */
export async function registryStates(db: D1Database, ids: string[]): Promise<Map<string, RegistryState>> {
  const out = new Map<string, RegistryState>();
  const unique = [...new Set(ids)];
  for (let i = 0; i < unique.length; i += 90) {
    const slice = unique.slice(i, i + 90);
    const { results } = await db
      .prepare(`SELECT id, privacy, state, admission FROM source_registry WHERE id IN (${slice.map(() => '?').join(',')})`)
      .bind(...slice)
      .all<{ id: string; privacy: string; state: string; admission: string }>();
    for (const r of results) out.set(r.id, { privacy: r.privacy, state: r.state, admission: r.admission });
  }
  return out;
}

/**
 * Every email the answer rests on is still ready, admitted, model-eligible and no stricter than when it was used.
 * Any single failure fails the whole answer: it is derived text from all of them.
 */
export function contextHolds(context: readonly ContextSource[], current: ReadonlyMap<string, RegistryState>): boolean {
  return context.every((c) => {
    const now = current.get(c.sourceId);
    return now !== undefined && now.state === 'ready' && now.admission === 'normal' && isEligiblePrivacy(now.privacy) && privacyRank(now.privacy) <= privacyRank(c.privacy);
  });
}

/** Cited chunks and proposal targets must all belong to emails the model was given; a stored row that says otherwise is not shown. */
export function answerFitsContext(answer: ChatAnswer, cited: readonly CitedRef[], context: readonly ContextSource[]): boolean {
  const ids = new Set(context.map((c) => c.sourceId));
  return cited.every((r) => ids.has(r.sourceId)) && answer.proposals.every((p) => ids.has(p.targetId));
}
