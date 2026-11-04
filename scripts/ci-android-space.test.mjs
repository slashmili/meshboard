import { strictEqual } from 'node:assert'
import { spawnSync } from 'node:child_process'
import { existsSync, mkdtempSync, mkdirSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import test from 'node:test'

test('CI cleanup removes unused NDKs and preserves the pinned NDK and other SDK tools', () => {
  const sdk = mkdtempSync(join(tmpdir(), 'meshboard sdk '))
  try {
    for (const path of ['ndk/27.0.1', 'ndk/29.0.1', 'ndk/28.2.13676358', 'emulator', 'platform-tools', 'system-images']) {
      mkdirSync(join(sdk, path), { recursive: true })
      writeFileSync(join(sdk, path, 'keep'), 'fixture')
    }
    for (let run = 0; run < 2; run++) {
      const result = spawnSync('bash', ['scripts/ci-android-space.sh'], {
        env: { ...process.env, ANDROID_HOME: sdk }, encoding: 'utf8',
      })
      strictEqual(result.status, 0, result.stderr)
      strictEqual(existsSync(join(sdk, 'ndk/27.0.1')), false)
      strictEqual(existsSync(join(sdk, 'ndk/29.0.1')), false)
      for (const path of ['ndk/28.2.13676358', 'emulator', 'platform-tools', 'system-images']) {
        strictEqual(existsSync(join(sdk, path, 'keep')), true)
      }
    }
  } finally { rmSync(sdk, { recursive: true, force: true }) }
})

test('CI cleanup handles an SDK without NDKs and rejects an unset SDK path', () => {
  const sdk = mkdtempSync(join(tmpdir(), 'meshboard-sdk-'))
  try {
    const result = spawnSync('bash', ['scripts/ci-android-space.sh'], {
      env: { ...process.env, ANDROID_HOME: sdk }, encoding: 'utf8',
    })
    strictEqual(result.status, 0, result.stderr)
    const missing = spawnSync('bash', ['scripts/ci-android-space.sh'], {
      env: { ...process.env, ANDROID_HOME: '' }, encoding: 'utf8',
    })
    strictEqual(missing.status, 1)
  } finally { rmSync(sdk, { recursive: true, force: true }) }
})
