import { WebPlugin } from '@capacitor/core'
import type { Dl, EngineApi, Net, NetId, Probe } from './engine'

const NETS: Net[] = [
  { id: 'wifi', name: 'Wi-Fi', online: true },
  { id: 'cellular', name: 'Mobile data', online: true }
]
const RATE: Record<string, number> = { wifi: 6e6, cellular: 2e6 }
const BLOCKS = 120

/** Browser stand-in for the native engines: fake downloads at a fixed speed per network. */
export class MockEngine extends WebPlugin implements Pick<EngineApi, 'networks' | 'probe' | 'start' | 'pause' | 'resume' | 'remove' | 'setNetwork' | 'setLimit' | 'list' | 'openFile'> {
  private dls = new Map<string, Dl>()
  private limits: Partial<Record<NetId, number>> = {}

  constructor() {
    super()
    setInterval(() => this.tick(0.5), 500)
  }

  private tick(dt: number): void {
    for (const d of this.dls.values()) {
      if (d.status !== 'downloading') continue
      d.speed = 0
      for (const n of d.nets) {
        const cap = this.limits[n.id] || Infinity
        n.speed = n.enabled ? Math.min(RATE[n.id] ?? 1e6, cap) : 0
        n.bytes += n.speed * dt
        d.speed += n.speed
      }
      d.done = Math.min(d.total, d.done + d.speed * dt)
      const filled = Math.floor((d.done / d.total) * BLOCKS)
      const own = d.nets.map((n) => n.bytes)
      const top = own.indexOf(Math.max(...own))
      d.cells = String(top).repeat(filled) + (filled < BLOCKS ? '*' : '') + '.'.repeat(Math.max(0, BLOCKS - filled - 1))
      if (d.done >= d.total) {
        d.status = 'completed'
        d.speed = 0
        d.finishedAt = Date.now()
        d.nets.forEach((n) => (n.speed = 0))
      }
      this.notifyListeners('update', { ...d })
    }
  }

  async networks(): Promise<{ networks: Net[] }> {
    return { networks: NETS }
  }
  async probe({ url }: { url: string }): Promise<Probe> {
    const fileName = decodeURIComponent(url.split('?')[0].split('/').pop() || 'download')
    return { finalUrl: url, fileName, total: 300_000_000, ranges: true }
  }
  async start(o: { url: string; fileName?: string; networks: NetId[] }): Promise<{ id: string }> {
    const id = Math.random().toString(36).slice(2)
    const d: Dl = {
      id, url: o.url, fileName: o.fileName || 'download.bin', status: 'downloading', total: 300_000_000, done: 0, speed: 0,
      ranges: true, error: null, cells: '.'.repeat(BLOCKS), createdAt: Date.now(), finishedAt: 0,
      nets: NETS.map((n) => ({ id: n.id, enabled: o.networks.includes(n.id), state: o.networks.includes(n.id) ? 'on' : 'off', bytes: 0, speed: 0 }))
    }
    this.dls.set(id, d)
    this.notifyListeners('update', { ...d })
    return { id }
  }
  async pause({ id }: { id: string }): Promise<void> {
    const d = this.dls.get(id)
    if (d) { d.status = 'paused'; d.speed = 0; d.nets.forEach((n) => (n.speed = 0)); this.notifyListeners('update', { ...d }) }
  }
  async resume({ id }: { id: string }): Promise<void> {
    const d = this.dls.get(id)
    if (d) { d.status = 'downloading'; this.notifyListeners('update', { ...d }) }
  }
  async remove({ id }: { id: string }): Promise<void> {
    this.dls.delete(id)
    this.notifyListeners('removed', { id })
  }
  async setNetwork({ id, network, enabled }: { id: string; network: NetId; enabled: boolean }): Promise<void> {
    const n = this.dls.get(id)?.nets.find((x) => x.id === network)
    if (n) { n.enabled = enabled; n.state = enabled ? 'on' : 'off' }
  }
  async setLimit({ network, bytesPerSec }: { network: NetId; bytesPerSec: number }): Promise<void> {
    this.limits[network] = bytesPerSec
  }
  async list(): Promise<{ downloads: Dl[]; limits: Partial<Record<NetId, number>> }> {
    return { downloads: [...this.dls.values()], limits: this.limits }
  }
  async openFile(): Promise<void> {}
}
