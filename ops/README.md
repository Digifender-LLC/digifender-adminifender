# Release and recovery evidence

The [TDD release gates](../docs/specifications/tdd.md#release-migration-and-recovery) and [SRS acceptance cases](../docs/specifications/srs.md#acceptance-scenarios) remain authoritative. These tools work locally, make no network calls and never deploy or restore resources.

Run from the repository root with the pinned backend Node version:

```sh
node --test tools/release_preflight.test.mjs tools/release_recovery.test.mjs
node tools/release_preflight.mjs
node tools/release_preflight.mjs --json
python3 tools/check_docs.py
```

The preflight normally exits **1**: absent evidence is a blocker. Exit **2** means invalid input; **0** means the recorded evidence is complete enough for review, never production acceptance. The report includes the exact Git revision, tracked-worktree cleanliness, migration inventory and SHA-256 hashes of selected build inputs. It does not infer that commands passed from the presence of source files, and this inventory is not the required release contract/model manifest.

To review recorded results, create a local `.quality-reports/release-evidence/registry.json` with the format below. That directory is already ignored. Store sanitized reports there, with no credentials, tokens, mail, keys or archive contents. The tool hashes only explicitly referenced reports in that directory and refuses path escapes and external symlinks. Do not put private evidence there. Each gate listed by the default preflight needs its own dated report at the exact candidate revision; a local mock cannot satisfy a device, Play, staging or isolated-recovery gate. Report scope is a claim for human review, not remotely attested provenance.

```json
{
  "version": 1,
  "sourceRevision": "<exact candidate commit>",
  "checks": [
    {
      "gate": "backend-check",
      "status": "passed",
      "scope": "local",
      "sourceRevision": "<same candidate commit>",
      "observedAt": "<UTC timestamp ending in Z>",
      "artifact": ".quality-reports/release-evidence/backend.txt",
      "sha256": "<SHA-256 of sanitized report bytes>"
    }
  ]
}
```

```sh
node tools/release_preflight.mjs --evidence .quality-reports/release-evidence/registry.json --json
```

Include exact commands/exit codes, source/build/model versions, fixture size, measured device/API and inspected artifacts as applicable. `p1-acceptance` must give case-level results, including cases that do not fit a single gate. An artifact hash authenticates bytes, not the truth or completeness of those results. Do not reuse a report after source, installed build, model, bindings or relevant configuration changes without reviewing its applicability.

## Recovery checks and remaining implementation

[The verifier](../tools/release_recovery.mjs) is a pure local library tested with synthetic inputs. It verifies the implemented HMAC-signed suppression head and canonical SHA-256 record chain, requires a separately obtained current-head anchor, follows hash links across legitimate orphan sequence gaps, and returns suppression IDs. It rejects missing predecessors, tampered records and stale heads. Restored-state auditing checks suppression across read/search/download summaries, removal of identity and review material, paused effects, fresh enrollment and an allowlist of undispatched replay-safe jobs. Its inputs and outputs contain opaque IDs/counts, not message content.

These checks are **not a backup producer, decryption tool, database restore, FTS rebuild or recovery drill against Cloudflare**. They do not measure RPO/RTO or establish that a supplied “current” head is fresh. There is deliberately no CLI that reads an operator signing key. The synthetic tests use a public fixture key that must never be configured in a deployment.

Before a real recovery tool serves restored data, it must:

1. Pause external dispatch and restore only into isolated resources using the recorded release/migration versions.
2. Obtain the independent current suppression head/chain through authenticated operator access. A head bundled with an old backup is insufficient. Verify its signature with securely supplied key material and retain a dated, content-free retrieval receipt. A genuinely empty ledger needs independently established empty state; absence is not proof.
3. Decrypt the specified AES-256-GCM/RSA-OAEP-SHA256 backup, verify manifest and attachment hashes, restore all partitions and registry mappings, then reapply current deletions before any read/search/download access.
4. Remove restored passkeys, sessions, recovery hashes, FCM registrations, outstanding authentication challenges and action reviews; require independent fresh enrollment. Rebuild FTS and reconcile retained object references.
5. Replay only safe internal work; preserve uncertain dispatched inference/push/effects until outcome readback. Measure restoration time and backup age against the SRS targets.
6. Record isolated restore, D1 Time Travel and offline export results separately. Same-account R2 storage does not establish recovery from loss of the Cloudflare account. Live cutover requires the existing release/owner verification gate.

The current backend has a deletion-ledger writer and export jobs, but has no encrypted backup producer, backup binding, incremental manifest/garbage-collection implementation or complete restore executor. [Continuation notes](goal-handoff.md) track the concrete next slices.
