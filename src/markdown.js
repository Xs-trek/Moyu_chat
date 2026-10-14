import { highlightInto } from './highlight.js';
import { displayMath, inlineMath, mathNode } from './math.js';

function textNode(value) { return document.createTextNode(String(value ?? '')); }

const ESCAPABLE = /[\\`*{}\[\]()#+\-.!_>~|]/;
const MAX_INLINE_DEPTH = 8;

function appendText(target, value) {
  if (!value) return;
  target.append(textNode(value));
}

function findClosing(source, marker, start) {
  let cursor = start;
  while (cursor < source.length) {
    const hit = source.indexOf(marker, cursor);
    if (hit < 0) return -1;
    let slashCount = 0;
    for (let i = hit - 1; i >= 0 && source[i] === '\\'; i -= 1) slashCount += 1;
    if (slashCount % 2 === 0) return hit;
    cursor = hit + marker.length;
  }
  return -1;
}

function safeHttps(url) {
  try {
    const parsed = new URL(url);
    return parsed.protocol === 'https:' && Boolean(parsed.hostname) ? parsed.href : '';
  } catch { return ''; }
}

function parseMarkdownLink(source, start) {
  if (source[start] !== '[') return null;
  const closeLabel = findClosing(source, ']', start + 1);
  if (closeLabel < 0 || source[closeLabel + 1] !== '(') return null;
  let cursor = closeLabel + 2;
  let depth = 0;
  let escaped = false;
  for (; cursor < source.length; cursor += 1) {
    const ch = source[cursor];
    if (escaped) { escaped = false; continue; }
    if (ch === '\\') { escaped = true; continue; }
    if (ch === '(') depth += 1;
    else if (ch === ')') {
      if (depth === 0) break;
      depth -= 1;
    }
  }
  if (cursor >= source.length) return null;
  const rawTarget = source.slice(closeLabel + 2, cursor).trim();
  const titleCut = rawTarget.search(/\s+["']/);
  const href = safeHttps(titleCut >= 0 ? rawTarget.slice(0, titleCut) : rawTarget);
  if (!href) return null;
  return { label: source.slice(start + 1, closeLabel), href, end: cursor + 1 };
}

function canUseUnderscore(source, index, markerLength) {
  const prev = index > 0 ? source[index - 1] : '';
  const next = source[index + markerLength] || '';
  return !(prev && next && /[\p{L}\p{N}]/u.test(prev) && /[\p{L}\p{N}]/u.test(next));
}

function inlineFragment(value, depth = 0) {
  const out = document.createDocumentFragment();
  const source = String(value ?? '');
  if (!source || depth > MAX_INLINE_DEPTH) { appendText(out, source); return out; }

  let plain = '';
  const flush = () => { appendText(out, plain); plain = ''; };
  let i = 0;
  while (i < source.length) {
    const ch = source[i];

    // Math before escapes: \( would otherwise be read as an escaped parenthesis.
    if (ch === '$' || (ch === '\\' && source[i + 1] === '(')) {
      if (source.startsWith('$$', i)) {
        const close = source.indexOf('$$', i + 2);
        if (close > i + 2 && source.slice(i + 2, close).trim()) { flush(); out.append(mathNode(source.slice(i + 2, close).trim(), true)); i = close + 2; continue; }
      } else {
        const math = inlineMath(source, i);
        if (math) { flush(); out.append(mathNode(math.tex, false)); i = math.end; continue; }
      }
    }

    if (ch === '\\' && i + 1 < source.length && ESCAPABLE.test(source[i + 1])) {
      plain += source[i + 1]; i += 2; continue;
    }

    if (ch === '`') {
      let run = 1;
      while (source[i + run] === '`') run += 1;
      const marker = '`'.repeat(run);
      const close = source.indexOf(marker, i + run);
      if (close >= 0) {
        flush();
        let raw = source.slice(i + run, close).replace(/\n/g, ' ');
        if (/^\s.*\s$/.test(raw) && !/^\s+$/.test(raw)) raw = raw.slice(1, -1);
        const code = document.createElement('code'); code.className = 'md-inline-code'; code.textContent = raw; out.append(code);
        i = close + run; continue;
      }
    }

    if (ch === '[') {
      const link = parseMarkdownLink(source, i);
      if (link) {
        flush();
        const control = document.createElement('button');
        control.type = 'button'; control.className = 'md-link'; control.dataset.action = 'open-source'; control.dataset.url = link.href;
        control.append(inlineFragment(link.label, depth + 1)); out.append(control);
        i = link.end; continue;
      }
    }

    if (ch === '<') {
      const close = source.indexOf('>', i + 1);
      if (close > i + 1) {
        const href = safeHttps(source.slice(i + 1, close));
        if (href) {
          flush();
          const control = document.createElement('button'); control.type = 'button'; control.className = 'md-link';
          control.dataset.action = 'open-source'; control.dataset.url = href; control.textContent = href; out.append(control);
          i = close + 1; continue;
        }
      }
    }

    const candidates = [
      ['**', 'strong'], ['__', 'strong'], ['~~', 'del'], ['*', 'em'], ['_', 'em'],
    ];
    let matched = false;
    for (const [marker, tag] of candidates) {
      if (!source.startsWith(marker, i)) continue;
      if (marker.includes('_') && !canUseUnderscore(source, i, marker.length)) continue;
      const close = findClosing(source, marker, i + marker.length);
      if (close <= i + marker.length) continue;
      if (marker.includes('_') && !canUseUnderscore(source, close, marker.length)) continue;
      flush();
      const node = document.createElement(tag); node.append(inlineFragment(source.slice(i + marker.length, close), depth + 1)); out.append(node);
      i = close + marker.length; matched = true; break;
    }
    if (matched) continue;

    plain += ch; i += 1;
  }
  flush();
  return out;
}

function splitTableRow(line) {
  let source = String(line ?? '').trim();
  if (source.startsWith('|')) source = source.slice(1);
  if (source.endsWith('|') && !source.endsWith('\\|')) source = source.slice(0, -1);
  const cells = [];
  let cell = '';
  let codeTicks = 0;
  let escaped = false;
  for (let i = 0; i < source.length; i += 1) {
    const ch = source[i];
    if (escaped) { cell += ch; escaped = false; continue; }
    if (ch === '\\') { escaped = true; cell += ch; continue; }
    if (ch === '`') { codeTicks = codeTicks ? 0 : 1; cell += ch; continue; }
    if (ch === '|' && !codeTicks) { cells.push(cell.trim()); cell = ''; continue; }
    cell += ch;
  }
  cells.push(cell.trim());
  return cells;
}

function tableDelimiter(line) {
  if (!String(line ?? '').includes('|')) return null;
  const cells = splitTableRow(line);
  if (!cells.length || cells.some((cell) => !/^:?-{3,}:?$/.test(cell.replace(/\s/g, '')))) return null;
  return cells.map((cell) => {
    const compact = cell.replace(/\s/g, '');
    return compact.startsWith(':') && compact.endsWith(':') ? 'center' : compact.endsWith(':') ? 'right' : 'left';
  });
}

function renderTable(lines, index) {
  if (index + 1 >= lines.length || !lines[index].includes('|')) return null;
  const align = tableDelimiter(lines[index + 1]);
  if (!align) return null;
  const headers = splitTableRow(lines[index]);
  if (!headers.length || headers.length !== align.length) return null;

  const table = document.createElement('table'); table.className = 'md-table';
  const head = document.createElement('thead'); const headRow = document.createElement('tr');
  headers.forEach((value, column) => {
    const th = document.createElement('th'); th.style.textAlign = align[column]; th.append(inlineFragment(value)); headRow.append(th);
  });
  head.append(headRow); table.append(head);
  const body = document.createElement('tbody');
  let cursor = index + 2;
  while (cursor < lines.length && lines[cursor].trim() && lines[cursor].includes('|')) {
    if (/^\s*(#{1,6})\s+/.test(lines[cursor]) || /^\s*```/.test(lines[cursor])) break;
    const values = splitTableRow(lines[cursor]);
    const row = document.createElement('tr');
    for (let column = 0; column < headers.length; column += 1) {
      const td = document.createElement('td'); td.style.textAlign = align[column]; td.append(inlineFragment(values[column] ?? '')); row.append(td);
    }
    body.append(row); cursor += 1;
  }
  if (body.childElementCount) table.append(body);
  const wrap = document.createElement('div'); wrap.className = 'md-table-wrap'; wrap.setAttribute('role', 'region'); wrap.setAttribute('aria-label', '表格'); wrap.tabIndex = 0; wrap.append(table);
  return { node: wrap, next: cursor };
}

function codeBlock(language, codeText) {
  const block = document.createElement('div'); block.className = 'md-code-block';
  const head = document.createElement('div'); head.className = 'md-code-head';
  const label = document.createElement('span'); label.className = 'md-code-language'; label.textContent = language || '代码';
  const copy = document.createElement('button'); copy.type = 'button'; copy.className = 'md-code-copy'; copy.dataset.action = 'copy-code'; copy.textContent = '复制';
  head.append(label, copy);
  const pre = document.createElement('pre'); pre.className = 'md-code'; const code = document.createElement('code');
  highlightInto(code, codeText, language); if (language) code.dataset.language = language; pre.append(code); block.append(head, pre); return block;
}

const MAX_BLOCK_DEPTH = 8;
/** A line opening display math: $$ or \[ (closed on the same or a later line). */
const MATH_BLOCK = /^ {0,3}(?:\$\$|\\\[)/;
const LIST_MARKER = /^([ \t]*)([-+*]|(\d{1,9})[.)])([ \t]+)(\S.*)$/;
const FENCE_OPEN = /^(`{3,}|~{3,})/;

/** Leading whitespace width in columns (a tab advances to the next multiple of 4). */
function indentOf(line) {
  let col = 0;
  for (const ch of String(line ?? '')) {
    if (ch === ' ') col += 1;
    else if (ch === '\t') col += 4 - (col % 4);
    else break;
  }
  return col;
}

/** Removes up to `count` columns of leading whitespace, splitting a tab when it straddles the cut. */
function stripColumns(line, count) {
  let col = 0;
  let i = 0;
  while (i < line.length && col < count) {
    if (line[i] === ' ') { col += 1; i += 1; continue; }
    if (line[i] !== '\t') break;
    const width = 4 - (col % 4);
    if (col + width > count) return ' '.repeat(col + width - count) + line.slice(i + 1);
    col += width; i += 1;
  }
  return line.slice(i);
}

function listMarker(line) {
  if (isThematicBreak(line)) return null;
  const match = String(line ?? '').match(LIST_MARKER);
  if (!match) return null;
  const indent = indentOf(match[1]);
  const markerEnd = indent + match[2].length;
  const gap = indentOf(' '.repeat(markerEnd) + match[4]) - markerEnd;
  return {
    indent, ordered: Boolean(match[3]), number: match[3] ? Number(match[3]) : 1, content: match[5],
    // A gap of five or more columns starts indented content; only one of its columns belongs to the marker.
    contentIndent: indent + match[2].length + (gap > 4 ? 1 : gap),
  };
}

function closesFence(line, fence) {
  const marker = line.trim().match(/^(`{3,}|~{3,})\s*$/);
  return Boolean(marker && marker[1][0] === fence[0] && marker[1].length >= fence.length);
}

/**
 * Collects one list item starting at `index`: the marker line, lines indented at least two columns past the
 * marker (nested lists, code fences, further paragraphs, also across blank lines), everything inside a fence
 * the item opened, and lazy paragraph continuation lines. Returns the dedented body and where the item ends.
 */
function collectListItem(lines, index, marker) {
  const base = marker.indent;
  const raw = [{ text: marker.content, kind: 'first' }];
  let fence = (marker.content.match(FENCE_OPEN) || [])[1] || '';
  let loose = false;
  let cursor = index + 1;
  while (cursor < lines.length) {
    const line = lines[cursor];
    if (fence) {
      raw.push({ text: line, kind: 'fenced' });
      if (closesFence(line, fence)) fence = '';
      cursor += 1; continue;
    }
    if (!line.trim()) {
      let next = cursor + 1;
      while (next < lines.length && !lines[next].trim()) next += 1;
      if (next >= lines.length || indentOf(lines[next]) < base + 2) break;
      for (; cursor < next; cursor += 1) raw.push({ text: '', kind: 'blank' });
      loose = true; continue;
    }
    if (indentOf(line) >= base + 2) {
      raw.push({ text: line, kind: 'indented' });
      fence = (line.trim().match(FENCE_OPEN) || [])[1] || '';
      cursor += 1; continue;
    }
    // Lazy continuation: an unindented line that simply continues the item's paragraph.
    const previous = raw[raw.length - 1];
    if (previous.kind === 'blank' || previous.kind === 'fenced' || isBlockStart(lines, cursor)) break;
    raw.push({ text: line, kind: 'lazy' });
    cursor += 1;
  }
  const indented = raw.filter((line) => line.kind === 'indented').map((line) => indentOf(line.text));
  const dedent = Math.min(marker.contentIndent, ...indented);
  const body = raw.map((line) => (line.kind === 'first' || line.kind === 'blank' ? line.text
    : line.kind === 'lazy' ? line.text.trimStart() : stripColumns(line.text, dedent)));
  return { body, next: cursor, loose };
}

function renderList(lines, index, depth) {
  const first = listMarker(lines[index]);
  const list = document.createElement(first.ordered ? 'ol' : 'ul'); list.className = 'md-list';
  if (first.ordered) list.start = first.number;
  const items = [];
  let loose = false;
  while (index < lines.length) {
    const marker = listMarker(lines[index]);
    if (!marker || marker.ordered !== first.ordered || marker.indent >= first.indent + 2) break;
    const item = collectListItem(lines, index, marker);
    items.push(item.body);
    loose ||= item.loose;
    index = item.next;
    let next = index;
    while (next < lines.length && !lines[next].trim()) next += 1;
    const sibling = next < lines.length ? listMarker(lines[next]) : null;
    if (!sibling || sibling.ordered !== first.ordered || sibling.indent >= first.indent + 2) break;
    loose ||= next > index;
    index = next;
  }
  for (const body of items) list.append(listItem(body, loose, depth));
  return { node: list, next: index };
}

function listItem(body, loose, depth) {
  const li = document.createElement('li');
  const task = body[0].match(/^\[([ xX])\]\s+(.*)$/);
  const lines = task ? [task[2], ...body.slice(1)] : body;
  const content = depth >= MAX_BLOCK_DEPTH ? paragraph(lines.filter((line) => line.trim())) : renderBlocks(lines, depth + 1);
  // A tight item shows its text inline rather than as a spaced paragraph.
  if (!loose) for (const node of [...content.childNodes]) if (node.classList?.contains('md-p')) node.replaceWith(...node.childNodes);
  if (!task) { li.append(content); return li; }
  li.className = 'md-task-item';
  const checkbox = document.createElement('input'); checkbox.type = 'checkbox'; checkbox.disabled = true; checkbox.checked = task[1].toLowerCase() === 'x'; checkbox.setAttribute('aria-hidden', 'true');
  const text = document.createElement('div'); text.className = 'md-task-body'; text.append(content); li.append(checkbox, text); return li;
}

function isThematicBreak(line) {
  const compact = String(line ?? '').trim().replace(/\s/g, '');
  return /^(?:\*{3,}|-{3,}|_{3,})$/.test(compact);
}

function isBlockStart(lines, index) {
  const line = lines[index] ?? '';
  if (!line.trim()) return true;
  if (/^ {0,3}(?:`{3,}|~{3,})/.test(line)) return true;
  if (/^ {0,3}#{1,6}(?:\s+|$)/.test(line)) return true;
  if (/^\s*>/.test(line)) return true;
  if (/^\s*(?:[-+*]|\d+[.)])\s+/.test(line)) return true;
  if (isThematicBreak(line)) return true;
  if (MATH_BLOCK.test(line) && displayMath(lines, index)) return true;
  if (renderTable(lines, index)) return true;
  return false;
}

/** Every newline inside a paragraph is a line break: chat replies use single newlines as intended breaks. */
function paragraph(lines) {
  const p = document.createElement('p'); p.className = 'md-p';
  lines.forEach((raw, index) => {
    if (index) p.append(document.createElement('br'));
    p.append(inlineFragment(raw.trimStart().replace(/(?: {2,}|\\)$/, '')));
  });
  return p;
}

/**
 * Safe, local GFM-style renderer for model output. It never uses innerHTML, executes raw HTML,
 * loads remote Markdown images, or turns non-HTTPS targets into active links. Unsupported syntax
 * remains visible inert text. Streaming replies go through renderMarkdownInto() below.
 */
export function renderSafeMarkdown(value) {
  return renderBlocks(String(value ?? '').replace(/\r\n?/g, '\n').split('\n'), 0);
}

function renderBlocks(lines, depth) {
  const root = document.createDocumentFragment();
  let index = 0;

  while (index < lines.length) {
    const line = lines[index];
    if (!line.trim()) { index += 1; continue; }

    const fence = line.match(/^ {0,3}(`{3,}|~{3,})\s*([^\s`~]*)[^\n]*$/);
    if (fence) {
      const markerChar = fence[1][0]; const markerLength = fence[1].length; const language = fence[2] || '';
      const codeLines = []; index += 1;
      while (index < lines.length && !new RegExp(`^ {0,3}${markerChar === '`' ? '`' : '~'}{${markerLength},}\\s*$`).test(lines[index])) codeLines.push(lines[index++]);
      if (index < lines.length) index += 1;
      root.append(codeBlock(language, codeLines.join('\n'))); continue;
    }

    if (MATH_BLOCK.test(line)) {
      const math = displayMath(lines, index);
      if (math) { root.append(mathNode(math.tex, true)); index = math.next; continue; }
    }

    const table = renderTable(lines, index);
    if (table) { root.append(table.node); index = table.next; continue; }

    const heading = line.match(/^ {0,3}(#{1,6})(?:\s+|$)(.*?)\s*#*\s*$/);
    if (heading) {
      const title = heading[2].trim(); const node = document.createElement(`h${heading[1].length}`); node.className = 'md-heading'; node.append(inlineFragment(title)); root.append(node); index += 1; continue;
    }

    if (index + 1 < lines.length && line.trim() && /^ {0,3}(?:=+|-+)\s*$/.test(lines[index + 1]) && !isThematicBreak(line)) {
      const node = document.createElement(lines[index + 1].trim().startsWith('=') ? 'h1' : 'h2'); node.className = 'md-heading'; node.append(inlineFragment(line.trim())); root.append(node); index += 2; continue;
    }

    if (isThematicBreak(line)) { const hr = document.createElement('hr'); hr.className = 'md-hr'; root.append(hr); index += 1; continue; }

    if (/^\s*>/.test(line)) {
      const quoteLines = [];
      while (index < lines.length && (/^\s*>/.test(lines[index]) || !lines[index].trim())) {
        if (!lines[index].trim()) quoteLines.push(''); else quoteLines.push(lines[index].replace(/^\s*>\s?/, ''));
        index += 1;
      }
      const block = document.createElement('blockquote'); block.className = 'md-quote';
      block.append(depth >= MAX_BLOCK_DEPTH ? paragraph(quoteLines.filter((quoted) => quoted.trim())) : renderBlocks(quoteLines, depth + 1));
      root.append(block); continue;
    }

    if (listMarker(line)) {
      const list = renderList(lines, index, depth);
      root.append(list.node); index = list.next; continue;
    }

    const paragraphLines = [line]; index += 1;
    while (index < lines.length && lines[index].trim() && !isBlockStart(lines, index)) {
      if (index + 1 < lines.length && /^ {0,3}(?:=+|-+)\s*$/.test(lines[index + 1])) break;
      paragraphLines.push(lines[index]); index += 1;
    }
    root.append(paragraph(paragraphLines));
  }
  return root;
}

/**
 * Offset just past the last blank line, outside a code fence, after which a new block surely starts: the next
 * line has text, is not indented and is not a list item (an indented line or a list item could still extend the
 * list before the blank line). Everything before it is a sequence of finished blocks; only the text after it can
 * still change shape as more tokens arrive. The last line is ignored because it may be incomplete.
 */
export function stableBoundary(source) {
  const lines = source.split('\n');
  let offset = 0;
  let fence = '';
  let boundary = 0;
  for (let i = 0; i < lines.length - 1; i += 1) {
    const line = lines[i];
    const marker = line.trim().match(FENCE_OPEN);
    if (fence) {
      if (closesFence(line, fence)) fence = '';
    } else if (marker) fence = marker[1];
    offset += line.length + 1;
    if (fence || line.trim()) continue;
    const next = lines[i + 1];
    const nextComplete = i + 1 < lines.length - 1;
    if (nextComplete && next.trim() && indentOf(next) < 2 && !LIST_MARKER.test(next)) boundary = offset;
  }
  return boundary;
}

/**
 * Renders Markdown into `container`, incrementally while streaming: finished blocks are rendered once and
 * kept, and only the growing tail is rebuilt on each patch. An unclosed fence in the tail already shows as a
 * code block. Finalizing with the same text keeps the DOM as is, so the reply never jumps at the end.
 */
export function renderMarkdownInto(container, text, streaming = false) {
  const source = String(text ?? '').replace(/\r\n?/g, '\n');
  let state = container.__md;
  if (!streaming) {
    if (state && state.text === source) { state.final = true; return; }
    container.replaceChildren(renderSafeMarkdown(source));
    container.__md = { text: source, stable: source.length, tail: null, tailText: '', final: true };
    return;
  }
  if (!state || state.final || !source.startsWith(state.text.slice(0, state.stable))) {
    container.replaceChildren();
    state = { text: '', stable: 0, tail: null, tailText: null, final: false };
  }
  const boundary = stableBoundary(source);
  if (boundary > state.stable) {
    const finished = renderSafeMarkdown(source.slice(state.stable, boundary));
    if (state.tail) state.tail.before(finished); else container.append(finished);
    state.stable = boundary;
  }
  const tailText = source.slice(state.stable);
  if (tailText !== state.tailText) {
    const tail = document.createElement('div');
    tail.className = 'md-tail';
    tail.append(renderSafeMarkdown(tailText));
    if (state.tail) state.tail.replaceWith(tail); else container.append(tail);
    state.tail = tail;
    state.tailText = tailText;
  }
  state.text = source;
  container.__md = state;
}
