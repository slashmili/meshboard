#!/bin/sh
set -eu

fail() { echo "TURN configuration error: $*" >&2; exit 1; }
[ "${#TURN_SECRET}" -eq 64 ] || fail 'TURN_SECRET must contain 64 hex characters.'
case "$TURN_SECRET" in *[!0-9a-fA-F]*) fail 'TURN_SECRET must contain only hex characters.';; esac
case "$TURN_DOMAIN" in ''|*[!a-zA-Z0-9.-]*) fail 'TURN_DOMAIN must be a DNS hostname.';; esac
case "$TURN_PUBLIC_IP" in ''|*[!0-9.]*) fail 'TURN_PUBLIC_IP must be an assigned public IPv4 address.';; esac
for value in "$TURN_TLS_PORT" "$TURN_MIN_PORT" "$TURN_MAX_PORT" "$TURN_TOTAL_QUOTA" "$TURN_MAX_BPS" "$TURN_BPS_CAPACITY"; do
  case "$value" in ''|*[!0-9]*) fail 'Ports and quotas must be positive integers.';; esac
  [ "$value" -gt 0 ] || fail 'Ports and quotas must be positive integers.'
done
[ "$TURN_TLS_PORT" -le 65535 ] || fail 'Invalid TURN TLS port.'
[ "$TURN_MIN_PORT" -ge 1024 ] && [ "$TURN_MAX_PORT" -le 65535 ] && [ "$TURN_MIN_PORT" -le "$TURN_MAX_PORT" ] || fail 'Invalid relay port range.'
[ "$TURN_TLS_PORT" -ne 3478 ] || fail 'TURN TLS must use a different port than plain TURN.'
if [ "$TURN_TLS_PORT" -eq 443 ]; then
  [ "$WEB_BIND_IP" != '0.0.0.0' ] && [ "$WEB_BIND_IP" != "$TURN_PUBLIC_IP" ] || fail 'TURN/TLS :443 requires a second IP and a distinct WEB_BIND_IP.'
fi
[ -r /etc/letsencrypt/live/turn/fullchain.pem ] && [ -r /etc/letsencrypt/live/turn/privkey.pem ] || fail 'Issue the TURN certificate first using manage.sh init.'

exec turnserver -c /etc/coturn/turnserver.conf \
  --realm="$TURN_DOMAIN" --server-name="$TURN_DOMAIN" \
  --static-auth-secret="$TURN_SECRET" \
  --listening-ip="$TURN_PUBLIC_IP" --relay-ip="$TURN_PUBLIC_IP" \
  --tls-listening-port="$TURN_TLS_PORT" \
  --min-port="$TURN_MIN_PORT" --max-port="$TURN_MAX_PORT" \
  --total-quota="$TURN_TOTAL_QUOTA" --max-bps="$TURN_MAX_BPS" --bps-capacity="$TURN_BPS_CAPACITY" \
  --cert=/etc/letsencrypt/live/turn/fullchain.pem \
  --pkey=/etc/letsencrypt/live/turn/privkey.pem
