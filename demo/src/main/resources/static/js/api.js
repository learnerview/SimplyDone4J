export async function api(path, options = {}) {
  const response = await fetch(path, options);
  const text = await response.text();
  let body = {};
  try {
    body = text ? JSON.parse(text) : {};
  } catch {
    body = { message: text };
  }
  if (!response.ok) {
    throw new Error(body.message || body.error || `Request failed (${response.status})`);
  }
  return body;
}

export function post(path, body, headers = {}) {
  return api(path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...headers },
    body: JSON.stringify(body)
  });
}

export function del(path) {
  return api(path, { method: 'DELETE' });
}

export async function readHealth() {
  try {
    const response = await fetch('/api/health');
    const body = await response.json().catch(() => ({}));
    return { ok: response.ok, ...body };
  } catch {
    return { ok: false, status: 'OFFLINE', redis: 'DOWN' };
  }
}

export function json(value) {
  try {
    return JSON.stringify(value ?? {}, null, 2);
  } catch {
    return String(value);
  }
}
