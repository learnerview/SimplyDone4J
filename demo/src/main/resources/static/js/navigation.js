import { $, $$ } from './ui.js';

const VIEWS = new Set(['overview', 'orders', 'jobs', 'dlq', 'engine', 'workload', 'architecture']);
let current = 'overview';
let onShow = () => {};

export function initNavigation(showHandler) {
  onShow = showHandler;
  $$('.nav-link').forEach(link => {
    link.onclick = () => showView(link.dataset.view);
  });
  $$('[data-view-target]').forEach(link => {
    link.onclick = event => {
      event.preventDefault();
      showView(link.dataset.viewTarget);
    };
  });

  const requested = location.hash.replace('#', '');
  showView(VIEWS.has(requested) ? requested : 'overview');
}

export function showView(name) {
  if (!VIEWS.has(name)) return;
  current = name;
  $$('.view').forEach(view => view.classList.toggle('active', view.id === `view-${name}`));
  $$('.nav-link').forEach(link => link.classList.toggle('active', link.dataset.view === name));
  history.replaceState(null, '', `#${name}`);
  window.scrollTo({ top: 0, behavior: 'smooth' });
  onShow(name);
}

export function currentView() {
  return current;
}

export function markActive(name) {
  $$('.nav-link').forEach(link => link.classList.toggle('active', link.dataset.view === name));
}

export function updateClock() {
  const clock = $('clock');
  if (clock) clock.textContent = new Date().toLocaleTimeString([], { hour12: false });
}
