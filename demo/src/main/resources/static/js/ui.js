export const $ = id => document.getElementById(id);
export const $$ = selector => [...document.querySelectorAll(selector)];

export function toast(message) {
  const target = $('toast');
  if (!target) return;
  target.textContent = message;
  target.classList.add('show');
  clearTimeout(toast.timer);
  toast.timer = setTimeout(() => target.classList.remove('show'), 4200);
}

export function setBusy(button, busy, busyText = 'Working...') {
  if (!button) return;
  if (busy) {
    button.dataset.originalLabel = button.textContent;
    button.textContent = busyText;
    button.disabled = true;
  } else {
    button.textContent = button.dataset.originalLabel || button.textContent;
    button.disabled = false;
  }
}

export async function withBusy(button, task, busyText = 'Working...') {
  setBusy(button, true, busyText);
  try {
    return await task();
  } finally {
    setBusy(button, false);
  }
}

export function openDialog(id) {
  const dialog = $(id);
  if (dialog && !dialog.open) dialog.showModal();
}

export function bindDialogClosers() {
  $$('[data-close]').forEach(button => {
    button.onclick = () => $(button.dataset.close)?.close();
  });
}

export function emptyState(message) {
  return `<div class="empty-state">${message}</div>`;
}

export function confirmAction({
  title = 'Are you sure?',
  message = '',
  confirmLabel = 'Confirm'
} = {}) {
  const dialog = $('confirm-dialog');
  const titleTarget = $('confirm-title');
  const messageTarget = $('confirm-message');
  const ok = $('confirm-ok');
  const cancel = $('confirm-cancel');
  if (!dialog || !ok || !cancel) return Promise.resolve(false);

  titleTarget.textContent = title;
  messageTarget.textContent = message;
  ok.textContent = confirmLabel;

  return new Promise(resolve => {
    const cleanup = () => {
      ok.removeEventListener('click', accept);
      cancel.removeEventListener('click', reject);
      dialog.removeEventListener('close', reject);
    };
    const finish = value => {
      cleanup();
      if (dialog.open) dialog.close();
      resolve(value);
    };
    const accept = () => finish(true);
    const reject = () => finish(false);

    ok.addEventListener('click', accept);
    cancel.addEventListener('click', reject);
    dialog.addEventListener('close', reject);
    dialog.showModal();
  });
}
