import { useRef } from 'react'

const COLORS = ['#60a5fa','#f472b6','#34d399','#fb923c','#a78bfa','#fbbf24']

const ARCHETYPES = [
  { value: 'Default',  label: 'Default',  desc: 'Balanced survivor'     },
  { value: 'Forager',  label: 'Forager',  desc: 'Food-obsessed'         },
  { value: 'Diplomat', label: 'Diplomat', desc: 'Alliance builder'      },
  { value: 'Explorer', label: 'Explorer', desc: 'Roams everywhere'      },
  { value: 'Fighter',  label: 'Fighter',  desc: 'Aggressive hunter'     },
]

export const ARCHETYPE_COLORS = {
  Default:  '#94a3b8',
  Forager:  '#4ade80',
  Diplomat: '#60a5fa',
  Explorer: '#fbbf24',
  Fighter:  '#f87171',
}

function NPCSetupCard({ npc, color, photo, displayName, archetype, onUpdateName, onUpdatePhoto, onUpdateArchetype }) {
  const fileRef = useRef(null)

  const handlePhoto = (e) => {
    const file = e.target.files[0]
    if (!file) return
    const reader = new FileReader()
    reader.onload = async (ev) => { await onUpdatePhoto(npc.id, ev.target.result) }
    reader.readAsDataURL(file)
    e.target.value = ''
  }

  const archetypeColor = ARCHETYPE_COLORS[archetype] ?? '#94a3b8'

  return (
    <div className="setup-npc-card">
      <div
        className="npc-avatar setup-avatar"
        style={{ background: npc.dead ? '#4b5563' : color }}
        onClick={() => fileRef.current?.click()}
        title="Click to upload photo"
      >
        {photo
          ? <img src={photo} alt={displayName} className="npc-avatar-img" />
          : <span className="npc-avatar-initial">{displayName ? displayName[0].toUpperCase() : '?'}</span>
        }
        <span className="npc-avatar-overlay">📷</span>
        <input
          ref={fileRef}
          type="file"
          accept="image/*"
          style={{ display: 'none' }}
          onChange={handlePhoto}
        />
      </div>

      <input
        className="setup-name-input"
        value={displayName}
        onChange={e => onUpdateName(npc.id, e.target.value)}
        placeholder={npc.id}
      />

      <select
        className="setup-archetype-select"
        value={archetype}
        onChange={e => onUpdateArchetype(npc.id, e.target.value)}
        style={{ borderColor: archetypeColor, color: archetypeColor }}
        title={ARCHETYPES.find(a => a.value === archetype)?.desc ?? ''}
      >
        {ARCHETYPES.map(a => (
          <option key={a.value} value={a.value}>{a.label}</option>
        ))}
      </select>

      <span className="setup-archetype-desc">
        {ARCHETYPES.find(a => a.value === archetype)?.desc ?? ''}
      </span>
    </div>
  )
}

export default function SetupScreen({ npcs, displayNames, photos, onUpdateName, onUpdatePhoto, onUpdateArchetype, onStart }) {
  return (
    <div className="setup-screen">
      <div className="setup-panel">
        <h1 className="setup-title">EcoSim</h1>
        <p className="setup-subtitle">Configure your NPCs before the simulation begins</p>

        <div className="setup-npc-grid">
          {npcs.map((npc, i) => (
            <NPCSetupCard
              key={npc.id}
              npc={npc}
              color={COLORS[i % COLORS.length]}
              photo={photos[npc.id] ?? null}
              displayName={displayNames[npc.id] ?? npc.id}
              archetype={npc.archetype ?? 'Default'}
              onUpdateName={onUpdateName}
              onUpdatePhoto={onUpdatePhoto}
              onUpdateArchetype={onUpdateArchetype}
            />
          ))}
        </div>

        <button className="btn btn-start" onClick={onStart}>
          Start Simulation
        </button>
      </div>
    </div>
  )
}
