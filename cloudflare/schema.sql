CREATE TABLE IF NOT EXISTS devices (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  token_hash TEXT NOT NULL UNIQUE,
  created_at INTEGER NOT NULL,
  last_seen_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS apps (
  device_id TEXT NOT NULL,
  package_name TEXT NOT NULL,
  app_name TEXT NOT NULL,
  PRIMARY KEY (device_id, package_name),
  FOREIGN KEY (device_id) REFERENCES devices(id)
);
CREATE TABLE IF NOT EXISTS locks (
  device_id TEXT NOT NULL,
  package_name TEXT NOT NULL,
  status INTEGER NOT NULL DEFAULT 0,
  expires_at INTEGER NOT NULL DEFAULT 0,
  otp_ciphertext TEXT,
  otp_iv TEXT,
  otp_expires_at INTEGER NOT NULL DEFAULT 0,
  fail_count INTEGER NOT NULL DEFAULT 0,
  lock_version INTEGER NOT NULL DEFAULT 0,
  updated_at INTEGER NOT NULL,
  PRIMARY KEY (device_id, package_name),
  FOREIGN KEY (device_id, package_name) REFERENCES apps(device_id, package_name)
);
CREATE INDEX IF NOT EXISTS apps_name_idx ON apps(device_id, app_name);
CREATE INDEX IF NOT EXISTS locks_device_idx ON locks(device_id, status);
