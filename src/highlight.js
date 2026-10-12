/**
 * Tiny local code highlighter: one left-to-right scan marking comments, strings, numbers and keywords.
 * No third-party code, no regular expressions over the whole block (no backtracking cost on long or
 * still-streaming input), and output is DOM text only. Unknown languages get strings and numbers.
 */
const WORDS = {
  c: 'auto bool break case catch char class const constexpr continue default define delete do double else enum explicit extern false float for friend goto if include inline int long namespace new noexcept nullptr NULL operator override private protected public register return short signed sizeof static struct switch template this throw true try typedef typename union unsigned using virtual void volatile while',
  java: 'abstract assert boolean break byte case catch char class const continue default do double else enum extends false final finally float for if implements import instanceof int interface long native new null package private protected public record return short static super switch synchronized this throw throws transient true try var void volatile while',
  kotlin: 'abstract as break by catch class companion const constructor continue data do else enum false final finally for fun if import in init interface internal is lateinit null object open override package private protected public return sealed super suspend this throw true try typealias val var when while',
  js: 'as async await break case catch class const continue debugger default delete do else enum export extends false finally for from function get if implements import in instanceof interface let new null of private protected public readonly return set static super switch this throw true try type typeof undefined var void while with yield',
  python: 'and as assert async await break class continue def del elif else except False finally for from global if import in is lambda None nonlocal not or pass raise return self True try while with yield',
  go: 'break case chan const continue default defer else fallthrough false for func go goto if import interface map nil package range return select struct switch true type var',
  rust: 'as async await break const continue crate dyn else enum extern false fn for if impl in let loop match mod move mut pub ref return self Self static struct super trait true type unsafe use where while',
  sh: 'case do done echo elif else esac exit export fi for function if in local read return set then unset until while',
  sql: 'all and as asc between by case create delete desc distinct drop else end exists foreign from group having in index inner insert into is join key left like limit not null offset on or order outer primary references right select set table then union update values when where',
};
const ALIASES = {
  c: 'c', h: 'c', cpp: 'c', 'c++': 'c', cc: 'c', hpp: 'c', cxx: 'c', objc: 'c',
  java: 'java', cs: 'java', csharp: 'java', 'c#': 'java', scala: 'java', dart: 'java',
  kotlin: 'kotlin', kt: 'kotlin', kts: 'kotlin', swift: 'kotlin',
  js: 'js', javascript: 'js', jsx: 'js', mjs: 'js', cjs: 'js', ts: 'js', typescript: 'js', tsx: 'js', json: 'js', jsonc: 'js',
  py: 'python', python: 'python', python3: 'python',
  go: 'go', golang: 'go',
  rs: 'rust', rust: 'rust',
  sh: 'sh', bash: 'sh', shell: 'sh', zsh: 'sh', console: 'sh', ps1: 'sh', powershell: 'sh', dockerfile: 'sh', yaml: 'sh', yml: 'sh', toml: 'sh', ini: 'sh',
  sql: 'sql', sqlite: 'sql', mysql: 'sql', postgres: 'sql', postgresql: 'sql',
};
const PLAIN = new Set(['', 'text', 'txt', 'plain', 'plaintext', 'md', 'markdown', 'diff', 'log', 'output']);
const LINE_COMMENT = { c: '//', java: '//', kotlin: '//', js: '//', go: '//', rust: '//', python: '#', sh: '#', sql: '--' };
const BLOCK_COMMENT = new Set(['c', 'java', 'kotlin', 'js', 'go', 'rust', 'sql']);
const QUOTES = { js: '\'"`', go: '"\'`', python: '\'"', sh: '\'"', sql: '\'"' };
const MAX_CHARS = 20000;
const sets = {};

function wordSet(lang) {
  if (!sets[lang]) sets[lang] = new Set(lang === 'sql' ? WORDS.sql.split(' ') : WORDS[lang].split(' '));
  return sets[lang];
}
const isWordStart = (c) => (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c === '_' || c === '$';
const isWordChar = (c) => isWordStart(c) || (c >= '0' && c <= '9');
const isDigit = (c) => c >= '0' && c <= '9';

/** Fills a <code> element; falls back to plain text for plain / oversized blocks. */
export function highlightInto(code, text, language) {
  const name = String(language || '').trim().toLowerCase();
  const lang = ALIASES[name] || '';
  if (PLAIN.has(name) || text.length > MAX_CHARS) { code.textContent = text; return; }
  const words = lang ? wordSet(lang) : null;
  const lineComment = lang ? LINE_COMMENT[lang] : '';
  const blockComment = BLOCK_COMMENT.has(lang);
  const quotes = QUOTES[lang] || '\'"';
  const frag = document.createDocumentFragment();
  let plain = '';
  const flush = () => { if (plain) { frag.append(document.createTextNode(plain)); plain = ''; } };
  const mark = (cls, value) => { flush(); const span = document.createElement('span'); span.className = `hl-${cls}`; span.textContent = value; frag.append(span); };
  const n = text.length;
  let i = 0;
  while (i < n) {
    const c = text[i];
    if (lineComment && text.startsWith(lineComment, i) && !(lineComment === '#' && i > 0 && isWordChar(text[i - 1]))) {
      const end = text.indexOf('\n', i); const stop = end < 0 ? n : end;
      mark('com', text.slice(i, stop)); i = stop; continue;
    }
    if (blockComment && c === '/' && text[i + 1] === '*') {
      const end = text.indexOf('*/', i + 2); const stop = end < 0 ? n : end + 2;
      mark('com', text.slice(i, stop)); i = stop; continue;
    }
    if (quotes.includes(c)) {
      const triple = lang === 'python' && text.startsWith(c + c + c, i);
      let j = i + (triple ? 3 : 1);
      while (j < n) {
        if (text[j] === '\\') { j += 2; continue; }
        if (triple ? text.startsWith(c + c + c, j) : text[j] === c) { j += triple ? 3 : 1; break; }
        if (!triple && c !== '`' && text[j] === '\n') break;
        j += 1;
      }
      mark('str', text.slice(i, Math.min(j, n))); i = Math.min(j, n); continue;
    }
    if (isDigit(c) && (i === 0 || !isWordChar(text[i - 1]))) {
      let j = i + 1;
      while (j < n && (isWordChar(text[j]) || (text[j] === '.' && isDigit(text[j + 1] || '')))) j += 1;
      mark('num', text.slice(i, j)); i = j; continue;
    }
    if (isWordStart(c)) {
      let j = i + 1;
      while (j < n && isWordChar(text[j])) j += 1;
      const word = text.slice(i, j);
      if (words && words.has(lang === 'sql' ? word.toLowerCase() : word)) mark('kw', word); else plain += word;
      i = j; continue;
    }
    plain += c; i += 1;
  }
  flush();
  code.replaceChildren(frag);
}
