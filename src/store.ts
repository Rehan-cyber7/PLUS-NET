import { useSyncExternalStore } from 'react'
import { Engine, type Dl, type Net, type NetId } from './engine'

export interface Sample {
  t: number
  total: number
  byNet: Partial<Record<NetId, number>>
}

interface State {
  dls: Dl[] // newest first
  nets: Net[]
  limits: Partial<Record<NetId, number>>
  names: Partial<Record<NetId, string>> // the user's own names for networks
  ready: boolean
}

const NAMES_KEY = 'plusnet.names'
const HISTORY_SECONDS = 60

function loadNames(): State['names'] {
  try {
    return JSON.parse(localStorage.getItem(NAMES_KEY) || '{}')
  } catch {
    return {}
  }
}

let state: State = { dls: [], nets: [], limits: {}, names: loadNames(), ready: false }
const subs = new Set<() => void>()
const samples = new Map<string, Sample[]>()

function set(patch: Partial<State>): void {
  state = { ...state, ...patch } // a new object each time: useSyncExternalStore compares by identity
  subs.forEach((f) => f())
}

const byNewest = (a: Dl, b: Dl): number => b.createdAt - a.createdAt

function upsert(d: Dl): void {
  if (d.status === 'downloading') {
    const list = samples.get(d.id) ?? []
    list.push({ t: Date.now(), total: d.speed, byNet: Object.fromEntries(d.nets.map((n) => [n.id, n.speed])) })
    samples.set(d.id, list.slice(-HISTORY_SECONDS * 2))
  }
  set({ dls: [d, ...state.dls.filter((x) => x.id !== d.id)].sort(byNewest) })
}

export const useStore = (): State =>
  useSyncExternalStore(
    (f) => (subs.add(f), () => subs.delete(f)),
    () => state
  )

export const speedHistory = (id: string): Sample[] => samples.get(id) ?? []

export const netName = (s: State, id: NetId): string =>
  s.names[id] || s.nets.find((n) => n.id === id)?.name || id

export function rename(id: NetId, name: string): void {
  const names = { ...state.names, [id]: name.trim() || undefined }
  localStorage.setItem(NAMES_KEY, JSON.stringify(names))
  set({ names })
}

export function setLimit(network: NetId, bytesPerSec: number): void {
  set({ limits: { ...state.limits, [network]: bytesPerSec } })
  void Engine.setLimit({ network, bytesPerSec })
}

let initPromise: Promise<void> | null = null

export function init(): Promise<void> {
  if (initPromise) return initPromise
  initPromise = (async () => {
    // Subscribe before taking the first snapshot. Native Android network callbacks can arrive
    // while the initial networks() call is in flight; subscribing afterwards can miss Wi-Fi.
    await Promise.all([
      Engine.addListener('update', upsert),
      Engine.addListener('removed', ({ id }) => {
        samples.delete(id)
        set({ dls: state.dls.filter((d) => d.id !== id) })
      }),
      Engine.addListener('networks', ({ networks }) => set({ nets: networks }))
    ])

    const [n, l] = await Promise.all([Engine.networks(), Engine.list()])
    set({ nets: n.networks, dls: l.downloads.sort(byNewest), limits: l.limits, ready: true })
  })().catch((error: unknown) => {
    initPromise = null
    throw error
  })
  return initPromise
}

/** Refresh the native network snapshot when the user opens the download sheet. */
export async function refreshNetworks(): Promise<void> {
  const n = await Engine.networks()
  set({ nets: n.networks })
}
