const UNITS = ['B', 'KB', 'MB', 'GB', 'TB']

export function formatBytes(n: number): string {
  if (!(n > 0)) return '0 B'
  const i = Math.min(UNITS.length - 1, Math.floor(Math.log(n) / Math.log(1000)))
  const v = n / 1000 ** i
  return `${v >= 100 || i === 0 ? v.toFixed(0) : v.toFixed(1)} ${UNITS[i]}`
}

export const formatSpeed = (bps: number): string => `${formatBytes(bps)}/s`

/** "46s left", "3m 05s left", "1h 12m left"; '' when it can't be told. */
export function formatEta(left: number, speed: number): string {
  if (!(left > 0) || !(speed > 1)) return ''
  const s = Math.ceil(left / speed)
  if (s < 60) return `${s}s left`
  if (s < 3600) return `${Math.floor(s / 60)}m ${String(s % 60).padStart(2, '0')}s left`
  return `${Math.floor(s / 3600)}h ${String(Math.floor((s % 3600) / 60)).padStart(2, '0')}m left`
}

/** Each value as a whole percent of their sum; all 0 when the sum is 0. */
export function shares(values: number[]): number[] {
  const sum = values.reduce((a, b) => a + b, 0)
  return values.map((v) => (sum > 0 ? Math.round((v / sum) * 100) : 0))
}

/** The "dn=" display name of a magnet link; '' if it has none. */
export function magnetName(magnet: string): string {
  const q = magnet.indexOf('?')
  if (q < 0) return ''
  for (const kv of magnet.slice(q + 1).split('&')) {
    if (kv.startsWith('dn=')) {
      try {
        return decodeURIComponent(kv.slice(3).replace(/\+/g, ' '))
      } catch {
        return ''
      }
    }
  }
  return ''
}
