/**
 * Owner enrollment page on the HTTPS enrollment origin (lld.md#identity-and-owner-enrollment). The page holds no
 * secrets: it trades the operator's one-time bootstrap credential for a passkey through the public bootstrap endpoints
 * and shows the recovery secret once. It keeps no session; the app signs in afterwards with the new passkey.
 */

const HTML = `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="referrer" content="no-referrer">
<title>Adminifender enrollment</title>
<link rel="stylesheet" href="/enroll.css">
<script src="/enroll.js" defer></script>
</head>
<body>
<main>
<h1>Enroll the owner passkey</h1>
<p>Paste the one-time enrollment code from the operator. It works once and expires 15 minutes after it was made.</p>
<form id="form">
<label for="token">Enrollment code</label>
<input id="token" name="token" autocomplete="off" autocapitalize="off" spellcheck="false" required pattern="afb_[A-Za-z0-9_\\-]{43}">
<label for="device">Name for this device</label>
<input id="device" name="device" maxlength="80" required value="Phone browser">
<button id="submit" type="submit">Create passkey</button>
</form>
<p id="status" role="status" aria-live="polite"></p>
<section id="done" hidden>
<h2>Enrolled</h2>
<p>Write this recovery secret down and keep it offline. It is shown only once, and it is the only way back in if every passkey is lost.</p>
<output id="recovery"></output>
<p>Now open the Adminifender app and choose <strong>Sign in with passkey</strong>.</p>
</section>
</main>
</body>
</html>
`;

const CSS = `body{margin:0;font:16px/1.5 system-ui,sans-serif;background:#fff;color:#111}
main{max-width:32rem;margin:0 auto;padding:16px}
label{display:block;margin-top:12px;font-weight:600}
input{box-sizing:border-box;width:100%;padding:10px;font:inherit;border:1px solid #888;border-radius:6px}
button{margin-top:16px;padding:10px 16px;font:inherit;border:0;border-radius:6px;background:#1a56db;color:#fff}
button:disabled{opacity:.6}
output{display:block;padding:12px;border:2px solid #111;border-radius:6px;font:15px/1.4 ui-monospace,monospace;word-break:break-all}
#status{min-height:1.5em}
@media (prefers-color-scheme:dark){body{background:#111;color:#eee}input{background:#222;color:#eee}output{border-color:#eee}}
`;

const JS = `'use strict';
const api = (path, body) => fetch('/api/v1/auth/' + path, { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(body), credentials: 'omit', cache: 'no-store' })
  .then(async (r) => ({ ok: r.ok, body: await r.json().catch(() => ({})) }));
const b64u = { decode: (s) => Uint8Array.from(atob(s.replace(/-/g, '+').replace(/_/g, '/') + '==='.slice((s.length + 3) % 4)), (c) => c.charCodeAt(0)),
  encode: (b) => btoa(String.fromCharCode(...new Uint8Array(b))).replace(/\\+/g, '-').replace(/\\//g, '_').replace(/=+$/, '') };
function creationOptions(json) {
  if (PublicKeyCredential.parseCreationOptionsFromJSON) return PublicKeyCredential.parseCreationOptionsFromJSON(json);
  return { ...json, challenge: b64u.decode(json.challenge), user: { ...json.user, id: b64u.decode(json.user.id) },
    excludeCredentials: (json.excludeCredentials || []).map((c) => ({ ...c, id: b64u.decode(c.id) })) };
}
function credentialJson(c) {
  if (typeof c.toJSON === 'function') return c.toJSON();
  return { id: c.id, rawId: b64u.encode(c.rawId), type: c.type, authenticatorAttachment: c.authenticatorAttachment || undefined,
    clientExtensionResults: c.getClientExtensionResults(),
    response: { clientDataJSON: b64u.encode(c.response.clientDataJSON), attestationObject: b64u.encode(c.response.attestationObject),
      transports: c.response.getTransports ? c.response.getTransports() : [] } };
}
const MESSAGES = { bootstrap_unavailable: 'That code is not valid, was already used, has expired, or an owner is already enrolled.',
  rate_limited: 'Too many attempts. Wait a few minutes and try again.', challenge_expired: 'This took too long. Start again with the same code if it has not expired.',
  verification_failed: 'The passkey could not be verified.', already_enrolled: 'An owner is already enrolled.' };
const say = (text) => { document.getElementById('status').textContent = text; };
document.getElementById('form').addEventListener('submit', async (event) => {
  event.preventDefault();
  const button = document.getElementById('submit');
  button.disabled = true;
  try {
    say('Requesting a passkey challenge…');
    const options = await api('bootstrap/options', { bootstrapToken: document.getElementById('token').value.trim(), deviceName: document.getElementById('device').value.trim() });
    if (!options.ok) return say(MESSAGES[options.body.error && options.body.error.code] || 'Enrollment is not available right now.');
    say('Follow the passkey prompt…');
    let credential;
    try {
      credential = await navigator.credentials.create({ publicKey: creationOptions(options.body.data.options) });
    } catch (e) {
      return say(e && e.name === 'NotAllowedError' ? 'The passkey prompt was cancelled. The code still works until it expires.' : 'This browser could not create a passkey.');
    }
    say('Verifying…');
    const verified = await api('bootstrap/verify', { challengeId: options.body.data.challengeId, credential: credentialJson(credential) });
    if (!verified.ok) return say(MESSAGES[verified.body.error && verified.body.error.code] || 'No answer from the server. If enrollment went through, sign in from the app; otherwise ask for a new code.');
    document.getElementById('form').hidden = true;
    document.getElementById('recovery').textContent = verified.body.data.recoverySecret;
    document.getElementById('done').hidden = false;
    say('');
  } catch {
    say('No answer from the server. If enrollment went through, sign in from the app; otherwise ask for a new code.');
  } finally {
    button.disabled = false;
  }
});
`;

const SECURITY = {
  'content-security-policy': "default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'",
  'x-content-type-options': 'nosniff',
  'referrer-policy': 'no-referrer',
  'cache-control': 'no-store',
};

const ASSETS: Record<string, { type: string; body: string }> = {
  '/enroll': { type: 'text/html; charset=utf-8', body: HTML },
  '/enroll.js': { type: 'text/javascript; charset=utf-8', body: JS },
  '/enroll.css': { type: 'text/css; charset=utf-8', body: CSS },
};

/** Serves the enrollment page and its assets, or returns null for any other path. */
export function enrollmentAsset(request: Request): Response | null {
  const asset = ASSETS[new URL(request.url).pathname];
  if (!asset || (request.method !== 'GET' && request.method !== 'HEAD')) return null;
  return new Response(request.method === 'HEAD' ? null : asset.body, { headers: { 'content-type': asset.type, ...SECURITY } });
}
