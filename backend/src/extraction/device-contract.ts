import { HttpError } from '../http/errors';
import { type Validator, v } from '../http/validate';
import { THERMAL_STATES } from '../inference/routing';

export const DEVICE_EXTRACTOR_VERSION = 'device-extract-v1';
export const EXTRACTION_LEASE_MS = 300_000;
export const MAX_EXTRACTION_TEXT_BYTES = 20 * 1024 * 1024;
export const MAX_EXTRACTION_BATCHES = 256;
export const MAX_BATCH_TEXT_BYTES = 128 * 1024;

export const extractionClaimBody = v.object({
  charging: v.boolean(), batteryPercent: v.integer({ min: 0, max: 100 }), thermal: v.oneOf(THERMAL_STATES),
  pdfText: v.boolean(), latinOcr: v.boolean(),
});
export const extractionHeartbeatBody = v.object({ leaseToken: v.uuid() });
const hash = v.string({ min: 64, max: 64, pattern: /^[0-9a-f]{64}$/ });
const revision = v.integer({ min: 0, max: Number.MAX_SAFE_INTEGER });
const fence = {
  leaseToken: v.uuid(), generationId: v.uuid(), contentHash: hash,
  sourceRevision: revision, policyRevision: revision,
  extractorVersion: v.string({ max: 80 }),
};
const part = v.object({
  page: v.integer({ min: 0, max: 1_000_000 }), part: v.integer({ min: 0, max: 100_000 }),
  startOffset: v.integer({ min: 0, max: MAX_EXTRACTION_TEXT_BYTES }),
  text: v.string({ min: 0, max: 6000 }), method: v.oneOf(['pdf_text', 'ocr'] as const), lastPart: v.boolean(),
});
export type ExtractionPart = ReturnType<typeof part>;
const parts: Validator<ExtractionPart[]> = (value, path) => {
  if (!Array.isArray(value) || value.length < 1 || value.length > 128) throw new HttpError(400, 'invalid_request', 'A batch needs between one and 128 bounded parts.');
  const result = value.map((item, index) => part(item, `${path}[${index}]`));
  if (new Set(result.map((item) => item.page)).size > 10 || result.reduce((sum, item) => sum + new TextEncoder().encode(item.text).byteLength, 0) > MAX_BATCH_TEXT_BYTES) {
    throw new HttpError(413, 'too_large', 'The extraction batch exceeds its text or page limit.');
  }
  return result;
};
export const extractionBatchBody = v.object({ ...fence, batchOrdinal: v.integer({ min: 0, max: MAX_EXTRACTION_BATCHES - 1 }), parts });
export const extractionFinishBody = v.object({ ...fence, manifestHash: hash, complete: v.boolean() });
export const extractionDeferBody = v.object({ ...fence, reason: v.oneOf(['ocr_unavailable', 'policy_blocked', 'encrypted', 'parser_error', 'file_limit', 'text_limit', 'thermal', 'device_unavailable'] as const) });
export const extractionResumeBody = v.object({ expectedRevision: revision });
export type ExtractionFence = ReturnType<typeof extractionFinishBody> | ReturnType<typeof extractionBatchBody> | ReturnType<typeof extractionDeferBody>;
