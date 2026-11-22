// Transient messages ("Rating not saved: …"). A tiny pub/sub so any module can
// raise one without importing UI code.

const listeners = new Set();
let nextId = 1;

export function toast(message, { tone = "info", ms = 4000 } = {}) {
  const entry = { id: nextId++, message, tone, ms };
  for (const fn of listeners) fn(entry);
}

export function onToast(fn) {
  listeners.add(fn);
  return () => listeners.delete(fn);
}
