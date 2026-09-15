ARG NODE_IMAGE=docker.io/library/node:22-alpine
ARG CADDY_IMAGE=docker.io/library/caddy:2.11.4-alpine

FROM ${NODE_IMAGE} AS dependencies
WORKDIR /app
RUN npm install --global pnpm@9.15.9
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml ./
COPY packages/web/package.json packages/web/package.json
COPY packages/signaling/package.json packages/signaling/package.json
COPY packages/shared-protocol/package.json packages/shared-protocol/package.json
RUN pnpm install --frozen-lockfile
COPY packages/web packages/web
COPY packages/signaling packages/signaling
COPY packages/shared-protocol packages/shared-protocol
COPY icons icons

FROM dependencies AS build
RUN pnpm check && pnpm --filter @meshboard/web build
RUN pnpm --filter @meshboard/signaling deploy --prod /runtime

FROM ${NODE_IMAGE} AS signaling
ENV NODE_ENV=production HOST=127.0.0.1 PORT=4444
WORKDIR /app
COPY --from=build --chown=node:node /runtime/ ./
USER node
CMD ["node", "node_modules/tsx/dist/cli.mjs", "src/main.ts"]

FROM ${CADDY_IMAGE} AS web
COPY --from=build /app/packages/web/dist /srv
COPY infra/deploy/Caddyfile /etc/caddy/Caddyfile
