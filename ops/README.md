# Operations

Operator procedures for Adminifender ([TDD release, migration and recovery](../docs/specifications/tdd.md#release-migration-and-recovery)). They must work without the phone or the app.

| Procedure | Use it for |
| --- | --- |
| [staging.md](staging.md) | The provisioned staging environment: resources, deploy, migrations, what is not configured yet |
| [release.md](release.md) | Signing, preflight and the private Play internal-testing release |
| [recovery-drill.md](recovery-drill.md) | The restore and recovery drill and its evidence record |
| [releases.json](releases.json) | Release manifests of uploaded builds, appended by `tools/release_preflight.py record` |

No secret values belong in this folder. Record where a secret lives and who can rotate it, never the value.
