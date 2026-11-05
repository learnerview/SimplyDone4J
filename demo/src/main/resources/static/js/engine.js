import { api } from './api.js';
import { escapeHtml, humanize, number, statusLabel } from './format.js';
import { $, emptyState, toast } from './ui.js';

const LANES = [
  ['HIGH', 'Priority customers', 'high'],
  ['NORMAL', 'Standard orders', 'normal'],
  ['LOW', 'Economy orders', 'low']
];

export function initEngine() {
  $('refresh-engine').onclick = () => refreshEngine().catch(error => toast(error.message));
}

export async function refreshEngine() {
  const [engine, summary] = await Promise.all([
    api('/api/engine'),
    api('/api/operations/summary')
  ]);
  renderHero(engine);
  renderCards(engine);
  renderQueues(engine, summary);
  renderJobCounts(summary);
  renderConfig(engine, summary);
  return { engine, summary };
}

function isHealthy(engine) {
  return engine.redisReachable
    && (!engine.schedulerEnabled || (engine.schedulerRunning && engine.maintenanceRunning));
}

function renderHero(engine) {
  const healthy = isHealthy(engine);
  $('engine-hero').innerHTML = `
    <div>
      <p class="eyebrow">ENGINE STATUS</p>
      <h2>${healthy ? 'The kitchen is open' : 'The kitchen needs attention'}</h2>
      <p>${healthy
        ? 'Redis is reachable and both background loops are alive. New work can be accepted safely.'
        : 'At least one dependency or background loop is not healthy. Check the cards below before submitting more work.'}</p>
    </div>
    <span class="badge ${healthy ? 'UP' : 'DOWN'}">${healthy ? 'Healthy' : 'Needs attention'}</span>
  `;
}

function renderCards(engine) {
  const cards = [
    ['Redis connection', engine.redisReachable ? 'Connected' : 'Not reachable', engine.redisReachable ? 'The shared waiting room is available.' : 'The engine cannot store or read jobs.', engine.redisReachable ? 'ok' : 'bad'],
    ['Scheduler loop', engine.schedulerEnabled ? (engine.schedulerRunning ? 'Running' : 'Stopped') : 'Disabled', engine.schedulerEnabled ? 'Picks due jobs from the queues.' : 'Scheduling is turned off for this instance.', engine.schedulerRunning ? 'ok' : 'warn'],
    ['Maintenance loop', engine.maintenanceRunning ? 'Running' : 'Stopped', 'Recovers expired leases and delayed work.', engine.maintenanceRunning ? 'ok' : 'bad'],
    ['Workers', `${engine.workersInFlight || 0} / ${engine.workerCapacity || 0}`, 'Busy slots compared with total capacity.', 'ok']
  ];
  $('engine-cards').innerHTML = cards.map(([label, value, foot, tone]) => `
    <div class="metric">
      <span class="metric-label">${escapeHtml(label)}</span>
      <b class="metric-value ${tone}">${escapeHtml(value)}</b>
      <span class="metric-foot">${escapeHtml(foot)}</span>
    </div>
  `).join('');
}

function renderQueues(engine, summary) {
  const depths = engine.queueDepths || summary.queueDepths || {};
  const max = Math.max(1, Number(summary.queueMaxDepth || 0), ...Object.values(depths).map(Number));
  $('engine-queues').innerHTML = LANES.map(([key, description, className]) => {
    const depth = Number(depths[key] || 0);
    const width = Math.min(100, depth * 100 / max);
    return `
      <div class="queue-lane ${className}">
        <div class="lane-head"><b>${escapeHtml(key)} <span class="muted">- ${description}</span></b><span>${number(depth)} waiting</span></div>
        <div class="lane-track"><div class="lane-fill" style="width:${width}%"></div></div>
      </div>
    `;
  }).join('');
}

function renderJobCounts(summary) {
  const counts = summary.jobCounts || {};
  const values = Object.entries(counts);
  const max = Math.max(1, ...values.map(([, count]) => Number(count)));
  $('engine-job-counts').innerHTML = values.length ? values.map(([status, count]) => `
    <div class="state-row">
      <span>${escapeHtml(statusLabel(status))}</span>
      <div class="state-track"><div class="state-fill" style="width:${Number(count) * 100 / max}%"></div></div>
      <b>${number(count)}</b>
    </div>
  `).join('') : emptyState('No job records yet.');
}

function renderConfig(engine, summary) {
  const items = [
    ['Scheduler enabled', engine.schedulerEnabled ? 'Yes' : 'No'],
    ['Worker slots', number(summary.workerSlots || engine.workerCapacity || 0)],
    ['Max attempts', number(summary.maxAttempts || 0)],
    ['Poll interval', `${number(summary.pollingIntervalMs || 0)} ms`],
    ['Queue limit', number(summary.queueMaxDepth || 0)],
    ['Dead letters', number(summary.dlqCount || 0)],
    ['Health', humanize(engine.redisReachable ? 'redis reachable' : 'redis down')],
    ['Last checked', summary.generatedAt ? new Date(summary.generatedAt).toLocaleTimeString() : 'now']
  ];
  $('engine-config').innerHTML = items.map(([label, value]) => `
    <div class="config-item"><span>${escapeHtml(label)}</span><b>${escapeHtml(value)}</b></div>
  `).join('');
}
