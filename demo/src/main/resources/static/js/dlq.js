import { api, del, post } from './api.js';
import {
  debounce, escapeHtml, formatDateTime, humanize, number, pill, shortId
} from './format.js';
import { $, confirmAction, emptyState, toast, withBusy } from './ui.js';

export function initDlq() {
  $('dlq-search').oninput = debounce(() => refreshDlq().catch(error => toast(error.message)), 250);
  $('refresh-dlq').onclick = () => refreshDlq().catch(error => toast(error.message));
  $('refresh-dlq-2').onclick = () => refreshDlq().catch(error => toast(error.message));
}

export async function refreshDlq() {
  const query = ($('dlq-search').value || '').trim();
  const page = await api(`/api/dlq?q=${encodeURIComponent(query)}&limit=100`);
  renderHero(page);
  renderList(page);
  return page;
}

function renderHero(page) {
  const count = Number(page.total || 0);
  $('dlq-hero').innerHTML = `
    <div>
      <p class="eyebrow">RECOVERY QUEUE</p>
      <h2>${count ? `${number(count)} job${count === 1 ? '' : 's'} need a decision` : 'Nothing needs a decision'}</h2>
      <p>${count
        ? 'These jobs used every retry. Replay gives one a fresh chance; discard closes it permanently.'
        : 'Every failed job has been handled. The engine is keeping the main queue clean.'}</p>
    </div>
    <div class="count">${number(count)}</div>
  `;
}

function renderList(page) {
  const jobs = page.jobs || [];
  const target = $('dlq-list');
  if (!jobs.length) {
    target.innerHTML = emptyState('No dead letters match this view. That is a good thing.');
    return;
  }
  target.innerHTML = jobs.map(job => `
    <article class="dlq-card">
      <div>
        <div class="dlq-card-title"><b>${escapeHtml(humanize(job.jobType))}</b> ${pill(job.status)}</div>
        <small><span class="mono">${escapeHtml(shortId(job.id))}</span> - producer ${escapeHtml(job.producer || 'unknown')} - ${job.attemptCount} attempts - ${escapeHtml(job.priority)} lane</small>
        <small class="dlq-reason">${escapeHtml(job.result || 'No failure message was recorded.')}</small>
        <small>Failed around ${formatDateTime(job.updatedAt)}</small>
      </div>
      <div class="dlq-actions">
        <button class="button primary" data-dlq-replay="${escapeHtml(job.id)}">Replay</button>
        <button class="button secondary" data-dlq-discard="${escapeHtml(job.id)}">Discard</button>
      </div>
    </article>
  `).join('');

  target.querySelectorAll('[data-dlq-replay]').forEach(button => {
    button.onclick = () => replay(button.dataset.dlqReplay, button);
  });
  target.querySelectorAll('[data-dlq-discard]').forEach(button => {
    button.onclick = () => discard(button.dataset.dlqDiscard, button);
  });
}

async function replay(jobId, button) {
  const confirmed = await confirmAction({
    title: 'Replay this job?',
    message: 'The engine will run it again with a fresh attempt budget.',
    confirmLabel: 'Replay'
  });
  if (!confirmed) return;
  try {
    await withBusy(button, () => post(`/api/dlq/${encodeURIComponent(jobId)}/replay`, {}), 'Replaying...');
    toast(`${shortId(jobId)} replayed. Watch Jobs for its next attempt.`);
    document.dispatchEvent(new CustomEvent('demo:changed'));
    await refreshDlq();
  } catch (error) {
    toast(error.message);
  }
}

async function discard(jobId, button) {
  const confirmed = await confirmAction({
    title: 'Discard this job permanently?',
    message: 'The record will be removed. This cannot be undone from the demo.',
    confirmLabel: 'Discard forever'
  });
  if (!confirmed) return;
  try {
    await withBusy(button, () => del(`/api/dlq/${encodeURIComponent(jobId)}`), 'Discarding...');
    toast(`${shortId(jobId)} discarded.`);
    document.dispatchEvent(new CustomEvent('demo:changed'));
    await refreshDlq();
  } catch (error) {
    toast(error.message);
  }
}
