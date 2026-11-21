import { registerPlugin, type PluginListenerHandle } from '@capacitor/core'

export type NetId = 'wifi' | 'cellular' | 'ethernet'
export type Status = 'queued' | 'downloading' | 'paused' | 'completed' | 'error'

export interface Net {
  id: NetId
  name: string
  online: boolean
}

/** One network as a single download sees it. `state` is what came of `enabled`. */
export interface DlNet {
  id: NetId
  enabled: boolean
  state: 'on' | 'off' | 'offline' | 'failed'
  bytes: number
  speed: number
  error?: string | null
}

export interface Dl {
  id: string
  url: string
  fileName: string
  status: Status
  /** -1 when the server did not say. */
  total: number
  done: number
  speed: number
  ranges: boolean
  error: string | null
  nets: DlNet[]
  /** One char per block of the file: '.' waiting, '*' downloading, '0' Wi-Fi, '1' mobile data, '2' Ethernet: done by that network. */
  cells: string
  createdAt: number
  finishedAt: number
}

export interface Probe {
  finalUrl: string
  fileName: string
  total: number
  ranges: boolean
}

export interface EngineApi {
  networks(): Promise<{ networks: Net[] }>
  probe(o: { url: string }): Promise<Probe>
  start(o: { url: string; fileName?: string; networks: NetId[] }): Promise<{ id: string }>
  pause(o: { id: string }): Promise<void>
  resume(o: { id: string }): Promise<void>
  remove(o: { id: string; deleteFile: boolean }): Promise<void>
  setNetwork(o: { id: string; network: NetId; enabled: boolean }): Promise<void>
  /** 0 removes the cap. Applies to every download on that network. */
  setLimit(o: { network: NetId; bytesPerSec: number }): Promise<void>
  list(): Promise<{ downloads: Dl[]; limits: Partial<Record<NetId, number>> }>
  openFile(o: { id: string }): Promise<void>
  addListener(e: 'update', f: (d: Dl) => void): Promise<PluginListenerHandle>
  addListener(e: 'removed', f: (d: { id: string }) => void): Promise<PluginListenerHandle>
  addListener(e: 'networks', f: (d: { networks: Net[] }) => void): Promise<PluginListenerHandle>
}

// In a browser (npm run dev) the engine is a simulation, so the UI can be worked on without a phone.
export const Engine = registerPlugin<EngineApi>('PlusNetEngine', {
  web: () => import('./mock').then((m) => new m.MockEngine())
})
