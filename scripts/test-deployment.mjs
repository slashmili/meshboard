// Local integration smoke test. Never contacts ACME or the operator's domain.
// Build the two images first; see docs/deployment.md. Requires Linux, Node 22,
// OpenSSL, installed workspace dependencies, and Docker or Podman.
import assert from 'node:assert/strict'
import { execFile } from 'node:child_process'
import { createHash, createHmac, randomBytes } from 'node:crypto'
import dgram from 'node:dgram'
import { mkdtemp, mkdir, readFile, writeFile, rm } from 'node:fs/promises'
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
const require = createRequire(new URL('../packages/signaling/package.json', import.meta.url))
const { WebSocket } = require('ws')
const temporary = await mkdtemp(join(tmpdir(), 'meshboard-deployment-'))
const secret = randomBytes(32).toString('hex')
const names = []
const suffix = randomBytes(4).toString('hex')
const turnImage = 'docker.io/coturn/coturn:4.18.0-r0-alpine'
async function command(args, timeout = 30_000) {
  const result = await exec(runtime, args, { timeout, maxBuffer: 2 * 1024 * 1024 })
  return result.stdout + result.stderr
}
async function port() {
  const server = net.createServer()
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve))
  const result = server.address().port
  await new Promise(resolve => server.close(resolve))
  return result
}
async function start(kind, args) {
  const name = `meshboard-smoke-${kind}-${suffix}`
  names.push(name)
  await command(['run', '-d', '--name', name, '--network', 'host', '--security-opt', 'label=disable', ...args])
  return name
}
async function ready(check) {
  for (let i = 0; i < 60; i++) {
    try { if (await check()) return } catch { /* wait for startup */ }
    await new Promise(resolve => setTimeout(resolve, 250))
  }
  throw new Error('Container did not become ready')
}
function get(url) {
  return new Promise((resolve, reject) => {
    const request = https.get(url, { rejectUnauthorized: false }, response => {
      let body = ''
      response.on('data', chunk => { body += chunk })
      response.on('end', () => resolve({ status: response.statusCode, headers: response.headers, body }))
    })
    request.setTimeout(2000, () => request.destroy(new Error('HTTPS timeout')))
    request.on('error', reject)
  })
}

// CREATE_PERMISSION checks the production ACL without sending traffic to the
// requested IP. Loopback relay tests alone miss rules that deny all public IPs.
async function checkTurnPermissions(port, username, credential) {
  const socket = dgram.createSocket('udp4')
  const cookie = 0x2112a442
  function attribute(type, value) {
    const bytes = Buffer.alloc(4 + Math.ceil(value.length / 4) * 4)
    bytes.writeUInt16BE(type); bytes.writeUInt16BE(value.length, 2); value.copy(bytes, 4)
    return bytes
  }
  async function request(type, attributes, key) {
    const transaction = randomBytes(12), body = Buffer.concat(attributes), header = Buffer.alloc(20)
    header.writeUInt16BE(type); header.writeUInt16BE(body.length + (key ? 24 : 0), 2)
    header.writeUInt32BE(cookie, 4); transaction.copy(header, 8)
    const unsigned = Buffer.concat([header, body])
    const packet = key ? Buffer.concat([unsigned, attribute(8, createHmac('sha1', key).update(unsigned).digest())]) : unsigned
    const response = await new Promise((resolve, reject) => {
      const finish = (error, bytes) => {
        clearTimeout(timer); socket.off('message', receive); socket.off('error', failed)
        if (error) reject(error); else resolve(bytes)
      }
      const failed = error => finish(error)
      const receive = (bytes, source) => {
        if (source.address === '127.0.0.1' && source.port === port && bytes.length >= 20 && bytes.subarray(8, 20).equals(transaction)) finish(null, bytes)
      }
      const timer = setTimeout(() => finish(new Error('TURN permission check timed out')), 5000)
      socket.on('message', receive); socket.on('error', failed)
      socket.send(packet, port, '127.0.0.1', error => { if (error) finish(error) })
    })
    const values = new Map()
    for (let offset = 20; offset + 4 <= response.length;) {
      const length = response.readUInt16BE(offset + 2)
      values.set(response.readUInt16BE(offset), response.subarray(offset + 4, offset + 4 + length))
      offset += 4 + Math.ceil(length / 4) * 4
    }
    return { type: response.readUInt16BE(0), values }
  }
  let auth, key, allocated = false
  try {
    const transport = attribute(0x19, Buffer.from([17, 0, 0, 0]))
    const challenge = await request(3, [transport])
    assert.equal(challenge.type, 0x113)
    const realm = challenge.values.get(0x14), nonce = challenge.values.get(0x15)
    assert(realm && nonce, 'TURN must challenge for long-term credentials')
    key = createHash('md5').update(Buffer.concat([Buffer.from(`${username}:`), realm, Buffer.from(`:${credential}`)])).digest()
    auth = [attribute(6, Buffer.from(username)), attribute(0x14, realm), attribute(0x15, nonce)]
    assert.equal((await request(3, [transport, ...auth], key)).type, 0x103)
    allocated = true
    for (const [ip, allowed] of [['8.8.8.8', true], ['1.1.1.1', true], ['10.1.2.3', false], ['172.16.1.2', false], ['192.168.1.2', false], ['169.254.169.254', false], ['127.0.0.2', false]]) {
      const address = Buffer.alloc(8); address[1] = 1
      address.writeUInt16BE(12345 ^ (cookie >>> 16), 2)
      Buffer.from(ip.split('.').map(Number)).copy(address, 4)
      address.writeUInt32BE((address.readUInt32BE(4) ^ cookie) >>> 0, 4)
      const result = await request(8, [attribute(0x12, address), ...auth], key)
      assert.equal(result.type, allowed ? 0x108 : 0x118, `TURN permission for ${ip}`)
      if (!allowed) {
        const error = result.values.get(9)
        assert(error && error[2] * 100 + error[3] === 403, `Expected forbidden peer ${ip}`)
      }
    }
    console.log('PASS: public IPv4 TURN permissions allowed; private/link-local/loopback peers blocked')
  } finally {
    try { if (allocated) await request(4, [attribute(0x0d, Buffer.alloc(4)), ...auth], key) }
    finally { socket.close() }
  }
}

try {
  const [signalPort, httpPort, httpsPort, healthPort, turnPort, tlsPort] = await Promise.all(Array.from({ length: 6 }, port))
  const certificates = join(temporary, 'certs')
  await mkdir(join(certificates, 'live/turn'), { recursive: true })
  await exec('openssl', ['req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '1', '-subj', '/CN=localhost',
    '-keyout', join(certificates, 'live/turn/privkey.pem'), '-out', join(certificates, 'live/turn/fullchain.pem')])
  const caddy = (await readFile(join(root, 'infra/deploy/Caddyfile'), 'utf8'))
    .replace('{\n', `{\n\thttp_port ${httpPort}\n\thttps_port ${httpsPort}\n\tlocal_certs\n\tskip_install_trust\n`)
    .replaceAll('bind 0.0.0.0', 'bind 127.0.0.1')
    .replaceAll('127.0.0.1:4444', `127.0.0.1:${signalPort}`)
    .replace('127.0.0.1:8081', `127.0.0.1:${healthPort}`)
  await writeFile(join(temporary, 'Caddyfile'), caddy)
  // Local-only exception in a temporary file, never in the production config.
  const turn = (await readFile(join(root, 'infra/deploy/turnserver.conf'), 'utf8'))
    .replace('listening-port=3478', `listening-port=${turnPort}`)
    .replace('log-file=/dev/null', 'log-file=stdout').replace('no-stdout-log', '')
    + '\nallow-loopback-peers\nallowed-peer-ip=127.0.0.1\n'
  await writeFile(join(temporary, 'turnserver.conf'), turn)

  await start('signaling', ['--read-only', '--tmpfs', '/tmp', '--cap-drop', 'ALL',
    '-e', `PORT=${signalPort}`, '-e', `MESHBOARD_TURN_SECRET=${secret}`, '-e', 'MESHBOARD_TRUST_PROXY=true',
    '-e', `MESHBOARD_TURN_URLS=turns:localhost:${tlsPort}?transport=tcp`,
    process.env.SIGNALING_IMAGE || 'meshboard-signaling:local'])
  await ready(async () => (await fetch(`http://127.0.0.1:${signalPort}/health`)).ok)

  await start('web', ['-e', 'APP_DOMAIN=localhost', '-e', 'TURN_DOMAIN=turn.localhost', '-e', 'ACME_EMAIL=test@example.com',
    '-e', 'WEB_BIND_IP=127.0.0.1', '-v', `${temporary}/Caddyfile:/etc/caddy/Caddyfile:ro`,
    process.env.WEB_IMAGE || 'meshboard-web:local'])
  const origin = `https://localhost:${httpsPort}`
  await ready(async () => (await get(`${origin}/health`)).status === 200)
  assert.match((await get(origin)).body, /Meshboard/)
  assert.equal((await get(`${origin}/meshboard-icon.svg`)).status, 200)
  const response = await get(`${origin}/api/rtc-config`)
  assert.equal(response.headers['cache-control'], 'no-store')
  const { username, credential } = JSON.parse(response.body).iceServers[0]
  assert(!response.body.includes(secret))
  assert.equal(credential, createHmac('sha1', secret).update(username).digest('base64'))
  await new Promise((resolve, reject) => {
    const socket = new WebSocket(`${origin.replace('https:', 'wss:')}/signal`, { rejectUnauthorized: false, origin })
    const timeout = setTimeout(() => { socket.terminate(); reject(new Error('WSS timeout')) }, 5000)
    socket.on('error', reject)
    socket.on('open', () => socket.send(JSON.stringify({ v: 1, type: 'join', room: '01234567-89ab-4def-8123-456789abcdef' })))
    socket.on('message', data => {
      try { assert.equal(JSON.parse(data).type, 'welcome'); resolve() } catch (error) { reject(error) }
      finally { clearTimeout(timeout); socket.close() }
    })
  })
  console.log('PASS: built web assets, HTTPS, proxy health, WSS, temporary credentials')

  await start('turn', ['--user', '0:0', '--read-only', '--tmpfs', '/tmp', '--cap-drop', 'ALL', '--cap-add', 'NET_BIND_SERVICE',
    '--entrypoint', '/bin/sh', '-e', `TURN_SECRET=${secret}`, '-e', 'TURN_DOMAIN=localhost',
    '-e', 'TURN_PUBLIC_IP=127.0.0.1', '-e', `TURN_TLS_PORT=${tlsPort}`, '-e', 'WEB_BIND_IP=127.0.0.1',
    '-e', 'TURN_MIN_PORT=55000', '-e', 'TURN_MAX_PORT=55099', '-e', 'TURN_TOTAL_QUOTA=100',
    '-e', 'TURN_MAX_BPS=1000000', '-e', 'TURN_BPS_CAPACITY=20000000',
    '-v', `${root}/infra/deploy/turn-start.sh:/etc/coturn/start.sh:ro`,
    '-v', `${temporary}/turnserver.conf:/etc/coturn/turnserver.conf:ro`,
    '-v', `${certificates}:/etc/letsencrypt:ro`, turnImage, '/etc/coturn/start.sh'])
  await ready(() => new Promise((resolve, reject) => {
    const socket = tls.connect({ host: '127.0.0.1', port: tlsPort, rejectUnauthorized: false }, () => { socket.end(); resolve(true) })
    socket.setTimeout(1000, () => socket.destroy(new Error('TURN TLS timeout')))
    socket.on('error', reject)
  }))
  await checkTurnPermissions(turnPort, username, credential)
  for (const [label, flags, selectedPort] of [['UDP', [], turnPort], ['TLS', ['-t', '-S'], tlsPort]]) {
    const result = await command(['run', '--rm', '--network', 'host', '--entrypoint', 'turnutils_uclient', turnImage,
      ...flags, '-y', '-c', '-n', '20', '-m', '1', '-u', username, '-w', credential, '-p', String(selectedPort), '127.0.0.1'])
    const totals = [...result.matchAll(/tot_send_msgs=(\d+), tot_recv_msgs=(\d+)/g)].at(-1)
    // The allocated peer leg is raw UDP even for a TLS client. This utility has
    // no SCTP retransmission like the app's reliable data channel. Require >=90%
    // delivery across the two directions; this is not a zero-loss benchmark.
    assert(totals && Number(totals[1]) >= 40 && Number(totals[2]) >= Number(totals[1]) * 0.9,
      `Bidirectional relay smoke test did not deliver enough packets:\n${result}`)
    console.log(`PASS: authenticated TURN ${label} allocation and bidirectional relay data (${totals[2]}/${totals[1]} packets)`)
  }
} catch (error) {
  for (const name of names) {
    try { console.error(name, (await command(['logs', '--tail', '15', name])).replaceAll(secret, '[test-secret]')) } catch { /* best effort */ }
  }
  throw error
} finally {
  for (const name of names.reverse()) {
    // Only containers with this test's unique names are removed.
    try { await command(['rm', '-f', '-v', name]) } catch { console.error(`Could not remove test container ${name}`) }
  }
  await rm(temporary, { recursive: true, force: true })
}
