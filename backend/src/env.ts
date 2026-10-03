/** Bindings declared in wrangler.jsonc. Only adapters read these directly. */
export interface Env {
  CONTROL_DB: D1Database;
  ARCHIVE_0001: D1Database;
  ARCHIVE_BUCKET: R2Bucket;
  WORK_QUEUE: Queue<QueueEnvelope>;
  /** WebAuthn relying party ID: the owner's HTTPS domain. */
  RP_ID: string;
  RP_NAME: string;
  /** Comma-separated: the HTTPS enrollment origin and the exact android:apk-key-hash origin of the Play signing certificate. */
  EXPECTED_ORIGINS: string;
}

export interface QueueEnvelope {
  version: 1;
  jobId: string;
}
