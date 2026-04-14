import { useState, useEffect, useCallback, useRef } from 'react'
import WorldGrid from './components/WorldGrid.jsx'
import NPCPanel from './components/NPCPanel.jsx'
import ControlBar from './components/ControlBar.jsx'
import SetupScreen from './components/SetupScreen.jsx'

const POLL_INTERVAL_MS = 500
const MAX_HISTORY      = 300   // max tick snapshots kept in memory

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
  const fetchingRef  = useRef(false)
  const prevStateRef = useRef(null)   // for reset detection (set by useEffect)
  const prevDataRef  = useRef(null)   // for dialog diffing (set inside fetchState)

  // ── Tick history ──────────────────────────────────────────────
  const historyRef     = useRef(new Map())  // tick → raw state snapshot
  const tickDialogsRef = useRef(new Map())  // tick → dialog[]
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
    const id = setInterval(fetchState, POLL_INTERVAL_MS)
    return () => clearInterval(id)
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
      prevDataRef.current = null
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

  // ── Scrubber state ────────────────────────────────────────────
  const isLive = viewedTick === null
  const sliderMax = Math.max(0, historyTicks.length - 1)
  const sliderVal = isLive
    ? sliderMax
    : Math.max(0, historyTicks.indexOf(viewedTick))

  const handleSlider = (e) => {
    const idx = +e.target.value
    // Rightmost position = back to live
    setViewedTick(idx === historyTicks.length - 1 ? null : historyTicks[idx])
  }

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
          />
        )}
      </header>

      {showScrubber && (
        <div className="scrubber-bar">
          {!isLive && <span className="viewing-badge">t{viewedTick}</span>}
          <span className="scrubber-end">t{historyTicks[0]}</span>
          <input
            type="range"
            className="tick-slider"
            min={0}
            max={sliderMax}
            value={sliderVal}
            onChange={handleSlider}
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
