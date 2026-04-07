import { useState, useEffect, useCallback, useRef } from 'react'
import WorldGrid from './components/WorldGrid.jsx'
import NPCPanel from './components/NPCPanel.jsx'
import ControlBar from './components/ControlBar.jsx'
import SetupScreen from './components/SetupScreen.jsx'

const POLL_INTERVAL_MS = 500

export default function App() {
  const [state, setState] = useState(null)
  const [selectedNPC, setSelectedNPC] = useState(null)
  const [error, setError] = useState(null)
  const fetchingRef = useRef(false)
  const prevStateRef = useRef(null)

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

  const fetchState = useCallback(async () => {
    if (fetchingRef.current) return   // drop poll if previous one is still in flight
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

  useEffect(() => {
    if (prevStateRef.current === null && state && state.tick === 0 && !state.running) {
      setPhotos({})
      setDisplayNames({})
      localStorage.removeItem('npc-photos')
      localStorage.removeItem('npc-display-names')
    }
    prevStateRef.current = state
  }, [state])

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

  const isSetup = state && !state.running && state.tick === 0

  return (
    <div className="app">
      <header className="app-header">
        <span className="app-title">EcoSim</span>
        {state && !isSetup && (
          <ControlBar
            tick={state.tick}
            running={state.running}
            paused={state.paused}
            onPause={() => sendControl('pause')}
            onResume={() => sendControl('resume')}
            onStop={() => sendControl('stop')}
          />
        )}
      </header>

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
        <div className="app-body">
          <WorldGrid
            width={state.width}
            height={state.height}
            npcs={state.npcs}
            resources={state.resources}
            selectedId={selectedNPC?.id}
            onSelectNPC={id => setSelectedNPC(state.npcs.find(n => n.id === id) ?? null)}
            displayNames={displayNames}
            photos={photos}
          />
          <NPCPanel
            npcs={state.npcs}
            selected={selectedNPC}
            onSelect={id => setSelectedNPC(state.npcs.find(n => n.id === id) ?? null)}
            displayNames={displayNames}
            photos={photos}
            onUpdateName={updateDisplayName}
            onUpdatePhoto={updatePhoto}
          />
        </div>
      )}
    </div>
  )
}
