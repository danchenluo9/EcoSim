import { useState, useRef } from 'react'
import { ARCHETYPE_COLORS } from './SetupScreen.jsx'
import { COLORS } from '../constants.js'

function StatBar({ label, value, max = 100, color }) {
  const pct = Math.max(0, Math.min(100, (value / max) * 100))
  return (
    <div className="stat-row">
      <span className="stat-label">{label}</span>
      <div className="stat-track">
        <div className="stat-fill" style={{ width: `${pct}%`, background: color }} />
      </div>
      <span className="stat-value">{value}</span>
    </div>
  )
}

const STRATEGY_COLORS = {
  GATHER_FOOD:     '#4ade80',
  SEEK_ALLIES:     '#60a5fa',
  EXPLORE:         '#fbbf24',
  AVOID_CONFLICT:  '#f87171',
  CONSERVE_ENERGY: '#c084fc',
  SURVIVE:         '#fb923c',
  RETALIATE:       '#ef4444',
}

const VALENCE_COLOR = v => v > 0.2 ? '#4ade80' : v < -0.2 ? '#f87171' : '#94a3b8'

function NPCCard({ npc, color, photo, displayName, displayNames, expanded, onClick, onUpdateName, onUpdatePhoto }) {
  const resolveName  = (id)   => displayNames?.[id] ?? id
  const resolveNames = (text) => {
    if (!text) return text
    return Object.entries(displayNames ?? {}).reduce((str, [id, name]) => str.replaceAll(id, name), text)
  }
  const [editing, setEditing] = useState(false)
  const [nameInput, setNameInput] = useState('')
  const fileRef = useRef(null)

  const startEdit = (e) => {
    e.stopPropagation()
    setNameInput(displayName)
    setEditing(true)
  }

  const commitEdit = () => {
    if (nameInput.trim()) onUpdateName(npc.id, nameInput.trim())
    setEditing(false)
  }

  const handleKeyDown = (e) => {
    if (e.key === 'Enter') commitEdit()
    if (e.key === 'Escape') setEditing(false)
  }

  const handlePhoto = (e) => {
    const file = e.target.files[0]
    if (!file) return
    const reader = new FileReader()
    reader.onload = async (ev) => { await onUpdatePhoto(npc.id, ev.target.result) }
    reader.readAsDataURL(file)
    e.target.value = ''
  }

  return (
    <div
      className={`npc-card ${expanded ? 'expanded' : ''} ${npc.dead ? 'dead' : ''}`}
      onClick={onClick}
    >
      <div className="npc-card-header">
        <div
          className="npc-avatar"
          style={{ background: npc.dead ? '#4b5563' : color }}
          onClick={(e) => { e.stopPropagation(); fileRef.current?.click() }}
          title="Click to change photo"
        >
          {photo
            ? <img src={photo} alt={displayName} className="npc-avatar-img" />
            : <span className="npc-avatar-initial">{displayName[0].toUpperCase()}</span>
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

        {editing
          ? <input
              className="npc-name-input"
              value={nameInput}
              autoFocus
              onChange={e => setNameInput(e.target.value)}
              onBlur={commitEdit}
              onKeyDown={handleKeyDown}
              onClick={e => e.stopPropagation()}
            />
          : <span className="npc-name" onDoubleClick={startEdit} title="Double-click to rename">
              {displayName}
            </span>
        }

        {npc.archetype && npc.archetype !== 'Default' && !npc.dead && (
          <span className="archetype-badge"
            style={{ color: ARCHETYPE_COLORS[npc.archetype] ?? '#94a3b8',
                     borderColor: ARCHETYPE_COLORS[npc.archetype] ?? '#94a3b8' }}>
            {npc.archetype}
          </span>
        )}
        {npc.strategy && !npc.dead && (
          <span className="strategy-badge"
            style={{ background: STRATEGY_COLORS[npc.strategy] ?? '#6b7280' }}>
            {npc.strategy.replace(/_/g, ' ')}
          </span>
        )}
        {npc.dead && <span className="dead-badge">Dead</span>}
        <span className="npc-age">age {npc.age}</span>
      </div>

      {expanded && (
        <div className="npc-card-body">
          <StatBar label="Health" value={npc.health} max={npc.maxHealth ?? 100} color="#f87171" />
          <StatBar label="Food"   value={npc.food}   max={npc.maxFood   ?? 100} color="#4ade80" />
          <StatBar label="Energy" value={npc.energy} max={npc.maxEnergy ?? 100} color="#60a5fa" />

          {npc.strategyIntent && (
            <div className="section-block">
              <span className="section-title">Strategy Intent</span>
              <p className="section-text">{resolveNames(npc.strategyIntent)}</p>
            </div>
          )}

          {/* Action History */}
          {npc.actionLog?.length > 0 && (
            <div className="section-block">
              <span className="section-title">Action History</span>
              <div className="action-log">
                {[...npc.actionLog].reverse().map((entry, i) => (
                  <div key={i} className="action-entry">{resolveNames(entry)}</div>
                ))}
              </div>
            </div>
          )}

          {/* Conversations */}
          {npc.conversations?.length > 0 && (
            <div className="section-block">
              <span className="section-title">Conversations</span>
              {[...npc.conversations].reverse().map((c, i) => (
                <div key={i} className="convo-block">
                  <div className="convo-header">
                    <span className="event-tick">t{c.tick}</span>
                    <span className="convo-participants">{resolveName(c.speakerId)} → {resolveName(c.listenerId)}</span>
                    <span className="convo-valence" style={{ color: VALENCE_COLOR(c.valence) }}>
                      {c.valence > 0 ? '+' : ''}{c.valence}
                    </span>
                  </div>
                  <div className="convo-line">
                    <span className="convo-who">{resolveName(c.speakerId)}:</span>
                    <span className="convo-text">"{resolveNames(c.speakerLine)}"</span>
                  </div>
                  <div className="convo-line">
                    <span className="convo-who">{resolveName(c.listenerId)}:</span>
                    <span className="convo-text">"{resolveNames(c.listenerLine)}"</span>
                  </div>
                </div>
              ))}
            </div>
          )}

          {/* Impressions */}
          {npc.impressions?.length > 0 && (
            <div className="section-block">
              <span className="section-title">Impressions</span>
              {npc.impressions.map(imp => (
                <div key={imp.npcId} className="impression-row">
                  <span>{resolveName(imp.npcId)}</span>
                  <span className="imp-trust">T {imp.trust}</span>
                  <span className="imp-hostility">H {imp.hostility}</span>
                </div>
              ))}
            </div>
          )}

          {/* Recent Events */}
          {npc.recentEvents?.length > 0 && (
            <div className="section-block">
              <span className="section-title">Recent Events</span>
              {npc.recentEvents.map((e, i) => {
                const isAttack = e.type === 'WAS_ATTACKED' || e.type === 'ATTACKED_NPC'
                const isTheft  = e.type === 'WAS_ROBBED'   || e.type === 'STOLE_FOOD'
                const cls = isAttack ? 'event-attack' : isTheft ? 'event-theft' : ''
                return (
                  <div key={i} className={`event-row ${cls}`}>
                    <span className="event-tick">t{e.tick}</span>
                    <span className="event-desc">{resolveNames(e.description)}</span>
                  </div>
                )
              })}
            </div>
          )}
        </div>
      )}
    </div>
  )
}

export default function NPCPanel({ npcs, selected, onSelect, displayNames, photos, onUpdateName, onUpdatePhoto }) {
  const alive = npcs.filter(n => !n.dead).length
  const dead  = npcs.length - alive

  // Stable color mapping — same logic as WorldGrid: sort IDs alphabetically so
  // colors don't shift when NPCs die and move to the end of the serialized array.
  const sortedIds = [...npcs].sort((a, b) => a.id.localeCompare(b.id)).map(n => n.id)
  const colorOf = (id) => COLORS[sortedIds.indexOf(id) % COLORS.length]

  return (
    <aside className="npc-panel">
      <h2 className="panel-title">
        NPCs — {alive} alive{dead > 0 ? `, ${dead} dead` : ''}
      </h2>
      {npcs.map((npc) => (
        <NPCCard
          key={npc.id}
          npc={npc}
          color={colorOf(npc.id)}
          photo={photos[npc.id] ?? null}
          displayName={displayNames[npc.id] ?? npc.id}
          displayNames={displayNames}
          expanded={selected?.id === npc.id}
          onClick={() => onSelect(selected?.id === npc.id ? null : npc.id)}
          onUpdateName={onUpdateName}
          onUpdatePhoto={onUpdatePhoto}
        />
      ))}
      {npcs.length === 0 && <p className="empty-state">All NPCs have died.</p>}
    </aside>
  )
}
