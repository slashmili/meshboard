import { appendFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

export function releaseConfiguration(tag, appOrigin, turnDomain) {
  const version = /^v?((?:0|[1-9]\d*)\.(?:0|[1-9]\d*)\.(?:0|[1-9]\d*))(?:-[0-9A-Za-z]+(?:[.-][0-9A-Za-z]+)*)?$/.exec(tag || '')?.[1]
  if (!version || tag !== tag.trim()) throw new Error('Release tag must be vMAJOR.MINOR.PATCH, optionally with a prerelease suffix.')
  if (!appOrigin) throw new Error('Set the repository variable MESHBOARD_APP_ORIGIN before publishing a release.')
  const url = new URL(appOrigin)
  if (url.protocol !== 'https:' || url.username || url.password || url.search || url.hash || url.pathname !== '/') {
    throw new Error('MESHBOARD_APP_ORIGIN must be an HTTPS origin without credentials, path, query, or fragment.')
  }
  if (!/^(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\.)+[a-z]{2,}$/i.test(turnDomain || '') || turnDomain !== turnDomain.trim()) {
    throw new Error('Set MESHBOARD_TURN_DOMAIN to the TURN hostname, without a scheme, port, or credentials.')
  }
  return { tag, version, appOrigin: url.origin, turnDomain: turnDomain.toLowerCase() }
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const config = releaseConfiguration(process.env.RELEASE_TAG, process.env.RELEASE_APP_ORIGIN, process.env.RELEASE_TURN_DOMAIN)
  if (process.env.GITHUB_OUTPUT) appendFileSync(process.env.GITHUB_OUTPUT, `version=${config.version}\napp-origin=${config.appOrigin}\n`)
  console.log(JSON.stringify({ ...config, turnDiscovery: `${config.appOrigin}/api/rtc-config` }, null, 2))
}
