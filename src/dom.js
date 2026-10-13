// Tiny DOM helpers shared by every renderer. No innerHTML: all text reaches the DOM as text nodes.
const SVG_NS = 'http://www.w3.org/2000/svg';

export function h(tag, attrs = {}, ...children) {
  const node = document.createElement(tag);
  for (const [key, value] of Object.entries(attrs)) {
    if (value === undefined || value === null || value === false) continue;
    if (key === 'class') node.className = value;
    else if (key === 'text') node.textContent = String(value);
    else if (key === 'disabled') node.disabled = Boolean(value);
    else if (key === 'value') node.value = value;
    else if (key === 'dataset') for (const [name, item] of Object.entries(value)) { if (item !== undefined && item !== null) node.dataset[name] = String(item); }
    else node.setAttribute(key, String(value));
  }
  for (const child of children.flat(Infinity)) if (child !== null && child !== undefined && child !== false) node.append(child instanceof Node ? child : document.createTextNode(String(child)));
  return node;
}

export function svg(tag, attrs = {}, ...children) {
  const node = document.createElementNS(SVG_NS, tag);
  for (const [key, value] of Object.entries(attrs)) if (value !== undefined && value !== null) node.setAttribute(key, String(value));
  for (const child of children.flat(Infinity)) if (child) node.append(child);
  return node;
}

// 24×24 stroke icons. Paths only; colour comes from currentColor.
const ICONS = {
  menu: ['M4 7h16', 'M4 12h16', 'M4 17h10'],
  more: ['M5 12h.01', 'M12 12h.01', 'M19 12h.01'],
  plus: ['M12 5v14', 'M5 12h14'],
  send: ['M12 19V5', 'M6 11l6-6 6 6'],
  down: ['M12 5v14', 'M6 13l6 6 6-6'],
  copy: ['M9 9h10v10H9z', 'M15 9V5H5v10h4'],
  check: ['M5 12.5l4.5 4.5L19 7.5'],
  chevronDown: ['M6 9l6 6 6-6'],
  chevronUp: ['M6 15l6-6 6 6'],
  close: ['M6 6l12 12', 'M18 6L6 18'],
  back: ['M15 6l-6 6 6 6'],
  branch: ['M7 4v10', 'M7 14a4 4 0 0 0 4 4h2', 'M17 6a2 2 0 1 0 0 .01', 'M17 8v2a4 4 0 0 1-4 4H7'],
  edit: ['M4 20h4L19 9l-4-4L4 16z', 'M13 7l4 4'],
  undo: ['M9 7L4 12l5 5', 'M4 12h11a5 5 0 0 1 0 10h-3'],
  trash: ['M5 7h14', 'M9 7V4h6v3', 'M7 7l1 13h8l1-13'],
  rename: ['M4 6h10', 'M4 12h7', 'M14 18l6-6-3-3-6 6v3z'],
  eye: ['M2 12s3.5-6 10-6 10 6 10 6-3.5 6-10 6S2 12 2 12z', 'M12 9.5a2.5 2.5 0 1 0 0 5 2.5 2.5 0 0 0 0-5z'],
  sliders: ['M5 7h14', 'M5 12h14', 'M5 17h9', 'M17 15v4'],
  gear: ['M12 9a3 3 0 1 0 0 6 3 3 0 0 0 0-6z', 'M12 3v2.5', 'M12 18.5V21', 'M3 12h2.5', 'M18.5 12H21', 'M5.6 5.6l1.8 1.8', 'M16.6 16.6l1.8 1.8', 'M5.6 18.4l1.8-1.8', 'M16.6 7.4l1.8-1.8'],
  image: ['M4 5h16v14H4z', 'M4 16l5-5 4 4 2-2 5 5', 'M15 9h.01'],
  stop: [],
  list: ['M8 6h12', 'M8 12h12', 'M8 18h12', 'M4 6h.01', 'M4 12h.01', 'M4 18h.01'],
  chart: ['M4 20h16', 'M7 16v-5', 'M12 16V7', 'M17 16v-8'],
  thumbUp: ['M7 11v9H4v-9z', 'M7 11l4-7a2 2 0 0 1 2 2v3h5a2 2 0 0 1 2 2.3l-1.2 6A2 2 0 0 1 16.8 20H7'],
  thumbDown: ['M7 13V4H4v9z', 'M7 13l4 7a2 2 0 0 0 2-2v-3h5a2 2 0 0 0 2-2.3l-1.2-6A2 2 0 0 0 16.8 4H7'],
  compare: ['M4 5h7v14H4z', 'M13 5h7v14h-7z'],
  retry: ['M20 12a8 8 0 1 1-2.3-5.7', 'M20 4v5h-5'],
};

export function icon(name, cls = 'ico') {
  const node = svg('svg', { class: `${cls} ico-${name}`, viewBox: '0 0 24 24', 'aria-hidden': 'true' });
  if (name === 'stop') node.append(svg('rect', { x: 7, y: 7, width: 10, height: 10, rx: 1.5, class: 'ico-fill' }));
  for (const d of ICONS[name] || []) node.append(svg('path', { d }));
  return node;
}

export function iconButton(name, label, action, data = {}, cls = 'icon-btn') {
  return h('button', { type: 'button', class: cls, 'aria-label': label, title: label, dataset: { action, ...data } }, icon(name));
}

export function button(text, action, data = {}, cls = 'btn') {
  return h('button', { type: 'button', class: cls, dataset: { action, ...data } }, text);
}

/**
 * Segmented controls are equal-width columns with one sliding pill (::before) positioned by --seg-n /
 * --seg-i. Call after building or after changing which button is on.
 */
export function syncSegment(node) {
  if (!node) return node;
  const items = [...node.children].filter((child) => child.tagName === 'BUTTON');
  const index = items.findIndex((child) => child.classList.contains('on') || child.getAttribute('aria-checked') === 'true' || child.getAttribute('aria-selected') === 'true');
  node.style.setProperty('--seg-n', String(Math.max(1, items.length)));
  node.style.setProperty('--seg-i', String(Math.max(0, index)));
  node.classList.toggle('has-pill', index >= 0);
  return node;
}

export function fmtTime(value) {
  try { return new Intl.DateTimeFormat('zh-CN', { hour: '2-digit', minute: '2-digit' }).format(new Date(value)); } catch { return ''; }
}

