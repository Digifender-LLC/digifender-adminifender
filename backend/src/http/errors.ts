/** A client-visible failure. Messages and details must never carry secrets, content or upstream bodies. */
export class HttpError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
    readonly details: Record<string, unknown> = {},
    readonly retryable = false,
  ) {
    super(message);
  }
}

export const unauthenticated = () => new HttpError(401, 'unauthenticated', 'Sign in again.');
export const notFound = () => new HttpError(404, 'not_found', 'Not found.');
