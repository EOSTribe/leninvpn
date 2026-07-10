# VPN Subscription Backend — Project Specification

## Overview

A backend service that manages VPN subscriptions for AmneziaVPN (AWG protocol). It generates per-client AWG keys, registers peers on the WireGuard server, tracks subscription expiry, and removes expired peers daily. No user accounts — clients are identified by a hashed device ID only.

**Deployment: same server as AmneziaWireGuard.** The backend calls the `awg` binary directly via subprocess. No SSH or remote agent needed.

---

## Tech Stack

- **Language**: Python 3.11+
- **Framework**: FastAPI with Uvicorn
- **Database**: PostgreSQL 15+ via asyncpg + SQLAlchemy (async)
- **Scheduler**: APScheduler (AsyncIOScheduler) — runs inside the FastAPI process
- **Crypto**: `cryptography` library (X25519 keypairs, AES-256-GCM encryption)
- **Process manager**: systemd

---

## Project Structure

```
vpn-backend/
├── main.py                  # FastAPI app + scheduler startup
├── config.py                # Settings from environment variables
├── database.py              # SQLAlchemy async engine + session factory
├── models.py                # ORM models
├── schemas.py               # Pydantic request/response models
├── routes/
│   └── internal.py          # All API endpoints
├── services/
│   ├── subscription.py      # Business logic: create, status, expire
│   ├── keygen.py            # vpn:// key generation and encoding
│   ├── awg.py               # AWG peer management (subprocess)
│   └── crypto.py            # AES-256-GCM encrypt/decrypt
├── scheduler.py             # APScheduler job: daily expiry sweep
├── requirements.txt
├── .env.example
└── vpn-backend.service      # systemd unit file
```

---

## Environment Variables

Defined in `.env`, loaded via `python-dotenv`. Never commit `.env` to version control.

```env
# Database
DATABASE_URL=postgresql+asyncpg://vpnuser:password@localhost:5432/vpndb

# Encryption key for vpn:// blobs stored in DB (generate with: openssl rand -hex 32)
ENCRYPTION_KEY=<64-char hex string>

# AWG interface name
AWG_INTERFACE=awg0

# AWG config file path (regenerated from DB on each peer change)
AWG_CONFIG_PATH=/etc/amnezia/awg/awg0.conf

# AWG server private key (read from existing config on the server)
AWG_SERVER_PRIVATE_KEY=<base64 server private key>

# AWG server public key
AWG_SERVER_PUBLIC_KEY=utJE3WCxOAykKAlIE3Aq1Et0EAGGx2stnYG4A5eTXE8=

# AWG server endpoint
AWG_SERVER_ENDPOINT=100.55.101.94:35983

# AWG obfuscation parameters (server-wide, same for all clients)
AWG_H1=2069684122-2109054790
AWG_H2=2146477942-2146702458
AWG_H3=2147020711-2147089023
AWG_H4=2147457264-2147471851
AWG_S1=133
AWG_S2=128
AWG_S3=10
AWG_S4=3
AWG_JC=4
AWG_JMIN=10
AWG_JMAX=50
AWG_I1=<r 2><b 0x858000010001000000000669636c6f756403636f6d0000010001c00c000100010000105a00044d583737>
AWG_I2=
AWG_I3=
AWG_I4=
AWG_I5=

# VPN network settings
AWG_SERVER_IP=10.8.1.1
AWG_SUBNET=10.8.1.0/24
AWG_SUBNET_ADDRESS=10.8.1.0
AWG_CLIENT_IP_START=10.8.1.2
AWG_CLIENT_IP_END=10.8.1.254
AWG_MTU=1376
AWG_KEEPALIVE=25
AWG_DNS1=1.1.1.1
AWG_DNS2=1.0.0.1
AWG_PORT=35983

# Internal API secret — all requests must include header: X-Internal-Secret: <value>
INTERNAL_API_SECRET=<random string, generate with: openssl rand -hex 32>
```

---

## Database Schema

### migrations/001_initial.sql

```sql
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

CREATE TABLE subscriptions (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    device_id_hash  CHAR(64) NOT NULL,           -- SHA-256(device_id) hex, lowercase
    client_pub_key  CHAR(44) NOT NULL UNIQUE,    -- X25519 public key, base64 standard encoding
    client_ip       INET     NOT NULL UNIQUE,    -- assigned /32 address from client subnet
    psk_key         CHAR(44) NOT NULL,           -- preshared key, base64 (stored plaintext for config regen)
    vpn_key_enc     TEXT     NOT NULL,           -- AES-256-GCM encrypted vpn:// string, base64
    status          VARCHAR(16) NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'expired')),
    months          SMALLINT NOT NULL CHECK (months > 0),
    expires_at      TIMESTAMPTZ NOT NULL,
    payment_ref     TEXT,                        -- opaque payment reference from bot/processor
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_subscriptions_device   ON subscriptions(device_id_hash);
CREATE INDEX idx_subscriptions_expiry   ON subscriptions(expires_at) WHERE status = 'active';
CREATE INDEX idx_subscriptions_pub_key  ON subscriptions(client_pub_key);

-- IP address pool for client subnet
-- Pre-populate on server setup (see seed below)
CREATE TABLE ip_pool (
    ip      INET PRIMARY KEY,
    in_use  BOOLEAN NOT NULL DEFAULT FALSE
);

-- Seed ip_pool for 10.8.1.2 → 10.8.1.254
INSERT INTO ip_pool (ip, in_use)
SELECT ('10.8.1.' || g)::inet, FALSE
FROM generate_series(2, 254) AS g;
```

---

## vpn:// Key Format

AmneziaVPN's proprietary connection format. Encoding:

```
vpn:// + BASE64URL_NO_PADDING( HEADER + ZLIB_COMPRESS(JSON_UTF8) )
```

**Header** (4 bytes):
- Bytes 0–1: `\x00\x00`
- Bytes 2–3: big-endian uint16 of the **uncompressed** JSON byte length

**Top-level JSON structure:**

```json
{
  "containers": [
    {
      "container": "amnezia-awg2",
      "awg": {
        "H1": "...", "H2": "...", "H3": "...", "H4": "...",
        "S1": "...", "S2": "...", "S3": "...", "S4": "...",
        "Jc": "...", "Jmin": "...", "Jmax": "...",
        "I1": "...", "I2": "", "I3": "", "I4": "", "I5": "",
        "port": "<port as string>",
        "protocol_version": "2",
        "subnet_address": "10.8.1.0",
        "transport_proto": "udp",
        "last_config": "<JSON-encoded string — see below>"
      }
    }
  ],
  "defaultContainer": "amnezia-awg2",
  "description": "AmneziaVPN",
  "dns1": "1.1.1.1",
  "dns2": "1.0.0.1",
  "hostName": "<server IP>"
}
```

**`last_config`** is a JSON-encoded string (double-serialized) containing:

```json
{
  "H1": "...", "H2": "...", "H3": "...", "H4": "...",
  "S1": "...", "S2": "...", "S3": "...", "S4": "...",
  "Jc": "...", "Jmin": "...", "Jmax": "...",
  "I1": "...", "I2": "", "I3": "", "I4": "", "I5": "",
  "allowed_ips": ["0.0.0.0/0", "::/0"],
  "clientId": "<client_pub_key>",
  "client_ip": "<assigned_ip>",
  "client_priv_key": "<client_private_key_base64>",
  "client_pub_key": "<client_public_key_base64>",
  "config": "<WireGuard INI string — see below>",
  "hostName": "<server IP>",
  "mtu": "1376",
  "persistent_keep_alive": "25",
  "port": 35983,
  "psk_key": "<preshared_key_base64>",
  "server_pub_key": "<server_public_key_base64>"
}
```

**`config`** field (WireGuard INI string inside `last_config`):

```
[Interface]
Address = <client_ip>/32
DNS = $PRIMARY_DNS, $SECONDARY_DNS
PrivateKey = <client_priv_key>
MTU = 1376
Jc = <value>
Jmin = <value>
Jmax = <value>
S1 = <value>
S2 = <value>
S3 = <value>
S4 = <value>
H1 = <value>
H2 = <value>
H3 = <value>
H4 = <value>
I1 = <value>
I2 = 
I3 = 
I4 = 
I5 = 

[Peer]
PublicKey = <server_pub_key>
PresharedKey = <psk_key>
AllowedIPs = 0.0.0.0/0, ::/0
Endpoint = <server_ip>:<port>
PersistentKeepalive = 25
```

---

## Services

### services/crypto.py

Encrypt and decrypt strings with AES-256-GCM.

**`encrypt(plaintext: str, key_hex: str) -> str`**
- Parse `key_hex` as 32 raw bytes
- Generate 12-byte random nonce
- Encrypt with AES-256-GCM → ciphertext + 16-byte tag
- Return `base64( nonce + tag + ciphertext )`

**`decrypt(ciphertext_b64: str, key_hex: str) -> str`**
- Decode base64
- Split: bytes 0–11 = nonce, 12–27 = tag, 28+ = ciphertext
- Decrypt and return plaintext string

---

### services/keygen.py

**`generate_keypair() -> tuple[str, str]`**
- Use `X25519PrivateKey.generate()` from `cryptography.hazmat.primitives.asymmetric.x25519`
- Return `(private_key_b64, public_key_b64)` — standard base64 (not url-safe)

**`generate_psk() -> str`**
- Return `base64.b64encode(os.urandom(32)).decode()`

**`build_vpn_key(client_priv: str, client_pub: str, psk: str, client_ip: str) -> str`**
- Construct the full JSON structure described in the vpn:// Key Format section above
- All AWG params and server details come from `config.py` (loaded from environment)
- Encode: `vpn://` + base64url_no_padding( `struct.pack(">HH", 0, len(json_bytes))` + `zlib.compress(json_bytes)` )

---

### services/awg.py

All commands use `subprocess.run([...], check=True, capture_output=True)`. The `vpnbackend` system user must have passwordless sudo access restricted to `awg` and `awg syncconf` only (configured via sudoers).

**`add_peer(client_pub_key: str, psk: str, client_ip: str) -> None`**
- Write PSK to a `tempfile.NamedTemporaryFile` (delete after use)
- Run: `sudo awg set <AWG_INTERFACE> peer <client_pub_key> preshared-key <tmpfile> allowed-ips <client_ip>/32 persistent-keepalive <AWG_KEEPALIVE>`
- Call `sync_config()`

**`remove_peer(client_pub_key: str) -> None`**
- Run: `sudo awg set <AWG_INTERFACE> peer <client_pub_key> remove`
- Call `sync_config()`

**`sync_config(db_session) -> None`**
- Query all active subscriptions from DB
- Regenerate full AWG config file content (see format below)
- Write to a temp file, then `os.replace()` to `AWG_CONFIG_PATH` (atomic rename)
- Run: `sudo awg syncconf <AWG_INTERFACE> <AWG_CONFIG_PATH>`

**`get_interface_status() -> dict`**
- Run: `sudo awg show <AWG_INTERFACE>`
- Return parsed output as dict (used by health check endpoint)

**Regenerated AWG config file format:**

```ini
[Interface]
PrivateKey = <AWG_SERVER_PRIVATE_KEY>
Address = <AWG_SERVER_IP>/24
ListenPort = <port>
Jc = <AWG_JC>
Jmin = <AWG_JMIN>
Jmax = <AWG_JMAX>
S1 = <AWG_S1>
S2 = <AWG_S2>
S3 = <AWG_S3>
S4 = <AWG_S4>
H1 = <AWG_H1>
H2 = <AWG_H2>
H3 = <AWG_H3>
H4 = <AWG_H4>

[Peer]
# One block per active subscription row
PublicKey = <client_pub_key>
PresharedKey = <psk_key>
AllowedIPs = <client_ip>/32
PersistentKeepalive = <AWG_KEEPALIVE>
```

---

### services/subscription.py

**`hash_device_id(device_id: str) -> str`**
- Return `hashlib.sha256(device_id.encode()).hexdigest()`

**`get_active_subscription(db, device_id_hash: str) -> Subscription | None`**
- Query: `status = 'active'` AND `expires_at > NOW()` AND `device_id_hash = ?`
- Return first result or None

**`allocate_ip(db) -> str`**
- `SELECT ip FROM ip_pool WHERE in_use = FALSE LIMIT 1 FOR UPDATE SKIP LOCKED`
- `UPDATE ip_pool SET in_use = TRUE WHERE ip = ?`
- Return IP string; raise HTTP 503 if pool exhausted

**`create_subscription(db, device_id: str, months: int, payment_ref: str) -> dict`**
1. Hash device_id
2. Check no active subscription exists — raise HTTP 409 if found
3. Allocate IP from pool
4. Generate keypair and PSK
5. Build vpn:// key string
6. Call `awg.add_peer()`
7. Encrypt vpn:// blob with AES-256-GCM
8. Insert subscription row: `expires_at = NOW() + interval '<months> months'`
9. Commit transaction
10. Return `{ vpn_key, expires_at, days_remaining }`

**`get_subscription_status(db, device_id: str) -> dict`**
- Hash device_id
- Find active subscription; if none, check for expired record
- Return `{ active: True, vpn_key (decrypted), days_remaining, expires_at }`
- Or `{ active: False, reason: "expired" | "not_found" }`

**`expire_subscription(db, subscription: Subscription) -> None`**
1. Call `awg.remove_peer(subscription.client_pub_key)` — log error but continue on failure
2. `UPDATE subscriptions SET status = 'expired'`
3. `UPDATE ip_pool SET in_use = FALSE WHERE ip = ?`
4. Commit

---

## API Endpoints

File: `routes/internal.py`

All endpoints require header `X-Internal-Secret: <INTERNAL_API_SECRET>`. Return HTTP 401 if missing or wrong. Bind server to `127.0.0.1` only — never expose to public internet.

### POST /internal/subscription/create

Request:
```json
{
  "device_id": "string",
  "months": 1,
  "payment_ref": "string"
}
```

Response `200`:
```json
{
  "vpn_key": "vpn://...",
  "expires_at": "2026-07-27T12:00:00Z",
  "days_remaining": 30
}
```

Errors: `409` active sub exists · `503` IP pool exhausted · `500` AWG failure

---

### GET /internal/subscription/status?device_id=\<string\>

Response `200` (active):
```json
{
  "active": true,
  "vpn_key": "vpn://...",
  "days_remaining": 14,
  "expires_at": "2026-07-27T12:00:00Z"
}
```

Response `200` (inactive):
```json
{
  "active": false,
  "reason": "expired"
}
```

---

### POST /internal/subscription/expire

Manual admin override to immediately kill a subscription.

Request: `{ "device_id": "string" }`

Response `200`: `{ "ok": true }`

Error `404` if no active subscription found.

---

### GET /internal/health

Response `200`: `{ "db": "ok", "awg": "ok" }`

Returns `503` if either check fails.

---

## Scheduler

File: `scheduler.py` — `AsyncIOScheduler` started in `main.py` lifespan.

**Job: `expire_stale_subscriptions`** — runs daily at **02:00 UTC**

```
1. SELECT all subscriptions WHERE expires_at < NOW() AND status = 'active'
2. For each:
   a. awg.remove_peer(client_pub_key)  — log and continue on failure
   b. subscription.expire_subscription(db, sub)
3. awg.sync_config(db)  — one sync after all removals
4. Log: "Expired N subscriptions"
```

---

## main.py Outline

```python
from contextlib import asynccontextmanager
from fastapi import FastAPI
from scheduler import start_scheduler, stop_scheduler
from database import init_db
from routes.internal import router

@asynccontextmanager
async def lifespan(app: FastAPI):
    await init_db()
    start_scheduler()
    yield
    stop_scheduler()

app = FastAPI(lifespan=lifespan)
app.include_router(router, prefix="/internal")
```

---

## requirements.txt

```
fastapi==0.111.0
uvicorn[standard]==0.30.0
sqlalchemy[asyncio]==2.0.30
asyncpg==0.29.0
apscheduler==3.10.4
cryptography==42.0.8
python-dotenv==1.0.1
pydantic==2.7.1
pydantic-settings==2.3.0
alembic==1.13.1
```

---

## systemd Unit File: vpn-backend.service

```ini
[Unit]
Description=VPN Subscription Backend
After=network.target postgresql.service

[Service]
Type=simple
User=vpnbackend
WorkingDirectory=/opt/vpn-backend
EnvironmentFile=/opt/vpn-backend/.env
ExecStart=/opt/vpn-backend/venv/bin/uvicorn main:app --host 127.0.0.1 --port 8000
Restart=on-failure
RestartSec=5

[Install]
WantedBy=multi-user.target
```

---

## Setup Checklist

1. Create Linux user `vpnbackend`
2. Grant passwordless sudo for `awg` and `awg syncconf` only via `/etc/sudoers.d/vpnbackend`
3. Create PostgreSQL database and user
4. Run `migrations/001_initial.sql` (creates tables and seeds `ip_pool`)
5. Copy `AWG_SERVER_PRIVATE_KEY` from existing `/etc/amnezia/awg/awg0.conf`
6. Create `/opt/vpn-backend/.env` with all values from the Environment Variables section
7. `python3 -m venv venv && venv/bin/pip install -r requirements.txt`
8. `systemctl enable --now vpn-backend`
9. Verify: `curl -H "X-Internal-Secret: <secret>" http://127.0.0.1:8000/internal/health`

---

## Security Notes

- API binds to `127.0.0.1` only. The Telegram bot (running on the same server) calls it via localhost.
- Device ID is never stored raw — always SHA-256 hashed before any DB write.
- The vpn:// blob (which contains the client WireGuard private key) is AES-256-GCM encrypted at rest. Encryption key lives only in `.env`.
- PSK is stored in plaintext — acceptable, as it has no value without the corresponding private key.
- `vpnbackend` system user has sudo access scoped to `awg` commands only, not full root.
