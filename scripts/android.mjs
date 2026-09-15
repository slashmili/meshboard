import { existsSync } from 'node:fs'
import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import { resolve, join } from 'node:path'

const root = fileURLToPath(new URL('../', import.meta.url))
const sdk = process.env.ANDROID_HOME || resolve(root, '.android-sdk')
const windows = process.platform === 'win32'
const mode = process.argv[2] ?? 'run'
const tasks = { build: ':androidApp:assembleDebug', test: ':androidApp:connectedDebugAndroidTest', run: ':androidApp:installDebug' }
if (!(mode in tasks)) throw new Error('Usage: node scripts/android.mjs [build|test|run]')
if (!existsSync(join(sdk, 'platforms/android-35/android.jar'))) throw new Error('Install Android SDK platform 35 and set ANDROID_HOME. See packages/native/androidApp/README.md.')
const env = { ...process.env, ANDROID_HOME: sdk }
function run(command, args) {
  const result = spawnSync(command, args, { cwd: root, env, stdio: 'inherit', shell: windows })
  if (result.error) throw result.error
  if (result.status !== 0) process.exit(result.status ?? 1)
}
const adb = join(sdk, 'platform-tools', windows ? 'adb.exe' : 'adb')
const serial = process.env.ANDROID_SERIAL || 'emulator-5554'
if (mode !== 'build') {
  const devices = spawnSync(adb, ['devices'], { encoding: 'utf8', env })
  const online = (devices.stdout ?? '').split('\n').filter(line => /\tdevice\s*$/.test(line)).map(line => line.split('\t')[0])
  if (!online.includes(serial)) throw new Error(`Start the emulator first, or set ANDROID_SERIAL to an online device. Expected ${serial}.`)
  // Gradle's connected tests run on all devices; require an unambiguous test target.
  if (online.length !== 1) throw new Error('Keep only the intended test device connected for this command.')
}
run(join(root, 'packages/native', windows ? 'gradlew.bat' : 'gradlew'), ['-p', 'packages/native', '-Pmeshboard.android=true', tasks[mode]])
if (mode === 'run') run(adb, ['-s', serial, 'shell', 'am', 'start', '-n', 'dev.meshboard.android/.MainActivity'])
