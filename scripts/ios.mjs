import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import { join } from 'node:path'

const root = fileURLToPath(new URL('../', import.meta.url))
const action = process.argv[2] || 'run'
if (process.platform !== 'darwin' || !['run', 'build', 'device-build'].includes(action)) {
  console.error('Usage on macOS: node scripts/ios.mjs [run|build|device-build]')
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
const output = join(root, 'packages/native/build', deviceBuild ? 'ios-device' : 'ios')
run('xcodebuild', ['-project', 'packages/native/iosApp/Meshboard.xcodeproj', '-scheme', 'Meshboard',
  '-configuration', 'Debug', '-destination', deviceBuild ? 'generic/platform=iOS' : `platform=iOS Simulator,id=${simulator.udid}`,
  '-derivedDataPath', output, 'build', 'CODE_SIGNING_ALLOWED=NO'])
if (action === 'run') {
  if (simulator.state !== 'Booted') run('xcrun', ['simctl', 'boot', simulator.udid])
  run('xcrun', ['simctl', 'bootstatus', simulator.udid, '-b'])
  run('xcrun', ['simctl', 'install', simulator.udid, join(output, 'Build/Products/Debug-iphonesimulator/Meshboard.app')])
  run('open', ['-a', 'Simulator', '--args', '-CurrentDeviceUDID', simulator.udid])
  run('xcrun', ['simctl', 'launch', simulator.udid, 'dev.meshboard.ios'])
}
if (deviceBuild) console.log('Unsigned device build only. To install on your iPad, select a signing team and Run in Xcode; see packages/native/iosApp/README.md.')
