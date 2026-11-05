import { api } from './api.js';
import { refreshArchitecture } from './architecture.js';
import { refreshDlq, initDlq } from './dlq.js';
import { initEngine, refreshEngine } from './engine.js';
import { initJobs, openJobDetail, refreshJobs } from './jobs.js';
import { currentView, initNavigation, showView, updateClock } from './navigation.js';
import { initOrders, refreshOrders } from './orders.js';
import { refreshOverview, updateSidebar } from './overview.js';
import { bindDialogClosers, $, openDialog, toast } from './ui.js';
import { initWorkload, refreshWorkload } from './workload.js';

const REFRESHERS = {
  overview: refreshOverview,
  orders: refreshOrders,
  jobs: refreshJobs,
  dlq: refreshDlq,
  engine: refreshEngine,
  workload: refreshWorkload,
  architecture: refreshArchitecture
};

const AUTO_REFRESH_VIEWS = new Set(['overview', 'orders', 'jobs', 'dlq', 'engine']);

async function refreshView(name = currentView()) {
  try {
    await REFRESHERS[name]?.();
  } catch (error) {
    toast(error.message);
  }
}

async function refreshSidebar() {
  try {
    updateSidebar(await api('/api/operations/summary'));
  } catch {
    /* the next full refresh will retry */
  }
}

initOrders();
initJobs();
initDlq();
initEngine();
initWorkload();
bindDialogClosers();
initNavigation(name => refreshView(name));

$('help-button').onclick = () => openDialog('help-dialog');
$('refresh-now').onclick = () => refreshView();
$('auto-refresh').onchange = event => {
  if (event.target.checked) refreshView();
};

document.addEventListener('demo:changed', () => {
  refreshView();
  refreshSidebar();
});
document.addEventListener('demo:job-open', event => {
  showView('jobs');
  openJobDetail(event.detail.jobId);
});

updateClock();
setInterval(updateClock, 1000);
setInterval(() => {
  if ($('auto-refresh').checked && AUTO_REFRESH_VIEWS.has(currentView())) {
    refreshView();
  }
}, 6000);
