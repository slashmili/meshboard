# Self-hosting Meshboard with Docker Compose

For a fresh Ubuntu 26.04 LTS server, start with the
[installation walkthrough and package list](../infra/README.md).

This setup runs the built web app, the signaling service, and Coturn on one Linux
VPS. It requires no managed TURN service or database. All domain names, addresses,
email addresses, and secrets are supplied at runtime; the repository is safe to
publish without operator-specific configuration.

**Testing release:** WebRTC encrypts transport traffic, but Meshboard's planned
application-layer encryption and authenticated invites are not implemented yet.
Do not advertise this deployment as meeting the complete security model in
AGENTS.md or use it for sensitive boards. Server backups do not save live boards.

## 1. Server and DNS

Use a fresh Linux VPS with Docker Engine and the Docker Compose v2 plugin. A
starting size is 2 vCPUs / 4 GB RAM / 40 GB disk. Install Docker using the
[official instructions](https://docs.docker.com/engine/install/). No Node, pnpm,
Java, or Android SDK is needed on the server: the images build the web app and
signaling service themselves.

This template uses **Linux host networking** and an IPv4 address assigned directly
to a server interface. It is not a Docker Desktop, rootless-container, NAT, or
IPv6 deployment recipe. Run Docker as root or through an appropriately trusted
Docker account (Docker access is effectively root access). Do not run another
web server on the same ports. Internal ports 4444, 8081, and Caddy's admin port
2019 are loopback-only and must not be exposed through another proxy.

Choose two different DNS names and create these records at your DNS provider:

| Record | Example name | Value |
| --- | --- | --- |
| A | `board.example.com` (or your domain apex) | VPS public IPv4 |
| A | `turn.example.com` | Same VPS public IPv4 |

The examples are placeholders, not Meshboard-owned endpoints. If using
Cloudflare DNS, set **both records to DNS only** for this first deployment.
Ordinary HTTP CDN proxying does not carry TURN. Do not add AAAA records until an
IPv6 deployment has been configured and tested. Remove stale AAAA records that
could send certificate validation or clients to the wrong server. If you have
restrictive CAA records, allow `letsencrypt.org` (and `pki.goog` for Caddy's
optional issuer fallback), or configure a single permitted issuer explicitly.
Allow time for DNS propagation before requesting certificates.

## 2. Firewall

Keep your existing SSH connection open while editing firewall rules. Allow your
SSH port **from your administrative IP** before enabling a default-deny firewall.
Configure both the provider firewall and the host firewall:

| Inbound port | Protocol | Purpose |
| --- | --- | --- |
| Your SSH port | TCP | Administration; restrict source addresses |
| 80 | TCP | ACME certificate validation and HTTP redirect |
| 443 | TCP | HTTPS and WSS |
| 3478 | UDP and TCP | TURN client connections |
| 5349 | TCP | TURN over TLS; use the configured `TURN_TLS_PORT` |
| 49160–49259 | UDP | TURN relay allocations; match the configured range |

Leave outbound connectivity available for DNS, certificate issuance, package
downloads, and relay traffic to peers' public addresses/ports. No UDP 443 is
needed: this configuration disables HTTP/3. `no-tcp-relay` disables RFC 6062 TCP
relay allocations, **not** TURN client connections over TCP/TLS; WebRTC uses UDP
relay allocations even when the client reaches TURN over TLS.

### TURN/TLS on port 443

With **one public IP**, web HTTPS owns TCP 443 and TURN/TLS uses TCP 5349. Some
corporate networks block 5349; this topology does not promise connectivity there.

For TURN/TLS on 443, provision a **second public IPv4**, configure it on the server,
then set:

- `WEB_BIND_IP` to the first IP, and point `APP_DOMAIN` at that IP.
- `TURN_PUBLIC_IP` to the second IP, and point `TURN_DOMAIN` at that IP.
- `TURN_TLS_PORT=443`; allow TCP 443 on both IPs and the TURN UDP ports on the
  second IP. Port 80 must reach the server on both IPs for certificate validation.

No proxy or custom Caddy build is needed. Merely changing the port to 443 while
both services use one IP will not work. Even TURN/TLS on 443 cannot guarantee
passage through every HTTP-only or TLS-inspecting corporate firewall.

## 3. Private configuration

Clone your chosen public repository URL onto the server. From the repository root:

```sh
cp infra/deploy/.env.example infra/deploy/.env
chmod 600 infra/deploy/.env
openssl rand -hex 32
```

Edit `infra/deploy/.env` and fill in:

- `APP_DOMAIN`: hostname only, without `https://`, port, slash, or path.
- `TURN_DOMAIN`: a different hostname, also without a scheme or path.
- `ACME_EMAIL`: your certificate contact email.
- `TURN_PUBLIC_IP`: the real public IPv4 assigned to the VPS interface.
- `TURN_SECRET`: the generated 64-character hex value.

For one IP, leave `WEB_BIND_IP=0.0.0.0` and `TURN_TLS_PORT=5349`. Leave the other
defaults for initial testing. `COMPOSE_PROJECT_NAME` separates this deployment's
containers/volumes; keep it stable across updates. Runtime settings do not require
rebuilding the web app.

The file is ignored by Git; `.dockerignore` also keeps environment files, keys,
native toolchains, and local dependencies out of image builds. Do not put these
values in a committed Compose override. Never publish `docker compose config`
output, container inspection output, or screenshots containing environment values:
those can reveal secrets. `manage.sh check` validates Compose without printing it.

`TURN_SECRET` is a **server-side credential-signing key**, not a board encryption
key. The public `/api/rtc-config` endpoint issues temporary HMAC credentials;
it never returns this key. Endpoint rate limits are basic abuse protection, not
user authentication: determined users can still obtain fresh credentials.

## 4. First start — runnable checkpoint

Once DNS and firewall rules are ready:

```sh
sh infra/deploy/manage.sh check
sh infra/deploy/manage.sh init
sh infra/deploy/manage.sh status
```

`init` builds the images, starts signaling and Caddy, obtains the TURN certificate
using HTTP validation, and then starts Coturn. Caddy manages the web certificate
automatically. Coturn uses a separate Certbot-managed certificate volume.
If DNS is not ready, correct it and rerun `init`; do not repeatedly retry failed
issuance because certificate authorities enforce rate limits.

**Pause here and test the app before proceeding with new features.** The server
being healthy is not proof that relay allocations work across networks.

1. Open `https://YOUR_APP_DOMAIN/health`; expect `{"ok":true}`.
2. Open the web app, create a board, and join from another device on a different
   network (for example, a phone on mobile data). Draw in both directions.
3. Temporarily set `MESHBOARD_RELAY_ONLY=true` in the deployment `.env` and run:

   ```sh
   docker compose --env-file infra/deploy/.env up -d signaling
   ```

   Refresh/rejoin every client, then verify drawing in both directions and the
   relay indicator where available. This exercises actual TURN, not just HTTPS.
4. To test **TLS specifically**, temporarily use the command below instead. It
   changes only the signaling container for this test; keep normal web and TURN
   containers running. Replace placeholders; do not commit the resulting URL.

   ```sh
   docker compose --env-file infra/deploy/.env stop signaling
   docker compose --env-file infra/deploy/.env run --rm --no-deps \
     -e MESHBOARD_RELAY_ONLY=true \
     -e MESHBOARD_TURN_URLS='turns:YOUR_TURN_DOMAIN:5349?transport=tcp' signaling
   ```

   Use port 443 if configured. Refresh/rejoin clients and draw again. Press Ctrl-C
   to stop the temporary signaling process; return `MESHBOARD_RELAY_ONLY=false`
   in `.env` and run `docker compose --env-file infra/deploy/.env up -d signaling`.
   Refresh clients again. A working UDP relay test alone does not validate TLS.
5. Repeat sharing with desktop/Android and eventually iPad/iPhone. Native clients
   should use the public HTTPS board link, not the emulator's local test address.

Do not rotate credentials or update/restart containers during an important live
session. Peers using TURN can lose connections when Coturn restarts.

## 5. Certificate renewal — required

Caddy automatically renews its own web certificate. **The TURN certificate needs
the renewal job below.** Without it, TURN/TLS will eventually stop working.

First verify the ACME renewal path:

```sh
sh infra/deploy/manage.sh dry-run
```

Schedule the following command twice daily using root's crontab (`sudo crontab -e`),
replacing `/ABSOLUTE/PATH/TO/REPO` with the actual server checkout path:

```cron
17 3,15 * * * /usr/bin/flock -n /run/lock/meshboard-cert-renew.lock /bin/sh /ABSOLUTE/PATH/TO/REPO/infra/deploy/manage.sh renew
```

Ensure `docker` is available in cron's PATH and configure delivery/monitoring for
job failures. Do not discard renewal output silently. Keep port 80 reachable.
The helper restarts Coturn **only after successful certificate renewal** and
checks that it becomes healthy. A renewal restart briefly interrupts relayed
connections; choose maintenance times appropriate to your users. No container
is given access to the Docker socket.

## 6. Limits, maintenance, and rollback

Defaults are conservative starting limits, not a tested user-capacity guarantee:

- 8 peers per board; 256 total signaling connections; 16 connections per client
  IP; 30 WebSocket upgrade attempts and 30 credential requests per IP per minute.
  Shared office NATs share these IP-based limits.
- 100 total TURN allocations, 16 per temporary username; 100 UDP relay ports.
  Full-mesh sessions can use multiple allocations per participant.
- Relay bandwidth limits are **bytes/second**, not bits: `TURN_MAX_BPS=1000000`
  per session and `TURN_BPS_CAPACITY=20000000` in aggregate. These are service
  limits, not a guarantee of a provider billing cap. Still check your VPS contract.
- Credentials expire after 24 hours by default. Current clients fetch settings
  once per session and do not refresh credentials automatically. Refresh/rejoin
  before that lifetime for long-running boards, or ICE restarts/relay refreshes
  may fail. Expiry is not per-user revocation and does not end direct connections.
- Private/link-local/reserved destinations are denied on TURN; the initial relay
  configuration uses IPv4 only. Do not enable the development loopback-peer policy.

Use `sh infra/deploy/manage.sh status` and `logs` for diagnostics. Container logs
are size-limited. Access logging is not enabled and Coturn session logs are
disabled to avoid retaining peer details. `/health` and container health checks
do not replace uptime monitoring and real cross-network relay tests.

For updates, save your current Git revision and image tag, fetch/review changes,
then use a **new** `MESHBOARD_IMAGE_TAG` in `.env` (for example your new Git revision):

```sh
docker compose --env-file infra/deploy/.env build --pull
docker compose --env-file infra/deploy/.env pull turn certbot
docker compose --env-file infra/deploy/.env up -d --wait
```

Repeat the acceptance tests. Keep old images and the previous source revision
until verified. To roll back, restore the previous source revision in a clean
deployment checkout and previous image tag, then run `up -d --wait --no-build`.
Keep the same project name, private env file, and persistent volumes. Avoid
automatic unattended application/image upgrades; intentionally update pinned
Coturn/Caddy/Certbot versions for security fixes.

Back up the private env file and certificate volumes securely. They contain
secrets and account keys. No board database exists. **Never run `down -v` or volume
pruning as an update step:** that removes certificate/account state. Ordinary
`docker compose --env-file infra/deploy/.env down` stops services but retains it.

## Validation boundary

The repository's automated tests cover credential generation, expiry, API rate
limits, and signaling behavior. Local container checks cannot establish public
DNS propagation, trusted ACME issuance, provider firewall behavior, or actual
corporate-network compatibility. Those remain the first server checkpoint above.

To repeat the local container smoke test on Linux (development machine only):

```sh
docker build --target signaling -t meshboard-signaling:local -f infra/deploy/Dockerfile .
docker build --target web -t meshboard-web:local -f infra/deploy/Dockerfile .
node scripts/test-deployment.mjs
```

This test also needs Node 22, installed workspace dependencies, and OpenSSL. Set
`CONTAINER_RUNTIME=podman` to use Podman; `SIGNALING_IMAGE` and `WEB_IMAGE` can
override the image names. It uses temporary containers, local certificates, and
an explicitly temporary loopback exception for TURN. It tests built assets,
HTTPS/WSS proxying, credential issuance, public IPv4 permission acceptance,
private/link-local/loopback permission rejection, and real UDP/TLS relay traffic, then
removes its own containers and temporary files. It never requests public
certificates or reads your deployment `.env`. Permission checks do not send
traffic to the requested public or private addresses. Do not run it on a live VPS.

References: [Caddy automatic HTTPS](https://caddyserver.com/docs/automatic-https),
[Coturn](https://github.com/coturn/coturn),
[Certbot renewal](https://eff-certbot.readthedocs.io/en/stable/using.html#renewing-certificates),
[Docker host networking](https://docs.docker.com/engine/network/drivers/host/).
