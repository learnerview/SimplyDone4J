import { api, del, post } from './api.js';
import {
  escapeHtml, formatDateTime, formatTime, money, number, pill, stageLabel
} from './format.js';
import { $, confirmAction, emptyState, openDialog, toast, withBusy } from './ui.js';

let orders = [];
let products = [];
let detailOrderId = null;

const NEXT_STEP = {
  ACCEPTED: 'The order is saved. The background job will pick it up shortly.',
  PROCESSING: 'A worker is handling this order right now.',
  RETRYING: 'A temporary problem occurred. The engine is waiting before trying again.',
  COMPLETED: 'The customer has the order. No action is needed.',
  CANCELLED: 'This order was cancelled and its reserved stock was released.',
  FAILED: 'This order could not finish. Open the linked job to see the exact failure.'
};

export function initOrders() {
  $('new-order').onclick = openOrderDialog;
  $('new-order-2').onclick = openOrderDialog;
  $('refresh-orders').onclick = () => refreshOrders().catch(error => toast(error.message));
  $('order-search').oninput = renderTable;
  $('order-filter').onchange = renderTable;
  $('order-form').onsubmit = submitOrder;
  $('sku').onchange = updatePreview;
  $('quantity').oninput = updatePreview;
  $('close-detail').onclick = () => $('order-detail-dialog').close();
  $('order-detail-dialog').addEventListener('close', () => { detailOrderId = null; });
  document.addEventListener('demo:order-open', event => openDetail(event.detail.orderId));
}

export async function refreshOrders() {
  orders = await api('/api/orders');
  renderStats();
  renderTable();
  if ($('order-detail-dialog').open && detailOrderId) {
    await openDetail(detailOrderId, false);
  }
  return orders;
}

function renderStats() {
  const counts = orders.reduce((result, order) => {
    result[order.status] = (result[order.status] || 0) + 1;
    return result;
  }, {});
  const revenue = orders.filter(order => order.status === 'COMPLETED')
    .reduce((total, order) => total + order.total, 0);
  const cards = [
    ['All orders', orders.length, 'Records saved by the app'],
    ['In progress', (counts.ACCEPTED || 0) + (counts.PROCESSING || 0) + (counts.RETRYING || 0), 'Being handled now'],
    ['Delivered', counts.COMPLETED || 0, `${money(revenue)} of completed value`],
    ['Needs attention', (counts.FAILED || 0) + (counts.CANCELLED || 0), 'Failed or cancelled orders']
  ];
  $('order-stats').innerHTML = cards.map(([label, value, foot]) => `
    <div class="stat"><span>${label}</span><b>${number(value)}</b><small class="muted">${escapeHtml(foot)}</small></div>
  `).join('');
}

function visibleOrders() {
  const search = ($('order-search').value || '').trim().toLowerCase();
  const filter = $('order-filter').value;
  return orders.filter(order => {
    const haystack = `${order.orderId} ${order.customer} ${order.productName} ${order.sku}`.toLowerCase();
    return (!search || haystack.includes(search)) && (filter === 'ALL' || order.status === filter);
  });
}

function renderTable() {
  const rows = visibleOrders();
  const target = $('orders-table');
  if (!rows.length) {
    target.innerHTML = emptyState('No orders match this view. Try clearing the search or placing an order.');
    return;
  }
  target.innerHTML = `
    <div class="table-wrap"><table><thead><tr>
      <th>Order</th><th>Customer</th><th>Product</th><th>Qty</th><th>Value</th>
      <th>Lane</th><th>Progress</th><th>Status</th><th>Updated</th><th>Action</th>
    </tr></thead><tbody>
      ${rows.map(order => `
        <tr class="order-link" data-order-id="${escapeHtml(order.orderId)}">
          <td><b>${escapeHtml(order.orderId)}</b><br><small class="mono">${escapeHtml((order.jobId || 'pending').slice(0, 8))}</small></td>
          <td>${escapeHtml(order.customer)}</td>
          <td>${escapeHtml(order.productName)}<br><small>${escapeHtml(order.sku)}</small></td>
          <td>${number(order.quantity)}</td>
          <td>${money(order.total)}</td>
          <td>${escapeHtml(order.priority)}</td>
          <td>${escapeHtml(stageLabel(order.stage))}</td>
          <td>${pill(order.status)}</td>
          <td>${formatTime(order.updatedAt)}</td>
          <td>${['ACCEPTED', 'PROCESSING', 'RETRYING'].includes(order.status)
            ? `<button class="text-button cancel-order" data-id="${escapeHtml(order.orderId)}">Cancel</button>`
            : '<span class="muted">-</span>'}</td>
        </tr>
      `).join('')}
    </tbody></table></div>`;

  target.querySelectorAll('.order-link').forEach(row => {
    row.onclick = () => openDetail(row.dataset.orderId);
  });
  target.querySelectorAll('.cancel-order').forEach(button => {
    button.onclick = async event => {
      event.stopPropagation();
      await cancelOrder(button.dataset.id, button);
    };
  });
}

async function openOrderDialog() {
  products = await api('/api/operations/catalog').catch(() => products);
  const select = $('sku');
  select.innerHTML = products.map(product => `
    <option value="${escapeHtml(product.sku)}">${escapeHtml(product.name)} - ${money(product.price)} (${product.stock} left)</option>
  `).join('');
  $('order-form').reset();
  $('customer').value = 'Asha Patel';
  $('quantity').value = '2';
  updatePreview();
  openDialog('order-dialog');
}

function updatePreview() {
  const product = products.find(item => item.sku === $('sku').value);
  const quantity = Math.max(1, Number($('quantity').value) || 1);
  $('order-preview').textContent = product
    ? `${quantity} x ${product.name} = ${money(product.price * quantity)} - ${product.stock} in stock`
    : 'Choose a product to continue.';
}

async function submitOrder(event) {
  event.preventDefault();
  const button = $('submit-order');
  try {
    const order = await withBusy(button, () => post('/api/orders', {
      customer: $('customer').value.trim(),
      sku: $('sku').value,
      quantity: Number($('quantity').value),
      priority: $('priority').value,
      simulatePaymentRetry: $('simulate-retry').checked
    }), 'Placing order...');
    $('order-dialog').close();
    toast(`${order.orderId} accepted. The engine will handle it in the background.`);
    document.dispatchEvent(new CustomEvent('demo:changed'));
    await refreshOrders();
  } catch (error) {
    toast(error.message);
  }
}

async function openDetail(orderId, show = true) {
  detailOrderId = orderId;
  try {
    const order = await api(`/api/orders/${encodeURIComponent(orderId)}`);
    renderDetail(order);
    if (show) openDialog('order-detail-dialog');
  } catch (error) {
    toast(error.message);
    if ($('order-detail-dialog').open) $('order-detail-dialog').close();
  }
}

function renderDetail(order) {
  const timeline = [...(order.timeline || [])].reverse();
  const events = timeline.length ? timeline.map(event => `
    <div class="timeline-event">
      <span class="timeline-dot"></span>
      <div><b>${escapeHtml(stageLabel(event.stage))}</b><span class="timeline-time">${formatDateTime(event.at)}</span>
        <p>${escapeHtml(event.message)}</p></div>
    </div>
  `).join('') : emptyState('No lifecycle events yet.');

  $('order-detail-body').innerHTML = `
    <div class="detail-head">
      <div><p class="eyebrow">ORDER RECORD</p><h2>${escapeHtml(order.orderId)}</h2>
        <p class="subheading">${escapeHtml(order.customer)} - ${escapeHtml(order.productName)}</p></div>
      <div class="detail-head-status">${pill(order.status)}</div>
    </div>
    <div class="explainer compact">
      <span class="explainer-icon">i</span>
      <div><strong>What happens next?</strong><p>${escapeHtml(NEXT_STEP[order.status] || 'Refresh to see the latest state.')}</p></div>
    </div>
    <div class="detail-grid">
      <div class="detail-item"><small>Progress</small><b>${escapeHtml(stageLabel(order.stage))}</b></div>
      <div class="detail-item"><small>Quantity</small><b>${number(order.quantity)}</b></div>
      <div class="detail-item"><small>Order value</small><b>${money(order.total)}</b></div>
      <div class="detail-item"><small>Priority lane</small><b>${escapeHtml(order.priority)}</b></div>
      <div class="detail-item"><small>Job ticket</small><b class="mono">${escapeHtml(order.jobId || 'not assigned')}</b></div>
      <div class="detail-item"><small>Tracking</small><b>${escapeHtml(order.trackingNumber || 'not shipped')}</b></div>
      <div class="detail-item"><small>Created</small><b>${formatDateTime(order.createdAt)}</b></div>
      <div class="detail-item"><small>Last update</small><b>${formatDateTime(order.updatedAt)}</b></div>
    </div>
    ${order.result ? `<div class="order-result">${escapeHtml(order.result)}</div>` : ''}
    <h3 class="detail-section-title">What happened, step by step</h3>
    <div class="timeline">${events}</div>
    <div class="modal-actions">
      ${order.jobId ? `<button class="button secondary" data-open-job="${escapeHtml(order.jobId)}">Inspect job ticket</button>` : ''}
      ${['ACCEPTED', 'PROCESSING', 'RETRYING'].includes(order.status)
        ? `<button class="button secondary" data-detail-cancel="${escapeHtml(order.orderId)}">Cancel order</button>` : ''}
    </div>
  `;

  const jobButton = $('order-detail-body').querySelector('[data-open-job]');
  if (jobButton) {
    jobButton.onclick = () => {
      $('order-detail-dialog').close();
      document.dispatchEvent(new CustomEvent('demo:job-open', { detail: { jobId: jobButton.dataset.openJob } }));
    };
  }
  const cancel = $('order-detail-body').querySelector('[data-detail-cancel]');
  if (cancel) cancel.onclick = () => cancelOrder(cancel.dataset.detailCancel, cancel);
}

async function cancelOrder(orderId, button) {
  const confirmed = await confirmAction({
    title: 'Cancel this order?',
    message: `${orderId} will stop progressing and reserved stock will be released.`,
    confirmLabel: 'Cancel order'
  });
  if (!confirmed) return;
  try {
    await withBusy(button, () => del(`/api/orders/${encodeURIComponent(orderId)}`), 'Cancelling...');
    toast(`${orderId} cancelled.`);
    document.dispatchEvent(new CustomEvent('demo:changed'));
    await refreshOrders();
  } catch (error) {
    toast(error.message);
  }
}
