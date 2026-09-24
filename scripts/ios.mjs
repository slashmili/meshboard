import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import { join } from 'node:path'

const root = fileURLToPath(new URL('../', import.meta.url))
const action = process.argv[2] || 'run'
if (process.platform !== 'darwin' || !['run', 'build', 'device-build', 'interop', 'crdt-test', 'crdt-run', 'crdt-interop'].includes(action)) {
  console.error('Usage on macOS: node scripts/ios.mjs [run|build|device-build|interop|crdt-test|crdt-run|crdt-interop]')
  process.exit(1)
}
function run(command, args, capture = false) {
  const result = spawnSync(command, args, { cwd: root, encoding: 'utf8', stdio: capture ? 'pipe' : 'inherit' })
  if (result.error) throw result.error
  if (result.status !== 0) {
    if (capture) console.error(result.stderr)
    process.exit(result.status || 1)
  }
  return result.stdout
}
const deviceBuild = action === 'device-build'
const preview = action === 'crdt-run' || action === 'crdt-interop'
const interop = action === 'interop' || action === 'crdt-interop'
const launch = action === 'run' || action === 'crdt-run'
// An inherited preview flag must never silently change an ordinary build.
process.env.MESHBOARD_CRDT_PREVIEW = preview ? '1' : '0'
let simulator
if (!deviceBuild) {
  const { devices } = JSON.parse(run('xcrun', ['simctl', 'list', 'devices', 'available', '--json'], true))
  const available = Object.entries(devices)
    .sort(([a], [b]) => b.localeCompare(a, undefined, { numeric: true }))
    .flatMap(([, list]) => list)
  simulator = process.env.MESHBOARD_IOS_SIMULATOR
    ? available.find(device => device.udid === process.env.MESHBOARD_IOS_SIMULATOR)
    : available.find(device => device.name.startsWith('iPad') && device.state === 'Booted')
      || available.find(device => device.name.startsWith('iPad'))
  if (!simulator) throw new Error('Install an iPad simulator in Xcode, or set MESHBOARD_IOS_SIMULATOR to an available simulator UUID.')
  console.log(`Building for ${simulator.name} (${simulator.udid})`)
}
if (action === 'crdt-test') {
  // Standalone simulator executable: no app install, live transport, or signing changes.
  if (simulator.state !== 'Booted') run('xcrun', ['simctl', 'boot', simulator.udid])
  run('xcrun', ['simctl', 'bootstatus', simulator.udid, '-b'])
  run('./packages/native/gradlew', ['-p', 'packages/native', '-Pmeshboard.ios=true', '-Pmeshboard.crdtIos=true',
    `-Pmeshboard.iosTestDevice=${simulator.udid}`, 'iosSimulatorArm64Test',
    'linkCrdtCompatDebugExecutableIosSimulatorArm64', 'linkDebugFrameworkIosArm64'])
  process.env.MESHBOARD_IOS_SIMULATOR = simulator.udid
  process.env.MESHBOARD_CRDT_IOS_BINARY = join(root, 'packages/native/build/bin/iosSimulatorArm64/crdtCompatDebugExecutable/crdtCompat.kexe')
  run('node', ['--test', 'packages/crdt-core/compat.test.mjs'])
  console.log('iOS CRDT bindings checked; normal iOS sharing still uses the existing protocol.')
  process.exit(0)
}
const output = join(root, 'packages/native/build', deviceBuild ? 'ios-device' : preview ? 'ios-crdt-preview' : 'ios')
run('xcodebuild', ['-project', 'packages/native/iosApp/Meshboard.xcodeproj', '-scheme', 'Meshboard',
  '-configuration', 'Debug', '-destination', deviceBuild ? 'generic/platform=iOS' : `platform=iOS Simulator,id=${simulator.udid}`,
  '-clonedSourcePackagesDirPath', join(root, 'packages/native/build/SourcePackages'),
  '-derivedDataPath', output, 'build', 'CODE_SIGNING_ALLOWED=NO', `MESHBOARD_CRDT_PREVIEW=${preview ? '1' : '0'}`])
if (launch || interop) {
  if (simulator.state !== 'Booted') run('xcrun', ['simctl', 'boot', simulator.udid])
  run('xcrun', ['simctl', 'bootstatus', simulator.udid, '-b'])
  run('xcrun', ['simctl', 'install', simulator.udid, join(output, 'Build/Products/Debug-iphonesimulator/Meshboard.app')])
  if (launch) {
    run('open', ['-a', 'Simulator', '--args', '-CurrentDeviceUDID', simulator.udid])
    run('xcrun', ['simctl', 'launch', simulator.udid, 'dev.meshboard.ios'])
  }
}
if (deviceBuild) console.log('Unsigned device build only. To install on your iPad, select a signing team and Run in Xcode; see packages/native/iosApp/README.md.')

if (interop) {
  process.env.MESHBOARD_IOS_SIMULATOR = simulator.udid
  const mise = !process.env.CI && spawnSync('mise', ['--version'], { stdio: 'ignore' }).status === 0
  const invoke = (command, args) => mise
    ? run('mise', ['exec', 'java@temurin-21', 'node@22', 'pnpm@9', '--', command, ...args])
    : run(command, args)
  invoke('./packages/native/gradlew', ['-p', 'packages/native', ...(preview ? ['-Pmeshboard.crdtInterop=true', 'prepareCrdtInterop'] : []), 'prepareInterop'])
  invoke('pnpm', ['--filter', '@meshboard/web', 'exec', 'playwright', 'test', '-c', preview ? 'playwright.ios-crdt.config.ts' : 'playwright.ios.config.ts', ...process.argv.slice(3)])
}
