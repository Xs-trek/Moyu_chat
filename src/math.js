// TeX math in model replies, rendered by the bundled KaTeX (src/vendor/katex, MIT, loaded from local files only;
// strict CSP forbids remote resources). KaTeX is loaded the first time a reply contains math; until it is ready
// a formula shows as its TeX source and is rendered in place once the library arrives. KaTeX builds DOM nodes
// (no innerHTML here) and sets styles through the CSSOM, which the page's CSP allows.

const KATEX_BASE = './src/vendor/katex/';
const MAX_TEX_CHARS = 4000;
let loading = null;

function katex() { return globalThis.katex && typeof globalThis.katex.render === 'function' ? globalThis.katex : null; }

/** One formula: a span (inline) or div (display) that renders now, or as soon as KaTeX has loaded. */
export function mathNode(tex, display = false) {
  const node = document.createElement(display ? 'div' : 'span');
  node.className = display ? 'md-math md-math-display' : 'md-math';
  node.dataset.tex = tex;
  if (display) node.dataset.display = '1';
  if (!renderInto(node)) {
    node.dataset.pending = '1';
    node.textContent = display ? tex : `$${tex}$`;
    ensureKatex();
  }
  return node;
}

/** Renders the node's TeX with KaTeX; false when KaTeX is not available (yet). A TeX error shows the source. */
function renderInto(node) {
  const lib = katex();
  if (!lib) return false;
  const tex = String(node.dataset.tex || '');
  if (tex.length > MAX_TEX_CHARS) { node.textContent = tex; return true; }
  try {
    node.textContent = '';
    lib.render(tex, node, { displayMode: node.dataset.display === '1', throwOnError: false, trust: false, strict: 'ignore', maxSize: 20, maxExpand: 500, output: 'htmlAndMathml' });
  } catch {
    node.textContent = tex;
  }
  delete node.dataset.pending;
  return true;
}

/** Loads the local KaTeX script and stylesheet once, then renders every formula still waiting for it. */
export function ensureKatex() {
  if (katex() || loading) return loading;
  const head = typeof document !== 'undefined' ? document.head : null;
  if (!head || typeof document.createElement('script').addEventListener !== 'function') return null;
  const style = document.createElement('link');
  style.rel = 'stylesheet';
  style.href = `${KATEX_BASE}katex.min.css`;
  head.append(style);
  loading = new Promise((resolve) => {
    const script = document.createElement('script');
    script.src = `${KATEX_BASE}katex.min.js`;
    script.addEventListener('load', () => { document.querySelectorAll('.md-math[data-pending]').forEach(renderInto); resolve(true); });
    script.addEventListener('error', () => { loading = null; resolve(false); });
    head.append(script);
  });
  return loading;
}

/**
 * Inline math at `source[i]`: `$…$` or `\(…\)`. `$` opens only before a non-space and closes only after a
 * non-space and not before a digit, so prices like "$5 and $10" stay text. Returns { tex, end } or null.
 */
export function inlineMath(source, i) {
  if (source.startsWith('\\(', i)) {
    const close = source.indexOf('\\)', i + 2);
    return close > i + 2 ? { tex: source.slice(i + 2, close).trim(), end: close + 2 } : null;
  }
  if (source[i] !== '$' || source[i + 1] === '$') return null;
  if (i > 0 && source[i - 1] === '\\') return null;
  const first = source[i + 1];
  if (!first || /\s/.test(first)) return null;
  for (let j = i + 2; j < source.length; j += 1) {
    if (source[j] !== '$' || source[j - 1] === '\\') continue;
    if (/\s/.test(source[j - 1]) || /\d/.test(source[j + 1] || '')) return null;
    const tex = source.slice(i + 1, j);
    return tex.trim() ? { tex, end: j + 1 } : null;
  }
  return null;
}

/** A display-math block starting at `lines[index]` (`$$…$$` or `\[…\]`, one or more lines), or null when it never closes. */
export function displayMath(lines, index) {
  const first = lines[index].trim();
  const open = first.startsWith('$$') ? '$$' : first.startsWith('\\[') ? '\\[' : '';
  if (!open) return null;
  const close = open === '$$' ? '$$' : '\\]';
  const rest = first.slice(2);
  if (rest.trim().endsWith(close) && rest.trim().length >= close.length) {
    const tex = rest.trim().slice(0, -close.length).trim();
    return tex ? { tex, next: index + 1 } : null;
  }
  const body = rest.trim() ? [rest] : [];
  for (let i = index + 1; i < lines.length; i += 1) {
    const line = lines[i].trim();
    if (line.endsWith(close)) {
      body.push(line.slice(0, -close.length));
      const tex = body.join('\n').trim();
      return tex ? { tex, next: i + 1 } : null;
    }
    body.push(lines[i]);
  }
  return null;
}
