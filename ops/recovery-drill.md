# Recovery drill

Exercises [TDD recovery and operational controls](../docs/specifications/tdd.md#recovery-and-operational-controls) on staging first, then production. The drill is not done until the evidence record below is filled in.

## Steps

1. **Freeze effects.** Pause external-effect dispatch and cloud AI in settings; leave capture running.
2. **Record the baseline.** Deployed version (`wrangler deployments list --env <env>`), `/api/v1/meta`, a D1 Time Travel bookmark for each database (`wrangler d1 time-travel info <db> --env <env>`) and a diagnostics export (`GET /api/v1/diagnostics`).
3. **Verify the suppression ledger.** Read the deletion-ledger head and chain from its bucket and check the signatures. Missing or unverifiable ledger evidence blocks restored archive access.
4. **Restore.** Restore the control and archive databases to the bookmark (`wrangler d1 time-travel restore`), or into fresh databases bound by a new deployment.
5. **Reapply deletions** from the ledger before serving any data, then confirm that deleted source IDs return 404.
6. **Reset identity.** Discard restored passkeys, sessions, recovery hashes and push registrations; enroll a fresh passkey through the recovery flow on a second device.
7. **Reconcile.** Compare the source registry with R2 objects and hashes, rebuild FTS, and replay only replay-safe jobs. Dispatched inference and effects stay `uncertain` until read back.
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

No drill has been run yet.
