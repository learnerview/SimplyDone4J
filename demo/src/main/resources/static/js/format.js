const STATUS_LABELS = {
  QUEUED: 'Waiting',
  PROCESSING: 'Running',
  SUCCESS: 'Done',
  RETRY_SCHEDULED: 'Retry later',
  RETRYING: 'Retrying',
  DLQ: 'Dead letter',
  FAILED: 'Failed',
  CANCELLED: 'Cancelled',
  ACCEPTED: 'Accepted',
  COMPLETED: 'Delivered',
  UP: 'Healthy',
  DOWN: 'Not reachable',
  DEGRADED: 'Needs attention',
  OFFLINE: 'Offline'
};

const STAGE_LABELS = {
  INVENTORY_RESERVED: 'Stock reserved',
  PAYMENT_PENDING: 'Payment pending',
  PAYMENT_CAPTURED: 'Payment captured',
  PACKING: 'Packing',
  SHIPPED: 'Shipped',
  DELIVERED: 'Delivered',
  CANCELLED: 'Cancelled',
  FAILED: 'Failed'
};

export function escapeHtml(value) {
  return String(value ?? '').replace(/[&<>"']/g, character => ({
    '&': '&amp;',
    '<': '&lt;',
    '>': '&gt;',
    '"': '&quot;',
    "'": '&#39;'
  }[character]));
}

export function humanize(value) {
  return String(value ?? '')
    .toLowerCase()
    .replace(/_/g, ' ')
    .replace(/\b\w/g, character => character.toUpperCase());
}

export function statusLabel(value) {
  return STATUS_LABELS[value] || humanize(value) || 'Unknown';
}

export function stageLabel(value) {
  return STAGE_LABELS[value] || humanize(value) || 'Unknown';
}

export function pill(value) {
  const raw = String(value ?? '').toUpperCase();
  return `<span class="badge ${escapeHtml(raw)}">${escapeHtml(statusLabel(raw))}</span>`;
}

export function formatTime(value) {
  if (!value) return 'Unknown';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return 'Unknown';
  return date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' });
}

export function formatDateTime(value) {
  if (!value) return 'Not set';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return 'Not set';
  return date.toLocaleString([], {
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit'
  });
}

export function formatDuration(value) {
  const millis = Number(value);
  if (!Number.isFinite(millis)) return '-';
  if (millis < 1000) return `${millis} ms`;
  return `${(millis / 1000).toFixed(1)} s`;
}

export function money(value) {
  const amount = Number(value);
  if (!Number.isFinite(amount)) return '$0';
  return `$${amount.toLocaleString()}`;
}

export function number(value) {
  const amount = Number(value);
  return Number.isFinite(amount) ? amount.toLocaleString() : '0';
}

export function shortId(value) {
  return value ? String(value).slice(0, 8) : 'pending';
}

export function debounce(callback, wait = 250) {
  let timer;
  return (...args) => {
    clearTimeout(timer);
    timer = setTimeout(() => callback(...args), wait);
  };
}
