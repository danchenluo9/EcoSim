import { useRef } from 'react'

/**
 * Custom tick scrubber with event markers.
 *
 * Markers above the track (▲ purple) = ticks where dialogs happened.
 * Markers below the track (▼ red)    = ticks where conflicts happened.
 * Clicking a marker or the track navigates to that tick.
 * Dragging the thumb scrubs continuously.
 */
export default function TickScrubber({
  historyTicks, viewedTick, onScrub, dialogTicks, conflictTicks,
}) {
  const containerRef = useRef(null)

  if (historyTicks.length < 2) return null

  const first = historyTicks[0]
  const last  = historyTicks.at(-1)
  const span  = last - first || 1

  // Tick → CSS left percentage string
  const pct = (tick) =>
    `${Math.max(0, Math.min(100, (tick - first) / span * 100))}%`

  // Client X → nearest tick in history
  const tickAt = (clientX) => {
    const { left, width } = containerRef.current.getBoundingClientRect()
    const ratio  = Math.max(0, Math.min(1, (clientX - left) / width))
    const target = first + ratio * span
    return historyTicks.reduce((best, t) =>
      Math.abs(t - target) < Math.abs(best - target) ? t : best
    )
  }

  // Navigate: snap to live when landing on or past the last tick
  const go = (tick) => onScrub(tick >= last ? null : tick)

  // Thumb drag
  const startDrag = (e) => {
    e.preventDefault()
    const onMove = (ev) => go(tickAt(ev.clientX))
    const onUp   = () => {
      window.removeEventListener('mousemove', onMove)
      window.removeEventListener('mouseup',   onUp)
    }
    window.addEventListener('mousemove', onMove)
    window.addEventListener('mouseup',   onUp)
  }

  const isLive    = viewedTick === null
  const thumbTick = isLive ? last : viewedTick
  const thumbPct  = pct(thumbTick)

  // Only render markers for ticks inside the visible history window
  const visDialog   = [...dialogTicks].filter(t => t >= first && t <= last)
  const visConflict = [...conflictTicks].filter(t => t >= first && t <= last)

  return (
    <div
      className="tick-scrubber"
      ref={containerRef}
      onClick={(e) => go(tickAt(e.clientX))}
    >
      {/* ── Dialog marks: upward triangles above the track ── */}
      {visDialog.map(tick => (
        <div
          key={`d-${tick}`}
          className="scrubber-mark scrubber-mark-dialog"
          style={{ left: pct(tick) }}
          onClick={(e) => { e.stopPropagation(); go(tick) }}
          title={`Dialog · tick ${tick}`}
        />
      ))}

      {/* ── Track ── */}
      <div className="scrubber-track">
        <div className="scrubber-fill" style={{ width: thumbPct }} />
      </div>

      {/* ── Conflict marks: downward triangles below the track ── */}
      {visConflict.map(tick => (
        <div
          key={`c-${tick}`}
          className="scrubber-mark scrubber-mark-conflict"
          style={{ left: pct(tick) }}
          onClick={(e) => { e.stopPropagation(); go(tick) }}
          title={`Conflict · tick ${tick}`}
        />
      ))}

      {/* ── Thumb ── */}
      <div
        className="scrubber-thumb"
        style={{ left: thumbPct }}
        onMouseDown={startDrag}
        onClick={(e) => e.stopPropagation()}
      />
    </div>
  )
}
