/** A mutation result with a non-200 success status (for example 202 for accepted asynchronous work). */
export class WithStatus {
  constructor(
    readonly status: number,
    readonly data: unknown,
  ) {}
}
