import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'
import vm from 'node:vm'
import test from 'node:test'

const scriptUrl = new URL('./ios.mjs', import.meta.url)
// Exercise the CLI on Linux with only platform commands replaced; no Apple checks run.
const source = readFileSync(scriptUrl, 'utf8')
  .replace(/^import .*\n/gm, '')
  .replaceAll('import.meta.url', JSON.stringify(scriptUrl.href))

function trace(action, selected, failCommand) {
  const calls = []
  const exit = {}
  const env = { CI: 'true', ...(selected ? { MESHBOARD_IOS_SIMULATOR: selected } : {}) }
  const devices = [
    { name: 'iPad default', udid: 'default-ipad', state: 'Booted' },
    { name: 'iPad selected', udid: 'chosen-ipad', state: 'Shutdown' },
  ]
  let status
  const process = { platform: 'darwin', argv: ['node', scriptUrl.pathname, action], env,
    exit(code) { status = code; throw exit } }
  try {
    vm.runInNewContext(source, { URL, join, fileURLToPath, process, console: { log() {}, error() {} },
      spawnSync(command, args, options) {
        calls.push({ command, args: Array.from(args), options })
        return { status: command === failCommand ? 7 : 0, stderr: '',
          stdout: command === 'xcrun' && args.includes('--json')
            ? JSON.stringify({ devices: { 'iOS-new': devices } }) : '' }
      },
    })
    assert.fail('Test modes must exit before app building')
  } catch (error) { if (error !== exit) throw error }
  return { calls, env, status }
}

for (const action of ['test', 'crdt-test']) for (const selected of [undefined, 'chosen-ipad']) {
  test(`${action} keeps its own stage on ${selected ?? 'default iPad'}`, () => {
    const { calls, env, status } = trace(action, selected)
    const device = selected ?? 'default-ipad'
    assert.equal(status, 0)
    assert.ok(calls.some(c => c.command === 'xcrun' && c.args.join(' ') === `simctl bootstatus ${device} -b`))
    assert.equal(calls.some(c => c.args.join(' ') === `simctl boot ${device}`), !!selected)
    const gradle = calls.filter(c => c.command === './packages/native/gradlew')
    assert.equal(gradle.length, 1)
    const args = gradle[0].args
    assert.equal(args.includes('iosSimulatorArm64Test'), action === 'test')
    assert.equal(args.includes(`-Pmeshboard.iosTestDevice=${device}`), action === 'test')
    for (const task of ['linkCrdtCompatDebugExecutableIosSimulatorArm64', 'linkDebugFrameworkIosArm64']) {
      assert.equal(args.includes(task), action === 'crdt-test')
    }
    assert.equal(calls.some(c => c.command === 'node' && c.args.join(' ') === '--test packages/crdt-core/compat.test.mjs'), action === 'crdt-test')
    assert.equal(calls.some(c => c.command === 'xcodebuild'), false)
    const warmup = calls.find(c => c.command === 'xcrun' && c.args[1] === 'spawn')
    assert.equal(!!warmup, action === 'crdt-test')
    if (warmup) {
      assert.equal(env.MESHBOARD_IOS_SIMULATOR, device)
      assert.equal(warmup.args[2], device)
      assert.equal(warmup.args[3], env.MESHBOARD_CRDT_IOS_BINARY)
      assert.equal(warmup.options.timeout, 180_000)
    }
  })
}

test('failed Kotlin tests and compatibility linking stop their stages', () => {
  for (const action of ['test', 'crdt-test']) {
    const { calls, status } = trace(action, undefined, './packages/native/gradlew')
    assert.equal(status, 7)
    assert.equal(calls.some(c => c.command === 'node' || c.command === 'xcodebuild'), false)
    assert.equal(calls.some(c => c.command === 'xcrun' && c.args[1] === 'spawn'), false)
  }
})

test('CI explicitly invokes each iOS stage once through its package command', () => {
  const pkg = JSON.parse(readFileSync(new URL('../package.json', import.meta.url)))
  assert.equal(pkg.scripts['test:ios'], 'node scripts/ios.mjs test')
  assert.equal(pkg.scripts['test:crdt:ios'], 'node scripts/ios.mjs crdt-test')
  const ci = readFileSync(new URL('./ci-tests.sh', import.meta.url), 'utf8').split('  ios)')[1].split(';;')[0]
  assert.deepEqual(Array.from(ci.matchAll(/^    (?:bash|pnpm) ([^|\n]+?)(?: \|.*)?$/gm), m => m[1]), [
    'scripts/test-apple-crypto.sh', 'test:ios', 'test:crdt:ios', 'test:interop:ios', 'build:ios:device',
  ])
  assert.doesNotMatch(ci, /gradlew|iosSimulatorArm64Test/)
})
