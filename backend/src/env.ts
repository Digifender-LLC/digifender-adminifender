/** Bindings declared in wrangler.jsonc. Only adapters read these directly. */
export interface Env {
  CONTROL_DB: D1Database;
  ARCHIVE_0001: D1Database;
  ARCHIVE_BUCKET: R2Bucket;
  WORK_QUEUE: Queue<QueueEnvelope>;
}

export interface QueueEnvelope {
  version: 1;
  jobId: string;
}
