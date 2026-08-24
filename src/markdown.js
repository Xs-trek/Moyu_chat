function textNode(value) { return document.createTextNode(String(value ?? '')); }

function inlineFragment(text) {
  const out = document.createDocumentFragment();
  const source = String(text ?? '');
  const pattern = /(\[[^\]\n]+\]\(https:\/\/[^\s)]+\)|`[^`\n]+`|\*\*[^*\n]+\*\*|__[^_\n]+__|\*[^*\n]+\*|_[^_\n]+_)/g;
  let cursor = 0;
  for (const match of source.matchAll(pattern)) {
    if (match.index > cursor) out.append(textNode(source.slice(cursor, match.index)));
    const token = match[0];
    if (token.startsWith('`')) {
      const code = document.createElement('code'); code.textContent = token.slice(1, -1); out.append(code);
    } else if (token.startsWith('**') || token.startsWith('__')) {
      const strong = document.createElement('strong'); strong.textContent = token.slice(2, -2); out.append(strong);
    } else {
      const em = document.createElement('em'); em.textContent = token.slice(1, -1); out.append(em);
    }
    cursor = match.index + token.length;
  }
  if (cursor < source.length) out.append(textNode(source.slice(cursor)));
  return out;
}

function paragraph(lines) {
  const p = document.createElement('p');
  p.className = 'md-p';
  lines.forEach((line, index) => { if (index) p.append(document.createElement('br')); p.append(inlineFragment(line)); });
  return p;
}

/**
 * Tiny local Markdown reader for model output. It never evaluates raw HTML and never assigns model
 * text to innerHTML, so unsupported markup stays inert text. Streaming rows use plain text instead.
 */
export function renderSafeMarkdown(value) {
  const root = document.createDocumentFragment();
  const lines = String(value ?? '').replace(/\r\n?/g, '\n').split('\n');
  let paragraphLines = [];
  let fence = null;
  let fenceLines = [];
  let list = null;
  let listType = '';

  const flushParagraph = () => {
    if (!paragraphLines.length) return;
    root.append(paragraph(paragraphLines)); paragraphLines = [];
  };
  const flushList = () => { if (list) root.append(list); list = null; listType = ''; };
  const flushFence = () => {
    if (!fence) return;
    const pre = document.createElement('pre'); pre.className = 'md-code';
    const code = document.createElement('code'); code.textContent = fenceLines.join('\n');
    if (fence.language) code.dataset.language = fence.language;
    pre.append(code); root.append(pre); fence = null; fenceLines = [];
  };

  for (const line of lines) {
    const fenceMatch = line.match(/^\s*```\s*([^\s`]*)\s*$/);
    if (fence) {
      if (fenceMatch) flushFence(); else fenceLines.push(line);
      continue;
    }
    if (fenceMatch) {
      flushParagraph(); flushList();
      fence = { language: fenceMatch[1] || '' }; fenceLines = []; continue;
    }
    if (!line.trim()) { flushParagraph(); flushList(); continue; }

    const heading = line.match(/^\s*(#{1,6})\s+(.+)$/);
    if (heading) {
      flushParagraph(); flushList();
      const h = document.createElement(`h${heading[1].length}`); h.className = 'md-heading'; h.append(inlineFragment(heading[2])); root.append(h); continue;
    }
    const quote = line.match(/^\s*>\s?(.*)$/);
    if (quote) {
      flushParagraph(); flushList();
      const block = document.createElement('blockquote'); block.className = 'md-quote'; block.append(inlineFragment(quote[1])); root.append(block); continue;
    }
    const unordered = line.match(/^\s*[-+*]\s+(.+)$/);
    const ordered = line.match(/^\s*\d+[.)]\s+(.+)$/);
    if (unordered || ordered) {
      flushParagraph();
      const nextType = ordered ? 'ol' : 'ul';
      if (!list || listType !== nextType) { flushList(); listType = nextType; list = document.createElement(nextType); list.className = 'md-list'; }
      const li = document.createElement('li'); li.append(inlineFragment((ordered || unordered)[1])); list.append(li); continue;
    }
    flushList(); paragraphLines.push(line);
  }
  flushParagraph(); flushList(); flushFence();
  return root;
}
