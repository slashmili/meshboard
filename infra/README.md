# Install Meshboard on Ubuntu 26.04 LTS

This walkthrough is for a **fresh Ubuntu Server 26.04 LTS (Resolute), 64-bit VPS**
with a public IPv4 assigned directly to a network interface. Use an administrator
account with `sudo`; if logged in as root, omit `sudo`. Run the commands on the
VPS, not on your development computer. Keep provider console access available.

All hostnames, addresses, and secrets are operator-supplied. None belong in Git.
The example checkout path `/opt/meshboard` can be changed; adjust the renewal job
to match. Do not overwrite an existing checkout or another service's setup.

The deployment runs Caddy, signaling, and Coturn in containers. It uses Linux host
networking, not Docker Desktop or rootless Docker. See the
[deployment reference](../docs/deployment.md) for architecture, limits, rollback,
and the optional two-IP TURN/TLS-on-443 configuration.

**Testing release:** application-layer encryption and authenticated invites are
not implemented yet. Do not use this checkpoint for sensitive boards.

## 1. Prepare Ubuntu and install supporting packages

Confirm the OS, architecture, addresses, and clock:

```bash
cat /etc/os-release
dpkg --print-architecture
ip -4 address show
timedatectl status
```

Confirm `VERSION_ID="26.04"` and `VERSION_CODENAME=resolute`. Keep time
synchronization enabled: certificates and expiring TURN credentials depend on an
accurate clock. Resolve any unsynchronized-clock warning before deployment.

```bash
sudo apt-get update
sudo apt-get upgrade
sudo apt-get install ca-certificates curl git openssl ufw cron util-linux dnsutils vim iproute2
```

What these packages provide:

| Packages | Purpose |
| --- | --- |
| `ca-certificates`, `curl` | Trusted HTTPS downloads and HTTP checks |
| `git` | Clone and update the deployment source |
| `openssl` | Generate the TURN signing secret and inspect certificates |
| `ufw` | Configure the host firewall |
| `cron`, `util-linux` | Schedule renewal; `flock` prevents overlapping jobs |
| `dnsutils`, `iproute2` | `dig`, `ip`, and `ss` for DNS/network diagnostics |
| `vim` | Edit private settings and cron entries; another editor is fine |

If `/var/run/reboot-required` exists after updating, reboot and reconnect before
continuing. This interrupts SSH, so confirm provider-console access first.

No host installation of Node.js, pnpm, Kotlin, Java, Rust, Caddy, Coturn, Certbot,
or a database is needed. The application build and services run inside containers.

## 2. Install Docker Engine and Compose

Docker lists Ubuntu 26.04 LTS as supported. Use its signed Ubuntu package
repository, not the convenience installer or the Ubuntu `docker.io` package.
[Official Docker installation instructions](https://docs.docker.com/engine/install/ubuntu/)

On a provider image that already has a container runtime, check for conflicting
packages first:

```bash
dpkg-query -W docker.io docker-compose docker-compose-v2 docker-doc docker-buildx podman-docker containerd runc
```

Missing-package messages are normal on a fresh server. If any are installed,
review the official migration instructions before removing them: existing
containers or other software may depend on them. Do not delete container data.

Add the signing key and repository. This writes Docker's repository configuration;
if `docker.sources` already exists, review it before replacing it.

```bash
sudo install -d -m 0755 /etc/apt/keyrings
sudo curl --fail --silent --show-error --location \
  https://download.docker.com/linux/ubuntu/gpg \
  --output /etc/apt/keyrings/docker.asc
sudo chmod 0644 /etc/apt/keyrings/docker.asc

MESHBOARD_DOCKER_ARCH=$(dpkg --print-architecture)
printf '%s\n' \
  'Types: deb' \
  'URIs: https://download.docker.com/linux/ubuntu' \
  'Suites: resolute' \
  'Components: stable' \
  "Architectures: $MESHBOARD_DOCKER_ARCH" \
  'Signed-By: /etc/apt/keyrings/docker.asc' \
  | sudo tee /etc/apt/sources.list.d/docker.sources > /dev/null

sudo apt-get update
sudo apt-get install docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
sudo systemctl enable --now docker cron
sudo docker version
sudo docker compose version
sudo docker run --rm hello-world
```

The five Docker packages provide the daemon, CLI, container runtime, image builder,
and Compose plugin. Use `docker compose` (with a space), not legacy
`docker-compose`. Commands below deliberately use `sudo`; joining the `docker`
group is not necessary and would grant root-equivalent access.

**Checkpoint:** confirm the test container succeeds before continuing.

## 3. Configure DNS

At your DNS provider create two **A records**, both pointing to the VPS public
IPv4 for the default single-IP deployment:

| Name | Example | Destination |
| --- | --- | --- |
| App domain or subdomain | `board.example.com` or your domain apex | VPS IPv4 |
| TURN subdomain | `turn.example.com` | Same VPS IPv4 |

Use two different hostnames. If using Cloudflare, choose **DNS only** for both.
Do not add AAAA records for this IPv4-only setup; remove any stale AAAA records.
If you restrict certificate issuance with CAA records, follow the
[certificate/DNS notes](../docs/deployment.md#1-server-and-dns).

Enter your actual names for these checks (not the example names):

```bash
read -r -p 'App hostname: ' MESHBOARD_APP_HOST
read -r -p 'TURN hostname: ' MESHBOARD_TURN_HOST
dig +short A "$MESHBOARD_APP_HOST"
dig +short A "$MESHBOARD_TURN_HOST"
dig +short AAAA "$MESHBOARD_APP_HOST"
dig +short AAAA "$MESHBOARD_TURN_HOST"
```

Wait until both A lookups return the correct IP and neither has an unintended
AAAA result. Check from another network as well; local DNS caches can differ.

## 4. Configure the firewall without locking out SSH

This section assumes a fresh VPS. Review existing rules first. Keep the current
SSH session open and know how to reach the provider's recovery console. The
administrator address below is **your computer/network's public IP or CIDR**, not
the VPS IP. Use the actual SSH port, which may differ from 22.

```bash
sudo ufw status verbose
read -r -p 'Your administrator public IP/CIDR: ' MESHBOARD_ADMIN_CIDR
read -r -p 'SSH port [22]: ' MESHBOARD_SSH_PORT
MESHBOARD_SSH_PORT=${MESHBOARD_SSH_PORT:-22}
sudo ufw allow from "$MESHBOARD_ADMIN_CIDR" to any port "$MESHBOARD_SSH_PORT" proto tcp

sudo ufw allow 80/tcp
sudo ufw allow 443/tcp
sudo ufw allow 3478/tcp
sudo ufw allow 3478/udp
sudo ufw allow 5349/tcp
sudo ufw allow 49160:49259/udp
sudo ufw default deny incoming
sudo ufw default allow outgoing
sudo ufw show added
```

Verify the SSH rule and mirror the allowed ports in your **provider firewall**.
Then enable UFW interactively:

```bash
sudo ufw enable
sudo ufw status verbose
```

**Checkpoint:** open a second SSH connection successfully before closing the
first. If your administrator IP changes later, update the SSH allow rule before
switching networks.

Ports 80/443 serve the web app and certificate validation; 3478 and 5349 accept
TURN clients; the UDP range carries relayed traffic. Keep outbound connectivity
available. Do not open 4444, 8081, or 2019: those listeners are loopback-only.

These ports match the default deployment env file. If you change TURN ports or
the relay range, update both firewalls. TURN/TLS on 443 requires a second IP in
this template; do not assign both HTTPS and TURN to one IP's TCP port 443.

Docker-published bridge ports can bypass UFW, but these long-running services use
host networking and no published ports. Revisit firewall rules if changing that
topology. References: [Ubuntu UFW guide](https://ubuntu.com/server/docs/how-to/security/firewalls/)
and [Docker firewall warning](https://docs.docker.com/engine/install/ubuntu/#firewall-limitations).

## 5. Clone the repository and create private settings

Ensure the deployment files have been pushed to the repository you intend to
clone. Choose an unused checkout location; these instructions use `/opt/meshboard`.

```bash
read -r -p 'Public Git repository HTTPS URL: ' MESHBOARD_REPO_URL
sudo git clone "$MESHBOARD_REPO_URL" /opt/meshboard
cd /opt/meshboard
sudo test -e infra/deploy/.env || sudo install -m 0600 infra/deploy/.env.example infra/deploy/.env
openssl rand -hex 32
sudo vim infra/deploy/.env
```

The existence check preserves an existing env file. On a fresh installation,
paste the generated secret into `TURN_SECRET` using the editor;
do not put secrets in shell command arguments or Git. Fill in:

- `APP_DOMAIN`: your app hostname, without a scheme, path, or port.
- `TURN_DOMAIN`: your TURN hostname, also without a scheme, path, or port.
- `ACME_EMAIL`: your certificate contact email.
- `TURN_PUBLIC_IP`: the public IPv4 actually assigned to the server interface.
- `TURN_SECRET`: the generated 64-character hex string.

For one IP, leave `WEB_BIND_IP=0.0.0.0` and `TURN_TLS_PORT=5349`. The file is ignored
by Git and excluded from image builds. Keep it root-owned with mode 600; never
share full Compose configuration or container-inspection output containing it.

## 6. Start Meshboard and try it

Run from `/opt/meshboard` after DNS and firewall checks pass:

```bash
sudo sh infra/deploy/manage.sh check
sudo sh infra/deploy/manage.sh init
sudo sh infra/deploy/manage.sh status
```

The helper builds the images, starts Caddy/signaling, requests the TURN
certificate, and starts Coturn. Initial downloads/builds can take a few minutes.
Do not repeatedly retry failed certificate requests; fix DNS/networking first.

Open your app's HTTPS URL. Check `/health`, create a board, and join from a second
device on another network. Draw in both directions. **Pause here and try the app**,
then perform the [forced TURN and TLS tests](../docs/deployment.md#4-first-start--runnable-checkpoint).
Prefix the Docker commands in that reference with `sudo` for this installation.

For diagnostics:

```bash
sudo sh infra/deploy/manage.sh logs
sudo ss -lntup
```

Keep this a testing deployment until the cross-network checks pass. A green
health check alone does not prove TURN works through the provider firewall.

## 7. Schedule certificate renewal — required

Caddy renews its web certificate automatically. Coturn's certificate uses Certbot
and needs this host job. Verify its renewal path first:

```bash
sudo sh infra/deploy/manage.sh dry-run
sudo systemctl is-active cron
sudo crontab -e
```

Add these entries to **root's** crontab, preserving any existing jobs. Change the
checkout path if you did not use `/opt/meshboard`:

```cron
PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
17 3,15 * * * /usr/bin/flock -n /run/lock/meshboard-cert-renew.lock /bin/sh /opt/meshboard/infra/deploy/manage.sh renew 2>&1 | /usr/bin/logger -t meshboard-cert-renew
```

This runs twice daily, with output in the system journal. Review it with:

```bash
sudo journalctl -t meshboard-cert-renew --since '2 days ago'
```

Configure monitoring/alerts for renewal errors and certificate expiry; writing to
the journal alone does not notify you. Leave TCP 80 reachable. Coturn restarts
only when a renewed certificate must be loaded; relayed connections can briefly
drop during that restart.

## Updates, backups, and next checkpoint

Follow the [maintenance and rollback steps](../docs/deployment.md#6-limits-maintenance-and-rollback),
using `sudo` for Docker commands in this root-owned installation. Keep the private
env file and certificate volumes backed up securely. Do not use `down -v` or
volume pruning for updates. Server backups cannot recover live whiteboards.

The installation commands have been checked against the linked Ubuntu/Docker
documentation, but have not been executed on your VPS. Public DNS, certificate
issuance, renewal, and real network behavior must be verified there before moving
beyond this deployment checkpoint.
