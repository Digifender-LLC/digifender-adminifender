import { HttpError } from './errors';

/** Small strict validators mirroring the JSON Schemas; no runtime code generation (tdd.md#backend-and-contracts). */
export type Validator<T> = (value: unknown, path: string) => T;

const invalid = (path: string, reason: string) => new HttpError(400, 'invalid_request', 'The request is invalid.', { path, reason });

export const v = {
  string(options: { min?: number; max: number; pattern?: RegExp }): Validator<string> {
    return (value, path) => {
      if (typeof value !== 'string') throw invalid(path, 'expected_string');
      if (value.length < (options.min ?? 1) || value.length > options.max) throw invalid(path, 'length');
      if (options.pattern && !options.pattern.test(value)) throw invalid(path, 'format');
      return value;
    };
  },
  uuid(): Validator<string> {
    return v.string({ max: 36, pattern: /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/ });
  },
  integer(options: { min: number; max: number }): Validator<number> {
    return (value, path) => {
      if (typeof value !== 'number' || !Number.isInteger(value) || value < options.min || value > options.max) throw invalid(path, 'expected_integer');
      return value;
    };
  },
  boolean(): Validator<boolean> {
    return (value, path) => {
      if (typeof value !== 'boolean') throw invalid(path, 'expected_boolean');
      return value;
    };
  },
  oneOf<const T extends string>(values: readonly T[]): Validator<T> {
    return (value, path) => {
      if (typeof value !== 'string' || !(values as readonly string[]).includes(value)) throw invalid(path, 'unknown_value');
      return value as T;
    };
  },
  /** An opaque JSON object passed to a maintained library (for example a WebAuthn response). Size is bounded by the body limit. */
  record(): Validator<Record<string, unknown>> {
    return (value, path) => {
      if (typeof value !== 'object' || value === null || Array.isArray(value)) throw invalid(path, 'expected_object');
      return value as Record<string, unknown>;
    };
  },
  optional<T>(inner: Validator<T>): Validator<T | undefined> & { optional: true } {
    const fn = ((value: unknown, path: string) => (value === undefined ? undefined : inner(value, path))) as Validator<T | undefined> & { optional: true };
    fn.optional = true;
    return fn;
  },
  /** Rejects unknown fields (lld.md#contract-conventions). */
  object<S extends Record<string, Validator<unknown>>>(shape: S): Validator<{ [K in keyof S]: ReturnType<S[K]> }> {
    return (value, path) => {
      if (typeof value !== 'object' || value === null || Array.isArray(value)) throw invalid(path, 'expected_object');
      const input = value as Record<string, unknown>;
      for (const key of Object.keys(input)) if (!Object.hasOwn(shape, key)) throw invalid(`${path}.${key}`, 'unknown_field');
      const out: Record<string, unknown> = {};
      for (const [key, validator] of Object.entries(shape)) {
        if (!Object.hasOwn(input, key) && !(validator as { optional?: boolean }).optional) throw invalid(`${path}.${key}`, 'required');
        const parsed = validator(input[key], `${path}.${key}`);
        if (parsed !== undefined) out[key] = parsed;
      }
      return out as { [K in keyof S]: ReturnType<S[K]> };
    };
  },
};

/** Ordinary JSON body limit (lld.md#contract-conventions). */
export const MAX_JSON_BYTES = 256 * 1024;

export async function readJson<T>(request: Request, validator: Validator<T>): Promise<T> {
  const type = request.headers.get('content-type') ?? '';
  if (!/^application\/json\b/i.test(type)) throw new HttpError(400, 'invalid_request', 'Expected a JSON body.', { reason: 'content_type' });
  const declared = Number(request.headers.get('content-length') ?? '0');
  if (declared > MAX_JSON_BYTES) throw new HttpError(413, 'too_large', 'The request body is too large.');
  const reader = request.body?.getReader();
  const parts: Uint8Array[] = [];
  let total = 0;
  if (reader) {
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      total += value.byteLength;
      if (total > MAX_JSON_BYTES) {
        await reader.cancel();
        throw new HttpError(413, 'too_large', 'The request body is too large.');
      }
      parts.push(value);
    }
  }
  const bytes = new Uint8Array(total);
  let offset = 0;
  for (const part of parts) {
    bytes.set(part, offset);
    offset += part.byteLength;
  }
  let parsed: unknown;
  try {
    parsed = JSON.parse(new TextDecoder('utf-8', { fatal: true, ignoreBOM: false }).decode(bytes));
  } catch {
    throw new HttpError(400, 'invalid_request', 'The request body is not valid JSON.', { reason: 'json' });
  }
  return validator(parsed, '$');
}
