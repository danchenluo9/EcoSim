import { useState, useEffect, useCallback, useRef } from 'react'
import WorldGrid from './components/WorldGrid.jsx'
import NPCPanel from './components/NPCPanel.jsx'
import ControlBar from './components/ControlBar.jsx'
import SetupScreen from './components/SetupScreen.jsx'
import TickScrubber from './components/TickScrubber.jsx'

const POLL_INTERVAL_MS      = 500
const MAX_HISTORY           = 300   // max tick snapshots kept in memory
const CACHE_KEY             = 'ecosim-cache'
const MAX_CACHE_SNAPSHOTS   = 60    // snapshots persisted to localStorage
const CACHE_SAVE_INTERVAL   = 10    // save every N new ticks

// ── Dialog strip ──────────────────────────────────────────────────
function DialogStrip({ dialogs, displayNames }) {
  if (!dialogs || dialogs.length === 0) return null
  const name  = id => displayNames?.[id] ?? id
  const vColor = v  => v > 0.2 ? '#4ade80' : v < -0.2 ? '#f87171' : '#94a3b8'
  return (
    <div className="dialog-strip">
      <span className="dialog-strip-title">Dialogs</span>
      <div className="dialog-strip-list">
        {dialogs.map((c, i) => (
          <div key={i} className="dialog-card">
            <div className="dialog-card-header">
              <span className="dialog-card-pair">{name(c.speakerId)} → {name(c.listenerId)}</span>
              <span className="dialog-card-valence" style={{ color: vColor(c.valence) }}>
                {c.valence > 0 ? '+' : ''}{c.valence}
              </span>
            </div>
            <div className="dialog-card-line"><b>{name(c.speakerId)}:</b> "{c.speakerLine}"</div>
            <div className="dialog-card-line"><b>{name(c.listenerId)}:</b> "{c.listenerLine}"</div>
          </div>
        ))}
      </div>
    </div>
  )
}

// ── App ───────────────────────────────────────────────────────────
export default function App() {
  const [state, setState]           = useState(null)
  const [selectedNPC, setSelectedNPC] = useState(null)
  const [error, setError]           = useState(null)
  const fetchingRef    = useRef(false)
  const prevStateRef   = useRef(null)   // for reset detection (set by useEffect)
  const prevDataRef    = useRef(null)   // for dialog/conflict diffing
  const lastSavedTick  = useRef(-Infinity)
  const pollIntervalRef = useRef(null)

  // ── Tick history ──────────────────────────────────────────────
  const historyRef       = useRef(new Map())  // tick → raw state snapshot
  const tickDialogsRef   = useRef(new Map())  // tick → dialog[]
  const tickConflictsRef = useRef(new Set())  // set of ticks where conflicts occurred
  const [historyTicks, setHistoryTicks] = useState([])  // sorted tick numbers
  const [viewedTick, setViewedTick]     = useState(null) // null = live mode

  // ── Display names / photos ────────────────────────────────────
  const [displayNames, setDisplayNames] = useState(() => {
    try { return JSON.parse(localStorage.getItem('npc-display-names') ?? '{}') }
    catch { return {} }
  })
  const [photos, setPhotos] = useState(() => {
    try { return JSON.parse(localStorage.getItem('npc-photos') ?? '{}') }
    catch { return {} }
  })

  const updateDisplayName = (npcId, name) => {
    const next = { ...displayNames, [npcId]: name }
    setDisplayNames(next)
    localStorage.setItem('npc-display-names', JSON.stringify(next))
  }

  const resizePhoto = (dataUrl) => new Promise((resolve, reject) => {
    const img = new Image()
    img.onload = () => {
      const MAX = 64
      const scale = Math.min(MAX / img.width, MAX / img.height, 1)
      const canvas = document.createElement('canvas')
      canvas.width  = Math.round(img.width  * scale)
      canvas.height = Math.round(img.height * scale)
      canvas.getContext('2d').drawImage(img, 0, 0, canvas.width, canvas.height)
      resolve(canvas.toDataURL('image/jpeg', 0.8))
    }
    img.onerror = () => reject(new Error('Failed to load image'))
    img.src = dataUrl
  })

  const updatePhoto = async (npcId, dataUrl) => {
    try {
      const resized = await resizePhoto(dataUrl)
      const next = { ...photos, [npcId]: resized }
      setPhotos(next)
      localStorage.setItem('npc-photos', JSON.stringify(next))
    } catch (e) {
      console.warn('Failed to load photo:', e.message)
    }
  }

  // ── Cache ─────────────────────────────────────────────────────
  // useCallback with [] is safe here: only reads refs, never state
  const saveToStorage = useCallback(() => {
    const map = historyRef.current
    if (map.size === 0) return
    const allTicks  = Array.from(map.keys()).sort((a, b) => a - b)
    const saveTicks = allTicks.slice(-MAX_CACHE_SNAPSHOTS)
    try {
      localStorage.setItem(CACHE_KEY, JSON.stringify({
        history:   saveTicks.map(t => [t, map.get(t)]),
        dialogs:   [...tickDialogsRef.current.entries()],
        conflicts: [...tickConflictsRef.current],
        savedAt:   Date.now(),
      }))
    } catch (e) {
      console.warn('Cache save failed:', e.message)
    }
  }, [])

  // Load persisted cache once on mount, before first poll arrives
  useEffect(() => {
    try {
      const raw = localStorage.getItem(CACHE_KEY)
      if (!raw) return
      const cache = JSON.parse(raw)
      for (const [tick, snap]    of cache.history   ?? []) historyRef.current.set(tick, snap)
      for (const [tick, dialogs] of cache.dialogs   ?? []) tickDialogsRef.current.set(tick, dialogs)
      for (const tick            of cache.conflicts ?? []) tickConflictsRef.current.add(tick)
      const ticks = Array.from(historyRef.current.keys()).sort((a, b) => a - b)
      if (ticks.length > 0) {
        setHistoryTicks(ticks)
        // Seed the diff baseline so the first live poll only picks up genuinely new events
        prevDataRef.current = historyRef.current.get(ticks.at(-1)) ?? null
      }
    } catch (e) {
      console.warn('Cache load failed:', e.message)
      localStorage.removeItem(CACHE_KEY)
    }
  }, [])  // eslint-disable-line react-hooks/exhaustive-deps

  // Save on tab close / navigation so the very last tick is always persisted
  useEffect(() => {
    window.addEventListener('beforeunload', saveToStorage)
    return () => window.removeEventListener('beforeunload', saveToStorage)
  }, [saveToStorage])

  // ── Polling ───────────────────────────────────────────────────
  const fetchState = useCallback(async () => {
    if (fetchingRef.current) return
    fetchingRef.current = true
    try {
      const res = await fetch('/api/state')
      if (!res.ok) throw new Error(`HTTP ${res.status}`)
      const data = await res.json()

      // Detect health drops to flash attacked NPCs
      setState(prev => {
        if (!prev) return data
        const npcs = data.npcs.map(npc => {
          const old = prev.npcs.find(n => n.id === npc.id)
          return old && npc.health < old.health ? { ...npc, _attacked: true } : npc
        })
        return { ...data, npcs }
      })
      setError(null)
      setSelectedNPC(prev =>
        prev ? data.npcs.find(n => n.id === prev.id) ?? null : null
      )

      // ── Store snapshot in history ─────────────────────────────
      const map = historyRef.current
      map.set(data.tick, data)
      if (map.size > MAX_HISTORY) {
        map.delete(Math.min(...map.keys()))
      }
      setHistoryTicks(prev => {
        const ticks = Array.from(map.keys()).sort((a, b) => a - b)
        // Skip re-render if nothing changed
        if (ticks.length === prev.length && ticks.at(-1) === prev.at(-1)) return prev
        return ticks
      })

      // ── Detect newly arrived dialogs via diffing ──────────────
      // Build a key set from the previous raw poll
      const prevConvKeys = new Set()
      for (const npc of prevDataRef.current?.npcs ?? []) {
        for (const c of npc.conversations ?? []) {
          prevConvKeys.add(convKey(c))
        }
      }
      // Any conversation not in the previous poll is "new"
      const newByTick = new Map() // tick → Map<key, conv>
      for (const npc of data.npcs) {
        for (const c of npc.conversations ?? []) {
          const k = convKey(c)
          if (!prevConvKeys.has(k)) {
            if (!newByTick.has(c.tick)) newByTick.set(c.tick, new Map())
            newByTick.get(c.tick).set(k, c)
          }
        }
      }
      for (const [tick, byKey] of newByTick) {
        const existing = tickDialogsRef.current.get(tick) ?? []
        tickDialogsRef.current.set(tick, [...existing, ...byKey.values()])
      }

      // ── Detect newly arrived conflict events via diffing ──────
      const CONFLICT_TYPES = new Set(['WAS_ATTACKED', 'WAS_ROBBED', 'ATTACKED_NPC', 'STOLE_FOOD'])
      const prevEventKeys = new Set()
      for (const npc of prevDataRef.current?.npcs ?? []) {
        for (const e of npc.recentEvents ?? []) {
          if (CONFLICT_TYPES.has(e.type)) {
            prevEventKeys.add(`${npc.id}@${e.tick}@${e.type}`)
          }
        }
      }
      for (const npc of data.npcs) {
        for (const e of npc.recentEvents ?? []) {
          if (CONFLICT_TYPES.has(e.type)) {
            const k = `${npc.id}@${e.tick}@${e.type}`
            if (!prevEventKeys.has(k)) {
              tickConflictsRef.current.add(e.tick)
            }
          }
        }
      }

      // Periodic cache save (and always save when simulation stops)
      if (data.tick - lastSavedTick.current >= CACHE_SAVE_INTERVAL || !data.running) {
        lastSavedTick.current = data.tick
        setTimeout(saveToStorage, 0)
      }

      // Stop polling once the simulation finishes (running=false, tick>0 rules out setup state)
      if (!data.running && data.tick > 0) {
        clearInterval(pollIntervalRef.current)
        pollIntervalRef.current = null
      }

      prevDataRef.current = data
    } catch (e) {
      setState(null)
      setError('Cannot reach simulation server. Is it running?')
    } finally {
      fetchingRef.current = false
    }
  }, [])

  useEffect(() => {
    fetchState()
    pollIntervalRef.current = setInterval(fetchState, POLL_INTERVAL_MS)
    return () => clearInterval(pollIntervalRef.current)
  }, [fetchState])

  // Clear history on simulation reset (new run started)
  useEffect(() => {
    if (prevStateRef.current === null && state && state.tick === 0 && !state.running) {
      setPhotos({})
      setDisplayNames({})
      localStorage.removeItem('npc-photos')
      localStorage.removeItem('npc-display-names')
      historyRef.current.clear()
      tickDialogsRef.current.clear()
      tickConflictsRef.current.clear()
      prevDataRef.current = null
      lastSavedTick.current = -Infinity
      localStorage.removeItem(CACHE_KEY)
      setHistoryTicks([])
      setViewedTick(null)
    }
    prevStateRef.current = state
  }, [state])

  // ── Control actions ───────────────────────────────────────────
  const sendControl = async (action) => {
    try {
      const res = await fetch('/api/control', { method: 'POST', body: action })
      if (!res.ok) {
        const body = await res.json().catch(() => ({}))
        setError(body.error ?? `Control request failed (HTTP ${res.status})`)
      } else {
        setError(null)
      }
    } catch (e) {
      setError('Cannot reach simulation server.')
    }
    fetchState()
  }

  const updateArchetype = async (npcId, archetype) => {
    try {
      const res = await fetch('/api/npc', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ id: npcId, archetype }),
      })
      if (!res.ok) {
        const body = await res.json().catch(() => ({}))
        setError(body.error ?? `Archetype update failed (HTTP ${res.status})`)
        return
      }
      setError(null)
    } catch (e) {
      setError('Cannot reach simulation server.')
      return
    }
    fetchState()
  }

  // ── Restart ───────────────────────────────────────────────────
  const handleRestart = async () => {
    try { await fetch('/api/control', { method: 'POST', body: 'stop' }) } catch {}
    localStorage.removeItem(CACHE_KEY)
    window.location.reload()
  }

  // ── Scrubber ──────────────────────────────────────────────────
  const isLive = viewedTick === null

  // Derive marker sets from refs on each render (refs update with history)
  const dialogTicks   = new Set(
    [...tickDialogsRef.current.entries()].filter(([, v]) => v.length > 0).map(([k]) => k)
  )
  const conflictTicks = new Set(tickConflictsRef.current)

  // ── Derived display data ──────────────────────────────────────
  const displayedState   = isLive ? state : (historyRef.current.get(viewedTick) ?? state)
  const displayedTick    = displayedState?.tick ?? null
  const displayedDialogs = displayedTick !== null ? (tickDialogsRef.current.get(displayedTick) ?? []) : []

  // When scrubbing, show the selected NPC's data from the historical snapshot
  const displayedNPC = selectedNPC
    ? (displayedState?.npcs.find(n => n.id === selectedNPC.id) ?? selectedNPC)
    : null

  const isSetup = state && !state.running && state.tick === 0
  const showScrubber = state && !isSetup && historyTicks.length > 1

  return (
    <div className="app">
      <header className="app-header">
        <span className="app-title">EcoSim</span>
        {state && !isSetup && (
          <ControlBar
            tick={state.tick}
            viewedTick={viewedTick}
            running={state.running}
            paused={state.paused}
            onPause={() => sendControl('pause')}
            onResume={() => sendControl('resume')}
            onStop={() => sendControl('stop')}
            onRestart={handleRestart}
          />
        )}
      </header>

      {showScrubber && (
        <div className="scrubber-bar">
          {!isLive && <span className="viewing-badge">t{viewedTick}</span>}
          <span className="scrubber-end">t{historyTicks[0]}</span>
          <TickScrubber
            historyTicks={historyTicks}
            viewedTick={viewedTick}
            onScrub={setViewedTick}
            dialogTicks={dialogTicks}
            conflictTicks={conflictTicks}
          />
          <span className="scrubber-end">t{historyTicks.at(-1)}</span>
          {!isLive && (
            <button className="btn btn-live" onClick={() => setViewedTick(null)}>
              ⏵ Live
            </button>
          )}
        </div>
      )}

      {error && <div className="error-banner">{error}</div>}

      {!state && !error && <div className="loading">Connecting to simulation…</div>}

      {isSetup && (
        <SetupScreen
          npcs={state.npcs}
          displayNames={displayNames}
          photos={photos}
          onUpdateName={updateDisplayName}
          onUpdatePhoto={updatePhoto}
          onUpdateArchetype={updateArchetype}
          onStart={() => sendControl('start')}
        />
      )}

      {state && !isSetup && (
        <>
          <div className="app-body">
            <WorldGrid
              width={displayedState?.width   ?? state.width}
              height={displayedState?.height  ?? state.height}
              npcs={displayedState?.npcs      ?? state.npcs}
              resources={displayedState?.resources ?? state.resources}
              selectedId={selectedNPC?.id}
              onSelectNPC={id => setSelectedNPC(state.npcs.find(n => n.id === id) ?? null)}
              displayNames={displayNames}
              photos={photos}
            />
            <NPCPanel
              npcs={displayedState?.npcs ?? state.npcs}
              selected={displayedNPC}
              onSelect={id => setSelectedNPC(state.npcs.find(n => n.id === id) ?? null)}
              displayNames={displayNames}
              photos={photos}
              onUpdateName={updateDisplayName}
              onUpdatePhoto={updatePhoto}
            />
          </div>
          <DialogStrip dialogs={displayedDialogs} displayNames={displayNames} />
        </>
      )}
    </div>
  )
}

// Stable dedup key for a conversation: sort speaker/listener so A→B and B→A
// (which would appear in both NPCs' lists) collapse to the same key.
function convKey(c) {
  return [...[c.speakerId, c.listenerId]].sort().join('↔') + '@' + c.tick
}
