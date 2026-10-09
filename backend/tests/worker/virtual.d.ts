declare module 'virtual:contract-validators' {
  /** Ajv standalone validators for every shared JSON Schema, keyed by schema name (vitest.config.ts). */
  export const validators: Record<string, ((data: unknown) => boolean) & { errors?: { instancePath: string; keyword: string; message?: string }[] | null }>;
}
