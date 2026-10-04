const JSON_HEADERS = {
  'content-type': 'application/json; charset=utf-8',
  'cache-control': 'no-store',
  'x-content-type-options': 'nosniff',
} as const;

export function ok<T>(data: T, requestId: string, status = 200): Response {
  return new Response(JSON.stringify({ data, requestId }), { status, headers: JSON_HEADERS });
}

/** Error envelope from lld.md#contract-conventions. Never include upstream bodies or secrets in message/details. */
export function fail(
  status: number,
  code: string,
  message: string,
  requestId: string,
  options: { retryable?: boolean; details?: Record<string, unknown> } = {},
): Response {
  const body = { error: { code, message, retryable: options.retryable ?? false, details: options.details ?? {} }, requestId };
  return new Response(JSON.stringify(body), { status, headers: JSON_HEADERS });
}
