/** Address parsing for admission (lld.md#ingestion step 1). Returns null for anything malformed or ambiguous. */

export interface ParsedAddress {
  /** Local part preserved exactly. */
  local: string;
  /** Lowercase IDNA ASCII domain. */
  domain: string;
}

const MAX_HEADER = 2048;

/** Lowercase IDNA ASCII form of an exact domain, or null. Wildcards and IP literals are rejected. */
export function normalizeDomain(raw: string): string | null {
  const value = raw.trim().replace(/\.$/, '');
  if (!value || value.length > 253 || /[\s/\\?#@*[\]:]/.test(value)) return null;
  let host: string;
  try {
    host = new URL(`http://${value}`).hostname;
  } catch {
    return null;
  }
  if (!host.includes('.') || /^[\d.]+$/.test(host)) return null;
  if (!host.split('.').every((label) => /^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$/.test(label))) return null;
  return host;
}

/** Parses one bare addr-spec, `local@domain`. */
export function parseAddrSpec(raw: string): ParsedAddress | null {
  const value = raw.trim();
  const at = value.lastIndexOf('@');
  if (at <= 0 || at === value.length - 1 || value.length > 320) return null;
  const local = value.slice(0, at);
  if (local.length > 64 || /[\s<>,;]/.test(local)) return null;
  if (local.includes('@') && !/^".*"$/.test(local)) return null;
  const domain = normalizeDomain(value.slice(at + 1));
  return domain ? { local, domain } : null;
}

/**
 * Parses a From header that must name exactly one mailbox: `addr`, `<addr>` or `Display <addr>`.
 * Group syntax, several mailboxes, comments and unbalanced quoting all return null.
 */
export function parseSingleMailbox(header: string | null): ParsedAddress | null {
  if (header === null || header.length > MAX_HEADER) return null;
  let quoted = false;
  let angle = -1;
  let close = -1;
  for (let i = 0; i < header.length; i += 1) {
    const ch = header[i];
    if (ch === '\\' && quoted) { i += 1; continue; }
    if (ch === '"') quoted = !quoted;
    else if (!quoted && (ch === ',' || ch === ';' || ch === ':' || ch === '(')) return null;
    else if (!quoted && ch === '<') { if (angle !== -1) return null; angle = i; }
    else if (!quoted && ch === '>') { if (angle === -1 || close !== -1) return null; close = i; }
  }
  if (quoted) return null;
  if (angle === -1 && close === -1) return parseAddrSpec(header);
  if (angle === -1 || close === -1 || header.slice(close + 1).trim() !== '') return null;
  return parseAddrSpec(header.slice(angle + 1, close));
}

export function formatAddress(address: ParsedAddress): string {
  return `${address.local}@${address.domain}`;
}
