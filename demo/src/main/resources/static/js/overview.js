import { api, readHealth } from './api.js';
import {
  escapeHtml, formatTime, humanize, money, number, pill, shortId
} from './format.js';
import { $, emptyState } from './ui.js';

const LANES = [
  ['HIGH', 'Priority customers', 'high'],
  ['NORMAL', 'Standard orders', 'normal'],
  ['LOW', 'Economy orders', 'low']
];

export async function refreshOverview() {
  const [summary, dashboard, orders, notifications, health] = await Promise.all([
    api('/api/operations/summary'),
    api('/api/dashboard'),
    api('/api/orders'),
    api('/api/operations/notifications'),
    readHealth()
  ]);
  renderHealthStrip(summary, health);
  renderMetrics(summary);
  renderQueues(summary);
  renderPipeline(summary, orders);
  renderWorkers(summary);
  renderRecentOrders(orders);
  renderRecentJobs(dashboard.jobs || []);
  renderActivity(dashboard.events || []);
  renderNotifications(notifications || []);
  updateSidebar(summary);
  return { summary, dashboard, orders, notifications, health };
}

export function updateSidebar(summary) {
  const engine = summary.engine || {};
  const depths = engine.queueDepths || summary.queueDepths || {};
  const waiting = Object.values(depths).reduce((total, value) => total + Number(value || 0), 0);
  if ($('sidebar-queue')) $('sidebar-queue').textContent = number(waiting);
  if ($('sidebar-workers')) {
    $('sidebar-workers').textContent = `${engine.workersInFlight || 0} / ${engine.workerCapacity || 0}`;
  }
  if ($('sidebar-dlq')) $('sidebar-dlq').textContent = number(summary.dlqCount || 0);
  if ($('nav-dlq-count')) $('nav-dlq-count').textContent = number(summary.dlqCount || 0);
}

function renderHealthStrip(summary, health) {
  const engine = summary.engine || {};
  const scheduler = engine.schedulerEnabled
    ? (engine.schedulerRunning ? 'Running' : 'Stopped')
    : 'Disabled';
  const items = [
    ['Engine', health.ok ? 'Healthy' : 'Needs attention', health.ok ? 'ok' : 'bad'],
    ['Redis', health.redis === 'UP' ? 'Connected' : 'Not reachable', health.redis === 'UP' ? 'ok' : 'bad'],
    ['Scheduler', scheduler, engine.schedulerRunning ? 'ok' : 'warn'],
    ['Workers', `${engine.workersInFlight || 0} busy / ${engine.workerCapacity || 0} total`, 'ok'],
    ['Dead letters', `${number(summary.dlqCount || 0)} need a decision`, summary.dlqCount ? 'warn' : 'ok']
  ];
  $('health-strip').innerHTML = items.map(([label, value, tone]) => `
    <div class="health-chip ${tone}"><span>${label}</span><b>${escapeHtml(value)}</b></div>
  `).join('');
}

function renderMetrics(summary) {
  const orders = summary.orders || {};
  const counts = summary.jobCounts || {};
  const success = counts.SUCCESS || 0;
  const failed = (counts.FAILED || 0) + (counts.DLQ || 0);
  const rate = summary.jobCompletionRate ?? (success + failed ? success * 100 / (success + failed) : 0);
  const cards = [
    ['Orders tracked', number(orders.tracked || 0), 'Customer orders saved in this demo'],
    ['Active now', number(orders.active || 0), 'Orders still moving through fulfillment'],
    ['Delivered value', money(orders.revenue || 0), `${number(orders.completed || 0)} orders reached the customer`],
    ['Job success rate', `${Number(rate).toFixed(1)}%`, `${number(success)} finished, ${number(failed)} failed`]
  ];
  $('metrics').innerHTML = cards.map(([label, value, foot]) => `
    <div class="metric">
      <span class="metric-label">${label}</span>
      <b class="metric-value">${escapeHtml(value)}</b>
      <span class="metric-foot">${escapeHtml(foot)}</span>
    </div>
  `).join('');
}

function renderQueues(summary) {
  const depths = summary.queueDepths || summary.engine?.queueDepths || {};
  const max = Math.max(1, Number(summary.queueMaxDepth || 0), ...Object.values(depths).map(Number));
  $('queue-lanes').innerHTML = LANES.map(([key, description, className]) => {
    const depth = Number(depths[key] || 0);
    const width = Math.max(0, Math.min(100, depth * 100 / max));
    return `
      <div class="queue-lane ${className}">
        <div class="lane-head"><b>${escapeHtml(key)} <span class="muted">- ${description}</span></b><span>${number(depth)} waiting</span></div>
        <div class="lane-track" title="${number(depth)} jobs waiting in the ${key} lane"><div class="lane-fill" style="width:${width}%"></div></div>
      </div>
    `;
  }).join('');
}

function renderPipeline(summary, orders) {
  const counts = summary.jobCounts || {};
  const steps = [
    ['1', 'Order saved', `${number(orders.length)} orders`, 'Customer records'],
    ['2', 'Waiting safely', `${number((counts.QUEUED || 0) + (counts.RETRY_SCHEDULED || 0))} jobs`, 'In the queue'],
    ['3', 'Worker running', `${number(counts.PROCESSING || 0)} jobs`, 'Being processed now'],
    ['4', 'Finished', `${number(counts.SUCCESS || 0)} jobs`, 'Work completed']
  ];
  $('pipeline').innerHTML = steps.map(([index, label, value, note], position) => `
    ${position ? '<span class="pipeline-arrow">&rarr;</span>' : ''}
    <div class="pipeline-step"><b>${index}</b><span>${escapeHtml(label)}</span><small>${escapeHtml(value)} - ${escapeHtml(note)}</small></div>
  `).join('');
}

function renderWorkers(summary) {
  const engine = summary.engine || {};
  const busy = Number(engine.workersInFlight || 0);
  const capacity = Number(engine.workerCapacity || 0);
  const free = Math.max(0, capacity - busy);
  const percent = capacity ? Math.min(100, busy * 100 / capacity) : 0;
  const queueLimit = summary.queueMaxDepth || 0;
  $('worker-panel').innerHTML = `
    <div class="worker-gauge" style="--used:${percent}%"><div><b>${busy}/${capacity}</b><small>workers busy</small></div></div>
    <div class="worker-facts">
      <div><span>Free worker slots</span><b>${free}</b></div>
      <div><span>Jobs waiting in queues</span><b>${number(totalQueue(summary))}</b></div>
      <div><span>Safe queue limit</span><b>${number(queueLimit)}</b></div>
      <div><span>What this means</span><b>${busy === capacity && capacity ? 'Work waits briefly' : 'Capacity available'}</b></div>
    </div>
  `;
}

function totalQueue(summary) {
  return Object.values(summary.queueDepths || {}).reduce((total, value) => total + Number(value || 0), 0);
}

function renderRecentOrders(orders) {
  const container = $('recent-orders');
  const recent = [...orders].sort((a, b) => new Date(b.updatedAt) - new Date(a.updatedAt)).slice(0, 5);
  if (!recent.length) {
    container.innerHTML = emptyState('No orders yet. Place the first one from checkout.');
    return;
  }
  container.innerHTML = recent.map(order => `
    <div class="order-row order-link" data-order-id="${escapeHtml(order.orderId)}">
      <div class="order-main">
        <b>${escapeHtml(order.orderId)} - ${escapeHtml(order.customer)}</b>
        <small>${order.quantity} x ${escapeHtml(order.productName)} - ${escapeHtml(order.priority)} lane</small>
      </div>
      ${pill(order.status)}
      <span class="order-meta">${money(order.total)} - ${formatTime(order.updatedAt)}</span>
    </div>
  `).join('');
  bindClicks(container, '[data-order-id]', 'demo:order-open', 'orderId');
}

function renderRecentJobs(jobs) {
  const container = $('recent-jobs');
  const recent = jobs.slice(0, 5);
  container.innerHTML = recent.length ? recent.map(job => `
    <div class="compact-row job-link" data-job-id="${escapeHtml(job.id)}">
      <span><b>${escapeHtml(humanize(job.jobType))}</b><small class="mono">${escapeHtml(shortId(job.id))} - ${escapeHtml(job.priority)} lane</small></span>
      ${pill(job.status)}
    </div>
  `).join('') : emptyState('No jobs yet. The engine is waiting for work.');
  bindClicks(container, '[data-job-id]', 'demo:job-open', 'jobId');
}

function renderActivity(events) {
  const container = $('activity');
  container.innerHTML = events.length ? events.slice(0, 8).map(event => `
    <div class="activity-row">
      <span class="activity-time">${formatTime(event.timestamp)}</span>
      <span><b>${escapeHtml(humanize(event.event))}</b> - ${escapeHtml(humanize(event.jobType))}<br>
        <small class="mono">${escapeHtml(shortId(event.jobId))} ${event.attempt ? `attempt ${event.attempt}` : ''}</small></span>
    </div>
  `).join('') : emptyState('Waiting for the next engine event.');
}

function renderNotifications(notifications) {
  const container = $('notifications');
  container.innerHTML = notifications.length ? notifications.slice(0, 8).map(item => `
    <div class="notification-row">
      <span class="activity-time">${formatTime(item.timestamp)}</span>
      <span><b>${escapeHtml(humanize(item.type))}</b><br><small>${escapeHtml(item.message)}</small></span>
    </div>
  `).join('') : emptyState('No customer notifications yet.');
}

function bindClicks(container, selector, eventName, key) {
  container.querySelectorAll(selector).forEach(row => {
    row.onclick = () => document.dispatchEvent(new CustomEvent(eventName, {
      detail: { [key]: row.dataset[key] }
    }));
  });
}
