const listeners = new Set();
let state = {
  revision: 0,
  view: null,
  route: 'home',
  notice: null,
};

export function getState() { return state; }
export function subscribe(listener) { listeners.add(listener); return () => listeners.delete(listener); }
export function setView(view, revision) {
  state = { ...state, view, revision, route: view.route || state.route };
  listeners.forEach((listener) => listener(state));
}
export function setRoute(route) {
  state = { ...state, route };
  listeners.forEach((listener) => listener(state));
}
export function setNotice(notice) {
  state = { ...state, notice };
  listeners.forEach((listener) => listener(state));
}
