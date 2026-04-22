# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
# Start full stack (backend + frontend) — recommended
./start.sh          # playback mode (no API calls, uses mock-data/)
./start.sh 1        # record mode  (calls real API, saves to mock-data/)

# Backend only
set -a; source .env; set +a
ECOSIM_LLM_MODE=playback mvn exec:java

# Frontend only (hot reload, proxies /api/* to :8081)
cd frontend && npm install && npm run dev

# Build fat JAR
mvn clean package
java -jar target/ai-npc-world-1.0-SNAPSHOT-jar-with-dependencies.jar
```

## Environment Variables

Configured in `.env` (not committed):
- `ANTHROPIC_API_KEY` — required for live/record modes only
- `ECOSIM_LLM_MODE` — `record` | `playback` | *(unset = live)*
- `ECOSIM_WIDTH` / `ECOSIM_HEIGHT` — grid size (default: 20×20)
- `ECOSIM_TICK_MS` — ms per tick (default: 300)
- `ECOSIM_MAX_TICKS` — 0 = infinite (default: 100)
- `ECOSIM_PORT` — backend HTTP port (default: 8081)

## Architecture

**EcoSim** is a 2D grid NPC simulation. Agents use utility-based goal scoring for actions; Claude LLM provides high-level strategy and dialogue.

### Tick Lifecycle (`WorldLoop.java`)

Each tick runs in three phases:
1. **Synchronized phase** (`World.tick()`): regenerate resources, update each NPC (goals → decision → action), collect pending dialog tasks
2. **Async phase**: fire up to 4 parallel HTTP calls to Claude for dialog; world lock is free so the frontend can poll
3. **Apply phase** (world lock): merge dialog results into NPC trust/memory

After each tick completes, `WorldLoop` serializes the world state and pushes it to an in-memory ring buffer (≤500 entries). This buffer powers `/api/history` so the frontend receives every tick, not just the one that happened to align with a poll.

### NPC Decision Pipeline

```
NPC.update() → GoalSystem.score() → DecisionEngine.selectAction() → Action.execute()
                     ↑
             StrategyManager (async LLM fetch, result cached 30–60 ticks)
```

Goals: `SurvivalGoal`, `ExploreGoal`, `SocialGoal`, `AggressionGoal`. Each produces a `[0, 1]` urgency; `DecisionEngine` multiplies action utilities by the active `Strategy`'s multiplier table before selecting.

### LLM Integration

- `ClaudeClient` — live Anthropic API; circuit-breaker after 3 consecutive failures
- `RecordingLLMClient` — wraps a delegate, truncates files on start, appends one JSON per line to `mock-data/<npc>_strategy.jsonl` and `mock-data/<npc>_dialog.jsonl`
- `PlaybackLLMClient` — loads those files at startup, serves responses round-robin; falls back to `MockLLMClient` if files are absent
- `StrategyManager` — triggers LLM calls on: cooldown expiry (30–60 ticks, jittered), starvation risk (<15% food), conflict event; caches result
- `LLMPromptBuilder` / `DialogPromptBuilder` — build the prompts; both are stateless per-call (no HTTP conversation history sent)

Wire-up in `Simulator.java` via `createLLMClient(npcName)` which reads `ECOSIM_LLM_MODE`.

### Backend API (`WorldServer.java`)

Built-in Java `HttpServer`, no framework:

| Endpoint | Description |
|----------|-------------|
| `GET /api/history?since=N` | All buffered tick snapshots with tick > N, plus current state. Primary polling endpoint. |
| `GET /api/state` | Current world snapshot only (legacy, still available) |
| `POST /api/control` | Body: `start` \| `pause` \| `resume` \| `stop` \| `reset` |
| `POST /api/npc` | Body: `{"id":"Alice","archetype":"Fighter"}` — setup phase only |

`reset` atomically rebuilds the world (via `Simulator.buildWorldLoop()`) and swaps it into `WorldServer` — the Java process keeps running and the frontend sees `tick=0` on the next poll.

`/api/history` response shape:
```json
{ "current": { ...state... }, "history": [ {...}, {...} ] }
```

### Frontend (`frontend/src/`)

React 18 + Vite. Polls `/api/history?since=<lastReceivedTick>` every 500ms, processes every snapshot in the response in tick order. Stops polling when `running: false && tick > 0`.

**Key state in `App.jsx`:**
- `historyRef` — `Map<tick, snapshot>`, capped at 300 in memory, 60 in localStorage
- `tickDialogsRef` — `Map<tick, dialog[]>` — populated by diffing consecutive snapshots
- `tickConflictsRef` — `Map<tick, event[]>` — same diffing approach
- `viewedTick` — `null` = live mode; number = historical tick shown on scrubber
- `lastReceivedTick` — cursor sent as `?since=` on each poll

**Component files** (`frontend/src/components/`):
- `SetupScreen` — NPC archetype selection, display-name editing, photo upload; shown before simulation starts
- `ControlBar` — tick counter (shows `Tick 30 / 100` in history mode), pause/resume/stop, ↺ New Sim
- `TickScrubber` — custom drag slider; ▲ purple marks = dialog ticks, ▼ red marks = conflict ticks; clicking a mark navigates to that tick
- `WorldGrid` — SVG 2D renderer; receives `displayedState` (historical or live)
- `NPCPanel` — stats, strategy, action log, conversations, impressions, events; also receives `displayedState`

**Inline components in `App.jsx`:**
- `DialogStrip` — conversations at the viewed tick, shown as cards at the bottom
- `ConflictStrip` — attack/theft events at the viewed tick, shown as compact chips

**localStorage cache** (`ecosim-cache`): saved every 10 ticks and on tab close; loaded on mount to pre-populate all history refs. Cleared automatically when the backend restarts (tick resets to 0).

### NPC Archetypes

Personality presets that apply goal multipliers:

| Archetype | Dominant goal |
|-----------|--------------|
| Default | Balanced (no dominant goal) |
| Forager | Survival (food) |
| Diplomat | Social |
| Explorer | Exploration |
| Fighter | Aggression |

Configured in the SetupScreen UI or via `POST /api/npc` before simulation start.

### Key Source Locations

| What | Where |
|------|-------|
| Entry point + world factory | `src/main/java/com/aiworld/Simulator.java` — `buildWorldLoop()` is called on startup and on each `reset` |
| Tick loop + ring buffer | `src/main/java/com/aiworld/core/WorldLoop.java` |
| World state + tick logic | `src/main/java/com/aiworld/core/World.java` |
| NPC base class | `src/main/java/com/aiworld/npc/AbstractNPC.java` |
| LLM clients | `src/main/java/com/aiworld/llm/` |
| HTTP server + endpoints | `src/main/java/com/aiworld/server/WorldServer.java` |
| JSON serialization | `src/main/java/com/aiworld/server/StateSerializer.java` |
| Frontend main | `frontend/src/App.jsx` |
| Tick scrubber | `frontend/src/components/TickScrubber.jsx` |
