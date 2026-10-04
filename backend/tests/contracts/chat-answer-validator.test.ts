import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import { validateAnswerShape } from '../../src/chat/answer';

// The domain validator must agree with the shared JSON Schema on every chat-answer fixture.
const dir = join(import.meta.dirname, '../../../contracts/fixtures/v1/chat-answer');

describe('chat answer validator parity', () => {
  for (const expected of ['valid', 'invalid'] as const) {
    for (const file of readdirSync(join(dir, expected))) {
      it(`${expected}/${file}`, () => {
        const fixture = JSON.parse(readFileSync(join(dir, expected, file), 'utf8')) as unknown;
        expect(validateAnswerShape(fixture).ok).toBe(expected === 'valid');
      });
    }
  }
});
