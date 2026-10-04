import type { Env } from '../env';
import { CLAIM_GUARD, type ClaimedJob, type JobResult, guardParams } from '../jobs/runner';
import { REGISTRY_COLUMNS, REGISTRY_FROM, type RegistryRow, archiveFor } from '../sources/registry';

/** Source-scoped R2 prefixes. There is no cross-source object dedup, so other sources' bytes are untouched. */
export const sourcePrefixes = (sourceId: string) => [`staging/${sourceId}/`, `attachments/${sourceId}/`, `text/${sourceId}/`];

const REDACTED = JSON.stringify({ status: 410, body: { error: { code: 'deleted', message: 'This item was deleted.', retryable: false, details: {} } } });

async function deletePrefix(bucket: R2Bucket, prefix: string): Promise<void> {
  for (;;) {
    const listed = await bucket.list({ prefix, limit: 1000 });
    if (listed.objects.length === 0) return;
    await bucket.delete(listed.objects.map((o) => o.key));
    if (!listed.truncated) return;
  }
}

/**
 * Cleanup after a confirmed deletion (lld.md#deletion): archive rows, chunks, FTS, source-scoped R2 objects,
 * derived control rows and stored mutation results that could quote the source, then `deleted` and a completed
 * ledger row in one fenced batch. Every step is idempotent; failures retry and stay visible as backlog.
 */
export async function cleanupJob(job: ClaimedJob, env: Env, now: number): Promise<JobResult> {
  if (!job.sourceId) return { kind: 'fail', errorCode: 'missing_source' };
  const db = env.CONTROL_DB;
  const source = await db.prepare(`SELECT ${REGISTRY_COLUMNS} FROM ${REGISTRY_FROM} WHERE r.id = ?`).bind(job.sourceId).first<RegistryRow>();
  if (!source || source.state === 'deleted') return { kind: 'complete', output: { skipped: 'already_deleted' } };
  if (source.state !== 'deleting') return { kind: 'fail', errorCode: 'not_deleting' };
  const id = source.id;

  const archive = archiveFor(env, source);
  if (source.binding_name && !archive) return { kind: 'retry', errorCode: 'archive_unavailable' };
  if (archive) {
    await archive.batch([
      archive.prepare('DELETE FROM source_fts WHERE source_id = ?').bind(id),
      archive.prepare('DELETE FROM chunks WHERE source_id = ?').bind(id),
      archive.prepare('DELETE FROM attachments WHERE source_id = ?').bind(id),
      archive.prepare('DELETE FROM archive_receipts WHERE source_id = ?').bind(id),
      archive.prepare('DELETE FROM sources WHERE id = ?').bind(id),
    ]);
  }
  for (const prefix of sourcePrefixes(id)) await deletePrefix(env.ARCHIVE_BUCKET, prefix);

  const [guardId, guardToken] = guardParams(job);
  const attachmentIds = (await db.prepare('SELECT attachment_id FROM attachment_processing WHERE source_id = ?').bind(id).all<{ attachment_id: string }>()).results.map((r) => r.attachment_id);
  const like = [id, ...attachmentIds].map(() => 'result_json LIKE ?').join(' OR ');
  return {
    kind: 'complete',
    output: { sourceId: id },
    statements: [
      db.prepare(`DELETE FROM attachment_processing WHERE source_id = ? AND ${CLAIM_GUARD}`).bind(id, guardId, guardToken),
      db.prepare(`DELETE FROM triage_results WHERE source_id = ? AND ${CLAIM_GUARD}`).bind(id, guardId, guardToken),
      db.prepare(`DELETE FROM source_threads WHERE source_id = ? AND ${CLAIM_GUARD}`).bind(id, guardId, guardToken),
      db.prepare(`DELETE FROM concern_sources WHERE source_id = ? AND ${CLAIM_GUARD}`).bind(id, guardId, guardToken),
      // Retained idempotent results may quote the source; a replay now reports the deletion instead.
      db.prepare(`UPDATE mutations SET result_json = ? WHERE state = 'complete' AND (${like}) AND ${CLAIM_GUARD}`).bind(REDACTED, ...[id, ...attachmentIds].map((x) => `%${x}%`), guardId, guardToken),
      db.prepare(`UPDATE source_registry SET state = 'deleted', staged_key = NULL WHERE id = ? AND state = 'deleting' AND ${CLAIM_GUARD}`).bind(id, guardId, guardToken),
      db.prepare(`UPDATE deletion_ledger SET state = 'completed', completed_at = ? WHERE source_id = ? AND state = 'pending' AND ${CLAIM_GUARD}`).bind(now, id, guardId, guardToken),
    ],
  };
}
