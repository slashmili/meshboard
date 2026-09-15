import { test } from 'node:test'
import assert from 'node:assert/strict'
import { releaseConfiguration } from './release-config.mjs'

test('release configuration remains reusable for other deployments', () => {
  assert.deepEqual(releaseConfiguration('v1.2.3-rc.1', 'https://board.example.com/', 'turn.example.com'), {
    tag: 'v1.2.3-rc.1', version: '1.2.3', appOrigin: 'https://board.example.com', turnDomain: 'turn.example.com',
  })
})
test('missing or unsafe release settings fail closed', () => {
  for (const tag of ['', 'main', '../v1.0.0', 'v01.0.0', 'v1.0.0;echo bad', 'v1.0.0\n']) {
    assert.throws(() => releaseConfiguration(tag, 'https://board.example.com', 'turn.example.com'))
  }
  for (const origin of ['', 'http://board.example.com', 'https://user:pass@board.example.com', 'https://board.example.com/path', 'https://board.example.com/#secret', 'https://board.example.com/?key=secret']) {
    assert.throws(() => releaseConfiguration('v1.0.0', origin, 'turn.example.com'))
  }
  for (const domain of ['', 'turns:turn.example.com', 'turn.example.com:5349', 'turn.example.com\nother', 'turn.example.com\n', '-turn.example.com']) {
    assert.throws(() => releaseConfiguration('v1.0.0', 'https://board.example.com', domain))
  }
})
