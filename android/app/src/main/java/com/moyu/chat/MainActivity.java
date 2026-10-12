package com.moyu.chat;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
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
    private static final String STATE_PENDING_IMAGE_SESSION = "pendingImageSessionId";
    private WebView webView;
    private ChatRuntime runtime;
    private ChatCoordinator coordinator;
    private AlertDialog activeConfigDialog;
    private String pendingImageSessionId;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) pendingImageSessionId = state.getString(STATE_PENDING_IMAGE_SESSION);
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG);
        MoyuChatApplication app = (MoyuChatApplication) getApplication();
        runtime = app.chatRuntime();
        runtime.attach(this);
        coordinator = runtime.coordinator();
        createWebView();
    }

    private void createWebView() {
        WebView next = new WebView(this); next.setBackgroundColor(Color.TRANSPARENT);
        webView = next;
        setContentView(next, new ViewGroup.LayoutParams(-1, -1));
        configureWebView();
        next.loadUrl(ORIGIN + "/assets/ui/index.html");
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
        @Override public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            recoverWebView(view);
            return true;
        }
        private WebResourceResponse response(String mime, String encoding, InputStream in) {
            Map<String,String> headers = new LinkedHashMap<>(); headers.put("Cache-Control", "no-store"); headers.put("X-Content-Type-Options", "nosniff");
            return new WebResourceResponse(mime, encoding, 200, "OK", headers, in);
        }
        private WebResourceResponse blocked() { return new WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", new LinkedHashMap<>(), new ByteArrayInputStream(new byte[0])); }
    }

    private void recoverWebView(WebView crashed) {
        if (crashed == null || webView != crashed) return;
        try { crashed.removeJavascriptInterface("MoyuHost"); } catch (Exception ignored) { }
        try {
            if (crashed.getParent() instanceof ViewGroup) ((ViewGroup) crashed.getParent()).removeView(crashed);
        } catch (Exception ignored) { }
        try { crashed.destroy(); } catch (Exception ignored) { }
        webView = null;
        createWebView();
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
            try { startActivityForResult(intent, REQUEST_IMAGE); }
            catch (Exception error) { pendingImageSessionId = null; Toast.makeText(this, "无法打开系统文件选择器：" + (error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()), Toast.LENGTH_LONG).show(); }
        });
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_IMAGE) {
            String sessionId = pendingImageSessionId; pendingImageSessionId = null;
            if (resultCode != RESULT_OK || data == null || data.getData() == null || sessionId == null) return;
            ChatCoordinator c = coordinator; if (c != null) c.attachImageFromUri(sessionId, data.getData(), getContentResolver());
        }
    }

    private boolean configEditorAlreadyOpen() {
        AlertDialog current = activeConfigDialog;
        if (current == null || !current.isShowing()) return false;
        Toast.makeText(this, "已有配置窗口打开，请先完成或取消当前编辑", Toast.LENGTH_SHORT).show();
        return true;
    }

    private void ownConfigDialog(AlertDialog dialog) {
        activeConfigDialog = dialog;
        dialog.setOnDismissListener(ignored -> { if (activeConfigDialog == dialog) activeConfigDialog = null; });
    }

    private TextView errorBanner(LinearLayout form) {
        TextView error = new TextView(this);
        error.setTextColor(Color.rgb(190, 32, 32));
        error.setTextSize(13);
        error.setPadding(0, 8, 0, 4);
        error.setVisibility(View.GONE);
        form.addView(error, new LinearLayout.LayoutParams(-1, -2));
        return error;
    }

    private void showEditorError(TextView error, String message) {
        String text = message == null || message.trim().isEmpty() ? "配置无效，请检查填写内容" : message.trim();
        if (error != null) {
            error.setText(text);
            error.setVisibility(View.VISIBLE);
        }
        Toast.makeText(this, text, Toast.LENGTH_LONG).show();
    }

    @Override public void openProviderEditor(ChatDatabase.Provider existing) {
        runOnUiThread(() -> {
            if (configEditorAlreadyOpen()) return;
            ChatDatabase.Provider p = existing == null ? new ChatDatabase.Provider() : existing;
            LinearLayout form = editorForm();
            TextView formError = errorBanner(form);
            EditText name = field(form, "显示名称", "仅用于本机识别这一组共享账号/endpoint，不会发送给模型。", "例如 OpenCode Go", p.displayName == null ? "AI Provider" : p.displayName, false, false);
            EditText base = field(form, "共享 Base URL", "同一 Provider 下所有 Model Profile 共享此 API 根地址与 API Key；protocol、authMode、reasoningWire 在各 Model Profile 单独配置。OpenCode Go 示例：https://opencode.ai/zen/go/v1。不要填写 /chat/completions、/responses 或 /messages。", "https://example.com/v1", p.baseUrl == null ? "" : p.baseUrl, false, false);
            EditText key = field(form, "API Key", "同一 Provider 下模型共享此密钥。仅保存在 Android 原生安全存储中，不进入 WebView、SQLite 或消息正文；编辑时留空表示保留已有密钥。", "sk-…", "", true, false);
            AlertDialog dialog = new AlertDialog.Builder(this).setTitle(existing == null ? "配置 Provider" : "编辑 Provider").setView(scroll(form)).setNegativeButton("取消", null).setPositiveButton("保存", null).create();
            ownConfigDialog(dialog);
            dialog.setOnShowListener(x -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                try {
                    formError.setVisibility(View.GONE);
                    if (p.id == null) p.id = "provider-" + UUID.randomUUID();
                    p.displayName = name.getText().toString().trim();
                    if (p.displayName.isEmpty()) throw new IllegalArgumentException("显示名称不能为空");
                    p.baseUrl = ProviderEndpoint.normalize(base.getText().toString());
                    // Legacy provider wire/model columns remain migration-only and have no UI authority.
                    p.model = "";
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
                    coordinator.saveProvider(p, key.getText().toString(), error -> runOnUiThread(() -> {
                        if (!dialog.isShowing()) return;
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                        if (error == null) dialog.dismiss();
                        else showEditorError(formError, error);
                    }));
                } catch (Exception error) { showEditorError(formError, error.getMessage()); }
            }));
            dialog.show();
        });
    }

    @Override public void openModelEditor(ChatDatabase.Provider provider, ChatDatabase.ModelProfile existing) {
        runOnUiThread(() -> {
            if (provider == null || configEditorAlreadyOpen()) return;
            ChatDatabase.ModelProfile p = existing == null ? new ChatDatabase.ModelProfile() : existing;
            LinearLayout form = editorForm();
            TextView formError = errorBanner(form);
            EditText name = field(form, "模型显示名称", "仅用于本机 UI，可与实际 Model ID 不同。", "例如 DeepSeek V4 Flash Vision", p.displayName == null ? "" : p.displayName, false, false);
            EditText model = field(form, "Model ID", "实际写入 API 请求 body 的 model 字符串。不要填写 OpenCode 客户端自己的 provider/model 前缀。", "deepseek-v4-flash-vision-exp", p.modelId == null ? "" : p.modelId, false, false);
            Switch autoConfig = new Switch(this);
            autoConfig.setText("自动配置（Model Catalog）");
            autoConfig.setChecked(existing == null || ModelCatalog.CONFIG_AUTO.equals(ModelCatalog.normalizeConfigMode(p.configMode)));
            form.addView(autoConfig, new LinearLayout.LayoutParams(-1, -2));
            TextView catalogSummary = new TextView(this);
            catalogSummary.setTextSize(12);
            catalogSummary.setPadding(0, 4, 0, 12);
            form.addView(catalogSummary, new LinearLayout.LayoutParams(-1, -2));
            String initialProtocol = existing == null ? OpenAiCompatibleTransport.CHAT_COMPLETIONS : p.protocol;
            EditText protocol = field(form, "协议 (protocol)", "这是当前模型部署的 wire，不再属于整个 Provider。openai-chat-completions → /chat/completions；openai-responses → /responses；anthropic-messages → /messages。三者都追加在 Provider 的共享 Base URL 后。", "openai-chat-completions", initialProtocol, false, false);
            EditText auth = field(form, "鉴权方式 (authMode)", "这是当前模型路线实际使用的 Header：bearer = Authorization: Bearer；x-api-key = x-api-key；api-key = api-key。同一个 Provider 下不同模型可不同。", "bearer", existing == null ? ProviderAuth.BEARER : p.authMode, false, false);
            EditText reasoningWire = field(form, "推理参数映射 (reasoningWire)", "当前模型如何接收会话推理档位：none / openai-responses / openai-chat-effort / anthropic-effort。它必须与本模型 protocol 匹配；只有 endpoint 明确支持时开启。", "例如 openai-chat-effort", existing == null ? ReasoningPolicy.WIRE_NONE : p.reasoningWire, false, false);
            EditText inputModalities = field(form, "输入模态 (inputModalities)", "text = 纯文本；text,image = 允许在 Chat 中选择图片并作为多模态 content block 发送。只有确认 endpoint 支持图片时才加入 image。", "text,image", p.inputModalities == null ? "text" : p.inputModalities, false, false);
            EditText outputModalities = field(form, "输出模态 (outputModalities)", "text = 常规文本响应；text,image = 允许保存并显示 provider 返回的图片结果。该字段不会自动启用服务端 image_generation 工具。", "text", p.outputModalities == null ? "text" : p.outputModalities, false, false);
            EditText toolCapabilities = field(form, "Provider-native Web capabilities", "仅声明该模型部署由 Provider 原生托管的 Web 能力。Responses 可填 web_search；Messages 必须填写该 endpoint 明确支持的实际 wire version（如 web_search_20260209、web_fetch_20250910）。通用 web_search/web_fetch 不会被猜成 Messages schema；无显式 wire 时走 Moyu fallback。", "Responses: web_search；Messages: web_search_20260209", p.toolCapabilities == null ? "" : p.toolCapabilities, false, false);
            EditText context = field(form, "Context tokens", "该 endpoint 上这个模型的总上下文窗口；不同托管平台即使模型名相同也可不同。Auto Catalog 不猜这个值，由你按实际部署填写。", "32000", String.valueOf(p.contextTokens > 0 ? p.contextTokens : ContextBudgetManager.DEFAULT_CONTEXT_TOKENS), false, true);
            EditText input = field(form, "Max input tokens", "0 = 自动使用 context - output - safety；若平台明确限制输入上限则填实际值。Auto Catalog 不覆盖此部署限制，图片也会占用本地保守估算预算。", "0", String.valueOf(Math.max(0, p.inputTokens)), false, true);
            EditText output = field(form, "Max output tokens", "本地预留的最大输出，同时映射到 max_tokens 或 max_output_tokens。Auto Catalog 不猜该部署上限。", "4096", String.valueOf(p.maxOutputTokens > 0 ? p.maxOutputTokens : ContextBudgetManager.DEFAULT_OUTPUT_TOKENS), false, true);
            EditText levels = field(form, "支持的推理档位 (reasoningLevels)", "这里只声明该具体部署支持的 capability range，不选择当前会话深度。OpenAI-compatible 可填 none,minimal,low,medium,high,xhigh,max 的实际子集；Anthropic Messages effort 仅填 low,medium,high,xhigh,max 的实际子集。", "例如 low,medium,high", p.reasoningLevels == null ? "" : p.reasoningLevels, false, false);
            EditText thinkingMode = field(form, "Messages thinking mode", "仅 anthropic-messages 协议族。provider-default 不猜第三方模型能力；adaptive / disabled / manual 必须由该具体 endpoint 明确支持。", "provider-default", p.thinkingMode == null ? ReasoningPolicy.THINKING_PROVIDER_DEFAULT : p.thinkingMode, false, false);
            EditText thinkingBudget = field(form, "Manual thinking budget", "仅 thinkingMode=manual 时使用；必须 >=1024 且小于 Max output tokens。其它模式填 0。", "0", String.valueOf(Math.max(0, p.thinkingBudgetTokens)), false, true);
            EditText continuationMode = field(form, "Provider-private continuation", "none = 仅使用 provider-neutral 可见历史；native = 允许该部署回放 provider 私有状态。第三方 endpoint 即使兼容 Responses/Messages，也只有明确支持原生 replay 时才选 native。", "none", p.continuationMode == null ? ProviderContinuationPolicy.NONE : p.continuationMode, false, false);
            EditText harnessPreset = field(form, "Harness preset", "高级模式下显式覆盖模型行为档位：lean / balanced / reinforced。Auto 模式由 Model Catalog 决定。", "balanced", p.harnessPreset == null ? HarnessProfile.BALANCED : p.harnessPreset, false, false);
            EditText clientToolCalling = field(form, "Client tool calling", "高级模式下声明该 endpoint 的 client tool wire：none / openai-chat-tools / openai-responses-tools / anthropic-tools。仅协议兼容并不代表支持工具调用。", "none", p.clientToolCalling == null ? ClientToolRuntime.NONE : p.clientToolCalling, false, false);
            // Context/input/output limits are explicit deployment inputs in both Auto and Advanced.
            // Auto owns only wire/capability/Harness metadata.
            EditText[] advancedFields = new EditText[]{protocol, auth, reasoningWire, inputModalities, outputModalities, toolCapabilities, levels, thinkingMode, thinkingBudget, continuationMode, harnessPreset, clientToolCalling};
            Runnable refreshCatalog = () -> {
                String modelId = model.getText().toString().trim();
                catalogSummary.setText(ModelCatalog.summary(provider, modelId));
                boolean advanced = !autoConfig.isChecked();
                for (EditText field : advancedFields) field.setEnabled(advanced);
                catalogSummary.setAlpha(autoConfig.isChecked() ? 1f : 0.62f);
            };
            autoConfig.setOnCheckedChangeListener((button, checked) -> refreshCatalog.run());
            model.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) { refreshCatalog.run(); }
                @Override public void afterTextChanged(Editable s) { }
            });
            refreshCatalog.run();
            AlertDialog dialog = new AlertDialog.Builder(this).setTitle((existing == null ? "新增模型 · " : "编辑模型 · ") + provider.displayName).setView(scroll(form)).setNegativeButton("取消", null).setPositiveButton("保存", null).create();
            ownConfigDialog(dialog);
            dialog.setOnShowListener(x -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                try {
                    formError.setVisibility(View.GONE);
                    if (p.id == null) p.id = "model-" + UUID.randomUUID();
                    p.providerId = provider.id; p.modelId = model.getText().toString().trim(); p.displayName = name.getText().toString().trim();
                    if (p.modelId.isEmpty()) throw new IllegalArgumentException("Model ID 不能为空");
                    // Limits are explicit deployment/user inputs in both modes; Catalog never guesses them.
                    p.contextTokens = ModelProfileValidator.parseInteger("Context Window", context.getText().toString());
                    p.inputTokens = ModelProfileValidator.parseInteger("Max Input", input.getText().toString());
                    p.maxOutputTokens = ModelProfileValidator.parseInteger("Max Output", output.getText().toString());
                    ModelProfileValidator.validateLimits(p.contextTokens, p.inputTokens, p.maxOutputTokens);
                    p.configMode = autoConfig.isChecked() ? ModelCatalog.CONFIG_AUTO : ModelCatalog.CONFIG_MANUAL;
                    if (autoConfig.isChecked()) {
                        // Coordinator resolves reviewed wire/capability/Harness metadata while preserving limits above.
                        p.catalogVersion = ModelCatalog.CATALOG_VERSION;
                    } else {
                        p.protocol = protocol.getText().toString().trim(); p.authMode = auth.getText().toString().trim(); p.reasoningWire = reasoningWire.getText().toString().trim();
                        p.inputModalities = inputModalities.getText().toString().trim(); p.outputModalities = outputModalities.getText().toString().trim();
                        p.toolCapabilities = toolCapabilities.getText().toString().trim();
                        p.reasoningLevels = levels.getText().toString().trim(); p.defaultReasoning = ReasoningPolicy.DEFAULT;
                        p.thinkingMode = thinkingMode.getText().toString().trim(); p.thinkingBudgetTokens = ModelProfileValidator.parseInteger("Manual thinking budget", thinkingBudget.getText().toString());
                        p.continuationMode = continuationMode.getText().toString().trim();
                        p.harnessPreset = harnessPreset.getText().toString().trim();
                        p.clientToolCalling = clientToolCalling.getText().toString().trim();
                        p.catalogKey = "unknown"; p.catalogVersion = ""; p.catalogSource = "manual";
                    }
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
                    coordinator.saveModelProfile(p, error -> runOnUiThread(() -> {
                        if (!dialog.isShowing()) return;
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                        if (error == null) dialog.dismiss();
                        else showEditorError(formError, error);
                    }));
                } catch (Exception error) { showEditorError(formError, error.getMessage()); }
            }));
            dialog.show();
        });
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

    @Override protected void onSaveInstanceState(Bundle outState) {
        if (pendingImageSessionId != null) outState.putString(STATE_PENDING_IMAGE_SESSION, pendingImageSessionId);
        super.onSaveInstanceState(outState);
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
        AlertDialog editor = activeConfigDialog; activeConfigDialog = null;
        if (editor != null) try { editor.dismiss(); } catch (Exception ignored) { }
        ChatRuntime r = runtime; runtime = null; if (r != null) r.detach(this);
        coordinator = null;
        if (webView != null) { webView.removeJavascriptInterface("MoyuHost"); webView.destroy(); webView = null; }
        super.onDestroy();
    }
}
