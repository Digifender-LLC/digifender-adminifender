import { base64url } from '../auth/tokens';
import type { Env } from '../env';
import { canonicalJson } from '../http/idempotency';
import { sha256Hex } from '../ids';

/** Content-free suppression record: opaque IDs and a time, never subjects, senders or text (lld.md#deletion). */
export interface LedgerRecord {
  version: 1;
  seq: number;
  operationId: string;
  sourceIds: string[];
  deletedAt: string;
  prevHash: string | null;
}

export interface LedgerHead {
  seq: number;
  recordHash: string;
  signature: string;
}

const HEAD_KEY = 'head.json';
const MAX_ATTEMPTS = 5;
export const recordKey = (seq: number) => `records/${String(seq).padStart(12, '0')}.json`;

async function sign(secret: string, seq: number, recordHash: string): Promise<string> {
  if (secret.length < 32) throw new Error('ledger_key_unconfigured');
  const key = await crypto.subtle.importKey('raw', new TextEncoder().encode(secret), { name: 'HMAC', hash: 'SHA-256' }, false, ['sign']);
  return base64url(new Uint8Array(await crypto.subtle.sign('HMAC', key, new TextEncoder().encode(`adminifender-ledger-v1\n${seq}\n${recordHash}`))));
}

export async function verifyHead(env: Pick<Env, 'LEDGER_SIGNING_KEY'>, head: LedgerHead): Promise<boolean> {
  return head.signature === (await sign(env.LEDGER_SIGNING_KEY, head.seq, head.recordHash));
}

/**
 * Appends one immutable record and advances the signed hash-chain head with a conditional ETag update, retrying
 * against the newest head on conflict. A record whose head update never landed is a harmless orphan that later
 * appends skip; the chain follows prevHash from the signed head. Throws when
 * the ledger cannot be written, so the caller leaves the deletion pending rather than claiming completion.
 */
export async function appendLedger(env: Pick<Env, 'DELETION_LEDGER' | 'LEDGER_SIGNING_KEY'>, entry: { operationId: string; sourceIds: string[]; deletedAt: number }): Promise<LedgerHead> {
  const bucket = env.DELETION_LEDGER;
  let seq = 0;
  for (let attempt = 0; attempt < MAX_ATTEMPTS * 2; attempt += 1) {
    const current = await bucket.get(HEAD_KEY);
    const head = current ? ((await current.json()) as LedgerHead) : null;
    if (head && !(await verifyHead(env, head))) throw new Error('ledger_head_invalid');
    // Skip sequences taken by orphaned records whose head update never landed; the chain links to the head.
    seq = Math.max(seq, head?.seq ?? 0) + 1;
    const record: LedgerRecord = {
      version: 1,
      seq,
      operationId: entry.operationId,
      sourceIds: [...entry.sourceIds].sort(),
      deletedAt: new Date(entry.deletedAt).toISOString(),
      prevHash: head?.recordHash ?? null,
    };
    const body = canonicalJson(record);
    const recordHash = await sha256Hex(body);
    // Records are immutable: never overwrite one another writer already placed at this sequence.
    const written = await bucket.put(recordKey(record.seq), body, { onlyIf: { etagDoesNotMatch: '*' }, httpMetadata: { contentType: 'application/json' } });
    if (!written) continue;
    const next: LedgerHead = { seq: record.seq, recordHash, signature: await sign(env.LEDGER_SIGNING_KEY, record.seq, recordHash) };
    const advanced = await bucket.put(HEAD_KEY, JSON.stringify(next), {
      onlyIf: current ? { etagMatches: current.etag } : { etagDoesNotMatch: '*' },
      httpMetadata: { contentType: 'application/json' },
    });
    if (advanced) return next;
  }
  throw new Error('ledger_conflict');
}
