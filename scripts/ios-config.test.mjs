import { strictEqual, match, doesNotMatch } from 'node:assert'
import { spawnSync } from 'node:child_process'
import { mkdtempSync, mkdirSync, writeFileSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'
import test from 'node:test'

const script = fileURLToPath(new URL('../packages/native/iosApp/build-framework.sh', import.meta.url))
for (const configuration of ['Debug', 'Release']) for (const platform of ['iphonesimulator', 'iphoneos']) {
  test(`Xcode builds CRDT for ${configuration}/${platform}, with test hooks only in debug simulator`, { skip: process.platform === 'win32' }, () => {
    const root = mkdtempSync(join(tmpdir(), 'meshboard-xcode-'))
    try {
      mkdirSync(join(root, 'iosApp'))
      writeFileSync(join(root, 'gradlew'), '#!/bin/sh\nprintf "%s\\n" "$@"\n', { mode: 0o755 })
      const result = spawnSync('sh', [script], {
        encoding: 'utf8', timeout: 5000,
        env: { ...process.env, CI: 'true', SRCROOT: join(root, 'iosApp'), CONFIGURATION: configuration,
          PLATFORM_NAME: platform, OVERRIDE_KOTLIN_BUILD_IDE_SUPPORTED: 'NO' },
      })
      strictEqual(result.error, undefined)
      strictEqual(result.status, 0, result.stderr)
      match(result.stdout, /-Pmeshboard.ios=true/)
      match(result.stdout, new RegExp(`-Pmeshboard.iosInterop=${configuration === 'Debug' && platform === 'iphonesimulator'}`))
      match(result.stdout, /embedAndSignAppleFrameworkForXcode/)
      doesNotMatch(result.stdout, /crdtIos=false|crdtIosPreview/)
    } finally { rmSync(root, { recursive: true, force: true }) }
  })
}
