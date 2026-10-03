import { sha256Hex } from '../ids';

export function base64url(bytes: Uint8Array): string {
  let binary = '';
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

/** Random 256-bit opaque secret with a type prefix. Only its digest is stored. */
export function newSecret(prefix: 'afa' | 'afr' | 'afk' | 'afb'): string {
  return `${prefix}_${base64url(crypto.getRandomValues(new Uint8Array(32)))}`;
}

export const digest = (secret: string): Promise<string> => sha256Hex(secret);

/** Constant-time comparison of equal-length hex digests. */
export function digestsEqual(a: string, b: string): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i += 1) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}
