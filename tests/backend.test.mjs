import test from 'node:test';
import assert from 'node:assert/strict';
import { DatabaseSync } from 'node:sqlite';
import { readFileSync } from 'node:fs';
import { webcrypto, randomBytes } from 'node:crypto';
import server from '../cloudflare/src/index.js';

if (!globalThis.crypto) globalThis.crypto = webcrypto;
const DEVICE_TOKEN = 'single-device-test-token-that-is-long-enough';

class TestD1 {
  constructor() {
    this.db = new DatabaseSync(':memory:');
    this.db.exec('PRAGMA foreign_keys = ON;');
    this.db.exec(readFileSync(new URL('../cloudflare/schema.sql', import.meta.url), 'utf8'));
  }
  prepare(sql) {
    const self = this;
    return {
      bind(...args) {
        const stmt = () => self.db.prepare(sql);
        return {
          async first() { return stmt().get(...args) ?? null; },
          async all() { return { results: stmt().all(...args) }; },
          async run() { return { meta: { changes: Number(stmt().run(...args).changes) } }; },
        };
      },
    };
  }
  async batch(queries) { return Promise.all(queries.map(q => q.run())); }
}

function setup() {
  const env = {
    DB: new TestD1(),
    ADMIN_TOKEN: 'admin-test-secret-long-enough',
    DEVICE_TOKEN,
    OTP_SECRET: randomBytes(32).toString('base64'),
  };
  async function call(path, method='GET', data=undefined, token=env.ADMIN_TOKEN) {
    const headers = {};
    if (token) headers.Authorization = `Bearer ${token}`;
    if (data !== undefined) headers['Content-Type'] = 'application/json';
    const request = new Request('https://example.workers.dev'+path, {
      method, headers, body: data === undefined ? undefined : JSON.stringify(data),
    });
    const response = await server.fetch(request, env);
    return { status: response.status, data: await response.json(), headers: response.headers };
  }
  return { call };
}

test('single phone auto-registers, uploads, locks, verifies OTP and unlocks', async () => {
  const { call } = setup();
  assert.equal((await call('/api/health')).data.ok, true);
  assert.equal((await call('/api/admin/devices', 'GET', undefined, 'wrong')).status, 401);
  assert.equal((await call('/api/device/state', 'GET', undefined, 'wrong-device-token-that-is-long-enough')).status, 401);
  const phoneToken = DEVICE_TOKEN;
  let response = await call('/api/device/apps', 'POST', { apps: [
    { packageName: 'com.google.android.youtube', name: 'YouTube' },
    { packageName: 'com.instagram.android', name: 'Instagram' },
  ] }, phoneToken);
  assert.equal(response.data.uploaded, 2);
  const devices = await call('/api/admin/devices');
  assert.equal(devices.data.devices.length, 1);
  const phoneId = devices.data.devices[0].id;
  assert.equal(devices.data.devices[0].name, 'My phone');
  response = await call(`/api/admin/devices/${phoneId}/apps`);
  assert.equal(response.data.apps.length, 2);
  const p = 'com.google.android.youtube';
  const lockPath = `/api/admin/devices/${phoneId}/locks`;
  assert.equal((await call(lockPath, 'POST', { packageName: p, minutes: 0 })).status, 400);
  response = await call(lockPath, 'POST', { packageName: p, minutes: 60 });
  assert.equal(response.status, 200);
  const firstOtp = response.data.otp;
  assert.match(firstOtp, /^\d{6}$/);
  response = await call(`/api/admin/devices/${phoneId}/apps`);
  assert.equal(response.data.apps.find(a=>a.packageName===p).otp, firstOtp);
  response = await call('/api/device/state', 'GET', undefined, phoneToken);
  assert.deepEqual(response.data.locks.map(x=>x.packageName), [p]);
  assert.equal(JSON.stringify(response.data).includes(firstOtp), false);
  assert.equal((await call('/api/device/verify', 'POST', { packageName:p, otp:'000000' }, phoneToken)).status, 401);
  const renewal = await call(`/api/admin/devices/${phoneId}/otp`, 'POST', { packageName:p });
  assert.equal(renewal.status, 200);
  const secondOtp = renewal.data.otp;
  assert.match(secondOtp, /^\d{6}$/);
  response = await call('/api/device/verify', 'POST', {packageName:p,otp:secondOtp},phoneToken);
  assert.equal(response.status, 200);
  assert.equal(response.data.unlocked, true);
  assert.equal((await call('/api/device/state', 'GET', undefined, phoneToken)).data.locks.length, 0);
  response = await call(`/api/admin/devices/${phoneId}/apps`);
  assert.equal(response.data.apps.find(a=>a.packageName===p).locked, false);
  const relock = await call(lockPath, 'POST', { packageName:p, minutes:30 });
  assert.equal(relock.status, 200);
  assert.equal((await call('/api/device/state', 'GET', undefined, phoneToken)).data.locks.length, 1);
  assert.equal((await call(`/api/admin/devices/${phoneId}/unlock`, 'POST', {packageName:p})).status, 200);
  assert.equal((await call('/api/device/state', 'GET', undefined, phoneToken)).data.locks.length, 0);
});

test('OTP attempts are limited and fresh OTP resets the counter', async () => {
  const { call } = setup();
  const t = DEVICE_TOKEN, pkg='com.test.lockapp';
  await call('/api/device/apps','POST',{apps:[{packageName:pkg,name:'Lock App'}]},t);
  const id=(await call('/api/admin/devices')).data.devices[0].id;
  const lock=await call(`/api/admin/devices/${id}/locks`,'POST',{packageName:pkg,minutes:120});
  for(let i=0;i<5;i++) assert.equal((await call('/api/device/verify','POST',{packageName:pkg,otp:'000000'},t)).status,401);
  assert.equal((await call('/api/device/verify','POST',{packageName:pkg,otp:lock.data.otp},t)).status,429);
  const refreshed=await call(`/api/admin/devices/${id}/otp`,'POST',{packageName:pkg});
  assert.equal((await call('/api/device/verify','POST',{packageName:pkg,otp:refreshed.data.otp},t)).status,200);
});
