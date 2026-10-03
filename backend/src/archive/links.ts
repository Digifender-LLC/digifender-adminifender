import { type DefaultTreeAdapterMap, parse } from 'parse5';

type Node = DefaultTreeAdapterMap['node'];
type Element = DefaultTreeAdapterMap['element'];

export interface LinkFacts {
  /** Distinct http(s) link hosts. */
  hosts: Set<string>;
  /** Links whose visible text names a domain different from the actual href host. */
  mismatchedLiteralDomain: boolean;
}

const VISIBLE_DOMAIN = /^(?:https?:\/\/)?((?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\.)+[a-z]{2,})(?:[/:?#]\S*)?$/i;
const MAX_LINKS = 2000;

function hostOf(href: string): string | null {
  try {
    const url = new URL(href);
    return url.protocol === 'http:' || url.protocol === 'https:' ? url.hostname : null;
  } catch {
    return null;
  }
}

function textOf(node: Node, depth = 0): string {
  if (node.nodeName === '#text') return (node as DefaultTreeAdapterMap['textNode']).value;
  if (depth > 32 || !('childNodes' in node)) return '';
  return node.childNodes.map((child) => textOf(child, depth + 1)).join('');
}

/** Link facts for spam-rules-v1 from HTML anchors and plain-text URLs. */
export function linkFacts(html: string | undefined, text: string): LinkFacts {
  const hosts = new Set<string>();
  let mismatched = false;
  let seen = 0;
  for (const match of text.matchAll(/https?:\/\/[^\s<>()"']+/g)) {
    if (++seen > MAX_LINKS) break;
    const host = hostOf(match[0]);
    if (host) hosts.add(host);
  }
  if (html) {
    const stack: Node[] = [parse(html)];
    while (stack.length > 0 && seen <= MAX_LINKS) {
      const node = stack.pop() as Node;
      if (!('childNodes' in node)) continue;
      const element = node as Element;
      if (element.tagName === 'a') {
        seen += 1;
        const href = element.attrs.find((a) => a.name === 'href')?.value.trim() ?? '';
        const host = hostOf(href);
        if (host) {
          hosts.add(host);
          const visible = VISIBLE_DOMAIN.exec(textOf(element).trim())?.[1]?.toLowerCase();
          if (visible && visible !== host && !host.endsWith(`.${visible}`)) mismatched = true;
        }
      }
      stack.push(...element.childNodes);
    }
  }
  return { hosts, mismatchedLiteralDomain: mismatched };
}
