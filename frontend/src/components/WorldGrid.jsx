import { COLORS } from '../constants.js'

const CELL = 30

const RESOURCE_STYLE = {
  FOOD:     { bg: '#14532d', border: '#4ade80', label: 'F', labelColor: '#4ade80' },
  MEDICINE: { bg: '#1e3a8a', border: '#60a5fa', label: 'M', labelColor: '#93c5fd' },
}

// Offsets for when multiple NPCs share a cell
const CLUSTER_OFFSETS = [
  [0, 0],
  [-6, -3], [6, 3],
  [-7, -5], [0, 6], [7, -5],
]

export default function WorldGrid({ width, height, npcs, resources, selectedId, onSelectNPC, displayNames, photos }) {
  // Stable color mapping: sort all NPC IDs (live + dead) alphabetically so that
  // when an NPC dies and moves to the end of the serialized array, no living NPC
  // changes color (array-index-based assignment would shift all subsequent colors).
  const sortedIds = [...npcs].sort((a, b) => a.id.localeCompare(b.id)).map(n => n.id)
  const colorOf = (id) => COLORS[sortedIds.indexOf(id) % COLORS.length]

  // Build position → [npc, ...] map
  const npcMap = {}
  npcs.forEach(n => {
    const key = `${n.x},${n.y}`
    if (!npcMap[key]) npcMap[key] = []
    npcMap[key].push(n)
  })

  // Show ALL resources (depleted ones rendered dimmed)
  const resourceMap = {}
  resources.forEach(r => { resourceMap[`${r.x},${r.y}`] = r })

  return (
    <div className="grid-wrapper">
      <svg
        className="world-grid"
        width={width * CELL}
        height={height * CELL}
        style={{ display: 'block' }}
      >
        {Array.from({ length: height }, (_, row) =>
          Array.from({ length: width }, (_, col) => {
            const key    = `${col},${row}`
            const group  = npcMap[key] ?? []
            const res    = resourceMap[key]
            const cx     = col * CELL + CELL / 2
            const cy     = row * CELL + CELL / 2

            return (
              <g key={key}>
                {/* Cell background */}
                <rect
                  x={col * CELL}
                  y={row * CELL}
                  width={CELL}
                  height={CELL}
                  fill="#1a1a2e"
                  stroke="#2a2a3e"
                  strokeWidth="0.5"
                />

                {/* Resource: bright border + fill bar + label; dimmed when depleted */}
                {res && (() => {
                  const rs      = RESOURCE_STYLE[res.type]
                  const fillW   = (CELL - 4) * Math.min(1, res.quantity / (res.maxQuantity || 100))
                  const opacity = res.depleted ? 0.25 : 0.85
                  return (
                    <>
                      <rect
                        x={col * CELL}
                        y={row * CELL}
                        width={CELL}
                        height={CELL}
                        fill={rs.bg}
                        opacity={opacity}
                      />
                      <rect
                        x={col * CELL + 1}
                        y={row * CELL + 1}
                        width={CELL - 2}
                        height={CELL - 2}
                        fill="none"
                        stroke={rs.border}
                        strokeWidth="1.5"
                        rx="2"
                        opacity={opacity}
                      />
                      <text
                        x={col * CELL + 4}
                        y={row * CELL + 11}
                        fontSize="8"
                        fontWeight="bold"
                        fill={rs.labelColor}
                        opacity={opacity}
                      >
                        {res.depleted ? '–' : rs.label}
                      </text>
                      {!res.depleted && (
                        <rect
                          x={col * CELL + 2}
                          y={row * CELL + CELL - 5}
                          width={fillW}
                          height={3}
                          fill={rs.border}
                          opacity="0.6"
                          rx="1"
                        />
                      )}
                    </>
                  )
                })()}

                {/* NPCs */}
                {group.map((npc, i) => {
                  const off         = CLUSTER_OFFSETS[i % CLUSTER_OFFSETS.length]
                  const nx          = cx + off[0]
                  const ny          = cy + off[1]
                  const selected    = npc.id === selectedId
                  const color       = colorOf(npc.id)
                  const fillColor   = npc.dead ? '#4b5563'
                                    : npc._attacked ? '#ef4444'
                                    : color
                  const photo       = photos?.[npc.id] ?? null
                  const displayName = displayNames?.[npc.id] ?? npc.id
                  const initial     = npc.dead ? '✕' : displayName[0].toUpperCase()
                  return (
                    <g
                      key={npc.id}
                      onClick={() => onSelectNPC(npc.id)}
                      style={{ cursor: 'pointer' }}
                      opacity={npc.dead ? 0.4 : 1}
                    >
                      {selected && (
                        <circle cx={nx} cy={ny} r={12} fill="none" stroke="#fff" strokeWidth="1.5" opacity="0.6" />
                      )}
                      <circle cx={nx} cy={ny} r={9} fill={fillColor}>
                        {npc._attacked && !npc.dead && (
                          <animate attributeName="fill" values="#ef4444;#ef4444;#ef4444" dur="0.4s" fill="freeze" />
                        )}
                      </circle>
                      {photo && !npc.dead
                        ? <image
                            href={photo}
                            x={nx - 9} y={ny - 9}
                            width={18} height={18}
                            preserveAspectRatio="xMidYMid slice"
                            style={{ clipPath: 'circle(50%)', pointerEvents: 'none' }}
                          />
                        : <text
                            x={nx} y={ny + 4}
                            textAnchor="middle"
                            fontSize="8"
                            fontWeight="bold"
                            fill={npc.dead ? '#9ca3af' : '#0f0f1a'}
                            style={{ pointerEvents: 'none' }}
                          >
                            {initial}
                          </text>
                      }
                    </g>
                  )
                })}

                {/* Stack indicator when >1 NPC */}
                {group.length > 1 && (
                  <text
                    x={col * CELL + CELL - 4}
                    y={row * CELL + 9}
                    textAnchor="end"
                    fontSize="7"
                    fill="#fff"
                    opacity="0.7"
                    style={{ pointerEvents: 'none' }}
                  >
                    ×{group.length}
                  </text>
                )}
              </g>
            )
          })
        )}
      </svg>

      <div className="grid-legend">
        <span className="legend-item">
          <span className="legend-swatch" style={{ background: '#14532d', border: '1.5px solid #4ade80' }} /> Food
        </span>
        <span className="legend-item">
          <span className="legend-swatch" style={{ background: '#1e3a8a', border: '1.5px solid #60a5fa' }} /> Medicine
        </span>
        {npcs.map((n) => (
          <span key={n.id} className="legend-item" style={{ opacity: n.dead ? 0.4 : 1 }}>
            <span className="legend-swatch" style={{ background: n.dead ? '#4b5563' : colorOf(n.id), borderRadius: '50%' }} />
            {displayNames?.[n.id] ?? n.id}{n.dead ? ' ✕' : ''}
          </span>
        ))}
      </div>
    </div>
  )
}
