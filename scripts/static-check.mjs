import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

const projectRoot = fileURLToPath(new URL('../', import.meta.url));
const uiRoot = join(projectRoot, 'src');
const javaRoot = join(projectRoot, 'android', 'app', 'src', 'main', 'java', 'com', 'moyu', 'chat');
let failed = false;

function fail(message) { console.error(`FAIL: ${message}`); failed = true; }
function read(relative) { return readFileSync(join(projectRoot, relative), 'utf8'); }
function walk(path, suffix, visit) {
  for (const name of readdirSync(path)) {
    const full = join(path, name);
    if (statSync(full).isDirectory()) walk(full, suffix, visit);
    else if (full.endsWith(suffix)) visit(full, readFileSync(full, 'utf8'));
  }
}

const forbiddenUi = [/\bfetch\s*\(/, /new\s+WebSocket\s*\(/, /localStorage/, /indexedDB/, /api[_-]?key/i, /authorization/i];
walk(uiRoot, '.js', (file, text) => {
  for (const pattern of forbiddenUi) if (pattern.test(text)) fail(`${file}: forbidden WebView surface ${pattern}`);
});

const forbiddenRuntime = [/\bEasyTier\b/, /\bBackendClient\b/, /\bOverlayService\b/, /com\.moyu\.remote\.(?!chatbridge)/];
walk(javaRoot, '.java', (file, text) => {
  for (const pattern of forbiddenRuntime) if (pattern.test(text)) fail(`${file}: Chat runtime must not depend on Remote runtime: ${pattern}`);
});

const db = read('android/app/src/main/java/com/moyu/chat/ChatDatabase.java');
if (/insertWithOnConflict\("sessions"[\s\S]{0,160}CONFLICT_REPLACE/.test(db)) fail('sessions must never use INSERT OR REPLACE because timeline has ON DELETE CASCADE');
if (!db.includes('recoverInterruptedSessions()')) fail('process-death recovery hook missing');
if (!db.includes('source_item_count') || !db.includes('truncated')) fail('snapshot truncation provenance missing');

const coordinator = read('android/app/src/main/java/com/moyu/chat/ChatCoordinator.java');
if (!coordinator.includes('contexts.build(session, provider.contextTokens, provider.maxOutputTokens, input)')) fail('send must preflight the pending user input before durable mutation');
if (/\bput\("text",\s*trimmed\b/.test(coordinator)) fail('send must store the raw user text; trim is only an emptiness check');
if (!coordinator.includes('db.recoverInterruptedSessions()')) fail('coordinator must recover stale running sessions at startup');

const registry = read('android/app/src/main/java/com/moyu/chat/ChatTransportRegistry.java');
for (const protocol of ['CHAT_COMPLETIONS', 'RESPONSES', 'AnthropicMessagesTransport.PROTOCOL']) if (!registry.includes(protocol)) fail(`transport registry missing ${protocol}`);

const manifest = read('android/app/src/main/AndroidManifest.xml');
if (!manifest.includes('android:allowBackup="false"')) fail('Android backup must remain disabled for local chat data/secrets');
if (!manifest.includes('android:usesCleartextTraffic="false"')) fail('cleartext provider traffic must remain disabled in v0.0.1');

const html = read('index.html');
if (!html.includes("connect-src 'none'")) fail('WebView CSP must block direct network connections');
if (!html.includes("default-src 'none'")) fail('WebView CSP must be deny-by-default');

const contract = read('contract/moyu-chat-contract.d.ts');
if (contract.includes("'provider.delete'")) fail('provider.delete is not a supported v0.0.1 intent');

if (failed) process.exit(1);
console.log('moyu-chat static architecture checks passed');
