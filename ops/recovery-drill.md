# Recovery drill

Exercises [TDD recovery and operational controls](../docs/specifications/tdd.md#recovery-and-operational-controls) on staging first, then production. The drill is not done until the evidence record below is filled in.

## Steps

1. **Freeze effects.** Pause external-effect dispatch and cloud AI in settings; leave capture running.
2. **Record the baseline.** Deployed version (`wrangler deployments list --env <env>`), `/api/v1/meta`, a D1 Time Travel bookmark for each database (`wrangler d1 time-travel info <db> --env <env>`) and a diagnostics export (`GET /api/v1/diagnostics`).
3. **Apply migrations first.** The recovery pass records its result in `recovery_runs` (control migration 0013). Apply pending control migrations before the drill: `npx wrangler d1 migrations apply <control-db> --env <env> --remote`.
4. **Restore.** Restore the control and archive databases to the bookmark (`wrangler d1 time-travel restore`), or into fresh databases bound by a new deployment. If the bookmark predates 0013, apply migrations again after the restore.
5. **Enter recovery mode.** Deploy the same version with a run ID: `npx wrangler deploy --env <env> --var RECOVERY_RUN_ID:drill-<date>`. While it is set, every API path except `/health` and `/meta` returns 503 `recovering`, queue messages are acknowledged without processing, and the next scheduled run performs one recovery pass (`backend/src/recovery/recovery.ts`):
   - **Verify the suppression ledger.** Walks the signed head and record chain in the ledger bucket. A forged head, missing record, hash mismatch or broken chain marks the run `blocked` and changes nothing else.
   - **Reapply deletions** for every ledgered source ID that is live again after the restore.
   - **Reset identity.** Revokes restored passkeys, sessions and devices (push tokens cleared), deletes recovery-key hashes and pending challenges, and advances the auth epoch.
   - **Mark in-flight inference** (`reserved` or `dispatched`) as `uncertain` with `restored_in_flight`.
   - **Reconcile** live sources against their raw R2 objects (first 5,000; at most 50 missing IDs listed).
   The run is idempotent per run ID. Read the outcome with `npx wrangler d1 execute <control-db> --env <env> --remote --command "SELECT state, result_json FROM recovery_runs"`; the JSON holds counts and IDs only, no content.
6. **Leave recovery mode.** Redeploy without the variable (`npx wrangler deploy --env <env>`). Confirm that a few ledgered source IDs return 404.
7. **Enroll a fresh passkey.** Issue a one-time bootstrap code (see [staging.md](staging.md)) and enroll through `/enroll` and the app on a second device. Bootstrap is accepted only while no unrevoked passkey exists. Rebuild FTS and replay only replay-safe jobs if the reconcile counts show gaps; dispatched inference and effects stay `uncertain` until read back.
8. **Resume** dispatch, then check the private health view and a real email-to-inbox, search and chat path.

## Evidence record

| Field | Value |
| --- | --- |
| Date, environment, operator | |
| Worker version before and after | |
| Bookmarks used | |
| Ledger head verified (hash, count) | |
| Deleted IDs rechecked | |
| Sources and objects reconciled (counts, mismatches) | |
| Fresh enrollment device | |
| Restore time | |
| Problems and follow-ups | |

No drill has been run yet. Recovery mode is implemented and locally verified (`backend/tests/worker/recovery.test.ts`); staging has not yet had migration 0013 applied.
