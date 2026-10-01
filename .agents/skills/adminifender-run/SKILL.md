---
name: adminifender-run
description: Run Adminifender's available local checks or future credential-free fixtures.
---

# Run

Read `AGENTS.md` and `docs/verification.md`; inspect the actual build files before choosing commands. Preserve unrelated changes. Do not read secrets or delegate without an explicit user request.

Today, only `python3 tools/check_docs.py` is runnable. Once implemented, use the committed `./gradlew` and backend lockfile/scripts, local loopback Worker bindings, synthetic mail and fake model/provider adapters. Do not point local fixtures at staging or production, expose a dev server publicly, or stop processes you did not start. Record the exact command, exit status, fixture scope and evidence path. A local run is not Play-installed or live-provider acceptance.
