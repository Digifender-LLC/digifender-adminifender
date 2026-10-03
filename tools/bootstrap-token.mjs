#!/usr/bin/env node
// Prints a single-use, 15-minute owner bootstrap credential and the SQL that installs only its hash
// (lld.md#identity). Run the SQL against the intended CONTROL_DB with wrangler; the plaintext is shown once.
import { createHash, randomBytes, randomUUID } from 'node:crypto';

const token = `afb_${randomBytes(32).toString('base64url')}`;
const digest = createHash('sha256').update(token).digest('hex');
const expires = Date.now() + 15 * 60 * 1000;
process.stderr.write(`Bootstrap credential (shown once, expires ${new Date(expires).toISOString()}):\n${token}\n\n`);
process.stdout.write(
  `INSERT INTO auth_challenges(id, kind, challenge_digest, device_id, context_json, expires_at) VALUES ('${randomUUID()}', 'bootstrap', '${digest}', NULL, '{}', ${expires});\n`,
);
