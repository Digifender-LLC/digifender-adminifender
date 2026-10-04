import { type DefaultTreeAdapterMap, parse } from 'parse5';

type Node = DefaultTreeAdapterMap['node'];
type Element = DefaultTreeAdapterMap['element'];

/** Elements whose content is never meaningful text. No scripts run and no remote resources are fetched. */
const SKIP = new Set(['head', 'script', 'style', 'template', 'noscript', 'svg', 'math', 'iframe', 'object', 'embed']);
const BLOCK = new Set([
  'address', 'article', 'aside', 'blockquote', 'body', 'caption', 'center', 'dd', 'details', 'dialog', 'div', 'dl', 'dt',
  'fieldset', 'figcaption', 'figure', 'footer', 'form', 'h1', 'h2', 'h3', 'h4', 'h5', 'h6', 'header', 'hr', 'html', 'li',
  'main', 'nav', 'ol', 'p', 'pre', 'section', 'summary', 'table', 'tbody', 'tfoot', 'thead', 'tr', 'ul',
]);
const SAFE_LINK = /^(https?:|mailto:)/i;
const MAX_DEPTH = 256;

function attr(element: Element, name: string): string | undefined {
  return element.attrs.find((a) => a.name === name)?.value;
}

function collapse(text: string): string {
  return text.replace(/[ \t\r\n\f ]+/g, ' ');
}

/**
 * Projects HTML to plain text (tdd.md#backend-and-contracts): paragraph and list boundaries become line breaks,
 * table cells stay on their row separated by " | ", and meaningful link targets are kept after the link text.
 */
export function htmlToText(html: string): string {
  const out: string[] = [];
  const newline = (count: 1 | 2) => {
    let trailing = 0;
    for (let i = out.length - 1; i >= 0 && out[i] === '\n'; i -= 1) trailing += 1;
    if (out.length === 0) return;
    for (; trailing < count; trailing += 1) out.push('\n');
  };
  const text = (value: string) => {
    if (!value) return;
    const last = out[out.length - 1];
    if ((last === undefined || last === '\n' || last.endsWith(' ')) && value.startsWith(' ')) value = value.slice(1);
    if (value) out.push(value);
  };

  const walk = (node: Node, pre: boolean, depth: number): void => {
    if (depth > MAX_DEPTH) return;
    if (node.nodeName === '#text') {
      const value = (node as DefaultTreeAdapterMap['textNode']).value;
      text(pre ? value : collapse(value));
      return;
    }
    if (!('childNodes' in node)) return;
    const element = node as Element;
    const tag = element.tagName ?? '';
    if (SKIP.has(tag)) return;
    if (tag === 'br') { out.push('\n'); return; }
    if (tag === 'img') {
      const alt = collapse(attr(element, 'alt') ?? '').trim();
      if (alt) text(`[${alt}]`);
      return;
    }
    const block = BLOCK.has(tag);
    if (tag === 'tr') newline(1);
    else if (block) newline(tag === 'li' || tag === 'dd' || tag === 'dt' ? 1 : 2);
    if (tag === 'li') text('- ');
    if ((tag === 'td' || tag === 'th') && out[out.length - 1] !== '\n' && out.length > 0) {
      const previous = element.parentNode && 'childNodes' in element.parentNode
        ? element.parentNode.childNodes.slice(0, element.parentNode.childNodes.indexOf(element)).some((c) => c.nodeName === 'td' || c.nodeName === 'th')
        : false;
      if (previous) text(' | ');
    }
    const children = tag === 'template' ? [] : element.childNodes;
    const startLength = out.length;
    for (const child of children) walk(child, pre || tag === 'pre', depth + 1);
    if (tag === 'a') {
      const href = attr(element, 'href')?.trim();
      const label = out.slice(startLength).join('').trim();
      if (href && SAFE_LINK.test(href) && href !== label && `mailto:${label}` !== href) text(` (${href})`);
    }
    if (tag === 'tr') newline(1);
    else if (block) newline(tag === 'li' || tag === 'dd' || tag === 'dt' ? 1 : 2);
  };

  walk(parse(html), false, 0);
  return out
    .join('')
    .split('\n')
    .map((line) => line.replace(/[ \t]+$/g, ''))
    .join('\n')
    .replace(/\n{3,}/g, '\n\n')
    .trim();
}
