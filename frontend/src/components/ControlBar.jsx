export default function ControlBar({ tick, viewedTick, running, paused, onPause, onResume, onStop }) {
  const isLive = viewedTick === null
  return (
    <div className="control-bar">
      <span className="tick-counter">
        {isLive ? `Tick ${tick}` : `Tick ${viewedTick} / ${tick}`}
      </span>
      <span className={`sim-status ${paused ? 'paused' : running ? 'running' : 'stopped'}`}>
        {paused ? 'Paused' : running ? 'Running' : 'Stopped'}
      </span>
      {running && !paused && (
        <button className="btn btn-pause" onClick={onPause}>Pause</button>
      )}
      {running && paused && (
        <button className="btn btn-resume" onClick={onResume}>Resume</button>
      )}
      {running && (
        <button className="btn btn-stop" onClick={onStop}>Stop</button>
      )}
    </div>
  )
}
