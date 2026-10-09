import { readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';
import { cloudflareTest, readD1Migrations } from '@cloudflare/vitest-pool-workers';
import Ajv2020 from 'ajv/dist/2020.js';
import standaloneCode from 'ajv/dist/standalone/index.js';
import type { Plugin } from 'vite';
import { defineConfig } from 'vitest/config';

const schemaDir = join(import.meta.dirname, '../contracts/schema/v1');
const VIRTUAL = 'virtual:contract-validators';

/**
 * The Workers runtime refuses code generation from strings, so Ajv cannot compile schemas inside a Worker test.
 * This plugin compiles every shared schema in Node into standalone validator code (the same Ajv and strict mode as
 * tests/contracts/fixtures.test.ts) and serves it as a virtual module, so Worker tests can check live responses
 * against the real JSON Schemas.
 */
function contractValidators(): Plugin {
  const resolved = `\0${VIRTUAL}`;
  return {
    name: 'contract-validators',
    resolveId: (id) => (id === VIRTUAL ? resolved : undefined),
    load(id) {
      if (id !== resolved) return undefined;
      const ajv = new Ajv2020({ strict: true, allErrors: true, code: { source: true } });
      const names = readdirSync(schemaDir).filter((name) => name.endsWith('.schema.json'));
      for (const name of names) ajv.addSchema(JSON.parse(readFileSync(join(schemaDir, name), 'utf8')) as object, name);
      const exportsMap = Object.fromEntries(names.filter((name) => name !== 'common.schema.json').map((name) => [name.replace('.schema.json', ''), name]));
      const body = standaloneCode(ajv, exportsMap);
      return `
        const ucs2length = (text) => { let length = 0; for (const _ of text) length += 1; return length; };
        const equal = (a, b) => {
          if (a === b) return true;
          if (a && b && typeof a === 'object' && typeof b === 'object') {
            if (Array.isArray(a) !== Array.isArray(b)) return false;
            const keys = Object.keys(a);
            if (keys.length !== Object.keys(b).length) return false;
            return keys.every((key) => Object.hasOwn(b, key) && equal(a[key], b[key]));
          }
          return false;
        };
        const shims = { 'ajv/dist/runtime/ucs2length': { default: ucs2length }, 'ajv/dist/runtime/equal': { default: equal } };
        const require = (name) => { if (!(name in shims)) throw new Error('unshimmed ' + name); return shims[name]; };
        const module = { exports: {} };
        const exports = module.exports;
        ${body}
        export const validators = module.exports;
      `;
    },
  };
}

export default defineConfig(async () => {
  const controlMigrations = await readD1Migrations('migrations/control');
  const archiveMigrations = await readD1Migrations('migrations/archive');
  return {
    test: {
      projects: [
        {
          plugins: [
            contractValidators(),
            cloudflareTest({
              wrangler: { configPath: './wrangler.jsonc' },
              miniflare: { bindings: { CONTROL_MIGRATIONS: controlMigrations, ARCHIVE_MIGRATIONS: archiveMigrations, CURSOR_SECRET: 'test-only-cursor-secret-0123456789abcdef', LEDGER_SIGNING_KEY: 'test-only-ledger-key-0123456789abcdef', PUSH_TOKEN_KEY: 'test-only-push-token-key-0123456789abcdef' } },
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
