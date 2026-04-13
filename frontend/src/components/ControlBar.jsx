export default function ControlBar({ tick, running, paused, onPause, onResume, onStop }) {
  return (
    <div className="control-bar">
      <span className="tick-counter">Tick {tick}</span>
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
