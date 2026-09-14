import { spawn, spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'

const config = fileURLToPath(new URL('../infra/turn/dev.conf', import.meta.url))
const requested = process.env.CONTAINER_RUNTIME
const runtime = requested || ['podman', 'docker'].find(name => spawnSync(name, ['--version'], { stdio: 'ignore' }).status === 0)
if (!runtime) {
  console.error('Install Podman or Docker to run the local TURN relay. See README.md for an external TURN configuration.')
  process.exit(1)
}
console.log('Starting a loopback-only TURN relay on 127.0.0.1:3478. Keep this terminal open.')
const child = spawn(runtime, ['run', '--rm', '--network=host', '--read-only', '--tmpfs=/tmp',
  '--name=meshboard-turn-dev', '-v', `${config}:/etc/coturn/turnserver.conf:ro,Z`,
  'docker.io/coturn/coturn:4.6.3', '-c', '/etc/coturn/turnserver.conf'], { stdio: 'inherit' })
child.on('error', error => { console.error(error.message); process.exitCode = 1 })
child.on('exit', code => { process.exitCode = code ?? 1 })
for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => child.kill(signal))
