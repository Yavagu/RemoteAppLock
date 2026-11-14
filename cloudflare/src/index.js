/* Remote App Lock MVP — Cloudflare Workers + D1 (no third-party runtime deps). */
const encoder = new TextEncoder();
const cors = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
  'Access-Control-Allow-Headers': 'Authorization, Content-Type',
  'Cache-Control': 'no-store',
  'X-Content-Type-Options': 'nosniff',
};

function json(data, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: { ...cors, 'Content-Type': 'application/json; charset=utf-8' },
  });
}
function error(message, status = 400) { return json({ error: message }, status); }
function hex(bytes) { return Array.from(bytes, x => x.toString(16).padStart(2, '0')).join(''); }
async function sha256(value) { return hex(new Uint8Array(await crypto.subtle.digest('SHA-256', encoder.encode(value)))); }
function randomBytes(n) { const b = new Uint8Array(n); crypto.getRandomValues(b); return b; }
function b64(bytes) { return btoa(String.fromCharCode(...bytes)); }
function fromB64(s) { return Uint8Array.from(atob(s), c => c.charCodeAt(0)); }
const SINGLE_DEVICE_ID = '00000000-0000-4000-8000-000000000001';
function otp() {
  const b = randomBytes(4);
  const n = new DataView(b.buffer).getUint32(0);
  return String(100000 + (n % 900000));
}
function safeEqual(a, b) {
  const x = encoder.encode(String(a)), y = encoder.encode(String(b));
  let diff = x.length ^ y.length;
  for (let i = 0; i < Math.max(x.length, y.length); i++) diff |= (x[i] || 0) ^ (y[i] || 0);
  return diff === 0;
}
async function encryptionKey(env) {
  const bytes = fromB64(env.OTP_SECRET || '');
  if (bytes.length !== 32) throw new Error('OTP_SECRET must be a base64-encoded 32-byte key');
  return crypto.subtle.importKey('raw', bytes, 'AES-GCM', false, ['encrypt', 'decrypt']);
}
async function encryptOtp(value, env) {
  const iv = randomBytes(12);
  const buffer = await crypto.subtle.encrypt({ name: 'AES-GCM', iv }, await encryptionKey(env), encoder.encode(value));
  return { ciphertext: b64(new Uint8Array(buffer)), iv: b64(iv) };
}
async function decryptOtp(ciphertext, iv, env) {
  const buffer = await crypto.subtle.decrypt({ name: 'AES-GCM', iv: fromB64(iv) }, await encryptionKey(env), fromB64(ciphertext));
  return new TextDecoder().decode(buffer);
}
async function requestBody(request) {
  if (Number(request.headers.get('content-length') || 0) > 100000) throw new Error('Request body too large');
  const raw = await request.text();
  if (raw.length > 100000) throw new Error('Request body too large');
  try { return JSON.parse(raw); } catch { throw new Error('Invalid JSON'); }
}
function bearer(request) { const h = request.headers.get('Authorization') || ''; return h.startsWith('Bearer ') ? h.slice(7) : ''; }
function validPackage(p) { return typeof p === 'string' && p.length <= 180 && /^[A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)+$/.test(p); }
async function deviceAuth(request, env) {
  const token = bearer(request);
  if (!env.DEVICE_TOKEN || token.length < 32 || !safeEqual(token, env.DEVICE_TOKEN)) return null;
  let device = await env.DB.prepare('SELECT id, name FROM devices WHERE id = ?').bind(SINGLE_DEVICE_ID).first();
  if (!device) {
    const now = Date.now();
    await env.DB.prepare(`INSERT OR IGNORE INTO devices (id, name, token_hash, created_at, last_seen_at)
      VALUES (?, ?, ?, ?, ?)`).bind(SINGLE_DEVICE_ID, 'My phone', await sha256(token), now, now).run();
    device = await env.DB.prepare('SELECT id, name FROM devices WHERE id = ?').bind(SINGLE_DEVICE_ID).first();
  }
  return device;
}
function adminAuth(request, env) { return Boolean(env.ADMIN_TOKEN) && safeEqual(bearer(request), env.ADMIN_TOKEN); }

export default {
  async fetch(request, env) {
    if (request.method === 'OPTIONS') return new Response(null, { status: 204, headers: cors });
    try {
      if (!env.DB) return error('D1 database is not configured', 503);
      const url = new URL(request.url), path = url.pathname;
      const now = Date.now();
      if (path === '/api/health' && request.method === 'GET') return json({ ok: true, service: 'Remote App Lock MVP' });

      // All other endpoints require authentication.
      if (path.startsWith('/api/admin/')) {
        if (!adminAuth(request, env)) return error('Invalid admin token', 401);
        if (path === '/api/admin/devices' && request.method === 'GET') {
          const q = await env.DB.prepare('SELECT id, name, created_at AS createdAt, last_seen_at AS lastSeenAt FROM devices ORDER BY created_at DESC').bind().all();
          return json({ devices: q.results || [] });
        }
        const m = path.match(/^\/api\/admin\/devices\/([a-f0-9-]{36})\/(apps|locks|unlock|otp)$/);
        if (!m) return error('Route not found', 404);
        const [, deviceId, action] = m;
        const device = await env.DB.prepare('SELECT id FROM devices WHERE id = ?').bind(deviceId).first();
        if (!device) return error('Device not found', 404);
        if (action === 'apps' && request.method === 'GET') {
          const q = await env.DB.prepare(`SELECT a.package_name AS packageName, a.app_name AS name,
            l.status AS status, l.expires_at AS expiresAt, l.otp_expires_at AS otpExpiresAt,
            l.otp_ciphertext AS ciphertext, l.otp_iv AS iv
            FROM apps a LEFT JOIN locks l ON a.device_id = l.device_id AND a.package_name = l.package_name
            WHERE a.device_id = ? ORDER BY a.app_name COLLATE NOCASE`).bind(deviceId).all();
          const apps = [];
          for (const r of q.results || []) {
            const locked = r.status === 1 && r.expiresAt > now;
            let code = null;
            if (locked && r.ciphertext && r.iv && r.otpExpiresAt > now) {
              code = await decryptOtp(r.ciphertext, r.iv, env);
            }
            apps.push({ packageName: r.packageName, name: r.name, locked, expiresAt: locked ? r.expiresAt : null,
              otp: code, otpExpiresAt: code ? r.otpExpiresAt : null });
          }
          return json({ apps, serverTime: now });
        }
        if (request.method !== 'POST') return error('Method not allowed', 405);
        const b = await requestBody(request);
        const pkg = b.packageName;
        if (!validPackage(pkg)) return error('Invalid package name');
        const app = await env.DB.prepare('SELECT package_name FROM apps WHERE device_id = ? AND package_name = ?').bind(deviceId, pkg).first();
        if (!app) return error('App not in this device inventory', 404);
        if (action === 'locks') {
          const minutes = Number(b.minutes);
          if (!Number.isInteger(minutes) || minutes < 1 || minutes > 10080) return error('Duration must be 1–10080 minutes');
          const code = otp();
          const encrypted = await encryptOtp(code, env);
          const expires = now + minutes * 60_000;
          const otpExpires = Math.min(expires, now + 5 * 60_000);
          await env.DB.prepare(`INSERT INTO locks (device_id,package_name,status,expires_at,otp_ciphertext,otp_iv,otp_expires_at,fail_count,lock_version,updated_at)
            VALUES (?, ?, 1, ?, ?, ?, ?, 0, 1, ?)
            ON CONFLICT(device_id,package_name) DO UPDATE SET status=1,expires_at=excluded.expires_at,
            otp_ciphertext=excluded.otp_ciphertext,otp_iv=excluded.otp_iv,otp_expires_at=excluded.otp_expires_at,
            fail_count=0,lock_version=locks.lock_version+1,updated_at=excluded.updated_at`)
            .bind(deviceId, pkg, expires, encrypted.ciphertext, encrypted.iv, otpExpires, now).run();
          return json({ ok: true, otp: code, otpExpiresAt: otpExpires, expiresAt: expires });
        }
        if (action === 'unlock') {
          await env.DB.prepare(`UPDATE locks SET status=0,otp_ciphertext=NULL,otp_iv=NULL,otp_expires_at=0,fail_count=0,
            lock_version=lock_version+1,updated_at=? WHERE device_id=? AND package_name=?`).bind(now, deviceId, pkg).run();
          return json({ ok: true });
        }
        if (action === 'otp') {
          const row = await env.DB.prepare('SELECT expires_at AS expiresAt, status FROM locks WHERE device_id=? AND package_name=?').bind(deviceId, pkg).first();
          if (!row || row.status !== 1 || row.expiresAt <= now) return error('App is not currently locked', 409);
          const code = otp();
          const encrypted = await encryptOtp(code, env);
          const expires = Math.min(row.expiresAt, now + 5 * 60_000);
          await env.DB.prepare(`UPDATE locks SET otp_ciphertext=?,otp_iv=?,otp_expires_at=?,fail_count=0,updated_at=?
            WHERE device_id=? AND package_name=? AND status=1 AND expires_at>?`)
            .bind(encrypted.ciphertext, encrypted.iv, expires, now, deviceId, pkg, now).run();
          return json({ ok: true, otp: code, otpExpiresAt: expires });
        }
        return error('Route not found', 404);
      }
      if (path.startsWith('/api/device/')) {
        const device = await deviceAuth(request, env);
        if (!device) return error('Invalid device token', 401);
        // Update last-seen without turning every polling request into a DB write.
        const stamp = async () => env.DB.prepare('UPDATE devices SET last_seen_at=? WHERE id=? AND last_seen_at<?')
          .bind(now, device.id, now - 60_000).run();
        if (path === '/api/device/state' && request.method === 'GET') {
          const q = await env.DB.prepare(`SELECT package_name AS packageName, expires_at AS expiresAt, lock_version AS lockVersion
            FROM locks WHERE device_id=? AND status=1 AND expires_at>?`).bind(device.id, now).all();
          await stamp();
          return json({ serverTime: now, locks: q.results || [] });
        }
        if (path === '/api/device/apps' && request.method === 'POST') {
          const b = await requestBody(request);
          if (!Array.isArray(b.apps) || b.apps.length > 40) return error('Expected an upload chunk of at most 40 apps');
          const apps = new Map();
          for (const item of b.apps) {
            if (!validPackage(item.packageName)) continue;
            const name = String(item.name || item.packageName).trim().slice(0, 100);
            apps.set(item.packageName, name);
          }
          // Upsert only: do not delete old inventory if Android temporarily filters apps.
          const statements = [...apps].map(([pkg, name]) => env.DB.prepare(`INSERT INTO apps (device_id,package_name,app_name) VALUES (?,?,?)
            ON CONFLICT(device_id,package_name) DO UPDATE SET app_name=excluded.app_name`).bind(device.id, pkg, name));
          if (statements.length) await env.DB.batch(statements);
          await stamp();
          return json({ ok: true, uploaded: apps.size });
        }
        if (path === '/api/device/verify' && request.method === 'POST') {
          const b = await requestBody(request), pkg = b.packageName, typed = String(b.otp || '');
          if (!validPackage(pkg) || !/^\d{6}$/.test(typed)) return error('Enter a valid six-digit code');
          const row = await env.DB.prepare(`SELECT status,expires_at AS expiresAt,otp_ciphertext AS ciphertext,
            otp_iv AS iv,otp_expires_at AS otpExpiresAt,fail_count AS failCount,lock_version AS lockVersion
            FROM locks WHERE device_id=? AND package_name=?`).bind(device.id, pkg).first();
          if (!row || row.status !== 1 || row.expiresAt <= now) return error('This lock is no longer active', 409);
          if (!row.ciphertext || row.otpExpiresAt <= now) return error('Code expired. Ask the PC owner for a new one', 410);
          if (row.failCount >= 5) return error('Too many attempts. Ask the PC owner to generate a new code', 429);
          const expected = await decryptOtp(row.ciphertext, row.iv, env);
          if (!safeEqual(typed, expected)) {
            await env.DB.prepare(`UPDATE locks SET fail_count=fail_count+1
              WHERE device_id=? AND package_name=? AND status=1 AND lock_version=? AND otp_ciphertext=?`)
              .bind(device.id, pkg, row.lockVersion, row.ciphertext).run();
            return error('Incorrect code', 401);
          }
          const changed = await env.DB.prepare(`UPDATE locks SET status=0,otp_ciphertext=NULL,otp_iv=NULL,otp_expires_at=0,
            lock_version=lock_version+1,updated_at=? WHERE device_id=? AND package_name=? AND status=1
            AND lock_version=? AND otp_ciphertext=? AND fail_count<5 AND expires_at>?`)
            .bind(now, device.id, pkg, row.lockVersion, row.ciphertext, now).run();
          if (!changed.meta?.changes) return error('Lock changed; refresh and try again', 409);
          return json({ ok: true, unlocked: true });
        }
        return error('Route not found', 404);
      }
      return error('Route not found', 404);
    } catch (e) {
      // Avoid logging tokens or OTPs in responses.
      if (/Invalid JSON|Request body too large/.test(e.message || '')) return error(e.message, 400);
      console.error('Request failed:', e?.message || 'Unknown error');
      return error('Server error. Check Worker configuration and logs', 500);
    }
  },
};
