import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import { validateTriage } from '../../src/inference/triage-output';

// The domain validator must agree with the shared JSON Schema on every triage-output fixture.
const dir = join(import.meta.dirname, '../../../contracts/fixtures/v1/triage-output');
const readJson = (path: string): unknown => JSON.parse(readFileSync(path, 'utf8'));

function context(fixture: unknown): [Map<string, number>, Set<string>] {
  const value = fixture as { evidence?: { chunkId?: unknown }[]; serviceIds?: unknown[] };
  const chunks = new Map((Array.isArray(value.evidence) ? value.evidence : []).map((e) => [String(e.chunkId), 6000]));
  const services = new Set((Array.isArray(value.serviceIds) ? value.serviceIds : []).map(String));
  return [chunks, services];
}

describe('triage output validator parity', () => {
  for (const expected of ['valid', 'invalid'] as const) {
    for (const file of readdirSync(join(dir, expected))) {
      it(`${expected}/${file}`, () => {
        const fixture = readJson(join(dir, expected, file));
        expect(validateTriage(fixture, ...context(fixture)).ok).toBe(expected === 'valid');
      });
    }
  }
});
