import { useEffect, useState } from 'react'
import { ClipboardPaste, FolderOpen, Pause, Play, Plus, RotateCw, Settings2, Trash2, ExternalLink } from 'lucide-react'
import pkg from '../package.json'
import { Engine, type Dl, type NetId, type Probe, type TorrentProbe } from './engine'
import { formatBytes, formatEta, formatSpeed, shares, magnetName } from './format'
import { chooseFolder, init, netName, refreshNetworks, rename, setLimit, setTheme, speedHistory, useDefaultFolder, useStore } from './store'
import type { Theme } from './theme'
import { BlockGrid, Chart, NetIcon, ProgressBar, ShareBar, Sheet, Switch, tone } from './components'

type Tab = 'active' | 'done'
type SheetState = null | 'add' | 'settings' | { id: string }

const pct = (d: Dl): number => (d.total > 0 ? (d.done / d.total) * 100 : 0)
const msg = (e: unknown): string => (e instanceof Error ? e.message : String(e))

export default function App(): React.JSX.Element {
  const s = useStore()
  const [tab, setTab] = useState<Tab>('active')
  const [sheet, setSheet] = useState<SheetState>(null)
  useEffect(() => void init(), [])

  const active = s.dls.filter((d) => d.status !== 'completed')
  const done = s.dls.filter((d) => d.status === 'completed')
  const shown = tab === 'active' ? active : done
  const speedOf = (id: NetId): number => active.reduce((a, d) => a + (d.nets.find((n) => n.id === id)?.speed ?? 0), 0)
  const total = active.reduce((a, d) => a + d.speed, 0)
  const heroNets = s.nets.filter((n) => n.online || n.id !== 'ethernet')

  return (
    <div className="flex h-full flex-col">
      {/* The panel every PlusNet screen opens on: per-network speeds and their sum. */}
      <header className="border-b border-b-[var(--hero-border)] bg-[image:var(--hero-bg)] px-5 pb-4 pt-[calc(env(safe-area-inset-top)+14px)]">
        <div className="mb-3 flex items-center justify-between">
          <div className="flex items-center gap-2">
            <img src="/icon.png" alt="" className="h-7 w-7 rounded-lg" />
            <h1 className="text-lg font-extrabold tracking-tight">PlusNet</h1>
          </div>
          <button aria-label="Settings" onClick={() => setSheet('settings')} className="p-2 text-muted-foreground">
            <Settings2 size={20} />
          </button>
        </div>
        <div className="flex gap-5">
          {heroNets.map((n) => (
            <div key={n.id} className="min-w-0">
              <div className="flex items-center gap-1 text-[10px] font-bold uppercase tracking-wider text-muted-foreground">
                <NetIcon id={n.id} size={11} />
                <span className="truncate">{netName(s, n.id)}</span>
              </div>
              <div className="font-mono text-sm" style={{ color: n.online ? tone(n.id).text : 'var(--text-tertiary)' }}>
                {n.online ? formatSpeed(speedOf(n.id)) : 'offline'}
              </div>
            </div>
          ))}
          <div className="ml-auto text-right">
            <div className="text-[10px] font-bold uppercase tracking-wider text-muted-foreground">Total speed</div>
            <div className="font-mono text-xl font-semibold leading-tight">{formatSpeed(total)}</div>
          </div>
        </div>
      </header>

      <nav className="flex gap-1 border-b border-border px-4 pt-2" role="tablist">
        {(['active', 'done'] as const).map((t) => (
          <button
            key={t}
            role="tab"
            aria-selected={tab === t}
            onClick={() => setTab(t)}
            className={`border-b-2 px-3 py-2 text-sm font-semibold ${tab === t ? 'border-primary text-foreground' : 'border-transparent text-muted-foreground'}`}
          >
            {t === 'active' ? `Downloads${active.length ? ` · ${active.length}` : ''}` : `Complete${done.length ? ` · ${done.length}` : ''}`}
          </button>
        ))}
      </nav>

      <main className="flex-1 space-y-2.5 overflow-y-auto px-4 py-3 pb-28">
        {s.ready && shown.length === 0 && (
          <p className="mt-16 text-center text-sm text-muted-foreground">
            {tab === 'active' ? 'No downloads yet. Tap + and paste a link.' : 'Finished downloads show up here.'}
          </p>
        )}
        {shown.map((d) => (
          <Card key={d.id} d={d} onOpen={() => setSheet({ id: d.id })} />
        ))}
      </main>

      <button
        aria-label="New download"
        onClick={() => setSheet('add')}
        className="fixed right-5 bottom-[calc(env(safe-area-inset-bottom)+20px)] flex h-14 w-14 items-center justify-center rounded-2xl bg-primary text-primary-foreground shadow-lg"
      >
        <Plus size={26} />
      </button>

      {sheet === 'add' && <AddSheet onClose={() => setSheet(null)} />}
      {sheet === 'settings' && <SettingsSheet onClose={() => setSheet(null)} />}
      {sheet && typeof sheet === 'object' && <DetailSheet id={sheet.id} onClose={() => setSheet(null)} />}
    </div>
  )
}

function statusLine(d: Dl): string {
  const size = d.total > 0 ? formatBytes(d.total) : 'unknown size'
  switch (d.status) {
    case 'queued': return 'Queued'
    case 'paused': return `Paused · ${formatBytes(d.done)} of ${size}`
    case 'error': return d.error ?? 'Failed'
    case 'completed': return `${size} · ${new Date(d.finishedAt).toLocaleDateString()}`
    default:
      if (d.note) return d.note // looking for a torrent, saving its files
      return [`${formatBytes(d.done)} of ${size}`, d.total > 0 ? `${Math.floor(pct(d))}%` : '', formatEta(d.total - d.done, d.speed), d.kind === 'torrent' ? `${d.peers ?? 0} peers` : ''].filter(Boolean).join(' · ')
  }
}

function Card({ d, onOpen }: { d: Dl; onOpen: () => void }): React.JSX.Element {
  const s = useStore()
  const running = d.status === 'downloading'
  const toggle = (): void => void (running || d.status === 'queued' ? Engine.pause({ id: d.id }) : Engine.resume({ id: d.id }))
  return (
    <div className="rounded-xl border border-border bg-card p-3.5">
      <div className="flex items-start gap-3">
        <button onClick={onOpen} className="min-w-0 flex-1 text-left">
          <div className="truncate text-[15px] font-semibold">{d.fileName}</div>
          <div className={`mt-0.5 truncate text-xs ${d.status === 'error' ? 'text-destructive' : 'text-muted-foreground'}`}>{statusLine(d)}</div>
        </button>
        {d.status !== 'completed' && (
          <button aria-label={running ? 'Pause' : d.status === 'error' ? 'Retry' : 'Resume'} onClick={toggle} className="rounded-full bg-secondary p-2.5">
            {running ? <Pause size={16} /> : d.status === 'error' ? <RotateCw size={16} /> : <Play size={16} />}
          </button>
        )}
      </div>
      {d.status !== 'completed' && (
        <div className="mt-2.5 space-y-1.5">
          <ProgressBar pct={pct(d)} />
          {running && (
            <div className="flex items-center gap-3 text-[11px]">
              {d.nets.filter((n) => n.enabled).map((n) => (
                <span key={n.id} className="flex items-center gap-1 font-mono" style={{ color: tone(n.id).text }}>
                  <NetIcon id={n.id} size={11} />
                  {formatSpeed(n.speed)}
                </span>
              ))}
              <span className="ml-auto font-mono font-semibold">{formatSpeed(d.speed)}</span>
            </div>
          )}
        </div>
      )}
      {d.status === 'completed' && d.nets.some((n) => n.bytes > 0) && (
        <div className="mt-2.5 space-y-1.5">
          <ShareBar parts={d.nets.map((n) => ({ id: n.id, value: n.bytes }))} />
          <div className="flex gap-3 text-[11px] text-muted-foreground">
            {d.nets.filter((n) => n.bytes > 0).map((n, _i, all) => (
              <span key={n.id}>{netName(s, n.id)} {shares(all.map((x) => x.bytes))[all.indexOf(n)]}%</span>
            ))}
          </div>
        </div>
      )}
    </div>
  )
}

function AddSheet({ onClose }: { onClose: () => void }): React.JSX.Element {
  const s = useStore()
  useEffect(() => { void refreshNetworks() }, [])
  const [url, setUrl] = useState('')
  const [info, setInfo] = useState<Probe | null>(null)
  const [tor, setTor] = useState<TorrentProbe | null>(null) // a torrent or magnet link that has been checked
  const [name, setName] = useState('')
  const [err, setErr] = useState('')
  const [busy, setBusy] = useState(false)
  const [off, setOff] = useState<NetId[]>([]) // networks switched off for this download
  const online = s.nets.filter((n) => n.online)
  // Wi-Fi and mobile data always show. Everything not switched off goes into the download, connected yet or not:
  // mobile data often needs a second or two to wake up while on Wi-Fi, and a network that connects later joins by itself.
  const rows = s.nets.filter((n) => n.online || n.id !== 'ethernet')
  const picked = rows.filter((n) => !off.includes(n.id))

  async function check(): Promise<void> {
    const u = url.trim()
    if (/^magnet:/i.test(u) || /\.torrent(\?|#|$)/i.test(u)) {
      if (!s.settings.torrents) return setErr("Torrents and magnet links aren't available on this device yet.")
      setErr('')
      if (/^magnet:/i.test(u)) { // the size is only known once it has been found on the network
        setTor({ name: magnetName(u) || 'Magnet link', total: -1, files: 0 })
        return
      }
      setBusy(true)
      try {
        setTor(await Engine.probeTorrent({ url: u }))
      } catch (e) {
        setTor(null)
        setErr(msg(e))
      } finally {
        setBusy(false)
      }
      return
    }
    if (!/^https?:\/\//i.test(u)) return setErr('Enter a link that starts with http://, https:// or magnet:')
    setBusy(true)
    setErr('')
    try {
      const p = await Engine.probe({ url: u })
      setInfo(p)
      setName(p.fileName)
    } catch (e) {
      setInfo(null)
      setErr(msg(e))
    } finally {
      setBusy(false)
    }
  }

  async function startTorrent(): Promise<void> {
    if (!tor) return
    try {
      await Engine.startTorrent({ source: url.trim(), fileName: tor.name })
      onClose()
    } catch (e) {
      setErr(msg(e))
    }
  }

  async function start(): Promise<void> {
    if (!info) return
    const u = url.trim()
    try {
      // Keep the original URL. Every selected Android Network must follow its own redirect chain;
      // a resolved URL obtained over Wi-Fi can be a temporary/IP-bound CDN URL that cellular rejects.
      await Engine.start({ url: u, fileName: name.trim() || info.fileName, networks: picked.map((n) => n.id) })
      onClose()
    } catch (e) {
      setErr(msg(e))
    }
  }

  return (
    <Sheet title="New download" onClose={onClose}>
      <div className="flex gap-2">
        <input
          value={url}
          onChange={(e) => { setUrl(e.target.value); setInfo(null); setTor(null) }}
          placeholder="https://example.com/file.zip"
          inputMode="url"
          autoCapitalize="none"
          autoCorrect="off"
          spellCheck={false}
          className="min-w-0 flex-1 rounded-lg border border-input bg-[var(--input-bg)] px-3 py-2.5 text-sm"
        />
        <button
          aria-label="Paste"
          onClick={() => void navigator.clipboard.readText().then((t) => { setUrl(t.trim()); setInfo(null); setTor(null) }).catch(() => setErr('Allow clipboard access, or paste into the field.'))}
          className="rounded-lg bg-secondary px-3"
        >
          <ClipboardPaste size={18} />
        </button>
      </div>
      {err && <p role="alert" className="mt-2 text-sm text-destructive">{err}</p>}

      {!info && !tor && (
        <button disabled={busy || !url.trim()} onClick={() => void check()} className="mt-4 w-full rounded-xl bg-primary py-3 font-bold text-primary-foreground disabled:opacity-40">
          {busy ? 'Checking…' : 'Check link'}
        </button>
      )}

      {tor && (
        <div className="mt-4 space-y-4">
          <div>
            <div className="text-[11px] font-bold uppercase tracking-wider text-muted-foreground">Torrent</div>
            <p className="mt-1 break-words text-sm font-semibold">{tor.name}</p>
            <p className="mt-1 text-xs text-muted-foreground">
              {[tor.total > 0 ? formatBytes(tor.total) : '', tor.files > 1 ? `${tor.files} files` : '', tor.total < 0 ? 'The size shows once it is found on the network' : ''].filter(Boolean).join(' · ')}
            </p>
          </div>
          <p className="text-xs leading-relaxed text-muted-foreground">
            Torrents download over your phone's main connection (Wi-Fi when connected), so Wi-Fi and mobile data are not combined. Files are saved to {s.settings.folderLabel}, and sharing stops when the download finishes.
          </p>
          <button onClick={() => void startTorrent()} className="w-full rounded-xl bg-primary py-3 font-bold text-primary-foreground">
            Start download
          </button>
        </div>
      )}

      {info && (
        <div className="mt-4 space-y-4">
          <div>
            <label className="text-[11px] font-bold uppercase tracking-wider text-muted-foreground" htmlFor="fname">File name</label>
            <input id="fname" value={name} onChange={(e) => setName(e.target.value)} className="mt-1 w-full rounded-lg border border-input bg-[var(--input-bg)] px-3 py-2.5 text-sm" />
            <p className="mt-1 text-xs text-muted-foreground">
              {info.total > 0 ? formatBytes(info.total) : 'Unknown size'} ·{' '}
              {info.ranges ? 'Can be split across networks' : 'This server allows one connection, so only one network is used'}
            </p>
          </div>
          <div>
            <div className="mb-1 text-[11px] font-bold uppercase tracking-wider text-muted-foreground">Download over</div>
            {rows.map((n) => (
              <div key={n.id} className="flex items-center gap-3 py-2">
                <NetIcon id={n.id} size={18} />
                <div className="flex-1">
                  <div className="text-sm font-medium">{netName(s, n.id)}</div>
                  {!n.online && <div className="text-xs text-muted-foreground">Not connected yet. It joins the download as soon as it connects.</div>}
                </div>
                <Switch on={!off.includes(n.id)} label={netName(s, n.id)} color={tone(n.id).solid} onChange={(v) => setOff(v ? off.filter((x) => x !== n.id) : [...off, n.id])} />
              </div>
            ))}
            {online.length < 2 && (
              <p className="text-xs text-muted-foreground">Only one network is connected, so there's nothing to combine yet. Turn on mobile data while on Wi-Fi to add speed.</p>
            )}
          </div>
          <button disabled={picked.length === 0} onClick={() => void start()} className="w-full rounded-xl bg-primary py-3 font-bold text-primary-foreground disabled:opacity-40">
            Start download
          </button>
        </div>
      )}
    </Sheet>
  )
}

function DetailSheet({ id, onClose }: { id: string; onClose: () => void }): React.JSX.Element | null {
  const s = useStore()
  const d = s.dls.find((x) => x.id === id)
  const [confirming, setConfirming] = useState(false)
  const [deleteFile, setDeleteFile] = useState(false)
  const [openErr, setOpenErr] = useState('') // e.g. which folder a multi-file torrent is in
  useEffect(() => { if (!d) onClose() }, [d, onClose])
  if (!d) return null

  const finished = d.status === 'completed'
  const running = d.status === 'downloading'
  const parts = d.nets.map((n) => ({ id: n.id, value: finished ? n.bytes : n.speed }))
  const share = shares(parts.map((p) => p.value))

  return (
    <Sheet title={d.fileName} onClose={onClose}>
      <p className={`text-sm ${d.status === 'error' ? 'text-destructive' : 'text-muted-foreground'}`}>{statusLine(d)}</p>
      {openErr && <p role="alert" className="mt-2 text-xs text-destructive">{openErr}</p>}
      {!finished && <div className="mt-3"><ProgressBar pct={pct(d)} /></div>}
      {!d.ranges && d.status !== 'queued' && <p className="mt-2 text-xs text-muted-foreground">This server doesn't allow splitting, so one network does all the work.</p>}

      {d.kind === 'torrent' ? (
        <p className="mt-5 text-xs leading-relaxed text-muted-foreground">
          Torrent. It downloads over your phone's main connection (Wi-Fi when connected), so Wi-Fi and mobile data are not combined. Sharing stops when it finishes.
        </p>
      ) : (
        <>
        <div className="mt-5 text-[11px] font-bold uppercase tracking-wider text-muted-foreground">Networks</div>
        <div className="mt-1 divide-y divide-[var(--border-subtle)]">
          {d.nets.map((n, i) => (
            <div key={n.id} className="flex items-center gap-3 py-2.5">
              <NetIcon id={n.id} size={18} />
              <div className="min-w-0 flex-1">
                <div className="text-sm font-medium">{netName(s, n.id)}</div>
                <div className="font-mono text-xs text-muted-foreground">
                  {n.state === 'on' ? `${running ? formatSpeed(n.speed) + ' · ' : ''}${formatBytes(n.bytes)}` : n.state === 'offline' ? 'Not connected' : n.state === 'failed' ? (n.error || 'Network request failed') : 'Off'}
                </div>
              </div>
              <span className="w-10 text-right font-mono text-xs" style={{ color: tone(n.id).text }}>{share[i]}%</span>
              {!finished && (
                <Switch on={n.enabled} label={netName(s, n.id)} color={tone(n.id).solid} onChange={(v) => void Engine.setNetwork({ id: d.id, network: n.id, enabled: v })} />
              )}
            </div>
          ))}
        </div>
        <div className="mt-2"><ShareBar parts={parts} /></div>
        </>
      )}

      {!finished && d.cells && (
        <>
          <div className="mt-5 mb-2 text-[11px] font-bold uppercase tracking-wider text-muted-foreground">File blocks</div>
          <BlockGrid cells={d.cells} />
        </>
      )}
      {running && (
        <div className="mt-5">
          <Chart samples={speedHistory(d.id)} ids={d.nets.map((n) => n.id)} />
        </div>
      )}

      {!confirming ? (
        <div className="mt-6 flex gap-2">
          {finished ? (
            <button onClick={() => { setOpenErr(''); void Engine.openFile({ id: d.id }).catch((e) => setOpenErr(msg(e))) }} className="flex flex-1 items-center justify-center gap-2 rounded-xl bg-primary py-3 font-bold text-primary-foreground">
              <ExternalLink size={18} /> Open
            </button>
          ) : (
            <button onClick={() => void (running || d.status === 'queued' ? Engine.pause({ id: d.id }) : Engine.resume({ id: d.id }))} className="flex flex-1 items-center justify-center gap-2 rounded-xl bg-primary py-3 font-bold text-primary-foreground">
              {running || d.status === 'queued' ? <><Pause size={18} /> Pause</> : <><Play size={18} /> {d.status === 'error' ? 'Retry' : 'Resume'}</>}
            </button>
          )}
          <button aria-label="Remove" onClick={() => setConfirming(true)} className="rounded-xl bg-secondary px-4 text-destructive">
            <Trash2 size={18} />
          </button>
        </div>
      ) : (
        <div className="mt-6 rounded-xl border border-border bg-card p-4">
          <p className="text-sm font-semibold">{finished ? 'Remove from the list?' : 'Cancel and remove this download?'}</p>
          {finished ? (
            <label className="mt-3 flex items-center gap-2 text-sm">
              <input type="checkbox" checked={deleteFile} onChange={(e) => setDeleteFile(e.target.checked)} /> Also delete the file
            </label>
          ) : (
            <p className="mt-1 text-xs text-muted-foreground">What has been downloaded so far is deleted.</p>
          )}
          <div className="mt-4 flex gap-2">
            <button onClick={() => setConfirming(false)} className="flex-1 rounded-lg bg-secondary py-2.5 text-sm font-semibold">Keep</button>
            <button onClick={() => void Engine.remove({ id: d.id, deleteFile: finished ? deleteFile : true })} className="flex-1 rounded-lg bg-destructive py-2.5 text-sm font-semibold text-[var(--bg)]">Remove</button>
          </div>
        </div>
      )}
    </Sheet>
  )
}

const LIMITS = [0, 1, 2, 5, 10, 25]

const THEMES: { id: Theme; label: string }[] = [
  { id: 'system', label: 'System' },
  { id: 'light', label: 'Light' },
  { id: 'dark', label: 'Dark' }
]

const HEADING = 'mb-2 text-[11px] font-bold uppercase tracking-wider text-muted-foreground'

function SettingsSheet({ onClose }: { onClose: () => void }): React.JSX.Element {
  const s = useStore()
  const [folderErr, setFolderErr] = useState('')
  return (
    <Sheet title="Settings" onClose={onClose}>
      <div className="mb-6">
        <div className={HEADING}>Appearance</div>
        <div className="flex gap-1.5">
          {THEMES.map((t) => (
            <button
              key={t.id}
              aria-pressed={s.theme === t.id}
              onClick={() => setTheme(t.id)}
              className={`flex-1 rounded-full px-3 py-2 text-sm font-semibold ${s.theme === t.id ? 'bg-primary text-primary-foreground' : 'bg-secondary'}`}
            >
              {t.label}
            </button>
          ))}
        </div>
      </div>

      {s.settings.canPickFolder && (
        <div className="mb-6">
          <div className={HEADING}>Download location</div>
          <div className="flex items-center gap-3 rounded-xl bg-secondary px-3.5 py-3">
            <FolderOpen size={18} className="shrink-0 text-muted-foreground" />
            <div className="min-w-0 flex-1">
              <div className="truncate text-sm font-semibold">{s.settings.folderLabel}</div>
              <div className="text-xs text-muted-foreground">{s.settings.customFolder ? 'A folder you chose' : 'Default (Downloads folder)'}</div>
            </div>
          </div>
          <div className="mt-2 flex gap-2">
            <button
              onClick={() => { setFolderErr(''); void chooseFolder().catch((e) => setFolderErr(msg(e))) }}
              className="flex-1 rounded-lg bg-primary py-2.5 text-sm font-semibold text-primary-foreground"
            >
              Choose folder
            </button>
            {s.settings.customFolder && (
              <button onClick={() => { setFolderErr(''); void useDefaultFolder().catch((e) => setFolderErr(msg(e))) }} className="rounded-lg bg-secondary px-4 text-sm font-semibold">
                Use default
              </button>
            )}
          </div>
          <p className="mt-2 text-xs leading-relaxed text-muted-foreground">New downloads are saved here. Downloads that are already finished stay where they are.</p>
          {folderErr && <p role="alert" className="mt-1 text-xs text-destructive">{folderErr}</p>}
        </div>
      )}

      <div className={HEADING}>Networks</div>
      <div className="space-y-5">
        {s.nets.filter((n) => n.online || n.id !== 'ethernet').map((n) => (
          <div key={n.id}>
            <div className="flex items-center gap-2">
              <NetIcon id={n.id} size={18} />
              <input
                aria-label={`Name for ${n.name}`}
                defaultValue={netName(s, n.id)}
                onBlur={(e) => rename(n.id, e.target.value === n.name ? '' : e.target.value)}
                className="min-w-0 flex-1 bg-transparent text-sm font-semibold"
              />
              <span className="text-xs" style={{ color: n.online ? tone(n.id).text : 'var(--text-tertiary)' }}>{n.online ? 'Connected' : 'Not connected'}</span>
            </div>
            <div className="mt-2 flex flex-wrap gap-1.5">
              {LIMITS.map((mb) => {
                const on = Math.round((s.limits[n.id] ?? 0) / 1e6) === mb
                return (
                  <button
                    key={mb}
                    aria-pressed={on}
                    onClick={() => setLimit(n.id, mb * 1e6)}
                    className={`rounded-full px-3 py-1.5 text-xs font-semibold ${on ? 'bg-primary text-primary-foreground' : 'bg-secondary'}`}
                  >
                    {mb === 0 ? 'No limit' : `${mb} MB/s`}
                  </button>
                )
              })}
            </div>
          </div>
        ))}
      </div>
      <p className="mt-6 text-xs leading-relaxed text-muted-foreground">
        A speed cap applies to everything downloaded over that network. A second network only adds speed when it reaches the internet by its own route, as Wi-Fi and mobile data do.
      </p>
      <p className="mt-4 text-center font-mono text-[11px] text-[var(--text-tertiary)]">PlusNet v{pkg.version}</p>
    </Sheet>
  )
}
