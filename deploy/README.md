# Dev server on a single VPS

The shared backend the frontend team builds against. `develop` and `infra/deploy` are deployed
automatically: every push that passes the `build` job is built into an image, pushed to GHCR, and
rolled out to the VPS by the `deploy` job in `.github/workflows/build.yml`.

`infra/deploy` is where changes to the deployment itself (this directory, the workflow) are tried
against the server before they reach `develop`. Both branches deploy to the **same** VPS, so
whichever was pushed last is what runs - push `develop` again afterwards to put it back.

```
browser ── Cloudflare Free (DNS, CDN, WAF, TLS "Full (strict)")
              │ 443 only, Cloudflare ranges only
           VPS ── nginx ─┬─ api.<domain>     → app:8080        (Swagger UI for the frontend)
                         ├─ s3.<domain>      → minio:9000      (presigned downloads, app uploads)
                         ├─ media.<domain>   → minio:9000/<bucket>/product-images/ only
                         ├─ jaeger.<domain>  → jaeger:16686    (basic auth)
                         └─ mail.<domain>    → mailpit:8025    (basic auth)
                  internal network, no published ports:
                  app · postgres · redis · minio · jaeger · mailpit
```

| URL | For |
|---|---|
| `https://api.<domain>/swagger-ui.html` | the frontend team: every endpoint, try-it-out |
| `https://api.<domain>/api/v1/...` | the API |
| `https://jaeger.<domain>` | traces of every request (team password) |
| `https://mail.<domain>` | every email the application sent - none reach a real inbox (team password) |

| File | Purpose |
|---|---|
| `docker-compose.yml` | the whole stack; only nginx publishes ports; a memory limit on everything |
| `.env.example` | every variable; copied to `.env` on the server, never committed |
| `nginx/nginx.conf` | real client IP from Cloudflare, origin lock, per-request DNS |
| `nginx/templates/stockflow.conf.template` | the five sites |
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
| **total** | **~3.4 GB** | leaves ~400 MB for the OS - **add the 2 GB swap below** |

Not here, on purpose: **ClamAV** (~1.2 GB; upload scanning is off and the application logs a
warning saying so) and **Elasticsearch** (unused by the code).

Requests run on **virtual threads** (`spring.threads.virtual.enabled` in `application.yml`, so the
dev profile inherits it): there is no 200-thread Tomcat pool to size, and a request blocked on
Postgres or MinIO costs a few KB rather than a platform thread's stack. The database pool
(`DB_POOL_MAX`) is therefore what bounds concurrent database work.

Mail: the application is pointed at Mailpit, but nothing sends mail yet - `NotificationSender`
only logs. Until it does, read notifications with `docker compose logs app | grep NOTIFY`.

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

**Jaeger / Mailpit password** - one file guards both (on the VPS, as `deploy`; one line per team
member if you like):

```bash
htpasswd -cB /opt/stockflow/auth/htpasswd team      # -c only the first time
```

## 2. One-time: Cloudflare (Free plan is enough)

1. Add the domain to Cloudflare and point its nameservers there.
2. DNS: `A` records for `api`, `s3`, `media`, `jaeger`, `mail` → VPS IPv4, **proxied** (orange cloud).
   **No `AAAA` records.** Docker does not publish IPv6 natively; it hands IPv6 connections to a
   userland proxy that replaces the source address, so nginx would see an internal address
   instead of Cloudflare's and drop every IPv6 request.
3. SSL/TLS → Overview → **Full (strict)**.
4. SSL/TLS → Origin Server → Create Certificate for `<domain>, *.<domain>` (RSA, 15 years). Save
   on the VPS as `/opt/stockflow/certs/origin.pem` and `/opt/stockflow/certs/origin.key`
   (`chmod 600 origin.key`).
5. Caching → Cache Rules:
   - `api.<domain>`, `s3.<domain>`, `jaeger.<domain>`, `mail.<domain>` → **Bypass cache**.
   - `media.<domain>` → Eligible for cache, Edge TTL "use cache-control header" (a year for 200s).
6. Security → Bots: leave **Bot Fight Mode off**. It would challenge the application's own S3
   calls to `s3.<domain>`, which are not a browser.

## 3. One-time: GitHub

Settings → Environments → **New environment `vps`**. Add required reviewers there if a deploy
should wait for approval. If you set *Deployment branches* on it, allow both `develop` and
`infra/deploy`, or the deploy job of the missing one is refused.

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

1. Push to `infra/deploy` (or `develop`) once, so CI rsyncs this directory to the server and
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
token. A token lasts 8 hours - and ends at the next deploy, which signs everyone out.

Check:

```bash
curl -s https://api.<domain>/oauth2/jwks                     # 200, the public signing key
curl -sI https://api.<domain>/swagger-ui.html                # 200 / 302
curl -sI https://api.<domain>/actuator/health                # 401: needs an admin token
curl -sI https://media.<domain>/product-images/x.jpg         # 403/404 from MinIO, Cache-Control: no-store
curl -sI https://media.<domain>/design-renders/x             # 404 from nginx
curl -sI https://jaeger.<domain>                             # 401 without the password
curl -sI https://mail.<domain>                               # 401 without the password
curl -sk --resolve api.<domain>:443:<vps-ip> https://api.<domain>/   # "Empty reply": origin lock works
```

## 5. Operating it

All from `/opt/stockflow`.

```bash
docker compose ps
docker stats --no-stream                   # memory per container against its limit
docker compose logs -f --tail=200 app
bash scripts/deploy.sh sha-1a2b3c4         # roll back / forward to any pushed tag
cat .deployed-tag                          # what is running
```

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

**Database access** - no published port. On the server:
`docker compose exec postgres psql -U stockflow stockflow`. For a GUI client (DBeaver, DataGrip),
tunnel to the container's address, which the host can reach directly:

```bash
# on the VPS: the container's IP
docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' stockflow-postgres-1
# on your machine, then connect the client to localhost:15432
ssh -N -L 15432:<that-ip>:5432 deploy@<vps-ip>
```

The same works for the MinIO console on port 9001 of `stockflow-minio-1`.

**Backups** - `crontab -e` as `deploy`, and copy `backups/` off the machine (a backup on the same
disk is not a backup). MinIO's files live in the `stockflow_minio-data` volume; back that up too if
the uploaded files matter.

```cron
0 3 * * *  cd /opt/stockflow && mkdir -p backups && docker compose exec -T postgres pg_dump -U stockflow -Fc stockflow > backups/stockflow-$(date +\%F).dump && find backups -name '*.dump' -mtime +14 -delete
0 4 1 * *  cd /opt/stockflow && bash scripts/refresh-cloudflare-ips.sh --reload
```

## 6. Known limits

- **Every deploy signs every user out and has downtime.** `JwtKeysConfig` generates a new signing
  key at startup, so tokens do not survive a restart. Fix that first (load the key from a mounted
  secret) before attempting zero-downtime deploys - which a 4 GB machine could not run anyway.
- **CORS**: only the origins in `CORS_ALLOWED_ORIGIN_PATTERNS` (`.env`) may call the API from a
  browser. `http://localhost:*` lets the frontend team run their dev server against this backend;
  add the host a deployed frontend is served from (`https://*.<domain>` covers every subdomain) and
  run `bash scripts/deploy.sh` to apply it. A bare `*`, a missing scheme or a trailing slash stops
  the application at startup with the reason in `docker compose logs app`.
- The application's own uploads go out to Cloudflare and back (`S3_ENDPOINT` must be the public
  host for presigned links to work, and the presigner and client share it).
- One machine: if the VPS is lost, so is everything not in an off-site backup.

## 7. Before this becomes production

`SPRING_PROFILES_ACTIVE=prod` (10% trace sampling, no SQL logging, narrower actuator), ClamAV back
in with `STOCKFLOW_UPLOAD_INSPECTION_ENABLED` removed (`docker-compose.media.yml` has the service
definition and its limits), `/actuator/` returning 404 in nginx, a persisted JWT signing key, and
a machine with at least 8 GB.
