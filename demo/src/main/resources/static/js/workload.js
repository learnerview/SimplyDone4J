import { api, json, post } from './api.js';
import {
  escapeHtml, formatTime, humanize, money, number, pill, shortId
} from './format.js';
import { $, emptyState, toast, withBusy } from './ui.js';

let handlers = [];
let products = [];
let scenarios = [];

export function initWorkload() {
  $('custom-job-form').onsubmit = submitCustomJob;
  $('batch-job-form').onsubmit = submitJobBatch;
  $('batch-order-form').onsubmit = submitOrderBatch;
}

export async function refreshWorkload() {
  const [freshHandlers, catalog, freshScenarios] = await Promise.all([
    api('/api/handlers'),
    api('/api/operations/catalog'),
    api('/api/scenarios')
  ]);
  handlers = freshHandlers;
  products = catalog;
  scenarios = freshScenarios;
  fillHandlers();
  fillProducts();
  renderScenarios();
  renderCatalog();
  return { handlers, products, scenarios };
}

function fillHandlers() {
  [['custom-job-type', 'custom'], ['batch-job-type', 'batch']].forEach(([id, label]) => {
    const select = $(id);
    const previous = select.value;
    select.innerHTML = handlers.map(handler => `
      <option value="${escapeHtml(handler)}">${escapeHtml(handler)}${handler.includes('dlq') ? ' - fails once, then succeeds' : ''}</option>
    `).join('');
    if (handlers.includes(previous)) select.value = previous;
    select.dataset.kind = label;
  });
}

function fillProducts() {
  const select = $('batch-order-sku');
  const previous = select.value;
  select.innerHTML = products.map(product => `
    <option value="${escapeHtml(product.sku)}">${escapeHtml(product.name)} - ${money(product.price)} (${product.stock} left)</option>
  `).join('');
  if (products.some(product => product.sku === previous)) select.value = previous;
}

function renderScenarios() {
  $('scenarios').innerHTML = scenarios.map(scenario => `
    <article class="scenario-card">
      <h3>${escapeHtml(scenario.title)}</h3>
      <p>${escapeHtml(scenario.description)}</p>
      <button class="button secondary" data-scenario="${escapeHtml(scenario.name)}">Run this story</button>
    </article>
  `).join('') || emptyState('No scenarios are available.');
  $('scenarios').querySelectorAll('[data-scenario]').forEach(button => {
    button.onclick = () => runScenario(button.dataset.scenario, button);
  });
}

function renderCatalog() {
  $('catalog').innerHTML = products.map(product => `
    <div class="product-row">
      <span><b>${escapeHtml(product.name)}</b><small>${escapeHtml(product.category)} - ${money(product.price)}</small></span>
      <span class="stock ${product.stock <= 5 ? 'low' : ''}">${number(product.stock)} in stock</span>
      <button class="text-button restock" data-restock="${escapeHtml(product.sku)}">Restock +10</button>
    </div>
  `).join('') || emptyState('No products configured.');
  $('catalog').querySelectorAll('[data-restock]').forEach(button => {
    button.onclick = () => restock(button.dataset.restock, button);
  });
}

function readPayload(id) {
  const value = $(id).value.trim();
  if (!value) return {};
  try {
    return JSON.parse(value);
  } catch {
    throw new Error('The payload is not valid JSON. Use double quotes around text and numbers without quotes.');
  }
}

function numberOrNull(id) {
  const value = $(id).value.trim();
  return value ? Number(value) : null;
}

function idempotencyKey() {
  return crypto.randomUUID?.()
    || `demo-${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

function delayDate(id) {
  const delay = Number($(id).value || 0);
  return delay > 0 ? new Date(Date.now() + delay).toISOString() : null;
}

async function submitCustomJob(event) {
  event.preventDefault();
  const button = event.submitter;
  try {
    const payload = readPayload('custom-job-payload');
    const response = await withBusy(button, () => post('/api/jobs', {
      jobType: $('custom-job-type').value,
      idempotencyKey: idempotencyKey(),
      priority: $('custom-job-priority').value,
      payload,
      maxAttempts: numberOrNull('custom-job-attempts'),
      timeoutSeconds: numberOrNull('custom-job-timeout'),
      nextRunAt: delayDate('custom-job-delay')
    }, { 'X-Producer': 'workload-lab' }), 'Submitting...');
    toast(`Job ${shortId(response.jobId)} accepted in the ${response.priority} lane.`);
    renderSingleResult(response);
    document.dispatchEvent(new CustomEvent('demo:changed'));
  } catch (error) {
    toast(error.message);
  }
}

async function submitJobBatch(event) {
  event.preventDefault();
  const button = event.submitter;
  try {
    const payload = readPayload('batch-job-payload');
    const result = await withBusy(button, () => post('/api/workload/jobs', {
      jobType: $('batch-job-type').value,
      count: Number($('batch-job-count').value),
      priority: $('batch-job-priority').value,
      payload,
      maxAttempts: numberOrNull('batch-job-attempts'),
      timeoutSeconds: numberOrNull('batch-job-timeout'),
      delayMs: Number($('batch-job-delay').value || 0),
      staggerMs: Number($('batch-job-stagger').value || 0)
    }), 'Generating...');
    toast(`${result.accepted} of ${result.requested} jobs accepted.`);
    renderBatchResult('job burst', result);
    document.dispatchEvent(new CustomEvent('demo:changed'));
  } catch (error) {
    toast(error.message);
  }
}

async function submitOrderBatch(event) {
  event.preventDefault();
  const button = event.submitter;
  try {
    const result = await withBusy(button, () => post('/api/workload/orders', {
      count: Number($('batch-order-count').value),
      sku: $('batch-order-sku').value,
      quantity: Number($('batch-order-quantity').value),
      priority: $('batch-order-priority').value,
      simulatePaymentRetry: $('batch-order-retry').checked
    }), 'Placing orders...');
    toast(`${result.accepted} of ${result.requested} orders placed.`);
    renderBatchResult('order batch', result);
    document.dispatchEvent(new CustomEvent('demo:changed'));
  } catch (error) {
    toast(error.message);
  }
}

function renderSingleResult(response) {
  $('workload-result-body').innerHTML = `
    <div class="result-summary">
      <span class="badge ${escapeHtml(response.status)}">${escapeHtml(response.status)}</span>
      <b>${escapeHtml(humanize(response.jobType))}</b>
      <span class="muted">ticket ${escapeHtml(shortId(response.jobId))} in the ${escapeHtml(response.priority)} lane</span>
    </div>
    <p class="muted">Open Jobs to watch this ticket move from waiting to running to done.</p>
  `;
}

function renderBatchResult(kind, result) {
  const acceptedJobs = result.jobs || [];
  const acceptedOrders = result.orders || [];
  const failures = result.failures || [];
  const acceptedRows = acceptedJobs.map(job => `
    <div class="workload-result-row">
      <span><b class="mono">${escapeHtml(shortId(job.jobId))}</b> ${escapeHtml(humanize(job.jobType))}</span>
      ${pill(job.status)}
    </div>
  `).concat(acceptedOrders.map(order => `
    <div class="workload-result-row">
      <span><b class="mono">${escapeHtml(order.orderId)}</b> ${escapeHtml(order.customer)}</span>
      ${pill(order.status)}
    </div>
  `)).join('');
  const failureRows = failures.map(failure => `
    <div class="workload-result-row failure-row">
      <span>Item ${failure.index + 1}</span><span>${escapeHtml(failure.message)}</span>
    </div>
  `).join('');

  $('workload-result-body').innerHTML = `
    <div class="result-summary">
      <b>${number(result.accepted)} of ${number(result.requested)} accepted</b>
      <span class="muted">${number(result.failed)} rejected - ${escapeHtml(kind)}</span>
    </div>
    <div class="workload-result-list">${acceptedRows || emptyState('No items were accepted.')}</div>
    ${failureRows ? `<h3 class="detail-section-title">Rejected items</h3><div class="workload-result-list">${failureRows}</div>` : ''}
    <p class="muted">Every accepted item is real. Open Jobs or Orders to follow it through the engine.</p>
  `;
}

async function runScenario(name, button) {
  try {
    const result = await withBusy(button, () => post(`/api/scenarios/${encodeURIComponent(name)}`, {}), 'Running...');
    toast(result.note);
    renderScenarioResult(result);
    document.dispatchEvent(new CustomEvent('demo:changed'));
  } catch (error) {
    toast(error.message);
  }
}

function renderScenarioResult(result) {
  const jobs = result.jobs || [];
  const jobsHtml = jobs.map(job => `
    <div class="workload-result-row">
      <span><b class="mono">${escapeHtml(shortId(job.jobId))}</b> ${escapeHtml(humanize(job.jobType))}</span>
      <span>${pill(job.status)}<small class="muted"> ${formatTime(job.scheduledAt)}</small></span>
    </div>
  `).join('');
  const detailsHtml = Object.entries(result.details || {}).map(([key, value]) => `
    <div class="workload-result-row"><span>${escapeHtml(humanize(key))}</span><b>${escapeHtml(json(value))}</b></div>
  `).join('');

  $('workload-result-body').innerHTML = `
    <div class="result-summary"><b>${escapeHtml(result.scenario)}</b><span class="muted">${jobs.length} jobs created</span></div>
    <p>${escapeHtml(result.note)}</p>
    <div class="workload-result-list">${jobsHtml}</div>
    ${detailsHtml ? `<h3 class="detail-section-title">Evidence</h3><div class="workload-result-list">${detailsHtml}</div>` : ''}
    <p class="muted">Open Jobs to inspect attempt history, or Dead letters if a job needs recovery.</p>
  `;
}

async function restock(sku, button) {
  try {
    const product = await withBusy(button, () => post(`/api/inventory/${encodeURIComponent(sku)}/restock`, { quantity: 10 }), 'Restocking...');
    toast(`${product.name} now has ${product.stock} units.`);
    await refreshWorkload();
  } catch (error) {
    toast(error.message);
  }
}
