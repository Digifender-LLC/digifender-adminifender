import { applyD1Migrations } from 'cloudflare:test';
import { env } from 'cloudflare:workers';

await applyD1Migrations(env.CONTROL_DB, env.CONTROL_MIGRATIONS);
await applyD1Migrations(env.ARCHIVE_0001, env.ARCHIVE_MIGRATIONS);
