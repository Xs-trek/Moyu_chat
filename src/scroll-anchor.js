const NEAR_BOTTOM_PX = 96;

export function captureTimelineAnchor() {
  const node = document.querySelector('.timeline-scroll');
  if (!node) return null;
  const maxScrollTop = Math.max(0, node.scrollHeight - node.clientHeight);
  const bottomDistance = Math.max(0, maxScrollTop - node.scrollTop);
  return {
    sessionId: node.dataset.sessionId || '',
    scrollTop: node.scrollTop,
    nearBottom: bottomDistance <= NEAR_BOTTOM_PX,
  };
}

export function restoreTimelineAnchor(anchor, sessionId) {
  const node = document.querySelector('.timeline-scroll');
  if (!node) return;
  const maxScrollTop = Math.max(0, node.scrollHeight - node.clientHeight);
  if (!anchor || anchor.sessionId !== sessionId) {
    node.scrollTop = maxScrollTop;
    return;
  }
  node.scrollTop = anchor.nearBottom ? maxScrollTop : Math.min(anchor.scrollTop, maxScrollTop);
}
