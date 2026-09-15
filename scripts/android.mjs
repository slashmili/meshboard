import { existsSync, mkdirSync } from 'node:fs'
import { spawn, spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import { resolve, join } from 'node:path'

const root = fileURLToPath(new URL('../', import.meta.url))
const sdk = process.env.ANDROID_HOME || resolve(root, '.android-sdk')
const windows = process.platform === 'win32'
const mode = process.argv[2] ?? 'run'
const tasks = { build: [':androidApp:assembleDebug'], test: [':androidApp:connectedDebugAndroidTest'], run: [':androidApp:installDebug'], interop: [':androidApp:installDebug', ':androidApp:installDebugAndroidTest', 'prepareInterop'] }
if (mode !== 'emulator' && !Object.hasOwn(tasks, mode)) throw new Error('Usage: node scripts/android.mjs [emulator|build|test|run|interop]')
const env = { ...process.env, ANDROID_HOME: sdk }
function run(command, args) {
  const result = spawnSync(command, args, { cwd: root, env, stdio: 'inherit', shell: windows })
  if (result.error) throw result.error
  if (result.status !== 0) process.exit(result.status ?? 1)
}
const adb = join(sdk, 'platform-tools', windows ? 'adb.exe' : 'adb')
const serial = process.env.ANDROID_SERIAL || 'emulator-5554'

function startEmulator() {
  const avdHome = process.env.ANDROID_AVD_HOME || join(root, '.android-avd')
  const name = 'meshboard-tablet-api35'
  const emulatorEnv = { ...env, ANDROID_AVD_HOME: avdHome }
  const emulator = join(sdk, 'emulator', windows ? 'emulator.exe' : 'emulator')
  const manager = join(sdk, 'cmdline-tools', 'latest', 'bin', windows ? 'avdmanager.bat' : 'avdmanager')
  if (!existsSync(emulator) || !existsSync(manager) || !existsSync(join(sdk, 'system-images/android-35/default/x86_64/system.img'))) {
    throw new Error('Install the Android emulator, command-line tools, and system-images;android-35;default;x86_64. See packages/native/androidApp/README.md.')
  }
  const devices = spawnSync(adb, ['devices'], { env: emulatorEnv, encoding: 'utf8', timeout: 10_000 })
  if (devices.error) throw devices.error
  if (devices.status !== 0) throw new Error(devices.stderr || 'Could not check Android devices.')
  const running = devices.stdout.split('\n').filter(line => /^emulator-\d+\s/.test(line))
  if (running.length) {
    const current = spawnSync(adb, ['-s', 'emulator-5554', 'emu', 'avd', 'name'], { env: emulatorEnv, encoding: 'utf8', timeout: 10_000 })
    if (running.length === 1 && current.status === 0 && current.stdout.split(/\r?\n/)[0] === name) {
      console.log('Meshboard tablet is already running. Use pnpm android to open the app.')
      return
    }
    throw new Error('Close the other Android emulator before starting the Meshboard tablet. Its in-memory drawing will be lost unless another peer keeps a copy.')
  }
  if (!existsSync(join(avdHome, `${name}.ini`))) {
    mkdirSync(avdHome, { recursive: true })
    const created = spawnSync(manager, ['create', 'avd', '--name', name, '--package', 'system-images;android-35;default;x86_64', '--device', 'pixel_tablet', '--path', join(avdHome, `${name}.avd`)], {
      env: emulatorEnv, input: 'no\n', stdio: ['pipe', 'inherit', 'inherit'], shell: windows,
    })
    if (created.error) throw created.error
    if (created.status !== 0) process.exit(created.status ?? 1)
  }
  console.log('Starting the Meshboard Pixel Tablet. Once Android finishes booting, run pnpm android in another terminal.')
  const child = spawn(emulator, ['-avd', name, '-port', '5554', '-memory', '4096', '-cores', '4', '-no-snapshot', '-no-audio', '-gpu', 'software', '-camera-back', 'none', '-camera-front', 'none', '-no-boot-anim'], { env: emulatorEnv, stdio: 'inherit' })
  child.on('error', error => { console.error(error.message); process.exitCode = 1 })
  child.on('exit', code => { process.exitCode = code ?? 0 })
  for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => child.kill(signal))
}

if (mode === 'emulator') {
  startEmulator()
} else {
  if (!existsSync(join(sdk, 'platforms/android-35/android.jar'))) throw new Error('Install Android SDK platform 35 and set ANDROID_HOME. See packages/native/androidApp/README.md.')
  if (mode !== 'build') {
    const devices = spawnSync(adb, ['devices'], { encoding: 'utf8', env })
    const online = (devices.stdout ?? '').split('\n').filter(line => /\tdevice\s*$/.test(line)).map(line => line.split('\t')[0])
    if (!online.includes(serial)) throw new Error(`Start the emulator first with pnpm emulator:android, or set ANDROID_SERIAL to an online device. Expected ${serial}.`)
    // Gradle's connected tests run on all devices; require an unambiguous test target.
    if (online.length !== 1) throw new Error('Keep only the intended test device connected for this command.')
  }
  run(join(root, 'packages/native', windows ? 'gradlew.bat' : 'gradlew'), ['-p', 'packages/native', '-Pmeshboard.android=true', ...tasks[mode]])
  if (mode === 'run') run(adb, ['-s', serial, 'shell', 'am', 'start', '-n', 'dev.meshboard.android/.MainActivity'])
  if (mode === 'interop') run('pnpm', ['--filter', '@meshboard/web', 'exec', 'playwright', 'test', '-c', 'playwright.android.config.ts', ...process.argv.slice(3)])
}
