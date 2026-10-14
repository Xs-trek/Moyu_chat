import { test } from 'node:test';
import assert from 'node:assert/strict';
import { installDom } from './dom-shim.mjs';

installDom();
const { renderSafeMarkdown, renderMarkdownInto, stableBoundary } = await import('../src/markdown.js');

function render(source) {
  const root = document.createElement('div');
  root.append(renderSafeMarkdown(source));
  return root;
}

const tags = (node) => node.children.map((child) => child.localName);

test('a code fence indented under a list item stays a code block inside the item', () => {
  const root = render('1. 安装：\n   ```bash\n   npm i\n   ```\n2. 运行');
  const list = root.querySelector('ol');
  assert.equal(list.children.length, 2);
  const block = list.children[0].querySelector('.md-code-block');
  assert.ok(block, 'code block inside the first item');
  assert.equal(block.querySelector('code').textContent, 'npm i');
  assert.equal(block.querySelector('.md-code-language').textContent, 'bash');
  assert.equal(list.children[0].querySelector('.md-inline-code'), null, 'no inline code fallback');
  assert.equal(list.children[1].textContent, '运行');
});

test('code inside a list keeps lines indented less than the fence and blank lines', () => {
  const root = render('- 步骤\n  ```py\n  def f():\n\n      return 1\n  ```\n- 下一步');
  const code = root.querySelector('code').textContent;
  assert.equal(code, 'def f():\n\n    return 1');
  assert.equal(root.querySelector('ul').children.length, 2);
});

test('nested lists nest instead of flattening', () => {
  const root = render('- a\n  - a1\n  - a2\n    - deep\n- b');
  const outer = root.querySelector('ul');
  assert.equal(outer.children.length, 2);
  const nested = outer.children[0].querySelector('ul');
  assert.ok(nested, 'nested list in first item');
  assert.equal(nested.children.length, 2);
  assert.ok(nested.children[1].querySelector('ul'), 'third level');
  assert.equal(nested.children[1].querySelector('ul li').textContent, 'deep');
});

test('a list nested two columns under an ordered marker still nests', () => {
  const root = render('1. top\n  - child\n2. next');
  const ol = root.querySelector('ol');
  assert.equal(ol.children.length, 2);
  assert.ok(ol.children[0].querySelector('ul'));
});

test('an ordered list keeps its start number and switching marker type starts a new list', () => {
  const root = render('3. c\n4. d\n- e');
  assert.deepEqual(tags(root), ['ol', 'ul']);
  assert.equal(root.querySelector('ol').start, 3);
});

test('tight list items render inline text; loose items keep paragraphs', () => {
  const tight = render('- one\n- two');
  assert.equal(tight.querySelector('p'), null);
  const loose = render('- one\n\n- two');
  assert.equal(loose.querySelectorAll('ul').length, 1, 'a blank line between items keeps one list');
  assert.equal(loose.querySelectorAll('li > p').length, 2);
});

test('lazy continuation lines join the item paragraph with a line break', () => {
  const root = render('- first line\nsecond line\n\nafter');
  const item = root.querySelector('li');
  assert.equal(item.querySelectorAll('br').length, 1);
  assert.equal(item.textContent, 'first linesecond line');
  assert.equal(root.children[1].localName, 'p');
});

test('a list ends at an unindented paragraph after a blank line', () => {
  const root = render('- item\n\nplain paragraph');
  assert.deepEqual(tags(root), ['ul', 'p']);
});

test('task items keep their checkbox', () => {
  const root = render('- [x] done\n- [ ] todo');
  const items = root.querySelectorAll('.md-task-item');
  assert.equal(items.length, 2);
  assert.equal(items[0].querySelector('input').checked, true);
  assert.equal(items[1].querySelector('input').checked, false);
  assert.equal(items[0].querySelector('.md-task-body').textContent, 'done');
});

test('a single newline inside a paragraph is a line break', () => {
  const root = render('第一行\n第二行\n第三行');
  const p = root.querySelector('p');
  assert.equal(p.querySelectorAll('br').length, 2);
  assert.equal(p.textContent, '第一行第二行第三行');
});

test('a thematic break is not mistaken for a list', () => {
  const root = render('text\n\n* * *\n\n- item');
  assert.deepEqual(tags(root), ['p', 'hr', 'ul']);
});

test('model HTML stays inert text', () => {
  const root = render('<img src=x onerror=alert(1)>\n<script>alert(1)</script>');
  assert.equal(root.querySelector('img'), null);
  assert.equal(root.querySelector('script'), null);
  assert.match(root.textContent, /<script>/);
});

test('stable boundary does not cut a list that continues after a blank line', () => {
  const source = '1. a\n\n   more of a\n\n2. b\n';
  assert.equal(stableBoundary(source), 0);
  const withParagraph = '- a\n\nparagraph\n\n';
  assert.equal(stableBoundary(withParagraph), '- a\n\n'.length);
});

test('stable boundary waits until the line after a blank line is complete', () => {
  assert.equal(stableBoundary('para\n\nne'), 0);
  assert.equal(stableBoundary('para\n\nnext\n'), 'para\n\n'.length);
});

test('streaming a nested list with a code block ends in the same DOM as a full render', () => {
  const full = '说明：\n\n1. 安装：\n   ```bash\n   npm i\n   ```\n\n   然后\n2. 运行\n   - 子项\n\n结束段落';
  const container = document.createElement('div');
  for (let end = 1; end <= full.length; end += 3) renderMarkdownInto(container, full.slice(0, end), true);
  renderMarkdownInto(container, full, true);
  renderMarkdownInto(container, full, false);
  const expected = document.createElement('div');
  expected.append(renderSafeMarkdown(full));
  const strip = (html) => html.replace(/<div class="md-tail">|<\/div>$/g, '');
  const flatten = (node) => node.querySelectorAll('.md-tail').forEach((tail) => tail.replaceWith(...tail.childNodes));
  flatten(container);
  assert.equal(strip(container.outerHTML), strip(expected.outerHTML));
});
