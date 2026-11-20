// Local server log-privacy check. Never contacts ACME, production, or any public
// address. Requires Linux (all of 127.0.0.0/8 routes to loopback), Node 22,
// OpenSSL, installed workspace dependencies, Docker (or Podman) and the
// signaling image; see docs/deployment.md, "Logging and privacy".
//
// Visitor traffic comes from a dummy loopback source IP and carries distinctive
// markers in every field the policy covers. The check fails if any marker reaches
// a container's stdout/stderr or a file the container wrote. Control runs with
// logging re-enabled must leak the same markers, proving the detector works.
import assert from 'node:assert/strict'
import { execFile } from 'node:child_process'
import { createHash, createHmac, randomBytes, randomUUID } from 'node:crypto'
import dgram from 'node:dgram'
import { chmod, mkdtemp, mkdir, readFile, writeFile, rm } from 'node:fs/promises'
import http from 'node:http'
import https from 'node:https'
import { createRequire } from 'node:module'
import net from 'node:net'
import tls from 'node:tls'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { promisify } from 'node:util'

const exec = promisify(execFile)
const root = fileURLToPath(new URL('../', import.meta.url))
const runtime = process.env.CONTAINER_RUNTIME || 'docker'
const { WebSocket } = createRequire(new URL('../packages/signaling/package.json', import.meta.url))('ws')
const caddyImage = process.env.CADDY_IMAGE || (await readFile(join(root, 'infra/deploy/Dockerfile'), 'utf8')).match(/^ARG CADDY_IMAGE=(\S+)$/m)[1]
const turnImage = (await readFile(join(root, 'compose.yaml'), 'utf8')).match(/image: (docker\.io\/coturn\/coturn:\S+)/)[1]
const signalingImage = process.env.SIGNALING_IMAGE || 'meshboard-signaling:local'

const DUMMY_IP = '127.13.37.42' // visitor source address (loopback alias)
const FORWARDED_IP = '198.51.100.77' // documentation range, only sent in headers
// A name that qualifies for a public certificate, so Caddy runs its ACME HTTP
// challenge lookup for it exactly as in production. Nothing resolves it.
const TURN_HOST = 'turn.example.test'
const tag = randomBytes(5).toString('hex')
const marker = name => `zz${name}${tag}`
const room = randomUUID(), peer = randomUUID(), otherPeer = randomUUID()
const acmeToken = `zzacme${tag}Unknown_-Token`
const markers = [DUMMY_IP, FORWARDED_IP, tag, room, peer, otherPeer]
const visitorHeaders = {
  'user-agent': `Mozilla/5.0 ${marker('agent')}`,
  'x-forwarded-for': FORWARDED_IP, 'x-real-ip': FORWARDED_IP,
  cookie: `session=${marker('cookie')}`, referer: `https://localhost/#crdt=${marker('invite')}`,
  'x-meshboard-test': marker('header'),
}

const temporary = await mkdtemp(join(tmpdir(), 'meshboard-log-privacy-'))
const secret = randomBytes(32).toString('hex')
const suffix = randomBytes(4).toString('hex')
const names = []
const failures = []

async function command(args, timeout = 30_000) {
  const result = await exec(runtime, args, { timeout, maxBuffer: 8 * 1024 * 1024 })
  return result.stdout + result.stderr
}
async function freePort() {
  const server = net.createServer()
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve))
  const result = server.address().port
  await new Promise(resolve => server.close(resolve))
  return result
}
async function start(kind, args) {
  const name = `meshboard-privacy-${kind}-${suffix}`
  names.push(name)
  await command(['run', '-d', '--name', name, '--network', 'host', '--security-opt', 'label=disable', ...args])
  return name
}
async function remove(name) { await command(['rm', '-f', '-v', name]).catch(() => {}) }
async function ready(check) {
  for (let i = 0; i < 80; i++) {
    try { if (await check()) return } catch { /* starting */ }
    await new Promise(resolve => setTimeout(resolve, 250))
  }
  throw new Error('Container did not become ready')
}
// stdout/stderr as retained by the test's own log driver, plus every regular
// file the container wrote to its writable mounts (tmpfs, /dev, data volumes).
async function evidence(name) {
  const output = await command(['logs', name])
  const files = await command(['exec', name, 'sh', '-c',
    'for d in /tmp /dev /dev/shm /data /config /var/log /var/tmp /var/lib/coturn; do [ -d "$d" ] && find "$d" -xdev -type f; done 2>/dev/null | sort -u | while read -r f; do printf "\\n==> %s\\n" "$f"; cat "$f"; done'])
  return { output, files }
}
function found(text) { return markers.filter(value => text.includes(value)) }
function expectClean(label, { output, files }) {
  const leaks = [...found(output).map(m => `stdout/stderr: ${m}`), ...found(files).map(m => `file: ${m}`)]
  if (leaks.length) failures.push(`${label} leaked ${leaks.join(', ')}`)
  else console.log(`PASS: ${label}: no visitor markers in stdout/stderr or written files`)
}
function expectLeak(label, { output, files }, required) {
  const text = output + files, missing = required.filter(m => !text.includes(m))
  assert(found(text).length && !missing.length, `${label} control did not expose ${missing}; detector is not sensitive`)
  console.log(`PASS: ${label} control (logging enabled) exposes markers, so the detector works`)
}

function request(url, { host, method = 'GET', headers = {} } = {}) {
  const target = new URL(url)
  const client = target.protocol === 'https:' ? https : http
  return new Promise((resolve, reject) => {
    const req = client.request(target, {
      method, family: 4, localAddress: DUMMY_IP, rejectUnauthorized: false, servername: host,
      headers: { ...visitorHeaders, ...headers, ...(host ? { host: `${host}:${target.port}` } : {}) },
    }, response => {
      let body = ''
      response.on('data', chunk => { body += chunk })
      response.on('end', () => resolve({ status: response.statusCode, headers: response.headers, body }))
    })
    req.setTimeout(5000, () => req.destroy(new Error(`timeout ${url}`)))
    req.on('error', reject)
    req.end()
  })
}

function signalingSession(origin) {
  const topic = `meshboard-crdt:${room}`
  return new Promise((resolve, reject) => {
    const socket = new WebSocket(`${origin.replace('https:', 'wss:')}/signal-y-webrtc?room=${room}&key=${marker('wsquery')}`, {
      rejectUnauthorized: false, family: 4, localAddress: DUMMY_IP, origin, headers: visitorHeaders,
    })
    const timer = setTimeout(() => { socket.terminate(); reject(new Error('signaling session timeout')) }, 8000)
    const steps = [
      { type: 'publish', topic, data: { type: 'announce', from: peer } },
      { type: 'publish', topic, data: { type: 'signal', from: peer, to: otherPeer, token: 1,
        signal: { type: 'offer', sdp: `v=0\r\no=- 1 1 IN IP4 ${FORWARDED_IP}\r\ns=${marker('sdp')}\r\n` } } },
      // Board content must never pass through signaling; this is rejected.
      { type: 'publish', topic, data: { type: 'update', from: peer, board: marker('board') } },
    ]
    let relayed = 0, rejected = false
    socket.on('error', error => { clearTimeout(timer); reject(error) })
    socket.on('open', () => {
      socket.send(JSON.stringify({ type: 'subscribe', topics: [topic] }))
      socket.send(JSON.stringify(steps.shift()))
    })
    socket.on('message', raw => {
      const message = JSON.parse(raw)
      if (message.type === 'publish') { relayed++; if (steps.length) socket.send(JSON.stringify(steps.shift())) }
      if (message.type === 'error') rejected = true
    })
    socket.on('close', code => {
      clearTimeout(timer)
      try { assert.equal(relayed, 2); assert(rejected); assert.equal(code, 1008); resolve() } catch (error) { reject(error) }
    })
  })
}

async function malformed(httpPort, httpsPort) {
  await new Promise(resolve => {
    const socket = net.connect({ host: '127.0.0.1', port: httpPort, localAddress: DUMMY_IP }, () =>
      socket.end(`GET /${marker('rawpath')} HTTP/1.1\r\nHost: \u0001${marker('rawhost')}\r\nUser-Agent: ${marker('rawagent')}\r\n\r\n`))
    socket.on('error', resolve); socket.on('close', resolve); socket.resume()
  })
  await new Promise(resolve => {
    const socket = tls.connect({ host: '127.0.0.1', port: httpsPort, localAddress: DUMMY_IP, servername: `${marker('sni')}.invalid`, rejectUnauthorized: false })
    socket.on('error', resolve); socket.on('close', resolve); socket.on('secureConnect', () => socket.end())
  })
}

async function caddySuite(label, caddyfile, signaling, ports) {
  const { httpPort, httpsPort, healthPort, signalPort } = ports
  await command(['start', signaling])
  await ready(async () => (await fetch(`http://127.0.0.1:${signalPort}/health`)).ok)
  await writeFile(join(temporary, `${label}.Caddyfile`), caddyfile)
  const caddy = await start(`caddy-${label}`, ['--read-only', '--tmpfs', '/tmp', '--tmpfs', '/data', '--tmpfs', '/config',
    '--cap-drop', 'ALL', '--cap-add', 'NET_BIND_SERVICE', '-e', 'APP_DOMAIN=localhost', '-e', `TURN_DOMAIN=${TURN_HOST}`, '-e', 'ACME_EMAIL=test@example.com',
    '-e', 'WEB_BIND_IP=127.0.0.1', '-v', `${join(temporary, `${label}.Caddyfile`)}:/etc/caddy/Caddyfile:ro`,
    '-v', `${join(temporary, 'srv')}:/srv:ro`, '-v', `${join(temporary, 'acme')}:/var/www/acme:ro`, caddyImage])
  const origin = `https://localhost:${httpsPort}`
  await ready(async () => (await request(`${origin}/health`, { host: 'localhost' })).status === 200)

  // 1. Normal requests with metadata in the path, query, and headers.
  assert.equal((await request(`${origin}/?invite=${marker('query')}`, { host: 'localhost' })).status, 200)
  assert.equal((await request(`${origin}/board/${marker('path')}?key=${marker('key')}`, { host: 'localhost' })).status, 200)
  assert.equal((await request(`${origin}/api/rtc-config`, { host: 'localhost', headers: { 'x-room': room } })).status, 200)
  assert.equal((await request(`${origin}/api/${marker('apipath')}`, { host: 'localhost' })).status, 404)
  await signalingSession(origin)

  // 2. ACME on the TURN host: Certbot's webroot token is still served (renewal
  // path intact); an unknown token falls through to a plain 404.
  const acme = `http://127.0.0.1:${httpPort}/.well-known/acme-challenge`
  const known = await request(`${acme}/certbotToken${tag}`, { host: TURN_HOST, headers: { 'user-agent': `acme-validator ${marker('acmeagent')}` } })
  assert.equal(known.status, 200); assert.equal(known.body, `certbotToken${tag}.thumbprint`)
  const unknown = await request(`${acme}/${acmeToken}`, { host: TURN_HOST, headers: { 'user-agent': `acme-validator ${marker('acmeagent')}` } })
  assert.equal(unknown.status, 404)
  assert.equal((await request(`http://127.0.0.1:${httpPort}/${marker('redirect')}`, { host: TURN_HOST })).status, 301)

  // 3. Signaling upstream unavailable: Caddy returns 502 and would log the request.
  await command(['stop', '-t', '2', signaling])
  assert.equal((await request(`${origin}/api/rtc-config?room=${room}&key=${marker('downquery')}`, { host: 'localhost' })).status, 502)
  assert.equal((await request(`${origin}/health`, { host: 'localhost' })).status, 502)
  assert.equal((await request(`http://127.0.0.1:${healthPort}/health`)).status, 502)
  await assert.rejects(signalingSession(origin))
  await malformed(httpPort, httpsPort)
  await new Promise(resolve => setTimeout(resolve, 500))
  const result = await evidence(caddy)
  await remove(caddy)
  return result
}

async function turnAllocate(port, username, credential) {
  const socket = dgram.createSocket('udp4')
  await new Promise(resolve => socket.bind({ address: DUMMY_IP }, resolve))
  const cookie = 0x2112a442
  const attribute = (type, value) => {
    const bytes = Buffer.alloc(4 + Math.ceil(value.length / 4) * 4)
    bytes.writeUInt16BE(type); bytes.writeUInt16BE(value.length, 2); value.copy(bytes, 4); return bytes
  }
  async function send(type, attributes, key) {
    const transaction = randomBytes(12), body = Buffer.concat(attributes), header = Buffer.alloc(20)
    header.writeUInt16BE(type); header.writeUInt16BE(body.length + (key ? 24 : 0), 2); header.writeUInt32BE(cookie, 4); transaction.copy(header, 8)
    const unsigned = Buffer.concat([header, body])
    const packet = key ? Buffer.concat([unsigned, attribute(8, createHmac('sha1', key).update(unsigned).digest())]) : unsigned
    const response = await new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error('TURN timeout')), 4000)
      socket.on('message', function receive(bytes) {
        if (bytes.subarray(8, 20).equals(transaction)) { clearTimeout(timer); socket.off('message', receive); resolve(bytes) }
      })
      socket.send(packet, port, '127.0.0.1')
    })
    const values = new Map()
    for (let offset = 20; offset + 4 <= response.length;) {
      const length = response.readUInt16BE(offset + 2)
      values.set(response.readUInt16BE(offset), response.subarray(offset + 4, offset + 4 + length))
      offset += 4 + Math.ceil(length / 4) * 4
    }
    return { type: response.readUInt16BE(0), values }
  }
  try {
    const transport = attribute(0x19, Buffer.from([17, 0, 0, 0]))
    const challenge = await send(3, [transport])
    const realm = challenge.values.get(0x14), nonce = challenge.values.get(0x15)
    const key = createHash('md5').update(Buffer.concat([Buffer.from(`${username}:`), realm, Buffer.from(`:${credential}`)])).digest()
    const auth = [attribute(6, Buffer.from(username)), attribute(0x14, realm), attribute(0x15, nonce)]
    const allocation = await send(3, [transport, ...auth], key)
    if (allocation.type === 0x103) await send(4, [attribute(0x0d, Buffer.alloc(4)), ...auth], key)
    return allocation.type
  } finally { socket.close() }
}

async function turnSuite(label, config, ports) {
  const { turnPort, tlsPort } = ports
  await writeFile(join(temporary, `${label}.turnserver.conf`), config.replace('listening-port=3478', `listening-port=${turnPort}`))
  // Same user, mounts, and restrictions as compose.yaml.
  const turn = await start(`turn-${label}`, ['--user', '0:0', '--read-only', '--tmpfs', '/tmp', '--cap-drop', 'ALL', '--cap-add', 'NET_BIND_SERVICE',
    '--entrypoint', '/bin/sh', '-e', `TURN_SECRET=${secret}`, '-e', 'TURN_DOMAIN=localhost', '-e', 'TURN_PUBLIC_IP=127.0.0.1',
    '-e', `TURN_TLS_PORT=${tlsPort}`, '-e', 'WEB_BIND_IP=127.0.0.1', '-e', 'TURN_MIN_PORT=55100', '-e', 'TURN_MAX_PORT=55199',
    '-e', 'TURN_TOTAL_QUOTA=100', '-e', 'TURN_MAX_BPS=1000000', '-e', 'TURN_BPS_CAPACITY=20000000',
    '-v', `${root}/infra/deploy/turn-start.sh:/etc/coturn/start.sh:ro`,
    '-v', `${join(temporary, `${label}.turnserver.conf`)}:/etc/coturn/turnserver.conf:ro`,
    '-v', `${join(temporary, 'certs')}:/etc/letsencrypt:ro`, turnImage, '/etc/coturn/start.sh'])
  await ready(() => new Promise((resolve, reject) => {
    const socket = tls.connect({ host: '127.0.0.1', port: tlsPort, rejectUnauthorized: false }, () => { socket.end(); resolve(true) })
    socket.on('error', reject)
  }))
  const expiry = Math.floor(Date.now() / 1000) + 600
  const good = `${expiry}:${room}`, bad = `${expiry}:${marker('turnuser')}`
  assert.equal(await turnAllocate(turnPort, good, createHmac('sha1', secret).update(good).digest('base64')), 0x103)
  assert.equal(await turnAllocate(turnPort, bad, 'wrong-credential'), 0x113)
  await new Promise(resolve => {
    const socket = tls.connect({ host: '127.0.0.1', port: tlsPort, localAddress: DUMMY_IP, servername: `${marker('turnsni')}.invalid`, rejectUnauthorized: false },
      () => socket.end(`GET /${marker('turnjunk')} HTTP/1.1\r\n\r\n`))
    socket.on('error', resolve); socket.on('close', resolve)
  })
  await new Promise(resolve => setTimeout(resolve, 1500))
  const result = await evidence(turn)
  await remove(turn)
  return result
}

try {
  // Effective configuration checks.
  const adapted = JSON.parse(await command(['run', '--rm', '-e', 'APP_DOMAIN=board.example.test', '-e', 'TURN_DOMAIN=turn.example.test',
    '-e', 'ACME_EMAIL=ops@example.test', '-v', `${root}/infra/deploy/Caddyfile:/etc/caddy/Caddyfile:ro`, '--entrypoint', 'sh', caddyImage,
    '-c', 'caddy adapt --config /etc/caddy/Caddyfile 2>/dev/null']))
  const discarded = JSON.stringify(adapted.logging) === JSON.stringify({ logs: { default: { writer: { output: 'discard' } } } })
  const accessLogs = Object.entries(adapted.apps.http.servers).filter(([, definition]) => definition.logs).map(([server]) => server)
  if (!discarded) failures.push(`Caddy effective logging is not a single discarded default log: ${JSON.stringify(adapted.logging)}`)
  if (accessLogs.length) failures.push(`Caddy access logs enabled on ${accessLogs.join(', ')}`)
  if (discarded && !accessLogs.length) console.log(`PASS: ${caddyImage} adapts the Caddyfile to a discarded default log and no access logs`)

  // Fixtures.
  await chmod(temporary, 0o755) // the TURN container runs as root without DAC override
  await mkdir(join(temporary, 'certs/live/turn'), { recursive: true })
  await exec('openssl', ['req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '1', '-subj', '/CN=localhost',
    '-keyout', join(temporary, 'certs/live/turn/privkey.pem'), '-out', join(temporary, 'certs/live/turn/fullchain.pem')])
  await chmod(join(temporary, 'certs/live/turn/privkey.pem'), 0o644) // throwaway test key
  await mkdir(join(temporary, 'srv'))
  await writeFile(join(temporary, 'srv/index.html'), '<!doctype html><title>Meshboard</title>')
  await mkdir(join(temporary, 'acme/.well-known/acme-challenge'), { recursive: true })
  await writeFile(join(temporary, `acme/.well-known/acme-challenge/certbotToken${tag}`), `certbotToken${tag}.thumbprint`)
  const ports = Object.fromEntries(await Promise.all(['signalPort', 'httpPort', 'httpsPort', 'healthPort', 'adminPort', 'turnPort', 'tlsPort'].map(async n => [n, await freePort()])))

  const signaling = await start('signaling', ['--read-only', '--tmpfs', '/tmp', '--cap-drop', 'ALL', '--init',
    '-e', 'HOST=127.0.0.1', '-e', `PORT=${ports.signalPort}`, '-e', `MESHBOARD_TURN_SECRET=${secret}`, '-e', 'MESHBOARD_TRUST_PROXY=true',
    '-e', `MESHBOARD_TURN_URLS=turn:localhost:${ports.turnPort}?transport=udp`, signalingImage])

  const caddyfile = (await readFile(join(root, 'infra/deploy/Caddyfile'), 'utf8'))
    .replace('{\n', `{\n\thttp_port ${ports.httpPort}\n\thttps_port ${ports.httpsPort}\n\tadmin 127.0.0.1:${ports.adminPort}\n\tacme_ca https://127.0.0.1:9/directory\n\tskip_install_trust\n`)
    .replaceAll('bind 0.0.0.0', 'bind 127.0.0.1')
    .replaceAll('127.0.0.1:4444', `127.0.0.1:${ports.signalPort}`)
    .replace('http://127.0.0.1:8081', `http://127.0.0.1:${ports.healthPort}`)
  const withoutDiscard = caddyfile.replace(/\tlog default \{\n\t\toutput discard\n\t\}\n/, '')

  const caddy = await caddySuite('discarded', caddyfile, signaling, ports)
  // Caddy buffers its startup messages until the logging config loads, so the
  // discarded default log also drops them; only config load failures print.
  expectClean('Caddy (normal requests, unavailable upstream, unknown ACME challenge, malformed input)', caddy)
  expectLeak('Caddy', await caddySuite('control', withoutDiscard, signaling, ports), [
    // The two production warnings, then the HTTP error log for the 502.
    'looking up info for HTTP challenge', `no information found to solve challenge for identifier: ${TURN_HOST}`,
    DUMMY_IP, `acme-validator ${marker('acmeagent')}`, acmeToken, marker('downquery')])

  const signalingEvidence = { output: await command(['logs', signaling]), files: '' }
  assert.match(signalingEvidence.output, /^Meshboard signaling listening on 127\.0\.0\.1:\d+$/m, 'fixed signaling startup message is still emitted')
  expectClean('Signaling (rtc-config, WSS subscribe/announce/offer, rejected board payload)', signalingEvidence)

  const turnConfig = await readFile(join(root, 'infra/deploy/turnserver.conf'), 'utf8')
  expectClean('Coturn (allocation, wrong credentials, TLS junk)', await turnSuite('discarded', turnConfig, ports))
  const verbose = turnConfig.replace('log-file=/dev/null', 'log-file=stdout').replace('no-stdout-log\n', '')
  expectLeak('Coturn', await turnSuite('control', verbose, ports), [marker('turnuser')])

  if (failures.length) throw new Error(`Log privacy check failed:\n${failures.join('\n')}`)
  console.log('PASS: log privacy check complete')
} catch (error) {
  for (const name of names) {
    try { console.error(name, (await command(['logs', '--tail', '15', name])).replaceAll(secret, '[test-secret]')) } catch { /* removed */ }
  }
  throw error
} finally {
  for (const name of names.reverse()) await remove(name)
  await rm(temporary, { recursive: true, force: true })
}
