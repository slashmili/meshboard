#!/bin/sh
set -eu
DEPLOY_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
DEPLOY_ENV_FILE=${DEPLOY_ENV_FILE:-$DEPLOY_ROOT/infra/deploy/.env}
compose() { docker compose --env-file "$DEPLOY_ENV_FILE" -f "$DEPLOY_ROOT/compose.yaml" "$@"; }

case "${1:-}" in
  init)
    compose config --quiet
    compose build --pull
    compose up -d --wait signaling web
    compose run --rm certbot issue
    # A repeated init may have renewed the certificate; always load it afresh.
    compose up -d --wait --force-recreate turn
    echo 'Deployment started. Complete the external relay tests in docs/deployment.md.'
    ;;
  renew)
    compose run --rm certbot renew
    if compose run --rm certbot needs-reload; then
      compose restart turn
      compose up -d --wait turn
      compose run --rm certbot reloaded
    fi
    ;;
  dry-run) compose run --rm certbot dry-run;;
  check) compose config --quiet;;
  status) compose ps;;
  logs) compose logs --tail=100;;
  *) echo 'Usage: sh infra/deploy/manage.sh {init|renew|dry-run|check|status|logs}' >&2; exit 1;;
esac
