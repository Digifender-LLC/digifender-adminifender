---
name: adminifender-verify
description: Select focused Adminifender checks and report their evidence limits.
---

# Verify

Read `AGENTS.md`, `docs/verification.md` and the affected acceptance cases. Inspect test setup before execution. Preserve unrelated changes; do not read secrets or delegate without an explicit user request.

Run `python3 tools/check_docs.py` for docs/agent setup. When build files exist, select focused Gradle compile/lint/JVM/instrumentation tasks, backend typecheck/lint/Vitest/bundle tasks, shared fixture and migration checks, and UI accessibility/device tests for the changed behavior. Do not invent task names or run paid/live providers as routine tests. Inspect screenshots and failure reports. Return exact commands, exits, fixture scope, source revision when available, failures and missing staging/device/Play evidence. Never lower a gate to obtain a green result.
