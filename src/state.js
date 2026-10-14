const listeners = new Set();
let state = { revision: 0, view: null, route: 'home', notice: null };

export function getState() { return state; }
export function subscribe(listener) { listeners.add(listener); return () => listeners.delete(listener); }

function emit(change) { listeners.forEach((listener) => listener(state, change)); }

export function setView(view, revision) {
  const nextRevision = Number(revision) || 0;
  if (nextRevision > 0 && nextRevision <= state.revision) return false;
  state = { ...state, view, revision: nextRevision || state.revision, route: view?.route || state.route };
  emit({ scope: 'view' });
  return true;
}

/** Merge a bounded native streaming projection without replacing structural application state. */
export function patchView(patch, revision) {
  const nextRevision = Number(revision) || 0;
  if (nextRevision > 0 && nextRevision <= state.revision) return false;
  const view = state.view;
  const current = view?.activeSession;
  if (!view || patch?.scope !== 'conversation.stream' || !current || patch.localSessionId !== current.localSessionId) return false;
  const sessionPatch = patch.session && typeof patch.session === 'object' ? patch.session : {};
  const nextSession = { ...current, ...sessionPatch };
  const item = patch.item;
  if (item && typeof item === 'object') {
    const localSeq = Number(item.localSeq);
    const messages = Array.isArray(current.messages) ? current.messages.slice() : [];
    const index = Number.isInteger(localSeq) && localSeq > 0 ? messages.findIndex((entry) => Number(entry?.localSeq) === localSeq) : -1;
    if (index >= 0) messages[index] = item;
    else if (Number.isInteger(localSeq) && localSeq > 0) messages.push(item);
    nextSession.messages = messages;
  }
  const sessions = Array.isArray(view.sessions) ? view.sessions.map((entry) => entry?.localSessionId === current.localSessionId ? { ...entry, ...sessionPatch } : entry) : view.sessions;
  const nextView = { ...view, activeSession: nextSession, sessions };
  state = { ...state, view: nextView, revision: nextRevision || state.revision, route: nextView.route || state.route };
  // The patched item and the view before it let the renderer patch just the live reply.
  emit({ scope: 'stream', item: item && typeof item === 'object' ? item : null, previous: view });
  return true;
}

/** Apply an immediate local turn-setting selection; the native full view remains authoritative. */
export function setOptimisticReasoningEffort(sessionId, reasoningEffort) {
  const view = state.view;
  const current = view?.activeSession;
  if (!view || !current || current.localSessionId !== sessionId) return false;
  const nextSession = { ...current, reasoningEffort };
  const sessions = Array.isArray(view.sessions)
    ? view.sessions.map((entry) => entry?.localSessionId === sessionId ? { ...entry, reasoningEffort } : entry)
    : view.sessions;
  const nextView = { ...view, activeSession: nextSession, sessions };
  state = { ...state, view: nextView };
  emit({ scope: 'stream' });
  return true;
}

/** Web mode is also a future-turn session setting; reflect the user's selection immediately. */
export function setOptimisticWebMode(sessionId, webMode) {
  const view = state.view;
  const current = view?.activeSession;
  if (!view || !current || current.localSessionId !== sessionId) return false;
  const nextSession = { ...current, webMode };
  const sessions = Array.isArray(view.sessions)
    ? view.sessions.map((entry) => entry?.localSessionId === sessionId ? { ...entry, webMode } : entry)
    : view.sessions;
  const nextView = { ...view, activeSession: nextSession, sessions };
  state = { ...state, view: nextView };
  emit({ scope: 'stream' });
  return true;
}

export function setNotice(notice) {
  state = { ...state, notice };
  emit({ scope: 'toast' });
}
