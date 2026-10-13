package com.moyu.chat;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsetsController;
import android.webkit.JavascriptInterface;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

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
        WebView next = new WebView(this);
        // Opaque, on the --canvas token: any frame the page has not painted yet (cold start, a large layer
        // re-rastered during a page transition) shows the page ground, never black or a transparent hole.
        next.setBackgroundColor(windowCanvas != 0 ? windowCanvas : canvasColor(systemDark(), "sage"));
        webView = next;
        setContentView(next, new ViewGroup.LayoutParams(-1, -1));
        configureWebView();
        next.loadUrl(ORIGIN + "/assets/ui/index.html");
    }

    /** Follow the system font size, bounded so 44px controls and one-line chips keep their layout. A font-size change recreates the Activity. */
    static int textZoomFor(float fontScale) {
        int zoom = Math.round((fontScale > 0f ? fontScale : 1f) * 100f);
        return Math.max(85, Math.min(160, zoom));
    }

    @SuppressLint("SetJavaScriptEnabled") private void configureWebView() {
        WebSettings s = webView.getSettings(); s.setJavaScriptEnabled(true); s.setDomStorageEnabled(false); s.setDatabaseEnabled(false);
        s.setAllowFileAccess(false); s.setAllowContentAccess(false); s.setAllowFileAccessFromFileURLs(false); s.setAllowUniversalAccessFromFileURLs(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW); s.setSupportMultipleWindows(false); s.setSafeBrowsingEnabled(true); s.setTextZoom(textZoomFor(getResources().getConfiguration().fontScale));
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
    /** Messages are shown by the WebView's own toast, so the app has one notification style. */
    @Override public void showMessage(String text) {
        if (text == null || text.trim().isEmpty()) return;
        try { dispatch(new JSONObject().put("version", 1).put("type", "notice").put("level", noticeLevel(text)).put("text", text)); }
        catch (Exception ignored) { }
    }

    static String noticeLevel(String text) {
        String t = text == null ? "" : text.toLowerCase(java.util.Locale.ROOT);
        return t.contains("失败") || t.contains("无效") || t.contains("无法") || t.contains("failed") || t.contains("error") || t.contains("not found") ? "error" : "info";
    }

    @Override public void openExternalUrl(String url) {
        runOnUiThread(() -> {
            try {
                Uri uri = Uri.parse(url == null ? "" : url.trim());
                if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getHost().trim().isEmpty()) {
                    throw new IllegalArgumentException("Only HTTPS external links are allowed");
                }
                startActivity(new Intent(Intent.ACTION_VIEW, uri));
            } catch (Exception error) {
                showMessage(error.getMessage() == null ? "无法打开链接" : "无法打开链接：" + error.getMessage());
            }
        });
    }

    @Override public void openImagePicker(String sessionId) {
        runOnUiThread(() -> {
            pendingImageSessionId = sessionId;
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*");
            intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/jpeg", "image/png", "image/webp", "image/gif"});
            try { startActivityForResult(intent, REQUEST_IMAGE); }
            catch (Exception error) { pendingImageSessionId = null; showMessage("无法打开系统文件选择器：" + (error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage())); }
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

    /**
     * The one native form left: a Provider's API Key. It is typed into a native password field and saved
     * straight to ChatSecretStore, so the key never enters the WebView, SQLite or logs. Everything else
     * about Providers and Model Profiles is edited in the WebView.
     */
    @SuppressLint("SetTextI18n")
    @Override public void openCredentialEditor(ChatDatabase.Provider provider, String hosts) {
        runOnUiThread(() -> {
            if (provider == null || isFinishing()) return;
            AlertDialog current = activeConfigDialog;
            if (current != null && current.isShowing()) return;
            boolean dark = appliedAppearance.startsWith("dark");
            int pad = Math.round(20 * getResources().getDisplayMetrics().density);
            LinearLayout form = new LinearLayout(this); form.setOrientation(LinearLayout.VERTICAL); form.setPadding(pad, pad / 2, pad, 0);
            TextView help = new TextView(this);
            help.setText("这个账号下的所有路线和模型共享这个密钥，只会发往：" + (hosts == null || hosts.isEmpty() ? "（尚无路线）" : hosts) + "。它只保存在 Android 安全存储里，不进入界面、数据库或日志。");
            help.setTextSize(13); help.setAlpha(0.75f);
            form.addView(help, new LinearLayout.LayoutParams(-1, -2));
            EditText key = new EditText(this);
            key.setHint("sk-…"); key.setSingleLine(true);
            key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            form.addView(key, new LinearLayout.LayoutParams(-1, -2));
            TextView error = new TextView(this);
            error.setTextSize(13); error.setTextColor(dark ? Color.rgb(224, 138, 128) : Color.rgb(176, 65, 58)); error.setVisibility(View.GONE);
            form.addView(error, new LinearLayout.LayoutParams(-1, -2));
            AlertDialog dialog = new AlertDialog.Builder(this, dark ? android.R.style.Theme_DeviceDefault_Dialog_Alert : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
                    .setTitle("API Key · " + provider.displayName).setView(form)
                    .setNegativeButton("取消", null).setPositiveButton("保存", null).create();
            activeConfigDialog = dialog;
            dialog.setOnDismissListener(ignored -> { if (activeConfigDialog == dialog) activeConfigDialog = null; });
            dialog.setOnShowListener(x -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String value = key.getText().toString().trim();
                if (value.isEmpty()) { error.setText("请填写 API Key"); error.setVisibility(View.VISIBLE); return; }
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
                ChatCoordinator c = coordinator;
                if (c == null) { dialog.dismiss(); return; }
                c.saveCredential(provider.id, value, failure -> runOnUiThread(() -> {
                    if (!dialog.isShowing()) return;
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                    if (failure == null) { dialog.dismiss(); showMessage("API Key 已保存"); }
                    else { error.setText(failure); error.setVisibility(View.VISIBLE); }
                }));
            }));
            dialog.show();
            key.requestFocus();
        });
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

    /** The WebView owns transient overlays (drawer, sheets, dialogs); it consumes Back before native route handling. */
    @Override @SuppressWarnings("deprecation") public void onBackPressed() {
        ChatCoordinator c = coordinator;
        if (c == null) { super.onBackPressed(); return; }
        WebView view = webView;
        if (view == null) { c.handleSystemBack(() -> runOnUiThread(this::finish)); return; }
        view.evaluateJavascript("(window.moyuHandleBack && window.moyuHandleBack()) ? 'handled' : ''", value -> {
            if ("\"handled\"".equals(value)) return;
            ChatCoordinator current = coordinator;
            if (current != null) current.handleSystemBack(() -> runOnUiThread(this::finish));
        });
    }

    private String appliedAppearance = "";
    private int windowCanvas = 0;

    private boolean systemDark() {
        return (getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    /** Keeps status/navigation bars and the pre-WebView window background on the same canvas token as the UI. */
    @Override public void applyAppearance(String resolvedTheme, String tone) {
        runOnUiThread(() -> {
            boolean dark = "dark".equals(resolvedTheme);
            String key = (dark ? "dark" : "light") + ":" + tone;
            if (key.equals(appliedAppearance)) return;
            appliedAppearance = key;
            int canvas = canvasColor(dark, tone);
            windowCanvas = canvas;
            if (webView != null) webView.setBackgroundColor(canvas);
            Window window = getWindow();
            window.setStatusBarColor(canvas);
            window.setNavigationBarColor(canvas);
            window.getDecorView().setBackgroundColor(canvas);
            setLightSystemBars(window, !dark);
        });
    }

    /** Dark icons on light bars. API 30+ uses WindowInsetsController; 26–29 still need the View flags. */
    private static void setLightSystemBars(Window window, boolean light) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = window.getInsetsController();
            if (controller == null) return;
            int mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            controller.setSystemBarsAppearance(light ? mask : 0, mask);
        } else setLegacyLightSystemBars(window.getDecorView(), light);
    }

    @SuppressWarnings("deprecation")
    private static void setLegacyLightSystemBars(View decor, boolean light) {
        int flags = decor.getSystemUiVisibility();
        int lightBars = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        decor.setSystemUiVisibility(light ? flags | lightBars : flags & ~lightBars);
    }

    /** Mirrors --canvas in src/styles.css. */
    static int canvasColor(boolean dark, String tone) {
        if ("warm".equals(tone)) return dark ? 0xFF18171A : 0xFFEBE6DD;
        if ("neutral".equals(tone)) return dark ? 0xFF16181B : 0xFFE6E8EB;
        return dark ? 0xFF15181A : 0xFFE5E9E3;
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
