package com.moyu.chat;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class MainActivity extends Activity implements ChatCoordinator.Host {
    private static final String ORIGIN = "https://appassets.androidplatform.net";
    private WebView webView;
    private ChatCoordinator coordinator;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG);
        webView = new WebView(this); webView.setBackgroundColor(Color.TRANSPARENT);
        setContentView(webView, new ViewGroup.LayoutParams(-1, -1)); configureWebView();
        coordinator = new ChatCoordinator((MoyuChatApplication) getApplication(), this);
        webView.loadUrl(ORIGIN + "/assets/ui/index.html");
    }

    @SuppressLint("SetJavaScriptEnabled") private void configureWebView() {
        WebSettings s = webView.getSettings(); s.setJavaScriptEnabled(true); s.setDomStorageEnabled(false); s.setDatabaseEnabled(false);
        s.setAllowFileAccess(false); s.setAllowContentAccess(false); s.setAllowFileAccessFromFileURLs(false); s.setAllowUniversalAccessFromFileURLs(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW); s.setSupportMultipleWindows(false); s.setSafeBrowsingEnabled(true);
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
            if (!"https".equals(uri.getScheme()) || !"appassets.androidplatform.net".equals(uri.getHost()) || !uri.getPath().startsWith("/assets/ui/")) return blocked();
            String relative = uri.getPath().substring("/assets/ui/".length()); if (relative.contains("..")) return blocked();
            try { InputStream in = getAssets().open("ui/" + relative); Map<String,String> headers = new LinkedHashMap<>(); headers.put("Cache-Control", "no-store"); headers.put("X-Content-Type-Options", "nosniff"); return new WebResourceResponse(mime(relative), "UTF-8", 200, "OK", headers, in); }
            catch (Exception error) { return blocked(); }
        }
        @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) { Uri uri = request.getUrl(); return !("https".equals(uri.getScheme()) && "appassets.androidplatform.net".equals(uri.getHost()) && uri.getPath().startsWith("/assets/ui/")); }
        private WebResourceResponse blocked() { return new WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", new LinkedHashMap<>(), new ByteArrayInputStream(new byte[0])); }
    }

    private static String mime(String path) { if (path.endsWith(".html")) return "text/html"; if (path.endsWith(".js")) return "application/javascript"; if (path.endsWith(".css")) return "text/css"; return "application/octet-stream"; }

    @Override public void dispatch(JSONObject envelope) { String script = "window.dispatchEvent(new CustomEvent('moyu:view',{detail:JSON.parse(" + JSONObject.quote(envelope.toString()) + ")}));"; runOnUiThread(() -> { if (webView != null) webView.evaluateJavascript(script, null); }); }
    @Override public void showMessage(String text) { runOnUiThread(() -> Toast.makeText(this, text, Toast.LENGTH_LONG).show()); }

    @Override public void openProviderEditor(ChatDatabase.Provider existing) {
        runOnUiThread(() -> {
            ChatDatabase.Provider p = existing == null ? new ChatDatabase.Provider() : existing;
            LinearLayout form = new LinearLayout(this); form.setOrientation(LinearLayout.VERTICAL); form.setPadding(28, 18, 28, 18);
            EditText name = field(form, "显示名称", p.displayName == null ? "AI Provider" : p.displayName, false, false);
            EditText protocol = field(form, "协议: openai-responses / openai-chat-completions / anthropic-messages", p.protocol == null ? OpenAiCompatibleTransport.RESPONSES : p.protocol, false, false);
            EditText base = field(form, "Base URL，例如 https://api.openai.com/v1", p.baseUrl == null ? "https://api.openai.com/v1" : p.baseUrl, false, false);
            EditText model = field(form, "Model", p.model == null ? "" : p.model, false, false);
            EditText context = field(form, "Context tokens", String.valueOf(p.contextTokens > 0 ? p.contextTokens : ContextBudgetManager.DEFAULT_CONTEXT_TOKENS), false, true);
            EditText output = field(form, "Max output tokens", String.valueOf(p.maxOutputTokens > 0 ? p.maxOutputTokens : ContextBudgetManager.DEFAULT_OUTPUT_TOKENS), false, true);
            EditText key = field(form, "API Key（留空保留已有）", "", true, false);
            AlertDialog dialog = new AlertDialog.Builder(this).setTitle(existing == null ? "配置 Provider" : "编辑 Provider").setView(form).setNegativeButton("取消", null).setPositiveButton("保存", null).create();
            dialog.setOnShowListener(x -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                try {
                    if (p.id == null) p.id = "provider-" + UUID.randomUUID();
                    p.displayName = name.getText().toString().trim(); p.protocol = protocol.getText().toString().trim(); p.baseUrl = base.getText().toString().trim(); p.model = model.getText().toString().trim();
                    p.contextTokens = Integer.parseInt(context.getText().toString().trim()); p.maxOutputTokens = Integer.parseInt(output.getText().toString().trim());
                    coordinator.saveProvider(p, key.getText().toString()); dialog.dismiss();
                } catch (Exception error) { Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show(); }
            }));
            dialog.show();
        });
    }

    private EditText field(LinearLayout parent, String hint, String value, boolean secret, boolean numeric) {
        EditText input = new EditText(this); input.setHint(hint); input.setText(value); input.setSingleLine(true);
        if (secret) input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        else if (numeric) input.setInputType(InputType.TYPE_CLASS_NUMBER);
        parent.addView(input, new LinearLayout.LayoutParams(-1, -2)); return input;
    }

    @Override protected void onDestroy() {
        ChatCoordinator c = coordinator; coordinator = null; if (c != null) c.close();
        if (webView != null) { webView.removeJavascriptInterface("MoyuHost"); webView.destroy(); webView = null; }
        super.onDestroy();
    }
}
