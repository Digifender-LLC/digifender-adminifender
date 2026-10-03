import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import Ajv2020 from 'ajv/dist/2020.js';
import { describe, expect, it } from 'vitest';

const root = join(import.meta.dirname, '../../../contracts');
const schemaDir = join(root, 'schema/v1');
const fixtureDir = join(root, 'fixtures/v1');
const readJson = (path: string): unknown => JSON.parse(readFileSync(path, 'utf8'));

const ajv = new Ajv2020({ strict: true, allErrors: true });
const schemaFiles = readdirSync(schemaDir).filter((name) => name.endsWith('.schema.json'));
for (const name of schemaFiles) ajv.addSchema(readJson(join(schemaDir, name)) as object, name);

describe('shared contract fixtures', () => {
  it('has fixtures for every non-shared schema', () => {
    const contracts = schemaFiles.filter((name) => name !== 'common.schema.json').map((name) => name.replace('.schema.json', ''));
    expect(readdirSync(fixtureDir).sort()).toEqual(contracts.sort());
  });

  for (const contract of readdirSync(fixtureDir)) {
    for (const expected of ['valid', 'invalid'] as const) {
      const dir = join(fixtureDir, contract, expected);
      const files = readdirSync(dir);
      it(`${contract} has ${expected} fixtures`, () => expect(files.length).toBeGreaterThan(0));
      for (const file of files) {
        it(`${contract}/${expected}/${file}`, () => {
          const validate = ajv.getSchema(`${contract}.schema.json`);
          if (!validate) throw new Error(`missing schema for ${contract}`);
          expect(validate(readJson(join(dir, file))), JSON.stringify(validate.errors)).toBe(expected === 'valid');
        });
      }
    }
  }
});
