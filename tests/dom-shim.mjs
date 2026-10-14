// Minimal DOM for frontend behaviour tests under `node --test`. It covers what src/ modules touch when
// building and patching nodes (tree edits, classes, data attributes, simple selectors), not layout or events
// beyond plain listeners. Install it before importing a module that reads `document` at load time.

const ELEMENT_NODE = 1;
const TEXT_NODE = 3;
const DOCUMENT_FRAGMENT_NODE = 11;

class Node {
  constructor(nodeType) { this.nodeType = nodeType; this.parentNode = null; this.childNodes = []; this.listeners = new Map(); }
  get firstChild() { return this.childNodes[0] ?? null; }
  get lastChild() { return this.childNodes[this.childNodes.length - 1] ?? null; }
  get nextSibling() { const siblings = this.parentNode?.childNodes; return siblings ? siblings[siblings.indexOf(this) + 1] ?? null : null; }
  get previousSibling() { const siblings = this.parentNode?.childNodes; return siblings ? siblings[siblings.indexOf(this) - 1] ?? null : null; }
  get parentElement() { return this.parentNode?.nodeType === ELEMENT_NODE ? this.parentNode : null; }
  get isConnected() { let node = this; while (node.parentNode) node = node.parentNode; return node === globalThis.document; }
  get textContent() { return this.childNodes.map((child) => child.textContent).join(''); }
  set textContent(value) { this.replaceChildren(); if (value !== '' && value != null) this.appendChild(new Text(String(value))); }

  static #nodes(items) {
    const out = [];
    for (const item of items) {
      if (item == null) continue;
      if (item instanceof Node && item.nodeType === DOCUMENT_FRAGMENT_NODE) out.push(...item.childNodes);
      else out.push(item instanceof Node ? item : new Text(String(item)));
    }
    return out;
  }

  insertBefore(node, reference) {
    const nodes = Node.#nodes([node]);
    for (const child of nodes) {
      child.parentNode?.removeChild(child);
      const at = reference ? this.childNodes.indexOf(reference) : -1;
      if (at < 0) this.childNodes.push(child); else this.childNodes.splice(at, 0, child);
      child.parentNode = this;
    }
    return node;
  }
  appendChild(node) { return this.insertBefore(node, null); }
  removeChild(node) {
    const at = this.childNodes.indexOf(node);
    if (at >= 0) { this.childNodes.splice(at, 1); node.parentNode = null; }
    return node;
  }
  append(...items) { for (const node of Node.#nodes(items)) this.insertBefore(node, null); }
  prepend(...items) { const first = this.firstChild; for (const node of Node.#nodes(items)) this.insertBefore(node, first); }
  replaceChildren(...items) { for (const child of [...this.childNodes]) this.removeChild(child); this.append(...items); }
  remove() { this.parentNode?.removeChild(this); }
  before(...items) { const parent = this.parentNode; if (parent) for (const node of Node.#nodes(items)) parent.insertBefore(node, this); }
  after(...items) {
    const parent = this.parentNode; if (!parent) return;
    const next = this.nextSibling;
    for (const node of Node.#nodes(items)) parent.insertBefore(node, next);
  }
  replaceWith(...items) {
    const parent = this.parentNode; if (!parent) return;
    const nodes = Node.#nodes(items).filter((node) => node !== this);
    for (const node of nodes) parent.insertBefore(node, this);
    if (this.parentNode === parent) parent.removeChild(this);
  }
  contains(node) { for (let cursor = node; cursor; cursor = cursor.parentNode) if (cursor === this) return true; return false; }
  addEventListener(type, listener) { if (!this.listeners.has(type)) this.listeners.set(type, []); this.listeners.get(type).push(listener); }
  removeEventListener(type, listener) { const list = this.listeners.get(type); if (list) list.splice(list.indexOf(listener) >>> 0, 1); }
  dispatchEvent(event) { event.target ??= this; for (const listener of this.listeners.get(event.type) || []) listener.call(this, event); return true; }
}

class Text extends Node {
  constructor(data = '') { super(TEXT_NODE); this.data = data; }
  get textContent() { return this.data; }
  set textContent(value) { this.data = String(value ?? ''); }
  get nodeValue() { return this.data; }
}

class DocumentFragment extends Node {
  constructor() { super(DOCUMENT_FRAGMENT_NODE); }
  get children() { return this.childNodes.filter((node) => node.nodeType === ELEMENT_NODE); }
  querySelector(selector) { return this.querySelectorAll(selector)[0] ?? null; }
  querySelectorAll(selector) { return queryAll(this, selector); }
}

class ClassList {
  constructor(element) { this.element = element; }
  #tokens() { return this.element.className.split(/\s+/).filter(Boolean); }
  contains(token) { return this.#tokens().includes(token); }
  add(...tokens) { const set = new Set(this.#tokens()); tokens.forEach((token) => set.add(token)); this.element.className = [...set].join(' '); }
  remove(...tokens) { this.element.className = this.#tokens().filter((token) => !tokens.includes(token)).join(' '); }
  toggle(token, force) {
    const on = force === undefined ? !this.contains(token) : Boolean(force);
    if (on) this.add(token); else this.remove(token);
    return on;
  }
  get length() { return this.#tokens().length; }
  [Symbol.iterator]() { return this.#tokens()[Symbol.iterator](); }
}

const camelToData = (key) => `data-${key.replace(/[A-Z]/g, (ch) => `-${ch.toLowerCase()}`)}`;
const dataToCamel = (name) => name.slice(5).replace(/-([a-z])/g, (_, ch) => ch.toUpperCase());

class Element extends Node {
  constructor(tagName) {
    super(ELEMENT_NODE);
    this.tagName = tagName.toUpperCase();
    this.attributes = new Map();
    this.style = {};
    this.classList = new ClassList(this);
    this.dataset = new Proxy({}, {
      get: (_, key) => (typeof key === 'string' ? this.attributes.get(camelToData(key)) : undefined),
      set: (_, key, value) => { this.attributes.set(camelToData(key), String(value)); return true; },
      deleteProperty: (_, key) => { this.attributes.delete(camelToData(key)); return true; },
      has: (_, key) => this.attributes.has(camelToData(key)),
      ownKeys: () => [...this.attributes.keys()].filter((name) => name.startsWith('data-')).map(dataToCamel),
      getOwnPropertyDescriptor: (_, key) => (this.attributes.has(camelToData(key))
        ? { enumerable: true, configurable: true, value: this.attributes.get(camelToData(key)) } : undefined),
    });
  }
  get nodeName() { return this.tagName; }
  get localName() { return this.tagName.toLowerCase(); }
  get className() { return this.attributes.get('class') ?? ''; }
  set className(value) { this.attributes.set('class', String(value)); }
  get id() { return this.attributes.get('id') ?? ''; }
  set id(value) { this.attributes.set('id', String(value)); }
  get children() { return this.childNodes.filter((node) => node.nodeType === ELEMENT_NODE); }
  get childElementCount() { return this.children.length; }
  get firstElementChild() { return this.children[0] ?? null; }
  get lastElementChild() { const children = this.children; return children[children.length - 1] ?? null; }
  get nextElementSibling() { let node = this.nextSibling; while (node && node.nodeType !== ELEMENT_NODE) node = node.nextSibling; return node; }
  get previousElementSibling() { let node = this.previousSibling; while (node && node.nodeType !== ELEMENT_NODE) node = node.previousSibling; return node; }
  setAttribute(name, value) { this.attributes.set(String(name).toLowerCase(), String(value)); }
  getAttribute(name) { return this.attributes.get(String(name).toLowerCase()) ?? null; }
  hasAttribute(name) { return this.attributes.has(String(name).toLowerCase()); }
  removeAttribute(name) { this.attributes.delete(String(name).toLowerCase()); }
  toggleAttribute(name, force) {
    const on = force === undefined ? !this.hasAttribute(name) : Boolean(force);
    if (on) this.setAttribute(name, ''); else this.removeAttribute(name);
    return on;
  }
  matches(selector) { return selector.split(',').some((part) => matchesComplex(this, part.trim())); }
  closest(selector) { for (let node = this; node && node.nodeType === ELEMENT_NODE; node = node.parentNode) if (node.matches(selector)) return node; return null; }
  querySelector(selector) { return this.querySelectorAll(selector)[0] ?? null; }
  querySelectorAll(selector) { return queryAll(this, selector); }
  getBoundingClientRect() { return { top: 0, left: 0, right: 0, bottom: 0, width: 0, height: 0 }; }
  focus() { globalThis.document.activeElement = this; }
  blur() { if (globalThis.document.activeElement === this) globalThis.document.activeElement = globalThis.document.body; }
  animate() { return { finished: Promise.resolve(), cancel() {}, onfinish: null }; }
  getAnimations() { return []; }
  scrollIntoView() {}
  get offsetHeight() { return 0; }
  get scrollHeight() { return 0; }
  get clientHeight() { return 0; }
  /** Compact markup for assertions: tag, class and data-* attributes, then children. */
  get outerHTML() {
    const attrs = [...this.attributes].map(([name, value]) => ` ${name}="${value}"`).join('');
    return `<${this.localName}${attrs}>${this.childNodes.map((node) => (node.nodeType === TEXT_NODE ? node.data : node.outerHTML)).join('')}</${this.localName}>`;
  }
}

// Reflected properties that src/ sets directly.
for (const name of ['type', 'name', 'title', 'placeholder', 'rel', 'role']) {
  Object.defineProperty(Element.prototype, name, {
    get() { return this.attributes.get(name) ?? ''; },
    set(value) { this.attributes.set(name, String(value)); },
    configurable: true,
  });
}
for (const name of ['disabled', 'checked', 'hidden', 'open']) {
  Object.defineProperty(Element.prototype, name, {
    get() { return this.attributes.has(name); },
    set(value) { if (value) this.attributes.set(name, ''); else this.attributes.delete(name); },
    configurable: true,
  });
}
Object.defineProperty(Element.prototype, 'value', {
  get() { return this._value ?? this.attributes.get('value') ?? ''; },
  set(value) { this._value = String(value ?? ''); },
  configurable: true,
});
Object.defineProperty(Element.prototype, 'start', {
  get() { return Number(this.attributes.get('start') ?? 1); },
  set(value) { this.attributes.set('start', String(value)); },
  configurable: true,
});
Object.defineProperty(Element.prototype, 'tabIndex', {
  get() { return Number(this.attributes.get('tabindex') ?? -1); },
  set(value) { this.attributes.set('tabindex', String(value)); },
  configurable: true,
});

// ---------- selectors: compound selectors joined by descendant (space) or child (>) combinators ----------

function parseCompound(source) {
  const parts = { tag: '', ids: [], classes: [], attrs: [], not: [] };
  const pattern = /([a-zA-Z][\w-]*)|#([\w-]+)|\.([\w-]+)|\[([\w-]+)(?:([~^$*|]?=)"?([^"\]]*)"?)?\]|:not\(([^)]*)\)|:([\w-]+)/g;
  let match;
  let consumed = 0;
  while ((match = pattern.exec(source))) {
    if (match.index !== consumed) throw new Error(`unsupported selector: ${source}`);
    consumed = pattern.lastIndex;
    if (match[1]) parts.tag = match[1].toUpperCase();
    else if (match[2]) parts.ids.push(match[2]);
    else if (match[3]) parts.classes.push(match[3]);
    else if (match[4]) parts.attrs.push({ name: match[4].toLowerCase(), op: match[5] || '', value: match[6] ?? '' });
    else if (match[7] !== undefined) parts.not.push(match[7]);
    else if (match[8]) throw new Error(`unsupported pseudo-class :${match[8]}`);
  }
  if (consumed !== source.length) throw new Error(`unsupported selector: ${source}`);
  return parts;
}

function matchesCompound(element, compound) {
  const parts = parseCompound(compound);
  if (parts.tag && element.tagName !== parts.tag) return false;
  if (parts.ids.some((id) => element.id !== id)) return false;
  if (parts.classes.some((name) => !element.classList.contains(name))) return false;
  for (const { name, op, value } of parts.attrs) {
    const actual = element.getAttribute(name);
    if (actual === null) return false;
    if (op === '=' && actual !== value) return false;
    if (op === '~=' && !actual.split(/\s+/).includes(value)) return false;
    if (op === '^=' && !actual.startsWith(value)) return false;
    if (op === '$=' && !actual.endsWith(value)) return false;
    if (op === '*=' && !actual.includes(value)) return false;
  }
  return !parts.not.some((inner) => element.matches(inner));
}

function matchesComplex(element, selector) {
  const tokens = selector.replace(/\s*>\s*/g, ' > ').trim().split(/\s+/);
  const match = (node, at) => {
    if (!matchesCompound(node, tokens[at])) return false;
    if (at === 0) return true;
    if (tokens[at - 1] === '>') { const parent = node.parentElement; return Boolean(parent) && match(parent, at - 2); }
    for (let parent = node.parentElement; parent; parent = parent.parentElement) if (match(parent, at - 1)) return true;
    return false;
  };
  return match(element, tokens.length - 1);
}

function queryAll(root, selector) {
  const out = [];
  const walk = (node) => {
    for (const child of node.childNodes) {
      if (child.nodeType !== ELEMENT_NODE) continue;
      if (child.matches(selector)) out.push(child);
      walk(child);
    }
  };
  walk(root);
  return out;
}

class Document extends Node {
  constructor() {
    super(9);
    this.documentElement = new Element('html');
    this.head = new Element('head');
    this.body = new Element('body');
    this.documentElement.append(this.head, this.body);
    this.appendChild(this.documentElement);
    this.activeElement = this.body;
  }
  createElement(tag) { return new Element(tag); }
  createElementNS(_ns, tag) { return new Element(tag); }
  createTextNode(value) { return new Text(String(value)); }
  createDocumentFragment() { return new DocumentFragment(); }
  querySelector(selector) { return this.documentElement.matches(selector) ? this.documentElement : this.documentElement.querySelector(selector); }
  querySelectorAll(selector) { return queryAll(this, selector); }
  getElementById(id) { return this.querySelector(`#${id}`); }
}

/** Installs a fresh document and the few window globals src/ reads. Returns the document. */
export function installDom() {
  const document = new Document();
  const frames = [];
  Object.assign(globalThis, {
    document,
    Node,
    Element,
    HTMLElement: Element,
    Text,
    DocumentFragment,
    window: globalThis,
    requestAnimationFrame: (callback) => { frames.push(callback); return frames.length; },
    cancelAnimationFrame: () => {},
    matchMedia: () => ({ matches: false, addEventListener() {}, removeEventListener() {} }),
    getComputedStyle: () => ({ getPropertyValue: () => '' }),
    CustomEvent: class CustomEvent { constructor(type, init = {}) { this.type = type; this.detail = init.detail; } },
  });
  globalThis.addEventListener ??= () => {};
  globalThis.dispatchEvent ??= () => true;
  /** Runs queued animation frames (and any they queue) synchronously. */
  globalThis.flushAnimationFrames = () => { while (frames.length) frames.shift()(performance.now()); };
  return document;
}
