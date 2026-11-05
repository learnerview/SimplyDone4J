import { api, del, json, post } from './api.js';
import {
  debounce, escapeHtml, formatDateTime, formatDuration, formatTime, humanize,
  number, pill, shortId, statusLabel
} from './format.js';
import { $, confirmAction, emptyState, openDialog, toast, withBusy } from './ui.js';

let handlers = [];
let activeJobId = null;

const MEANING = {
  QUEUED: 'The ticket is waiting in a queue. A worker will pick it up soon.',
  PROCESSING: 'A worker is running this ticket right now.',
  SUCCESS: 'The work finished successfully. No action is needed.',
  RETRY_SCHEDULED: 'A temporary failure happened. The engine will try again automatically.',
  DLQ: 'All retries are used up. A person must replay or discard this ticket.',
  FAILED: 'The work failed permanently.',
  CANCELLED: 'Someone cancelled this ticket before it could finish.'
};

export function initJobs() {
  $('job-search').oninput = debounce(() => refreshJobs().catch(error => toast(error.message)), 250);
  $('job-status-filter').onchange = () => refreshJobs().catch(error => toast(error.message));
  $('job-priority-filter').onchange = () => refreshJobs().catch(error => toast(error.message));
  $('refresh-jobs').onclick = () => refreshJobs().catch(error => toast(error.message));
  $('close-job-detail').onclick = () => $('job-detail-dialog').close();
  $('job-detail-dialog').addEventListener('close', () => { activeJobId = null; });
}

export async function loadHandlers() {
  if (!handlers.length) handlers = await api('/api/handlers');
  return handlers;
}

export async function refreshJobs() {
  const params = new URLSearchParams({
    status: $('job-status-filter').value,
    priority: $('job-priority-filter').value,
    q: ($('job-search').value || '').trim(),
    limit: '100'
  });
  const [page, summary] = await Promise.all([
    api(`/api/jobs?${params}`),
    api('/api/operations/summary')
  ]);
  renderStats(summary);
  renderTable(page);
  if ($('job-detail-dialog').open && activeJobId) {
    await openJobDetail(activeJobId, false);
  }
  return page;
}

function renderStats(summary) {
  const counts = summary.jobCounts || {};
  const cards = [
    ['Waiting safely', (counts.QUEUED || 0) + (counts.RETRY_SCHEDULED || 0), 'Tickets in the queue'],
    ['Running now', counts.PROCESSING || 0, 'Tickets held by workers'],
    ['Done', counts.SUCCESS || 0, 'Finished successfully'],
    ['Dead letters', counts.DLQ || 0, 'Need a human decision']
  ];
  $('job-stats').innerHTML = cards.map(([label, value, foot]) => `
    <div class="stat"><span>${label}</span><b>${number(value)}</b><small class="muted">${escapeHtml(foot)}</small></div>
  `).join('');
}

function renderTable(page) {
  const target = $('jobs-table');
  const jobs = page.jobs || [];
  if (!jobs.length) {
    target.innerHTML = emptyState('No jobs match this view. Try another status, lane, or search text.');
    return;
  }
  target.innerHTML = `
    <div class="table-wrap"><table><thead><tr>
      <th>Ticket</th><th>Work type</th><th>Producer</th><th>Lane</th>
      <th>Attempts</th><th>Status</th><th>Created</th><th>Action</th>
    </tr></thead><tbody>
      ${jobs.map(job => `
        <tr class="job-link" data-job-id="${escapeHtml(job.id)}">
          <td><b class="mono">${escapeHtml(shortId(job.id))}</b><br><small>${escapeHtml(job.priority)} priority</small></td>
          <td>${escapeHtml(humanize(job.jobType))}</td>
          <td>${escapeHtml(job.producer || 'unknown')}</td>
          <td>${escapeHtml(job.priority)}</td>
          <td>${job.attemptCount} / ${job.maxAttempts}</td>
          <td>${pill(job.status)}</td>
          <td>${formatTime(job.createdAt)}</td>
          <td>${['QUEUED', 'RETRY_SCHEDULED'].includes(job.status)
            ? `<button class="text-button cancel-job" data-id="${escapeHtml(job.id)}">Cancel</button>`
            : '<span class="muted">Inspect</span>'}</td>
        </tr>
      `).join('')}
    </tbody></table></div>`;

  target.querySelectorAll('.job-link').forEach(row => {
    row.onclick = () => openJobDetail(row.dataset.jobId);
  });
  target.querySelectorAll('.cancel-job').forEach(button => {
    button.onclick = async event => {
      event.stopPropagation();
      await cancelJob(button.dataset.id, button);
    };
  });
}

export async function openJobDetail(jobId, show = true) {
  activeJobId = jobId;
  try {
    const [job, logs] = await Promise.all([
      api(`/api/jobs/${encodeURIComponent(jobId)}`),
      api(`/api/jobs/${encodeURIComponent(jobId)}/logs`).catch(() => [])
    ]);
    renderDetail(job, logs);
    if (show) openDialog('job-detail-dialog');
  } catch (error) {
    toast(error.message);
    if ($('job-detail-dialog').open) $('job-detail-dialog').close();
  }
}

function renderDetail(job, logs) {
  const logRows = logs.length ? logs.map(log => `
    <tr>
      <td>${log.attempt}</td>
      <td><span class="log-status ${escapeHtml(log.status)}">${escapeHtml(statusLabel(log.status))}</span></td>
      <td>${formatDuration(log.durationMs)}</td>
      <td>${formatDateTime(log.executedAt)}</td>
      <td>${escapeHtml(log.message || '-')}</td>
    </tr>
  `).join('') : '<tr><td colspan="5" class="muted">No execution attempts recorded yet.</td></tr>';

  $('job-detail-body').innerHTML = `
    <div class="job-detail-top">
      <div><p class="eyebrow">WORK TICKET</p><h2>${escapeHtml(humanize(job.jobType))}</h2>
        <p class="mono">${escapeHtml(job.id)}</p></div>
      <div class="job-detail-actions">
        ${pill(job.status)}
        ${job.status === 'DLQ' ? `<button class="button primary" data-replay-job="${escapeHtml(job.id)}">Replay</button>` : ''}
        ${['QUEUED', 'RETRY_SCHEDULED'].includes(job.status) ? `<button class="button secondary" data-cancel-job="${escapeHtml(job.id)}">Cancel</button>` : ''}
      </div>
    </div>
    <div class="explainer compact">
      <span class="explainer-icon">i</span>
      <div><strong>What this means</strong><p>${escapeHtml(MEANING[job.status] || 'Refresh to see the latest state.')}</p></div>
    </div>
    <div class="detail-grid">
      <div class="detail-item"><small>Status</small><b>${escapeHtml(statusLabel(job.status))}</b></div>
      <div class="detail-item"><small>Priority lane</small><b>${escapeHtml(job.priority)}</b></div>
      <div class="detail-item"><small>Producer</small><b>${escapeHtml(job.producer || 'unknown')}</b></div>
      <div class="detail-item"><small>Attempts</small><b>${job.attemptCount} of ${job.maxAttempts}</b></div>
      <div class="detail-item"><small>Timeout</small><b>${job.timeoutSeconds ? `${job.timeoutSeconds} seconds` : 'default'}</b></div>
      <div class="detail-item"><small>Next run</small><b>${formatDateTime(job.nextRunAt)}</b></div>
      <div class="detail-item"><small>Started</small><b>${formatDateTime(job.startedAt)}</b></div>
      <div class="detail-item"><small>Completed</small><b>${formatDateTime(job.completedAt)}</b></div>
    </div>
    <h3 class="detail-section-title">Input payload</h3>
    <pre class="code-block">${escapeHtml(json(job.payload))}</pre>
    ${job.result ? `<h3 class="detail-section-title">Result</h3><pre class="code-block">${escapeHtml(job.result)}</pre>` : ''}
    <h3 class="detail-section-title">Attempt history</h3>
    <div class="table-wrap"><table class="log-table"><thead><tr>
      <th>Attempt</th><th>Outcome</th><th>Duration</th><th>When</th><th>Message</th>
    </tr></thead><tbody>${logRows}</tbody></table></div>
  `;

  const cancel = $('job-detail-body').querySelector('[data-cancel-job]');
  if (cancel) cancel.onclick = () => cancelJob(cancel.dataset.cancelJob, cancel);
  const replay = $('job-detail-body').querySelector('[data-replay-job]');
  if (replay) replay.onclick = () => replayJob(replay.dataset.replayJob, replay);
}

async function cancelJob(jobId, button) {
  const confirmed = await confirmAction({
    title: 'Cancel this work ticket?',
    message: 'If the job is still queued, it will not run. Work already in progress may stop.',
    confirmLabel: 'Cancel job'
  });
  if (!confirmed) return;
  try {
    await withBusy(button, () => del(`/api/jobs/${encodeURIComponent(jobId)}`), 'Cancelling...');
    toast(`${shortId(jobId)} cancelled.`);
    document.dispatchEvent(new CustomEvent('demo:changed'));
    await refreshJobs();
  } catch (error) {
    toast(error.message);
  }
}

async function replayJob(jobId, button) {
  const confirmed = await confirmAction({
    title: 'Replay this dead letter?',
    message: 'The job will receive a fresh attempt budget and run again.',
    confirmLabel: 'Replay job'
  });
  if (!confirmed) return;
  try {
    await withBusy(button, () => post(`/api/dlq/${encodeURIComponent(jobId)}/replay`, {}), 'Replaying...');
    toast(`${shortId(jobId)} replayed. The engine will try again.`);
    document.dispatchEvent(new CustomEvent('demo:changed'));
    await refreshJobs();
  } catch (error) {
    toast(error.message);
  }
}
