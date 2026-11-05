import { api } from './api.js';
import { escapeHtml } from './format.js';
import { $, toast } from './ui.js';

const EXPLANATIONS = [
  'The customer presses Place order. The app validates the request.',
  'The app writes a durable ticket to the shared queue.',
  'A worker claims the ticket with a time-limited lease.',
  'If work fails, the engine schedules a retry or moves it to dead letters.',
  'Only after success does the customer receive a confirmation.'
];

export async function refreshArchitecture() {
  try {
    const data = await api('/api/operations/architecture');
    const workflow = data.workflow || [];
    $('architecture-content').innerHTML = workflow.map((step, index) => `
      <article class="architecture-card">
        <b>STEP 0${index + 1}</b>
        <h3>${escapeHtml(step)}</h3>
        <p>${escapeHtml(EXPLANATIONS[index] || 'The next part of the durable workflow.')}</p>
        <small class="muted">Guarantee: ${escapeHtml(data.guarantees?.[index % (data.guarantees.length || 1)] || 'built in')}</small>
      </article>
    `).join('');
  } catch (error) {
    toast(error.message);
  }
}
