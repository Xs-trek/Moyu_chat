package com.moyu.chat;

import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-scoped Chat runtime. The Activity/WebView is only a presentation attachment; provider
 * turns and durable state continue while the UI is paused or recreated.
 */
final class ChatRuntime implements ChatCoordinator.Host {
    private final MoyuChatApplication app;
    private final ChatCoordinator coordinator;
    private final Set<String> activeTurnIds = ConcurrentHashMap.newKeySet();
    private WeakReference<MainActivity> activity = new WeakReference<>(null);
    private boolean resumed;
    private String deferredMessage;

    ChatRuntime(MoyuChatApplication app) {
        this.app = app;
        this.coordinator = new ChatCoordinator(app, this);
    }

    ChatCoordinator coordinator() { return coordinator; }

    synchronized void attach(MainActivity next) {
        activity = new WeakReference<>(next);
    }

    synchronized void setResumed(MainActivity owner, boolean value) {
        MainActivity current = activity.get();
        if (current != owner) return;
        resumed = value;
        if (value) {
            String message = deferredMessage;
            deferredMessage = null;
            if (message != null && !message.isEmpty()) owner.showMessage(message);
            coordinator.refreshView();
        }
    }

    synchronized void detach(MainActivity owner) {
        if (activity.get() == owner) {
            resumed = false;
            activity.clear();
        }
    }

    private synchronized MainActivity visibleActivity() {
        MainActivity current = activity.get();
        return resumed ? current : null;
    }

    @Override public void dispatch(JSONObject envelope) {
        MainActivity current = visibleActivity();
        if (current != null) current.dispatch(envelope);
    }

    @Override public void showMessage(String text) {
        MainActivity current = visibleActivity();
        if (current != null) current.showMessage(text);
        else synchronized (this) { deferredMessage = text; }
    }

    @Override public void openCredentialEditor(ChatDatabase.Provider provider) {
        MainActivity current = visibleActivity();
        if (current != null) current.openCredentialEditor(provider);
    }

    @Override public void openImagePicker(String sessionId) {
        MainActivity current = visibleActivity();
        if (current != null) current.openImagePicker(sessionId);
    }

    @Override public void applyAppearance(String resolvedTheme, String tone) {
        MainActivity current = activity.get();
        if (current != null) current.applyAppearance(resolvedTheme, tone);
    }

    @Override public void openExternalUrl(String url) {
        MainActivity current = visibleActivity();
        if (current != null) current.openExternalUrl(url);
    }

    @Override public synchronized boolean setTurnRuntimeActive(String sessionId, boolean active) {
        if (sessionId == null || sessionId.isEmpty()) return false;
        if (active) {
            if (activeTurnIds.contains(sessionId)) return true;
            if (activeTurnIds.isEmpty() && !ChatTurnService.start(app)) return false;
            activeTurnIds.add(sessionId);
            return true;
        }
        activeTurnIds.remove(sessionId);
        if (activeTurnIds.isEmpty()) ChatTurnService.stop(app);
        return true;
    }
}
