import { BlockList, isIP } from 'node:net';
import { open as openFile } from 'node:fs/promises';

export const RETENTION_MS = 30 * 24 * 60 * 60 * 1000;
const ZERO_UUID = '00000000-0000-0000-0000-000000000000';
const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const REQUEST_ID_RE = /^[0-9a-f]{32}$/i;
const MAX_LOG_READ = 256 * 1024;
const BASE_RETRY_MS = 5000;
const MAX_RETRY_MS = 5 * 60_000;

// Limit retries during upstream failures; never silently skip an audit event.
export function retryDelayMs(failures) {
  const exponent = Number.isSafeInteger(failures) && failures > 0
    ? Math.min(failures - 1, 6) : 0;
  return Math.min(MAX_RETRY_MS, BASE_RETRY_MS * 2 ** exponent);
}
const reservations = new BlockList();

for (const [ip, mask] of [
  ['0.0.0.0', 8], ['10.0.0.0', 8], ['100.64.0.0', 10],
  ['127.0.0.0', 8], ['169.254.0.0', 16], ['172.16.0.0', 12],
  ['192.0.0.0', 24], ['192.0.2.0', 24], ['192.168.0.0', 16],
  ['198.18.0.0', 15], ['198.51.100.0', 24], ['203.0.113.0', 24],
  ['224.0.0.0', 4], ['240.0.0.0', 4],
]) reservations.addSubnet(ip, mask, 'ipv4');
for (const [ip, mask] of [
  ['::', 128], ['::1', 128], ['fc00::', 7], ['fe80::', 10],
  ['ff00::', 8], ['2001:db8::', 32],
]) reservations.addSubnet(ip, mask, 'ipv6');

export function publicClientIp(value) {
  if (typeof value !== 'string' || value.length > 64) return null;
  let ip = value.trim();
  if (/^::ffff:/i.test(ip) && isIP(ip.slice(7)) === 4) ip = ip.slice(7);
  const family = isIP(ip);
  if (!family || reservations.check(ip, family === 4 ? 'ipv4' : 'ipv6')) return null;
  return ip;
}

function cleanLocationPart(value) {
  if (typeof value !== 'string') return null;
  const part = value.replace(/[\u0000-\u001f\u007f]/g, ' ').trim().slice(0, 80);
  return part || null;
}

export function coarseGeoLocation(record) {
  if (!record || typeof record !== 'object') return null;
  const parts = [
    cleanLocationPart(record.city?.names?.en),
    cleanLocationPart(record.subdivisions?.[0]?.names?.en),
    cleanLocationPart(record.country?.names?.en),
  ].filter(Boolean);
  return parts.length ? [...new Set(parts)].join(', ').slice(0, 240) : null;
}

export function auditIngestUrl(base) {
  const url = new URL(base);
  const privateHost = ['127.0.0.1', 'localhost', '[::1]', 'openobserve', 'host.docker.internal'].includes(url.hostname);
  if (!(url.protocol === 'https:' || (url.protocol === 'http:' && privateHost)) ||
      url.username || url.password || url.search || url.hash ||
      !/^\/api\/[a-zA-Z0-9_-]+\/starlit_(?:usage|auth)\/_json$/.test(url.pathname)) {
    throw new Error('Untrusted OpenObserve destination');
  }
  url.pathname = url.pathname.replace(/\/starlit_(?:usage|auth)\/_json$/, '/starlit_auth/_json');
  return url.toString();
}

function timestamp(value, now) {
  const date = value instanceof Date ? value : new Date(value);
  const ms = date.getTime();
  return Number.isSafeInteger(ms) && ms > 0 && ms >= now - RETENTION_MS &&
    ms <= now + 5 * 60_000 ? ms : null;
}

function annotate(eventId, at, method, outcome, source, ipValue, geoLookup, statusCode, now) {
  const ms = timestamp(at, now);
  if (ms === null) return null;
  const ip = publicClientIp(ipValue);
  let location = null;
  if (ip) {
    try { location = cleanLocationPart(geoLookup(ip)); } catch { /* GeoIP is optional. */ }
  }
  return {
    _timestamp: ms * 1000,
    app: 'starlit', schema_version: 1, event: 'auth_login',
    event_id: eventId,
    outcome,
    login_method: method,
    source,
    ip_address: ip,
    approximate_location: location,
    http_status: statusCode,
    event_time: new Date(ms).toISOString(),
  };
}

const SUCCESS_METHODS = new Set(['email', 'google', 'apple', 'github', 'passkey', 'sso']);
const FAILURE_METHODS = new Map([
  ['password', 'email'], ['otp', 'otp'], ['pkce', 'oauth_pkce'],
  ['id_token', 'id_token'], ['webauthn', 'passkey'], ['passkey', 'passkey'],
]);

export function successfulLogin(row, geoLookup = () => null, now = Date.now()) {
  if (!UUID_RE.test(row?.event_id || '')) return null;
  const method = SUCCESS_METHODS.has(row.login_method) ? row.login_method : 'unknown';
  return annotate('success:' + row.event_id.toLowerCase(), row.login_at, method,
    'success', 'gotrue_audit', row.ip_address, geoLookup, null, now);
}

export function failedLogin(row, geoLookup = () => null, now = Date.now()) {
  if (!REQUEST_ID_RE.test(row?.request_id || '') || !Number.isInteger(Number(row.status)) ||
      Number(row.status) < 400 || Number(row.status) > 499 ||
      !FAILURE_METHODS.has(row.grant)) return null;
  return annotate('failed:' + row.request_id.toLowerCase(), row.at,
    FAILURE_METHODS.get(row.grant), 'failure', 'gateway_token_response', row.ip,
    geoLookup, Number(row.status), now);
}

export class AuthAuditReporter {
  constructor({ ingestUrl, authorization, fetcher = fetch }) {
    this.ingestUrl = auditIngestUrl(ingestUrl);
    if (!/^Basic [A-Za-z0-9+/]+={0,2}$/.test(authorization || '')) {
      throw new Error('A dedicated OpenObserve ingestion authorization is required');
    }
    this.authorization = authorization;
    this.fetcher = fetcher;
  }

  async send(records) {
    if (!records.length) return;
    const response = await this.fetcher(this.ingestUrl, {
      method: 'POST',
      headers: { Authorization: this.authorization, 'Content-Type': 'application/json' },
      body: JSON.stringify(records),
      signal: AbortSignal.timeout(4000),
      redirect: 'error',
    });
    if (!response.ok) throw new Error('Auth audit ingestion unavailable');
    const ack = await response.json();
    const entries = ack?.status;
    // HTTP 200 may still report rejected records (including old timestamps).
    const accepted = Array.isArray(entries) && entries.length > 0 &&
      entries.every(entry => Number.isSafeInteger(entry?.successful) &&
        entry.successful >= 0 && entry.failed === 0) &&
      entries.reduce((count, entry) => count + entry.successful, 0) === records.length;
    if (ack?.code !== 200 || !accepted) {
      const error = new Error('Auth audit ingestion did not accept every record');
      // Never expose upstream errors: they may contain client IPs or payloads.
      error.code = Array.isArray(entries) && entries.some(entry =>
        typeof entry?.error === 'string' && /Too old data/i.test(entry.error))
        ? 'AUTH_AUDIT_INGEST_WINDOW' : 'AUTH_AUDIT_REJECTED';
      throw error;
    }
  }
}

// A PostgreSQL cursor row lock prevents concurrent exporter instances from
// sending the same batch. If the sink acknowledged a batch but the durable
// cursor commit failed, do not retry automatically: that is an ambiguous
// delivery and repeated retries can amplify one login thousands of times.
function uncertainCheckpoint() {
  const error = new Error('Auth audit cursor checkpoint uncertain after accepted delivery');
  error.code = 'AUTH_AUDIT_CURSOR_UNCERTAIN';
  return error;
}

async function withCursorCheckpoint(db, source, task) {
  const transactional = typeof db.connect === 'function';
  const client = transactional ? await db.connect() : db;
  let transactionStarted = false;
  let accepted = false;
  try {
    if (transactional) {
      await client.query('BEGIN');
      transactionStarted = true;
      const lock = await client.query(
        'SELECT source FROM starlit_analytics.export_cursor WHERE source=$1 FOR UPDATE', [source]);
      if (lock.rowCount !== 1) throw new Error('Missing auth audit cursor lock');
    }
    const value = await task(client, () => { accepted = true; });
    if (transactional) await client.query('COMMIT');
    return value;
  } catch (error) {
    if (transactionStarted) {
      try { await client.query('ROLLBACK'); } catch { /* Preserve original failure. */ }
    }
    if (accepted) throw uncertainCheckpoint();
    throw error;
  } finally {
    if (transactional) client.release();
  }
}

export async function exportSuccessfulLogins(db, reporter, geoLookup = () => null, now = Date.now()) {
  return withCursorCheckpoint(db, 'success', async (client, markAccepted) => {
    const state = (await client.query(
      `SELECT to_char(cursor_time AT TIME ZONE 'UTC',
                      'YYYY-MM-DD"T"HH24:MI:SS.US"Z"') AS cursor_time, cursor_id
       FROM starlit_analytics.export_cursor WHERE source='success'`,
    )).rows[0];
    if (!state) throw new Error('Missing success audit cursor');
    const rows = (await client.query(
      // node-postgres converts TIMESTAMPTZ to a JavaScript Date, losing its
      // microseconds. Preserve the exact database timestamp for the cursor.
      `SELECT event_id,
              to_char(login_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"') AS login_at,
              login_method, ip_address
       FROM starlit_analytics.login_successes($1,$2,$3)`,
      [state.cursor_time, state.cursor_id || ZERO_UUID, 100],
    )).rows;
    if (!rows.length) return 0;
    const records = rows.map(row => successfulLogin(row, geoLookup, now)).filter(Boolean);
    // Never checkpoint a failed or partially acknowledged upstream batch.
    await reporter.send(records);
    if (records.length) markAccepted();
    const last = rows.at(-1);
    const result = await client.query(
      "UPDATE starlit_analytics.export_cursor SET cursor_time=$1,cursor_id=$2,updated_at=now() WHERE source='success'",
      [last.login_at, last.event_id],
    );
    if (result.rowCount !== 1) throw new Error('Success audit cursor was not updated');
    return records.length;
  });
}

export async function exportFailedLogins(db, reporter, path, geoLookup = () => null,
  now = Date.now(), open = openFile) {
  return withCursorCheckpoint(db, 'failure', async (client, markAccepted) => {
    const state = (await client.query(
      "SELECT file_inode,file_offset FROM starlit_analytics.export_cursor WHERE source='failure'",
    )).rows[0];
    if (!state) throw new Error('Missing failure audit cursor');
    const handle = await open(path, 'r');
    try {
      const st = await handle.stat();
      const inode = String(st.dev) + ':' + String(st.ino);
      const oldOffset = Number(state.file_offset || 0);
      const offset = state.file_inode === inode && oldOffset <= st.size ? oldOffset : 0;
      const count = Math.min(MAX_LOG_READ, Math.max(0, st.size - offset));
      if (count === 0) {
        if (state.file_inode !== inode || oldOffset !== offset) {
          const result = await client.query(
            "UPDATE starlit_analytics.export_cursor SET file_inode=$1,file_offset=$2,updated_at=now() WHERE source='failure'",
            [inode, offset],
          );
          if (result.rowCount !== 1) throw new Error('Failure audit cursor was not updated');
        }
        return 0;
      }
      const data = Buffer.alloc(count);
      const { bytesRead } = await handle.read(data, 0, count, offset);
      const lastBreak = data.lastIndexOf(10, bytesRead - 1);
      if (lastBreak < 0) {
        if (bytesRead >= MAX_LOG_READ) throw new Error('Overlong gateway audit record');
        return 0;
      }
      const records = [];
      for (const line of data.subarray(0, lastBreak + 1).toString('utf8').split('\n')) {
        if (!line.trim()) continue;
        let parsed;
        try { parsed = JSON.parse(line); } catch { continue; }
        const record = failedLogin(parsed, geoLookup, now);
        if (record) records.push(record);
      }
      await reporter.send(records);
      if (records.length) markAccepted();
      const result = await client.query(
        "UPDATE starlit_analytics.export_cursor SET file_inode=$1,file_offset=$2,updated_at=now() WHERE source='failure'",
        [inode, offset + lastBreak + 1],
      );
      if (result.rowCount !== 1) throw new Error('Failure audit cursor was not updated');
      return records.length;
    } finally {
      await handle.close();
    }
  });
}
