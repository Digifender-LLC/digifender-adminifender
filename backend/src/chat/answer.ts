import { ACTION_KINDS, CHUNK_ID, isInt, isObject, isString, onlyKeys } from '../inference/triage-output';

/**
 * Validation of a model chat answer (lld.md#chat), mirroring contracts/schema/v1/chat-answer.schema.json plus
 * the domain checks: every citation names a chunk the model was given and lies inside it, and a proposal must
 * be a P1-supported kind aimed at a source in context. Errors are paths and reasons only.
 */

export const BASES = ['evidence', 'report', 'inference', 'hypothesis'] as const;
/** P1 proposals: only a reply to a source in context. Other kinds need their P2/P3 adapters. */
export const P1_PROPOSALS = ['email.reply'] as const;

export interface Citation {
  chunkId: string;
  start: number;
  end: number;
}

export interface ChatAnswer {
  answer: string;
  claims: { text: string; basis: (typeof BASES)[number]; citations: Citation[] }[];
  gaps: string[];
  proposals: { kind: (typeof ACTION_KINDS)[number]; targetId: string; description: string }[];
}

export interface AnswerContext {
  /** Chunk ID to text length for the chunks the model was given. */
  chunks: ReadonlyMap<string, number>;
  /** Source IDs in context; proposal targets must be one of them. */
  sources: ReadonlySet<string>;
}

export type AnswerValidation = { ok: true; value: ChatAnswer } | { ok: false; errors: string[] };

/** Schema-level checks only, for parity with the shared contract; `validateAnswer` adds the context checks. */
export function validateAnswerShape(value: unknown): AnswerValidation {
  return check(value, null);
}

export function validateAnswer(value: unknown, context: AnswerContext): AnswerValidation {
  return check(value, context);
}

function check(value: unknown, context: AnswerContext | null): AnswerValidation {
  const errors: string[] = [];
  if (!isObject(value)) return { ok: false, errors: ['$:expected_object'] };
  onlyKeys(value, ['answer', 'claims', 'gaps', 'proposals'], ['answer', 'claims', 'gaps', 'proposals'], '$', errors);
  if (!isString(value.answer, 4000)) errors.push('$.answer:invalid');

  if (!Array.isArray(value.claims) || value.claims.length > 10) errors.push('$.claims:invalid');
  else value.claims.forEach((claim, i) => {
    const path = `$.claims[${i}]`;
    if (!isObject(claim)) return errors.push(`${path}:invalid`);
    onlyKeys(claim, ['text', 'basis', 'citations'], ['text', 'basis', 'citations'], path, errors);
    if (!isString(claim.text, 1000)) errors.push(`${path}.text:invalid`);
    if (!(BASES as readonly unknown[]).includes(claim.basis)) errors.push(`${path}.basis:unknown_value`);
    if (!Array.isArray(claim.citations) || claim.citations.length > 4) return errors.push(`${path}.citations:invalid`);
    if ((claim.basis === 'evidence' || claim.basis === 'report') && claim.citations.length === 0) errors.push(`${path}.citations:required_for_basis`);
    claim.citations.forEach((citation: unknown, j: number) => {
      const at = `${path}.citations[${j}]`;
      if (!isObject(citation)) return errors.push(`${at}:invalid`);
      onlyKeys(citation, ['chunkId', 'start', 'end'], ['chunkId', 'start', 'end'], at, errors);
      if (typeof citation.chunkId !== 'string' || !CHUNK_ID.test(citation.chunkId)) return errors.push(`${at}.chunkId:invalid`);
      if (!isInt(citation.start, 6000) || !isInt(citation.end, 6000)) return errors.push(`${at}:invalid`);
      if (!context) return;
      const length = context.chunks.get(citation.chunkId);
      // A fabricated or out-of-scope citation is rejected, never displayed.
      if (length === undefined) return errors.push(`${at}.chunkId:not_in_context`);
      if (citation.start >= citation.end || citation.end > length) errors.push(`${at}:outside_chunk`);
    });
  });

  if (!Array.isArray(value.gaps) || value.gaps.length > 5 || !value.gaps.every((g) => isString(g, 300))) errors.push('$.gaps:invalid');

  if (!Array.isArray(value.proposals) || value.proposals.length > 3) errors.push('$.proposals:invalid');
  else value.proposals.forEach((item, i) => {
    const path = `$.proposals[${i}]`;
    if (!isObject(item)) return errors.push(`${path}:invalid`);
    onlyKeys(item, ['kind', 'targetId', 'description'], ['kind', 'targetId', 'description'], path, errors);
    if (!(ACTION_KINDS as readonly unknown[]).includes(item.kind)) errors.push(`${path}.kind:unknown_value`);
    else if (context && !(P1_PROPOSALS as readonly unknown[]).includes(item.kind)) errors.push(`${path}.kind:unsupported`);
    if (!isString(item.targetId, 200)) errors.push(`${path}.targetId:invalid`);
    else if (context && !context.sources.has(item.targetId)) errors.push(`${path}.targetId:not_in_context`);
    if (!isString(item.description, 500)) errors.push(`${path}.description:invalid`);
  });

  return errors.length === 0 ? { ok: true, value: value as unknown as ChatAnswer } : { ok: false, errors: errors.slice(0, 20) };
}
