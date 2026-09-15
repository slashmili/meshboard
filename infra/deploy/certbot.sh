#!/bin/sh
set -eu
case "${1:-}" in
  issue)
    exec certbot certonly --non-interactive --agree-tos --email "$ACME_EMAIL" \
      --webroot -w /var/www/acme --cert-name turn -d "$TURN_DOMAIN" --keep-until-expiring
    ;;
  renew)
    # Coturn needs a restart to load the new certificate. Mark only successful
    # renewals; keep the marker if restarting later fails so it can be retried.
    exec certbot renew --non-interactive --cert-name turn \
      --deploy-hook 'touch /etc/letsencrypt/reload-turn'
    ;;
  needs-reload) test -f /etc/letsencrypt/reload-turn;;
  reloaded) rm -f /etc/letsencrypt/reload-turn;;
  dry-run) exec certbot renew --dry-run --non-interactive --cert-name turn;;
  *) echo 'Expected issue, renew, dry-run, needs-reload, or reloaded.' >&2; exit 1;;
esac
