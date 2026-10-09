import { describe, expect, it } from 'vitest';
import { exportBody, parseStoredScope } from '../../src/exports/scope';
import { batchBody, claimBody, outcomeBody } from '../../src/extraction/device';
import { reviewBody } from '../../src/reviews/routes';
import { contractValidator } from './support';

// The shared JSON Schemas, compiled to standalone Ajv validators, run here inside the Workers runtime, so the same
// checks that judge fixtures also judge the Worker's real responses (see expectContract in the feature tests).
const fixtures = import.meta.glob<unknown>('../../../contracts/fixtures/v1/{export-scope,export-request,export-status,review-request,review-prepared,review-readback,review-confirmed,operation,extraction-claim-request,extraction-claim,extraction-batch,extraction-batch-result,extraction-outcome}/*/*.json', {
  eager: true,
  import: 'default',
});
const entries = Object.entries(fixtures).map(([path, value]) => {
  const [, contract, expected, file] = /v1\/([^/]+)\/(valid|invalid)\/([^/]+)\.json$/.exec(path) as RegExpExecArray & [string, string, 'valid' | 'invalid', string];
  return { contract, expected, file, value };
});
const parses = (parser: () => unknown) => {
  try {
    parser();
    return true;
  } catch {
    return false;
  }
};

describe('shared schemas in the Workers runtime', () => {
  it('finds every fixture', () => expect(entries.length).toBeGreaterThan(90));
  for (const { contract, expected, file, value } of entries) {
    it(`${contract}/${expected}/${file}`, () => expect(contractValidator(contract)(value)).toBe(expected === 'valid'));
  }
});

describe('request validators agree with the shared request schemas', () => {
  for (const { contract, expected, file, value } of entries.filter((e) => e.contract === 'export-request')) {
    it(`export-request/${expected}/${file}`, () => expect(parses(() => exportBody(value, '$'))).toBe(expected === 'valid'));
  }
  for (const { contract, expected, file, value } of entries.filter((e) => e.contract === 'review-request')) {
    it(`review-request/${expected}/${file}`, () => expect(parses(() => reviewBody(value, '$'))).toBe(expected === 'valid'));
  }
  for (const { contract, expected, file, value } of entries.filter((e) => ['extraction-claim-request', 'extraction-batch', 'extraction-outcome'].includes(e.contract))) {
    const parser = { 'extraction-claim-request': claimBody, 'extraction-batch': batchBody, 'extraction-outcome': outcomeBody }[contract]!;
    it(`${contract}/${expected}/${file}`, () => expect(parses(() => parser(value, '$'))).toBe(expected === 'valid'));
  }
  // A stored scope must already be in the normalized form the schema describes; anything else is refused, never widened.
  for (const { expected, file, value } of entries.filter((e) => e.contract === 'export-scope')) {
    it(`export-scope/${expected}/${file}`, () => expect(parseStoredScope(JSON.stringify(value)) !== null).toBe(expected === 'valid'));
  }
  it('normalizes a request to the explicit stored form', () => {
    expect(exportBody({}, '$')).toEqual({ scope: { kind: 'archive', admission: 'normal' } });
    expect(exportBody({ scope: { kind: 'filter', filter: { sender: ' Ops@Vendor.Example ', to: '2026-10-01T00:00:00Z' } } }, '$')).toEqual({
      scope: { kind: 'filter', admission: 'normal', filter: { to: '2026-10-01T00:00:00.000Z', sender: 'ops@vendor.example' } },
    });
    const ids = ['9d8c7b6a-5f4e-4d3c-b2a1-0f9e8d7c6b5a', '3f9a1c2e-5b7d-4e8f-9a0b-1c2d3e4f5a6b'];
    expect(exportBody({ scope: { kind: 'sources', sourceIds: ids } }, '$').scope).toEqual({ kind: 'sources', admission: 'normal', sourceIds: [...ids].sort() });
  });
});
