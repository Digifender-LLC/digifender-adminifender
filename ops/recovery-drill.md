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

### Staging drill, 2026-10-09 (steps 1 to 6; step 7 pending the owner)

| Field | Value |
| --- | --- |
| Date, environment, operator | 2026-10-09, staging, an agent with the Cloudflare API token |
| Worker version before and after | Before `222e2074-6915-4fd1-938a-79da3eeea2f9`; recovery mode `a315b999-f6fb-4f11-b8d6-f087b0bf37b7` (`--var RECOVERY_RUN_ID:drill-20261009`); after `24b43dfd-18b3-4ae7-bd7b-28b24f7b9b63`. Backend code identical to commit `36a1a91` (empty `git diff` of `backend/`). |
| Bookmarks used | Control `00000162-000000ae-000050ff-064c64d69105c9a4dcf54eaa373754cd`, archive `00000015-00000000-000050ff-3d57ff55bc3968abe3d67148d12c39f6`, both taken seconds before the restore. Undo bookmarks printed by the restore: control `00000162-ffffffff-000050ff-91bfb66d5d13f7da3de3d0a7fbc95ee8`, archive `00000015-ffffffff-000050ff-24c5aca3d388f8684a3cb9cd10300651`. |
| Ledger head verified (hash, count) | `recovery_runs` row `drill-20261009`, state `completed`: ledger `ok: true`, head sequence 2, 2 records, 2 deleted sources (head hash is not printed in the result). |
| Deleted IDs rechecked | 2 ledgered sources; `reapplied: 0`, `alreadyDeleted: 2`. The 404 check through the API was **not** run (needs a session). |
| Sources and objects reconciled (counts, mismatches) | 8 live sources, 8 raw objects checked, 0 missing. |
| Identity reset | Passkeys revoked 1, sessions 1, devices 2, recovery-key hashes 1, challenges 13. After the run the control database shows 0 live passkeys and 0 live sessions. |
| In-flight inference | 0 marked uncertain. |
| Fresh enrollment device | **Not done.** Staging has no usable passkey until the owner enrols one (bootstrap procedure in [staging.md](staging.md)); the staging app cannot sign in until then. |
| Restore time | Both `time-travel restore` calls finished in 14 seconds (18:28:22 to 18:28:36 UTC). Recovery mode lasted until the scheduled pass completed, within about 3 minutes of the deploy. |
| Problems and follow-ups | Limits of this drill: the restore was to a bookmark taken moments earlier, so it proves the mechanism and the recovery pass but not data loss handling; step 1 (freeze effects) and the diagnostics export were skipped because they need a session; steps 7 and 8 (fresh passkey, real email-to-inbox, search and chat path) need the owner's phone. The restore does not exercise a production-sized archive. |

### Production

No production drill has been run (no production environment exists).
