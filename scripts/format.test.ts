import assert from 'node:assert/strict'
import { formatBytes, formatEta, formatSpeed, magnetName, shares } from '../src/format.ts'

assert.equal(formatBytes(0), '0 B')
assert.equal(formatBytes(999), '999 B')
assert.equal(formatBytes(34_800_000), '34.8 MB')
assert.equal(formatBytes(2_300_000_000), '2.3 GB')
assert.equal(formatBytes(250_000_000), '250 MB')
assert.equal(formatSpeed(46_200_000), '46.2 MB/s')
assert.equal(formatEta(100, 0), '')
assert.equal(formatEta(46 * 1000, 1000), '46s left')
assert.equal(formatEta(185 * 1000, 1000), '3m 05s left')
assert.equal(formatEta(4320 * 1000, 1000), '1h 12m left')
assert.deepEqual(shares([34.8, 11.4]), [75, 25])
assert.deepEqual(shares([0, 0]), [0, 0])
assert.equal(magnetName('magnet:?xt=urn:btih:abc&dn=Other+Mommy+2026&tr=udp%3A%2F%2Fx'), 'Other Mommy 2026')
assert.equal(magnetName('magnet:?dn=A%26B'), 'A&B')
assert.equal(magnetName('magnet:?xt=urn:btih:abc'), '')
assert.equal(magnetName('magnet:?dn=%E0%A4%A'), '') // broken escape: no name rather than an error
console.log('format ok')
