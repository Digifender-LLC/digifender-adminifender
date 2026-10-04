import { cloudflareTest, readD1Migrations } from '@cloudflare/vitest-pool-workers';
import { defineConfig } from 'vitest/config';

export default defineConfig(async () => {
  const controlMigrations = await readD1Migrations('migrations/control');
  const archiveMigrations = await readD1Migrations('migrations/archive');
  return {
    test: {
      projects: [
        {
          plugins: [
            cloudflareTest({
              wrangler: { configPath: './wrangler.jsonc' },
              miniflare: { bindings: { CONTROL_MIGRATIONS: controlMigrations, ARCHIVE_MIGRATIONS: archiveMigrations, CURSOR_SECRET: 'test-only-cursor-secret-0123456789abcdef' } },
            }),
          ],
          test: { name: 'worker', include: ['tests/worker/**/*.test.ts'], setupFiles: ['tests/worker/apply-migrations.ts'] },
        },
        {
          test: { name: 'contracts', environment: 'node', include: ['tests/contracts/**/*.test.ts'] },
        },
      ],
    },
  };
});
