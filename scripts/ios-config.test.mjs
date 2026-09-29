import { strictEqual, match } from 'node:assert'
import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import test from 'node:test'

const script = fileURLToPath(new URL('../packages/native/iosApp/build-framework.sh', import.meta.url))
const source = fileURLToPath(new URL('../packages/native/iosApp', import.meta.url))
for (const [configuration, platform] of [['Release', 'iphonesimulator'], ['Debug', 'iphoneos'], ['Release', 'iphoneos']]) {
  test(`Xcode refuses CRDT preview for ${configuration}/${platform} before invoking Gradle`, { skip: process.platform === 'win32' }, () => {
    const result = spawnSync('sh', [script], {
      encoding: 'utf8', timeout: 5000,
      env: { ...process.env, SRCROOT: source, CONFIGURATION: configuration, PLATFORM_NAME: platform,
        MESHBOARD_CRDT_PREVIEW: '1', OVERRIDE_KOTLIN_BUILD_IDE_SUPPORTED: 'NO' },
    })
    strictEqual(result.error, undefined)
    strictEqual(result.status, 1)
    match(result.stderr, /CRDT preview is Debug simulator-only/)
  })
}
