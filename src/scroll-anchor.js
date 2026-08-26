const NEAR_BOTTOM_PX = 96;
const REENABLE_BOTTOM_PX = 32;
const states = new Map();

function stateFor(node) {
  const sessionId = node?.dataset?.sessionId || '';
  let state = states.get(sessionId);
  if (!state) {
    state = { lastScrollTop: Math.max(0, Number(node?.scrollTop) || 0), followLatest: true, programmatic: false };
    states.set(sessionId, state);
  }
  return { sessionId, state };
}

function bottomDistance(node) {
  if (!node) return Infinity;
  const scrollHeight = Math.max(0, Number(node.scrollHeight) || 0);
  const clientHeight = Math.max(0, Number(node.clientHeight) || 0);
  const scrollTop = Math.max(0, Number(node.scrollTop) || 0);
  return Math.max(0, scrollHeight - clientHeight - scrollTop);
}

/**
 * Mobile-chat follow policy: once the user scrolls upward, streaming output stops owning scroll.
 * Following resumes only after the user returns very close to the bottom or explicitly jumps there.
 */
export function noteTimelineScroll(node) {
  if (!node) return { nearBottom: true, followLatest: true };
  const { state } = stateFor(node);
  const nextTop = Math.max(0, Number(node.scrollTop) || 0);
  const distance = bottomDistance(node);
  if (!state.programmatic) {
    if (nextTop < state.lastScrollTop - 1) state.followLatest = false;
    else if (distance <= REENABLE_BOTTOM_PX) state.followLatest = true;
  }
  state.lastScrollTop = nextTop;
  return { nearBottom: distance <= NEAR_BOTTOM_PX, followLatest: state.followLatest };
}

export function setTimelineFollowLatest(sessionId, enabled) {
  if (!sessionId) return;
  const state = states.get(sessionId) || { lastScrollTop: 0, followLatest: true, programmatic: false };
  state.followLatest = Boolean(enabled);
  states.set(sessionId, state);
}

export function captureTimelineAnchor() {
  const node = document.querySelector('.timeline-scroll');
  if (!node) return null;
  const { sessionId, state } = stateFor(node);
  return {
    sessionId,
    scrollTop: Math.max(0, Number(node.scrollTop) || 0),
    followLatest: state.followLatest,
  };
}

export function restoreTimelineAnchor(anchor, sessionId) {
  const node = document.querySelector('.timeline-scroll');
  if (!node) return;
  const { state } = stateFor(node);
  const maxScrollTop = Math.max(0, node.scrollHeight - node.clientHeight);
  const sameSession = Boolean(anchor && anchor.sessionId === sessionId);
  const followLatest = sameSession ? Boolean(anchor.followLatest) : true;
  state.followLatest = followLatest;
  state.programmatic = true;
  node.scrollTop = followLatest ? maxScrollTop : Math.max(0, Math.min(anchor?.scrollTop || 0, maxScrollTop));
  state.lastScrollTop = node.scrollTop;
  requestAnimationFrame(() => {
    state.programmatic = false;
    state.lastScrollTop = Math.max(0, Number(node.scrollTop) || 0);
  });
}
