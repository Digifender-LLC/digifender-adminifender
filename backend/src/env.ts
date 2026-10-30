import type { PushSender } from './notifications/push';

/** Bindings declared in wrangler.jsonc. Only adapters read these directly. */
export interface Env {
  CONTROL_DB: D1Database;
  ARCHIVE_0001: D1Database;
  ARCHIVE_BUCKET: R2Bucket;
  /** Separate private bucket for the content-free deletion suppression ledger; no ordinary lifecycle deletion. */
  DELETION_LEDGER: R2Bucket;
  WORK_QUEUE: Queue<QueueEnvelope>;
  /** WebAuthn relying party ID: the owner's HTTPS domain. */
  RP_ID: string;
  RP_NAME: string;
  /** Comma-separated: the HTTPS enrollment origin and the exact android:apk-key-hash origin of the Play signing certificate. */
  EXPECTED_ORIGINS: string;
  /** Worker secret (>= 32 chars) that authenticates list and search cursors. */
  CURSOR_SECRET: string;
  /** Worker secret (>= 32 chars) that signs the deletion ledger head. */
  LEDGER_SIGNING_KEY: string;
  /** Set only for a recovery pass (ops/recovery-drill.md): the API answers 503 and jobs pause until it is removed. */
  RECOVERY_RUN_ID?: string;
  /**
   * Staging only, owner decision 2026-10-30 (ops/staging.md): also forward each newly stored message here.
   * Must be a verified Email Routing destination address. Unset everywhere else.
   */
  FORWARD_TO?: string;
  /**
   * Optional Workers AI binding. Absent locally and until a reviewed environment binds it; routing then treats
   * cloud as unavailable and queues work for the phone.
   */
  AI?: Ai;
  /** Worker secret (>= 32 chars) that encrypts stored push tokens. */
  PUSH_TOKEN_KEY: string;
  /**
   * P2 signed reports are off unless this is exactly "true" (lld.md#reports). Each producer still needs its own
   * acceptance gate before a deployment turns it on.
   */
  REPORTS_ENABLED?: string;
  /** Worker secret (>= 32 chars) that encrypts report HMAC keys at rest; required when reports are enabled. */
  REPORT_KEY_WRAP?: string;
  /**
   * Optional Worker secret: the Firebase service-account JSON for FCM sends. Backend-only; never logged,
   * returned or given to a model. Absent locally, so delivery reports `push_unavailable`.
   */
  FCM_SERVICE_ACCOUNT?: string;
  /** Test-only push adapter injected in place of FCM. Never bound in a deployed environment. */
  PUSH?: PushSender;
}

export interface QueueEnvelope {
  version: 1;
  jobId: string;
}
