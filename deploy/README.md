# Dev server on a single VPS

The shared backend the frontend team builds against. `develop` is deployed automatically: every
push that passes the `build` job is built into an image, pushed to GHCR, and rolled out to the VPS
by the `deploy` job in `.github/workflows/build.yml`. Before a new image goes live, `deploy.sh`
dumps the database to `backups/pre-deploy-*.dump`.

Changes to the deployment itself (this directory, the workflow) go through a pull request into
`develop` like any other change; the pull request runs the build but never deploys.

```
browser ─┬─ api, s3, media ─────── DNS-only, straight to the VPS ───────┐
         └─ jaeger, mail, logs ── Cloudflare Free ("Full (strict)") ───┤ 443, Let's Encrypt wildcard;
                                                                       │ these three: Cloudflare ranges only
           VPS ── nginx ─┬─ api.<domain>     → app:8080        (Swagger UI for the frontend)
                         ├─ s3.<domain>      → minio:9000      (presigned downloads, app uploads)
                         ├─ media.<domain>   → minio:9000/<bucket>/product-images/ only
                         ├─ jaeger.<domain>  → jaeger:16686    (basic auth)
                         ├─ mail.<domain>    → mailpit:8025    (basic auth)
                         └─ logs.<domain>    → dozzle:8080     (basic auth)
                  internal network, no public ports:
                  app · postgres · redis · minio · jaeger · mailpit · dozzle
                  (postgres also on 127.0.0.1:5432 of the VPS, for SSH tunnels)
```

| URL | For |
|---|---|
| `https://api.<domain>/swagger-ui.html` | the frontend team: every endpoint, try-it-out |
| `https://api.<domain>/api/v1/...` | the API |
| `https://jaeger.<domain>` | traces of every request (team password) |
| `https://mail.<domain>` | every email the application sent - none reach a real inbox (team password) |
| `https://logs.<domain>` | live logs of every container in this stack, the application's first (team password) |

| File | Purpose |
|---|---|
| `docker-compose.yml` | the whole stack; only nginx publishes public ports (Postgres: loopback only); a memory limit on everything |
| `.env.example` | every variable; copied to `.env` on the server, never committed |
| `nginx/nginx.conf` | real client IP from Cloudflare, origin lock, flood limits for the DNS-only hosts, per-request DNS |
| `nginx/templates/stockflow.conf.template` | the six sites |
| `nginx/snippets/` | TLS, proxy headers, Cloudflare ranges (generated) |
| `scripts/deploy.sh` | pull, replace, wait for health, roll back on failure |
| `scripts/refresh-cloudflare-ips.sh` | regenerates the Cloudflare range lists |

## Memory budget (2 vCPU / 4 GB)

| Service | Limit | Note |
|---|---|---|
| app | 1400 MB | heap 60% (~840 MB), the rest metaspace and stacks |
| postgres | 768 MB | shared_buffers 256 MB |
| minio | 512 MB | |
| jaeger | 384 MB | in memory, at most 5,000 traces |
| redis | 192 MB | 128 MB of cache, LRU |
| nginx | 64 MB | |
| mailpit | 64 MB | at most 1,000 messages, kept on a volume |
| dozzle | 64 MB | stores nothing; reads the rotated json-file logs through the Docker socket |
| **total** | **~3.4 GB** | leaves ~400 MB for the OS - **add the 2 GB swap below** |

Not here, on purpose: **ClamAV** (~1.2 GB; upload scanning is off and the application logs a
warning saying so) and **Elasticsearch** (unused by the code).

Requests run on **virtual threads** (`spring.threads.virtual.enabled` in `application.yml`, so the
dev profile inherits it): there is no 200-thread Tomcat pool to size, and a request blocked on
Postgres or MinIO costs a few KB rather than a platform thread's stack. The database pool
(`DB_POOL_MAX`) is therefore what bounds concurrent database work.

Mail: supplier purchase orders and cancellation notices are sent through SMTP to Mailpit at
`mail.<domain>`. Other notification templates still write to the application log.

Before deploying this feature, set all six `PO_BUYER_*` / `PO_RECEIVING_ADDRESS` values from
`.env.example` in `/opt/stockflow/.env`. Compose refuses missing or empty values. Use the actual
buyer identity and receiving address, including a valid reply-to email. Set
`SUPPLIER_API_ALLOWED_HOSTS` only for approved supplier HTTPS hosts; leave it empty for email-only
delivery. Validate with `docker compose config --quiet` before deploying. Mailpit captures messages
for testing; it does not deliver them to external supplier inboxes.

Back-office login rate limit: the Next.js back-office calls the API from its own server, so without
help every admin shares one login rate-limit bucket. Set `BFF_ORIGIN_SECRET` (`openssl rand -hex 32`,
hex only) in `/opt/stockflow/.env` and the SAME value as a server-only env var of the back-office
deployment (never `NEXT_PUBLIC_*`). nginx then accepts the back-office's `X-Stockflow-Client-IP` only
when `X-Stockflow-Bff-Secret` matches, and strips both before Spring; any other caller keeps the
Cloudflare-restored address. Compose refuses an unset or empty value. Rotate both sides together.
B2B onboarding (SCRUM-429) needs `PORTAL_ACTIVATION_URL` in `/opt/stockflow/.env`: the customer portal's
activation page, e.g. `https://portal.<domain>/portal/activate` - the invitation e-mail links to it with the token
appended. Compose refuses a missing or empty value. Without it the application would answer 503
`PORTAL_INVITATION_UNAVAILABLE` to every customer creation, and since self-registration is closed no customer could
be created at all. Check after the deploy: Sales creates a test customer and gets 201, and the mail in Mailpit carries
the portal link.

---

## 1. One-time: the VPS

Ubuntu 22.04/24.04.

```bash
# as root
apt update && apt install -y ca-certificates curl rsync apache2-utils
curl -fsSL https://get.docker.com | sh

adduser --disabled-password --gecos "" deploy
usermod -aG docker deploy
mkdir -p /opt/stockflow/certs /opt/stockflow/auth && chown -R deploy:deploy /opt/stockflow

# SSH: keys only. ufw for SSH is fine; it does NOT protect Docker's published ports (80/443 are
# locked to Cloudflare inside nginx instead).
sed -i 's/^#\?PasswordAuthentication .*/PasswordAuthentication no/' /etc/ssh/sshd_config
systemctl restart ssh
ufw allow OpenSSH && ufw allow 80/tcp && ufw allow 443/tcp && ufw --force enable

# Swap: 4 GB of RAM is fully allotted. Without swap, the first start (JVM + every Flyway
# migration at once) or a memory spike gets a container OOM-killed.
fallocate -l 2G /swapfile && chmod 600 /swapfile && mkswap /swapfile && swapon /swapfile
echo '/swapfile none swap sw 0 0' >> /etc/fstab
sysctl -w vm.swappiness=10 && echo 'vm.swappiness=10' >> /etc/sysctl.conf
```

The deploy key, generated **on your machine**, not on the server:

```bash
ssh-keygen -t ed25519 -C "github-actions-deploy" -f ./stockflow_deploy -N ""
# put stockflow_deploy.pub into /home/deploy/.ssh/authorized_keys on the VPS
ssh-keyscan -H <vps-ip>          # output -> secret VPS_SSH_KNOWN_HOSTS
```

**Jaeger / Mailpit / Dozzle password** - one file guards all three (on the VPS, as `deploy`; one line per team
member if you like):

```bash
htpasswd -cB /opt/stockflow/auth/htpasswd team      # -c only the first time
```

## 2. One-time: Cloudflare (Free plan is enough)

1. Add the domain to Cloudflare and point its nameservers there.
2. DNS: `A` records → VPS IPv4:
   - `api`, `s3`, `media` → **DNS only** (grey cloud). Cloudflare Free sends Vietnamese ISPs to
     PoPs as far as Europe (measured: 13 of 20 requests), and back to a VPS in Vietnam - up to
     seconds for a trivial request. These hosts have no origin lock; nginx rate-limits them.
   - `jaeger`, `mail`, `logs` → **proxied** (orange cloud), locked to Cloudflare's ranges.

   **No `AAAA` records.** Docker does not publish IPv6 natively; it hands IPv6 connections to a
   userland proxy that replaces the source address, so nginx would see an internal address - every
   IPv6 client in one rate-limit bucket on the DNS-only hosts, every request dropped on the
   proxied ones.
3. SSL/TLS → Overview → **Full (strict)**.
4. The certificate: a Let's Encrypt wildcard, see 2a. (The Cloudflare Origin Certificate this
   used before is trusted by Cloudflare only, so a DNS-only host cannot use it.)
5. Caching → Cache Rules: `jaeger.<domain>`, `mail.<domain>`, `logs.<domain>` → **Bypass cache**.
   The DNS-only hosts never pass through Cloudflare, so no rule applies to them.
6. Security → Bots: leave **Bot Fight Mode off**.

## 2a. One-time: the certificate

Issued by certbot's DNS-01 challenge, which works whether or not a host is proxied - no port 80,
no downtime. It lands in `certs/letsencrypt/`, which CI's rsync never touches. **It must exist
before nginx starts with this configuration**: without it `nginx -t` fails and `deploy.sh`
refuses to restart nginx.

1. Cloudflare → My Profile → API Tokens → Create Token → template **Edit zone DNS**, zone
   `<domain>` only. Never paste it anywhere but the file below.
2. On the VPS:

```bash
sudo mkdir -p /root/.secrets && sudo nano /root/.secrets/cf.ini   # dns_cloudflare_api_token = <token>
sudo chmod 600 /root/.secrets/cf.ini
sudo docker run --rm -v /opt/stockflow/certs/letsencrypt:/etc/letsencrypt -v /root/.secrets/cf.ini:/cf.ini:ro \
  certbot/dns-cloudflare certonly --dns-cloudflare --dns-cloudflare-credentials /cf.ini \
  --dns-cloudflare-propagation-seconds 30 --cert-name wildcard -d <domain> -d "*.<domain>" \
  --agree-tos -m <email> --non-interactive
sudo ls -l /opt/stockflow/certs/letsencrypt/live/wildcard/        # fullchain.pem, privkey.pem
```

3. Renewal, in **root's** crontab (`sudo crontab -e`) - the token file is root's. `renew` does
   nothing until 30 days before expiry; the reload is harmless when nothing changed:

```cron
0 3 * * * docker run --rm -v /opt/stockflow/certs/letsencrypt:/etc/letsencrypt -v /root/.secrets/cf.ini:/cf.ini:ro certbot/dns-cloudflare renew --quiet && cd /opt/stockflow && docker compose exec -T nginx nginx -s reload
```

## 3. One-time: GitHub

Settings → Environments → **New environment `vps`**. Add required reviewers there if a deploy
should wait for approval. If you set *Deployment branches* on it, allow `develop`, or the deploy
job is refused.

| Kind | Name | Value |
|---|---|---|
| secret | `VPS_HOST` | VPS IPv4 (not the domain: Cloudflare proxies the domain, not SSH) |
| secret | `VPS_USER` | `deploy` |
| secret | `VPS_SSH_KEY` | contents of `stockflow_deploy` (the private key) |
| secret | `VPS_SSH_KNOWN_HOSTS` | output of `ssh-keyscan -H <vps-ip>` |
| secret | `VPS_SSH_PORT` | optional, default 22 |
| variable | `DOMAIN` | e.g. `example.com` (only for the environment link) |
| variable | `VPS_DEPLOY_PATH` | optional, default `/opt/stockflow` |

The jobs declare their own `packages: write` / `packages: read`; no repository-wide permission
change is needed.

## 4. First deploy

1. Push to `develop` once, so CI rsyncs this directory to the server and
   pushes the first image.
   The deploy step fails at this point because `.env` does not exist yet - expected.
2. On the VPS:
   ```bash
   cd /opt/stockflow
   cp .env.example .env && chmod 600 .env
   nano .env          # every CHANGE_ME, DOMAIN, APP_IMAGE
   ```
3. Re-run the failed workflow (Actions → the run → Re-run failed jobs).

The first start is the slow one - Flyway applies every migration while the JVM warms up on two
cores. `deploy.sh` waits up to 300 s.

4. **Create the first accounts.** The database starts with no user who can sign in (security is on
   here, unlike the local profile), so nobody can obtain a token until you do this:
   ```bash
   bash scripts/create-user.sh admin admin@<domain> ECOMMERCE_ADMIN
   bash scripts/create-user.sh kho1  kho1@<domain>  WAREHOUSE_STAFF
   ```
   It asks for the password (12+ characters). Running it again for an existing username resets the
   password and adds the roles given. Role codes are listed at the top of the script.

### How the frontend signs in

```
POST https://api.<domain>/api/v1/identity/auth/login
{"username": "admin", "password": "..."}
-> {"success": true, "data": {"accessToken": "eyJ...", "tokenType": "Bearer", "expiresInSeconds": 28800}}
```

Send `Authorization: Bearer <accessToken>` on every call. In Swagger UI: **Authorize**, paste the
token. A token lasts 8 hours, across deploys and restarts.

### The JWT signing key (`/opt/stockflow/jwt`)

Tokens are signed with an RSA key kept in `/opt/stockflow/jwt/signing-key.pem` on the host, mounted into the
application container (`JWT_PRIVATE_KEY_PATH` in `docker-compose.yml`). Nothing to do by hand:

- `scripts/deploy.sh` creates `jwt/` if missing and gives it to the image's `stockflow` user - uid/gid **10001**,
  pinned in the Dockerfile - (mode 700, the key 600) through a throwaway root container of the same image - no sudo
  needed. The `deploy` user can therefore not read, copy or delete anything in `jwt/` itself: use the commands
  below.
- The first start after that writes a new 2048-bit key there; every later start reads it. **The first deploy that
  brings this in signs every user out once** (the old per-start key is gone); after that, deploys and restarts keep
  everyone signed in.
- CI's rsync excludes `jwt/` (`.github/workflows/build.yml`), so `--delete` never removes it. It is not in the
  database dumps either.
- Whoever has the file can sign a token for any account: never copy it off the server or into the repository.
- **Rotate** (a leaked key, say): `bash scripts/deploy.sh --rotate-jwt-key`. It removes the key through a root
  container of the running image and restarts the application, which writes a new one; everyone signs in again once.
- **Bring your own key**: `openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out signing-key.pem`, copy
  it to the server, then `bash scripts/deploy.sh --install-jwt-key signing-key.pem && shred -u signing-key.pem`. The
  key is written owner-only into `jwt/` the same way and the application restarted; everyone signs in again once.
- Both commands keep the previous key until the application is healthy with the new one; if it is not (a bad key
  file stops startup with the reason in `docker compose logs app` - it never falls back to a throwaway key), the
  previous key is put back and the application restarted with it. Neither pulls an image or runs migrations.

Check:

```bash
curl -s https://api.<domain>/oauth2/jwks                     # 200, the public signing key
curl -sI https://api.<domain>/swagger-ui.html                # 200 / 302
curl -sI https://api.<domain>/actuator/health                # 401: needs an admin token
curl -sI https://media.<domain>/product-images/x.jpg         # 403/404 from MinIO, Cache-Control: no-store
curl -sI https://media.<domain>/design-renders/x             # 404 from nginx
curl -sI https://jaeger.<domain>                             # 401 without the password
curl -sI https://mail.<domain>                               # 401 without the password
curl -sI https://logs.<domain>                               # 401 without the password
curl -sI --resolve api.<domain>:443:<vps-ip> https://api.<domain>/oauth2/jwks   # 200, no -k: the certificate is public
curl -sk --resolve jaeger.<domain>:443:<vps-ip> https://jaeger.<domain>/   # "Empty reply": origin lock works
```

## 5. Operating it

All from `/opt/stockflow`.

```bash
docker compose ps
docker stats --no-stream                   # memory per container against its limit
docker compose logs -f --tail=200 app     # or, without SSH: https://logs.<domain>
bash scripts/deploy.sh sha-1a2b3c4         # roll back / forward to any pushed tag
bash scripts/deploy.sh                     # re-apply the running tag, e.g. after editing .env
grep ^IMAGE_TAG= .env                      # what is running
```

`deploy.sh` records the running tag as `IMAGE_TAG` in `.env`, so a plain `docker compose up -d`
also uses the deployed image. It pulls only a tag that is not already on the server - CI logs the
server in to GHCR for its own run only - so rolling back to a recent tag or re-applying `.env`
works without a registry login. It keeps the running and the previous image and removes older
ones, refuses to start while another deploy holds its lock, and fails (red CI) when the nginx
configuration does not pass `nginx -t`, even though the application itself was updated.

**What a deploy restarts:** only `app` (new image). Postgres, Redis and MinIO keep running and
keep their data; `minio-init` re-runs for a few seconds and changes nothing; nginx restarts only
when a file under `nginx/` changed. Every deploy has one to two minutes of downtime and **signs
every user out** (see the limits below) - tell the frontend team, or deploy less often.

**Reset the database** - wipes every row; needed to switch the demo data on or off, or to start
clean. MinIO files, Redis and the other containers are untouched.

```bash
docker compose stop app
docker compose rm -sf postgres
docker volume rm stockflow_postgres-data
bash scripts/deploy.sh                     # recreates Postgres and re-runs every migration
# then create the accounts again (scripts/create-user.sh)
```

**Database access** - Postgres is published on the VPS's **loopback only** (`127.0.0.1:5432`), so
it is invisible from the internet but reachable through SSH. On the server:
`docker compose exec postgres psql -U stockflow stockflow`. From your machine:

```bash
# then connect the client to localhost:15432 with DB_NAME / DB_USER / DB_PASSWORD from .env
ssh -N -L 15432:localhost:5432 deploy@<vps-ip>
```

In DBeaver / DataGrip, the built-in SSH tab does the same: SSH host `<vps-ip>`, user `deploy`, your
key; main tab host `localhost`, port `5432`. Use the VPS IP, not the domain - Cloudflare proxies
the domain, not SSH. The publish needs Docker >= 28 (`docker version`): older releases let hosts on
the same L2 segment reach loopback-published ports.

The MinIO console (port 9001) is not published; tunnel to the container's address instead
(`docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' stockflow-minio-1`),
which changes whenever the container is recreated.

**Backups** - `scripts/deploy.sh` dumps the database to `backups/pre-deploy-*.dump` before every
deploy of a new tag and refuses to deploy if the dump fails (the last 10 are kept): a migration
cannot be rolled back, only restored over. Add the nightly dump below with `crontab -e` as `deploy`,
and copy `backups/` off the machine (a backup on the same disk is not a backup). MinIO's files live in the `stockflow_minio-data` volume; back that up too if
the uploaded files matter.

```cron
0 3 * * *  cd /opt/stockflow && mkdir -p backups && docker compose exec -T postgres pg_dump -U stockflow -Fc stockflow > backups/stockflow-$(date +\%F).dump && find backups -name '*.dump' -mtime +14 -delete
0 4 1 * *  cd /opt/stockflow && bash scripts/refresh-cloudflare-ips.sh --reload
```

## 6. Known limits

- **Every deploy has downtime** (one application container, replaced). Users stay signed in across it: the signing
  key is persisted in `jwt/` (section "The JWT signing key").
- **CORS**: only the origins in `CORS_ALLOWED_ORIGIN_PATTERNS` (`.env`) may call the API from a
  browser. `http://localhost:*` lets the frontend team run their dev server against this backend;
  add the host a deployed frontend is served from (`https://*.<domain>` covers every subdomain) and
  run `bash scripts/deploy.sh` to apply it. A bare `*`, a missing scheme or a trailing slash stops
  the application at startup with the reason in `docker compose logs app`.
- The application's own uploads go out to the VPS's public address and back (`S3_ENDPOINT` must
  be the public host for presigned links to work, and the presigner and client share it).
- api, s3 and media have no DDoS protection or WAF in front of them - only nginx's per-address
  limits. Acceptable for a dev server; a production deployment would want an edge again.
- One machine: if the VPS is lost, so is everything not in an off-site backup.

## 7. Before this becomes production

`SPRING_PROFILES_ACTIVE=prod` (10% trace sampling, no SQL logging, narrower actuator), ClamAV back
in with `STOCKFLOW_UPLOAD_INSPECTION_ENABLED` removed (`docker-compose.media.yml` has the service
definition and its limits), `/actuator/` returning 404 in nginx, the JWT signing key from a real secret store (`JWT_PRIVATE_KEY`, PEM
content) rather than a file on the host, and a machine with at least 8 GB.
