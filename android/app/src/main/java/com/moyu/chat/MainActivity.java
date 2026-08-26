package com.moyu.chat;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class MainActivity extends Activity implements ChatCoordinator.Host {
    private static final String ORIGIN = "https://appassets.androidplatform.net";
    private static final int REQUEST_IMAGE = 4201;
    private WebView webView;
    private ChatRuntime runtime;
    private ChatCoordinator coordinator;
    private String pendingImageSessionId;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG);
        webView = new WebView(this); webView.setBackgroundColor(Color.TRANSPARENT);
        setContentView(webView, new ViewGroup.LayoutParams(-1, -1)); configureWebView();
        MoyuChatApplication app = (MoyuChatApplication) getApplication();
        runtime = app.chatRuntime();
        runtime.attach(this);
        coordinator = runtime.coordinator();
        webView.loadUrl(ORIGIN + "/assets/ui/index.html");
    }

    @SuppressLint("SetJavaScriptEnabled") private void configureWebView() {
        WebSettings s = webView.getSettings(); s.setJavaScriptEnabled(true); s.setDomStorageEnabled(false); s.setDatabaseEnabled(false);
        s.setAllowFileAccess(false); s.setAllowContentAccess(false); s.setAllowFileAccessFromFileURLs(false); s.setAllowUniversalAccessFromFileURLs(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW); s.setSupportMultipleWindows(false); s.setSafeBrowsingEnabled(true); s.setTextZoom(100);
        webView.setVerticalScrollBarEnabled(false); webView.setHorizontalScrollBarEnabled(false); webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        webView.addJavascriptInterface(new MoyuHostBridge(), "MoyuHost"); webView.setWebViewClient(new LocalClient());
    }

    public final class MoyuHostBridge {
        @JavascriptInterface public void postMessage(String message) {
            ChatCoordinator c = coordinator; if (c != null && message != null && message.length() <= 1024 * 1024) c.postIntent(message);
        }
    }

    private final class LocalClient extends WebViewClient {
        @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();
            if (!"https".equals(uri.getScheme()) || !"appassets.androidplatform.net".equals(uri.getHost())) return blocked();
            String path = uri.getPath() == null ? "" : uri.getPath();
            if (path.startsWith("/assets/ui/")) {
                String relative = path.substring("/assets/ui/".length()); if (relative.contains("..")) return blocked();
                try { InputStream in = getAssets().open("ui/" + relative); return response(mime(relative), "UTF-8", in); }
                catch (Exception error) { return blocked(); }
            }
            if (path.startsWith("/media/")) {
                String mediaId = path.substring("/media/".length());
                try {
                    ChatMediaStore store = ((MoyuChatApplication) getApplication()).mediaStore();
                    return response(store.mimeType(mediaId), null, store.open(mediaId));
                } catch (Exception error) { return blocked(); }
            }
            return blocked();
        }
        @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl(); String path = uri.getPath() == null ? "" : uri.getPath();
            return !("https".equals(uri.getScheme()) && "appassets.androidplatform.net".equals(uri.getHost()) && path.startsWith("/assets/ui/"));
        }
        private WebResourceResponse response(String mime, String encoding, InputStream in) {
            Map<String,String> headers = new LinkedHashMap<>(); headers.put("Cache-Control", "no-store"); headers.put("X-Content-Type-Options", "nosniff");
            return new WebResourceResponse(mime, encoding, 200, "OK", headers, in);
        }
        private WebResourceResponse blocked() { return new WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", new LinkedHashMap<>(), new ByteArrayInputStream(new byte[0])); }
    }

    private static String mime(String path) { if (path.endsWith(".html")) return "text/html"; if (path.endsWith(".js")) return "application/javascript"; if (path.endsWith(".css")) return "text/css"; return "application/octet-stream"; }

    @Override public void dispatch(JSONObject envelope) { String script = "window.dispatchEvent(new CustomEvent('moyu:view',{detail:JSON.parse(" + JSONObject.quote(envelope.toString()) + ")}));"; runOnUiThread(() -> { if (webView != null) webView.evaluateJavascript(script, null); }); }
    @Override public void showMessage(String text) { runOnUiThread(() -> Toast.makeText(this, text, Toast.LENGTH_LONG).show()); }

    @Override public void openExternalUrl(String url) {
        runOnUiThread(() -> {
            try {
                Uri uri = Uri.parse(url == null ? "" : url.trim());
                if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getHost().trim().isEmpty()) {
                    throw new IllegalArgumentException("Only HTTPS external links are allowed");
                }
                startActivity(new Intent(Intent.ACTION_VIEW, uri));
            } catch (Exception error) {
                Toast.makeText(this, error.getMessage() == null ? "Unable to open link" : error.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
    }

    @Override public void openImagePicker(String sessionId) {
        runOnUiThread(() -> {
            pendingImageSessionId = sessionId;
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*");
            intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/jpeg", "image/png", "image/webp", "image/gif"});
            startActivityForResult(intent, REQUEST_IMAGE);
        });
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_IMAGE) return;
        String sessionId = pendingImageSessionId; pendingImageSessionId = null;
        if (resultCode != RESULT_OK || data == null || data.getData() == null || sessionId == null) return;
        ChatCoordinator c = coordinator; if (c != null) c.attachImageFromUri(sessionId, data.getData(), getContentResolver());
    }

    @Override public void openProviderEditor(ChatDatabase.Provider existing) {
        runOnUiThread(() -> {
            ChatDatabase.Provider p = existing == null ? new ChatDatabase.Provider() : existing;
            LinearLayout form = editorForm(); boolean isNew = existing == null;
            EditText name = field(form, "显示名称", "仅用于本机识别这个 endpoint，不会发送给模型。", "例如 OpenCode Go", p.displayName == null ? "AI Provider" : p.displayName, false, false);
            EditText protocol = field(form, "协议 (protocol)", "决定请求体和固定路径：openai-chat-completions → /chat/completions；openai-responses → /responses；anthropic-messages → /v1/messages。", "openai-chat-completions", p.protocol == null ? OpenAiCompatibleTransport.CHAT_COMPLETIONS : p.protocol, false, false);
            EditText auth = field(form, "鉴权方式 (authMode)", "bearer = Authorization: Bearer <key>；x-api-key = x-api-key: <key>；api-key = api-key: <key>。密钥是否以 sk- 开头不决定鉴权方式。", "bearer", p.authMode == null ? ProviderAuth.BEARER : p.authMode, false, false);
            EditText reasoningWire = field(form, "推理参数映射 (reasoningWire)", "把中立 reasoning 档位映射到该 endpoint 的 JSON。可用：none / openai-responses / openai-chat-effort / anthropic-effort。留空按协议默认。", "例如 openai-chat-effort", isNew ? "" : (p.reasoningWire == null ? "" : p.reasoningWire), false, false);
            EditText base = field(form, "Base URL", "填写最终固定路径之前的前缀，不要写 /chat/completions、/responses 或 /v1/messages。例：OpenCode Go 的 Chat/Responses 用 https://opencode.ai/zen/go/v1；Anthropic 协议用 https://opencode.ai/zen/go。", "https://api.openai.com/v1", p.baseUrl == null ? suggestedBaseUrl(p.protocol) : p.baseUrl, false, false);
            EditText model = field(form, "初始 Model ID", "仅在新建 Provider 时用于创建第一个 Model Profile；之后模型统一在 Provider 页面单独管理。这里填写 API body 实际发送的裸 model ID。", "例如 deepseek-v4-flash-vision-exp", p.model == null ? "" : p.model, false, false);
            EditText initialInputModalities = field(form, "初始输入模态", "text = 纯文本；text,image = 在 Chat composer 中开放图片选择并按当前协议编码发送。只在确认该 endpoint 支持图片时加入 image。", "text,image", "text", false, false);
            EditText initialOutputModalities = field(form, "初始输出模态", "text = 文本；text,image = 允许保存和展示 Provider 实际返回的图片结果。Moyu 不会因为填写 image 就假设服务端一定具备图片生成工具。", "text,image", "text", false, false);
            EditText initialToolCapabilities = field(form, "初始 Provider-native Web capabilities", "仅声明 Provider 原生 hosted Web 能力；允许 web_search / web_fetch。Moyu 自有 Exa/Fetch fallback 无需填写。", "web_search,web_fetch", "", false, false);
            EditText initialReasoningLevels = field(form, "初始推理档位", "逗号分隔，例如 none,low,medium,high。留空即 UI 显示推理关闭；不要猜测未确认的模型能力。", "none,low,medium,high", "", false, false);
            EditText initialReasoning = field(form, "初始默认推理", "default = 保持 Provider 默认；也可选上面已声明的 none / low / medium / high / xhigh / max。", "default", ReasoningPolicy.DEFAULT, false, false);
            EditText context = field(form, "初始 Context tokens", "该模型部署的总上下文窗口。只用于创建初始 Model Profile；不是自动探测值。", "32000", String.valueOf(p.contextTokens > 0 ? p.contextTokens : ContextBudgetManager.DEFAULT_CONTEXT_TOKENS), false, true);
            EditText inputLimit = field(form, "初始 Max input tokens", "0 表示按 context - output - safety 自动计算；若托管平台有更低输入上限，请填写实际限制。", "0", String.valueOf(Math.max(0, p.inputTokens)), false, true);
            EditText output = field(form, "初始 Max output tokens", "单次响应允许的最大输出 token，用于本地预算和 API 请求字段。", "4096", String.valueOf(p.maxOutputTokens > 0 ? p.maxOutputTokens : ContextBudgetManager.DEFAULT_OUTPUT_TOKENS), false, true);
            model.setEnabled(isNew); initialInputModalities.setEnabled(isNew); initialOutputModalities.setEnabled(isNew); initialToolCapabilities.setEnabled(isNew); initialReasoningLevels.setEnabled(isNew); initialReasoning.setEnabled(isNew); context.setEnabled(isNew); inputLimit.setEnabled(isNew); output.setEnabled(isNew);
            EditText key = field(form, "API Key", "仅保存在 Android 原生安全存储中，不进入 WebView、SQLite 或消息正文。留空表示保留已有密钥。", "sk-…", "", true, false);
            AlertDialog dialog = new AlertDialog.Builder(this).setTitle(existing == null ? "配置 Provider" : "编辑 Provider").setView(scroll(form)).setNegativeButton("取消", null).setPositiveButton("保存", null).create();
            dialog.setOnShowListener(x -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                try {
                    if (p.id == null) p.id = "provider-" + UUID.randomUUID();
                    p.displayName = name.getText().toString().trim(); p.protocol = protocol.getText().toString().trim();
                    p.authMode = auth.getText().toString().trim(); p.reasoningWire = reasoningWire.getText().toString().trim();
                    p.baseUrl = base.getText().toString().trim();
                    if (p.baseUrl.isEmpty()) p.baseUrl = suggestedBaseUrl(p.protocol);
                    if (p.baseUrl.isEmpty()) throw new IllegalArgumentException("Base URL required");
                    p.model = model.getText().toString().trim();
                    p.contextTokens = Integer.parseInt(context.getText().toString().trim()); p.inputTokens = Integer.parseInt(inputLimit.getText().toString().trim()); p.maxOutputTokens = Integer.parseInt(output.getText().toString().trim());
                    coordinator.saveProvider(p, key.getText().toString());
                    if (isNew) {
                        ChatDatabase.ModelProfile initial = new ChatDatabase.ModelProfile();
                        initial.id = p.id + ":default"; initial.providerId = p.id; initial.modelId = p.model; initial.displayName = p.model;
                        initial.contextTokens = p.contextTokens; initial.inputTokens = p.inputTokens; initial.maxOutputTokens = p.maxOutputTokens;
                        initial.inputModalities = initialInputModalities.getText().toString().trim(); initial.outputModalities = initialOutputModalities.getText().toString().trim();
                        initial.toolCapabilities = initialToolCapabilities.getText().toString().trim();
                        initial.reasoningLevels = initialReasoningLevels.getText().toString().trim(); initial.defaultReasoning = initialReasoning.getText().toString().trim();
                        coordinator.saveModelProfile(initial);
                    }
                    dialog.dismiss();
                } catch (Exception error) { Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show(); }
            }));
            dialog.show();
        });
    }

    @Override public void openModelEditor(ChatDatabase.Provider provider, ChatDatabase.ModelProfile existing) {
        runOnUiThread(() -> {
            if (provider == null) return;
            ChatDatabase.ModelProfile p = existing == null ? new ChatDatabase.ModelProfile() : existing;
            LinearLayout form = editorForm();
            EditText name = field(form, "模型显示名称", "仅用于本机 UI，可与实际 Model ID 不同。", "例如 DeepSeek V4 Flash Vision", p.displayName == null ? "" : p.displayName, false, false);
            EditText model = field(form, "Model ID", "实际写入 API 请求 body 的 model 字符串。不要填写 OpenCode 客户端自己的 provider/model 前缀。", "deepseek-v4-flash-vision-exp", p.modelId == null ? "" : p.modelId, false, false);
            EditText inputModalities = field(form, "输入模态 (inputModalities)", "text = 纯文本；text,image = 允许在 Chat 中选择图片并作为多模态 content block 发送。只有确认 endpoint 支持图片时才加入 image。", "text,image", p.inputModalities == null ? "text" : p.inputModalities, false, false);
            EditText outputModalities = field(form, "输出模态 (outputModalities)", "text = 常规文本响应；text,image = 允许保存并显示 provider 返回的图片结果。该字段不会自动启用服务端 image_generation 工具。", "text", p.outputModalities == null ? "text" : p.outputModalities, false, false);
            EditText toolCapabilities = field(form, "Provider-native Web capabilities", "仅声明该模型部署由 Provider 原生托管的 Web 能力。允许 web_search / web_fetch，逗号分隔；普通 Chat Completions 或不支持 hosted tool 时留空。不要把 Moyu Exa/Fetch fallback 填在这里。", "web_search,web_fetch", p.toolCapabilities == null ? "" : p.toolCapabilities, false, false);
            EditText context = field(form, "Context tokens", "该 endpoint 上这个模型的总上下文窗口；不同托管平台即使模型名相同也可不同。", "32000", String.valueOf(p.contextTokens > 0 ? p.contextTokens : ContextBudgetManager.DEFAULT_CONTEXT_TOKENS), false, true);
            EditText input = field(form, "Max input tokens", "0 = 自动使用 context - output - safety；若平台明确限制输入上限则填实际值。图片也会占用本地保守估算预算。", "0", String.valueOf(Math.max(0, p.inputTokens)), false, true);
            EditText output = field(form, "Max output tokens", "本地预留的最大输出，同时映射到 max_tokens 或 max_output_tokens。", "4096", String.valueOf(p.maxOutputTokens > 0 ? p.maxOutputTokens : ContextBudgetManager.DEFAULT_OUTPUT_TOKENS), false, true);
            EditText levels = field(form, "支持的推理档位 (reasoningLevels)", "逗号分隔，例如 low,medium,high。留空表示 UI 不提供推理档位选择，也不会假设模型支持 reasoning。", "low,medium,high", p.reasoningLevels == null ? "" : p.reasoningLevels, false, false);
            EditText effort = field(form, "默认推理档位 (defaultReasoning)", "default = 不发送额外 reasoning 参数；也可选 none / low / medium / high / xhigh / max，但必须包含在上面的支持列表中。", "default", p.defaultReasoning == null ? ReasoningPolicy.DEFAULT : p.defaultReasoning, false, false);
            AlertDialog dialog = new AlertDialog.Builder(this).setTitle((existing == null ? "新增模型 · " : "编辑模型 · ") + provider.displayName).setView(scroll(form)).setNegativeButton("取消", null).setPositiveButton("保存", null).create();
            dialog.setOnShowListener(x -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                try {
                    if (p.id == null) p.id = "model-" + UUID.randomUUID();
                    p.providerId = provider.id; p.modelId = model.getText().toString().trim(); p.displayName = name.getText().toString().trim();
                    p.inputModalities = inputModalities.getText().toString().trim(); p.outputModalities = outputModalities.getText().toString().trim();
                    p.toolCapabilities = toolCapabilities.getText().toString().trim();
                    p.contextTokens = Integer.parseInt(context.getText().toString().trim()); p.inputTokens = Integer.parseInt(input.getText().toString().trim()); p.maxOutputTokens = Integer.parseInt(output.getText().toString().trim());
                    p.reasoningLevels = levels.getText().toString().trim(); p.defaultReasoning = effort.getText().toString().trim();
                    coordinator.saveModelProfile(p); dialog.dismiss();
                } catch (Exception error) { Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show(); }
            }));
            dialog.show();
        });
    }

    /** UI-level suggestions only; transports never hardcode vendor addresses, only standard protocol paths. */
    private static String suggestedBaseUrl(String protocol) {
        if (protocol == null) return "";
        String value = protocol.trim();
        if (OpenAiCompatibleTransport.RESPONSES.equals(value) || OpenAiCompatibleTransport.CHAT_COMPLETIONS.equals(value)) return "https://api.openai.com/v1";
        if (AnthropicMessagesTransport.PROTOCOL.equals(value)) return "https://api.anthropic.com";
        return "";
    }

    private LinearLayout editorForm() { LinearLayout form = new LinearLayout(this); form.setOrientation(LinearLayout.VERTICAL); form.setPadding(32, 24, 32, 24); return form; }
    private ScrollView scroll(LinearLayout form) { ScrollView scroll = new ScrollView(this); scroll.addView(form, new ScrollView.LayoutParams(-1, -2)); return scroll; }

    private EditText field(LinearLayout parent, String label, String help, String hint, String value, boolean secret, boolean numeric) {
        TextView title = new TextView(this); title.setText(label); title.setTextSize(16); title.setPadding(0, 14, 0, 2); parent.addView(title, new LinearLayout.LayoutParams(-1, -2));
        TextView description = new TextView(this); description.setText(help); description.setTextSize(12); description.setPadding(0, 0, 0, 4); parent.addView(description, new LinearLayout.LayoutParams(-1, -2));
        EditText input = new EditText(this); input.setHint(hint); input.setText(value); input.setSingleLine(true);
        if (secret) input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        else if (numeric) input.setInputType(InputType.TYPE_CLASS_NUMBER);
        parent.addView(input, new LinearLayout.LayoutParams(-1, -2)); return input;
    }

    @Override protected void onResume() {
        super.onResume();
        ChatRuntime r = runtime; if (r != null) r.setResumed(this, true);
    }

    @Override protected void onPause() {
        ChatRuntime r = runtime; if (r != null) r.setResumed(this, false);
        super.onPause();
    }

    @Override @SuppressWarnings("deprecation") public void onBackPressed() {
        ChatCoordinator c = coordinator;
        if (c == null) { super.onBackPressed(); return; }
        c.handleSystemBack(() -> runOnUiThread(this::finish));
    }

    @Override protected void onDestroy() {
        ChatRuntime r = runtime; runtime = null; if (r != null) r.detach(this);
        coordinator = null;
        if (webView != null) { webView.removeJavascriptInterface("MoyuHost"); webView.destroy(); webView = null; }
        super.onDestroy();
    }
}
