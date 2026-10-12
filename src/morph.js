// In-place DOM update. `morph(from, to)` makes the live node look like a freshly built one while keeping
// every node whose structure still matches, so running transitions, scroll offsets, focus, open <details>
// and user-toggled state survive a native view update instead of the whole page flashing.
//
// Matching: element children pair up by `data-key` / `data-item-key` when present, otherwise by tag in
// order (looking ahead, so an inserted or removed optional child does not cascade into rebuilds).
// `data-morph="own"` keeps an element's children (the timeline has its own keyed reconciler).
// Motion classes (`fx-*`) only play on nodes that are new: morph never adds them to a node that already
// exists, and keeps the ones still running. A removed node with `data-exit` plays `fx-exit-<name>` first.

const reduceMotion = () => Boolean(window.matchMedia?.('(prefers-reduced-motion: reduce)')?.matches);
export function motionAllowed() { return !reduceMotion(); }

function keyOf(node) {
  if (node.nodeType !== Node.ELEMENT_NODE) return null;
  return node.getAttribute('data-key') ?? node.getAttribute('data-item-key');
}

function sameKind(a, b) {
  return a.nodeType === b.nodeType && a.nodeName === b.nodeName && a.namespaceURI === b.namespaceURI && keyOf(a) === keyOf(b);
}

const leaving = (node) => node.nodeType === Node.ELEMENT_NODE && node.getAttribute('data-leaving') === '1';
const isFx = (name) => name.startsWith('fx-');

function syncClass(from, to) {
  const next = (to.getAttribute('class') || '').split(/\s+/).filter((name) => name && !isFx(name));
  const running = (from.getAttribute('class') || '').split(/\s+/).filter((name) => name && isFx(name));
  const value = next.concat(running).join(' ');
  if ((from.getAttribute('class') || '') === value) return;
  if (value) from.setAttribute('class', value); else from.removeAttribute('class');
}

// class: merged above; style: CSSOM only (the CSP blocks style attributes); open: the user's choice.
const OWN = new Set(['class', 'style', 'open', 'data-leaving']);

function syncAttributes(from, to) {
  for (const name of from.getAttributeNames()) if (!OWN.has(name) && !to.hasAttribute(name)) from.removeAttribute(name);
  for (const name of to.getAttributeNames()) {
    if (OWN.has(name)) continue;
    const value = to.getAttribute(name);
    if (from.getAttribute(name) !== value) from.setAttribute(name, value);
  }
  syncClass(from, to);
  if (from.style && from.style.cssText !== to.style.cssText) from.style.cssText = to.style.cssText;
}

function syncFormState(from, to) {
  if (from === document.activeElement) return;
  if ((from instanceof HTMLTextAreaElement || from instanceof HTMLInputElement || from instanceof HTMLSelectElement) && from.value !== to.value) from.value = to.value;
  if (from instanceof HTMLInputElement && from.checked !== to.checked) from.checked = to.checked;
}

/** Removes a node, letting it play its exit animation first when it declares one. */
export function removeNode(node) {
  const exit = node.nodeType === Node.ELEMENT_NODE ? node.getAttribute('data-exit') : null;
  if (!exit || !motionAllowed() || !node.isConnected) { node.remove(); return; }
  node.setAttribute('data-leaving', '1');
  node.classList.add(`fx-exit-${exit}`);
  setTimeout(() => node.remove(), 400);
}

function morphChildren(from, to) {
  const keyed = new Map();
  for (const child of from.childNodes) { const key = keyOf(child); if (key !== null && !leaving(child)) keyed.set(key, child); }
  const kept = new Set();
  let cursor = from.firstChild;
  for (let next = to.firstChild; next;) {
    const following = next.nextSibling;
    if (next.nodeType !== Node.ELEMENT_NODE && next.nodeType !== Node.TEXT_NODE) { next = following; continue; }
    let match = null;
    const key = keyOf(next);
    if (key !== null) {
      const candidate = keyed.get(key);
      if (candidate && !kept.has(candidate) && sameKind(candidate, next)) match = candidate;
    } else {
      for (let c = cursor; c; c = c.nextSibling) if (!kept.has(c) && !leaving(c) && keyOf(c) === null && sameKind(c, next)) { match = c; break; }
    }
    const node = match ? morph(match, next) : next;
    kept.add(node);
    if (node === cursor) cursor = cursor.nextSibling;
    else from.insertBefore(node, cursor);
    next = following;
  }
  for (const child of [...from.childNodes]) if (!kept.has(child) && !leaving(child)) removeNode(child);
}

/** Updates `from` to match `to`; returns the live node (a replacement only when the kinds differ). */
export function morph(from, to) {
  if (!sameKind(from, to)) { from.replaceWith(to); return to; }
  if (from.nodeType === Node.TEXT_NODE) { if (from.data !== to.data) from.data = to.data; return from; }
  syncAttributes(from, to);
  if (from.getAttribute('data-morph') !== 'own') morphChildren(from, to);
  syncFormState(from, to);
  return from;
}

/** Morphs the children of a live container into `children` (nodes, arrays or nulls). */
export function morphInto(parent, ...children) {
  const holder = document.createElement(parent.nodeName);
  for (const child of children.flat(Infinity)) if (child) holder.append(child);
  morphChildren(parent, holder);
}

/** Marks a freshly built node with a one-shot motion class; it is cleared when the animation ends. */
export function withFx(node, name) {
  if (node && motionAllowed()) node.classList.add(`fx-${name}`);
  return node;
}

function settle(event) {
  const node = event.target;
  if (!String(event.animationName || '').startsWith('fx-') || !(node instanceof Element)) return;
  if (leaving(node)) { node.remove(); return; }
  for (const name of [...node.classList]) if (isFx(name)) node.classList.remove(name);
}
document.addEventListener('animationend', settle, true);
document.addEventListener('animationcancel', settle, true);
