import assert from 'node:assert/strict'

// a minimal stand-in for the browser pieces theme.ts touches
const store = new Map<string, string>()
const attrs = new Map<string, string>()
;(globalThis as any).localStorage = {
  getItem: (k: string) => store.get(k) ?? null,
  setItem: (k: string, v: string) => void store.set(k, v),
  removeItem: (k: string) => void store.delete(k)
}
;(globalThis as any).document = {
  documentElement: {
    setAttribute: (k: string, v: string) => void attrs.set(k, v),
    removeAttribute: (k: string) => void attrs.delete(k)
  }
}
const { applyTheme, getTheme, saveTheme } = await import('../src/theme.ts')

assert.equal(getTheme(), 'system') // nothing saved: follow the phone
saveTheme('dark'); applyTheme('dark')
assert.equal(getTheme(), 'dark'); assert.equal(attrs.get('data-theme'), 'dark')
saveTheme('light'); applyTheme('light')
assert.equal(getTheme(), 'light'); assert.equal(attrs.get('data-theme'), 'light')
saveTheme('system'); applyTheme('system')
assert.equal(getTheme(), 'system'); assert.equal(attrs.has('data-theme'), false); assert.equal(store.has('plusnet.theme'), false)
store.set('plusnet.theme', 'purple') // junk in storage must not break startup
assert.equal(getTheme(), 'system')
console.log('theme ok')
