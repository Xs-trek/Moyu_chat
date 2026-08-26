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
  code.textContent = codeText; if (language) code.dataset.language = language; pre.append(code); block.append(head, pre); return block;
}

function renderTaskItem(raw) {
  const task = raw.match(/^\[([ xX])\]\s+(.*)$/);
  const li = document.createElement('li');
  if (!task) { li.append(inlineFragment(raw)); return li; }
  li.className = 'md-task-item';
  const checkbox = document.createElement('input'); checkbox.type = 'checkbox'; checkbox.disabled = true; checkbox.checked = task[1].toLowerCase() === 'x'; checkbox.setAttribute('aria-hidden', 'true');
  const text = document.createElement('span'); text.append(inlineFragment(task[2])); li.append(checkbox, text); return li;
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
  if (renderTable(lines, index)) return true;
  return false;
}

function paragraph(lines) {
  const p = document.createElement('p'); p.className = 'md-p';
  let previousHardBreak = false;
  lines.forEach((raw, index) => {
    let line = raw;
    const hardBreak = /(?: {2,}|\\)$/.test(line);
    if (hardBreak) line = line.replace(/(?: {2,}|\\)$/, '');
    if (index) p.append(previousHardBreak ? document.createElement('br') : textNode(' '));
    p.append(inlineFragment(line));
    previousHardBreak = hardBreak;
  });
  return p;
}

/**
 * Safe, local GFM-style renderer for model output. It never uses innerHTML, executes raw HTML,
 * loads remote Markdown images, or turns non-HTTPS targets into active links. Unsupported syntax
 * remains visible inert text. Streaming messages intentionally stay plain text until finalized.
 */
export function renderSafeMarkdown(value) {
  const root = document.createDocumentFragment();
  const lines = String(value ?? '').replace(/\r\n?/g, '\n').split('\n');
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
      const block = document.createElement('blockquote'); block.className = 'md-quote'; block.append(renderSafeMarkdown(quoteLines.join('\n'))); root.append(block); continue;
    }

    const listMatch = line.match(/^\s*((?:[-+*])|(\d+)[.)])\s+(.+)$/);
    if (listMatch) {
      const ordered = Boolean(listMatch[2]); const list = document.createElement(ordered ? 'ol' : 'ul'); list.className = 'md-list';
      if (ordered) list.start = Number(listMatch[2]) || 1;
      while (index < lines.length) {
        const current = lines[index].match(/^\s*((?:[-+*])|(\d+)[.)])\s+(.+)$/);
        if (!current || Boolean(current[2]) !== ordered) break;
        const item = renderTaskItem(current[3]); index += 1;
        const continuation = [];
        while (index < lines.length && /^\s{2,}\S/.test(lines[index]) && !/^\s*(?:[-+*]|\d+[.)])\s+/.test(lines[index])) continuation.push(lines[index++].trim());
        if (continuation.length) { item.append(document.createElement('br')); item.append(inlineFragment(continuation.join(' '))); }
        list.append(item);
      }
      root.append(list); continue;
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
