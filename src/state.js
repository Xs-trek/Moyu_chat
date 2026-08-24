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

export function setNotice(notice) {
  state = { ...state, notice };
  emit({ scope: 'toast' });
}
