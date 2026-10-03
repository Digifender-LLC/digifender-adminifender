const encoder = new TextEncoder();

export async function sha256Hex(data: string | ArrayBuffer | Uint8Array): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', typeof data === 'string' ? encoder.encode(data) : data);
  return [...new Uint8Array(digest)].map((byte) => byte.toString(16).padStart(2, '0')).join('');
}

/** UUIDv4-shaped identifier derived from a seed, so retried jobs reuse the same IDs. */
export async function deterministicUuid(seed: string): Promise<string> {
  const hex = (await sha256Hex(seed)).slice(0, 32).split('');
  hex[12] = '4';
  hex[16] = ((parseInt(hex[16] ?? '0', 16) & 0x3) | 0x8).toString(16);
  const h = hex.join('');
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20, 32)}`;
}
