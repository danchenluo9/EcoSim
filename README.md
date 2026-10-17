# EcoSim

A 2D grid-based NPC simulation where autonomous agents pursue survival, exploration, and social goals. Each NPC uses utility-based goal scoring for moment-to-moment decisions, and calls the Claude LLM for high-level strategic planning and inter-NPC dialogue. The simulation is visualized through a React web UI with a full tick-history scrubber.

![EcoSim UI](docs/screenshot-placeholder.png)

## Features

- **Utility-based AI** — NPCs score competing goals (Survival, Exploration, Social, Aggression) and pick the highest-utility action each tick
- **LLM strategy layer** — Claude generates strategic directives that bias the utility engine for 30–60 ticks at a time
- **LLM dialogue** — NPCs generate in-character conversations when they meet; dialogue affects trust scores and memory
- **Personality archetypes** — Default, Forager, Diplomat, Explorer, Fighter each weight goals differently
- **NPC customisation** — rename any NPC and upload a profile photo from the setup screen
- **Memory system** — NPCs remember attacks, alliances, robberies, and conversations; impressions decay over time
- **React web UI** — live 2D grid, NPC panel with stats/memory/conversations, tick history scrubber
- **LLM mock system** — record real API responses once, replay offline indefinitely (no API cost during debugging)

## Prerequisites

| Tool | Version |
|------|---------|
| Java JDK | 17+ |
| Maven | 3.8+ |
| Node.js | 18+ |

```bash
java -version && mvn --version && node --version
```

## Quick Start

```bash
# 1. Clone the repo
git clone <repo-url> && cd EcoSim

# 2. Copy and fill in your API key
cp .env.example .env          # or create .env manually (see below)

# 3. Record a session so you have mock data for debugging
./start.sh 1

# 4. Open http://localhost:5173, configure NPCs, click Start
# 5. When done, Ctrl+C. From now on you can develop without API calls:
./start.sh                    # uses saved mock data by default
```

> **First time?** You need a real API key for step 3. After that, `./start.sh` (no argument) replays saved responses — no key needed.

## Environment Variables

Create a `.env` file in the project root (never committed):

```bash
ANTHROPIC_API_KEY=sk-ant-...   # required for live/record modes
ECOSIM_WIDTH=20                # grid width  (default: 20)
ECOSIM_HEIGHT=20               # grid height (default: 20)
ECOSIM_TICK_MS=300             # ms per tick (default: 300)
ECOSIM_MAX_TICKS=100           # 0 = run forever (default: 100)
ECOSIM_PORT=8081               # backend HTTP port (default: 8081)
```

## Running

### Recommended: launcher script

```bash
./start.sh       # playback mode — uses saved mock data, no API key needed
./start.sh 1     # record mode  — calls real API and saves responses to mock-data/
```

The launcher loads `.env`, starts the Java backend and Vite frontend in parallel, and opens the browser.

### Manual

```bash
# Backend only
set -a; source .env; set +a
ECOSIM_LLM_MODE=playback mvn exec:java   # or: record / (omit for live)

# Frontend only (dev server, hot reload, proxies /api/* to :8081)
cd frontend && npm install && npm run dev

# Build fat JAR
mvn clean package
java -jar target/ai-npc-world-1.0-SNAPSHOT-jar-with-dependencies.jar
```

## LLM Modes

EcoSim has three LLM modes, controlled by the `ECOSIM_LLM_MODE` environment variable:

| Mode | Command | Description |
|------|---------|-------------|
| **live** | *(default, no var)* | Calls the real Claude API every time |
| **record** | `./start.sh 1` | Calls the real API **and** saves responses to `mock-data/` |
| **playback** | `./start.sh` | Replays saved responses — no API calls, no key needed |

Each NPC gets its own pair of files:
```
mock-data/
  Alice_strategy.jsonl    ← one strategy JSON per line
  Alice_dialog.jsonl      ← one dialog JSON per line
  Bob_strategy.jsonl
  ...
```

Responses cycle round-robin, so a playback run can last longer than the recording. See [docs/llm-modes.md](docs/llm-modes.md) for full details.

## Web UI

Open **http://localhost:5173** after starting.

### Setup screen
Configure each NPC before the simulation starts:
- Choose a **personality archetype** (Default, Forager, Diplomat, Explorer, Fighter)
- Set a **display name** (double-click the name field)
- Upload a **profile photo** (click the avatar)

Click **Start** when ready.

### Simulation view

| Element | Description |
|---------|-------------|
| **Grid** | 2D world — click an NPC to select it |
| **NPC panel** | Stats, strategy, action log, conversations, impressions, events |
| **Control bar** | Tick counter, pause/resume/stop, **↺ New Sim** button |
| **Tick scrubber** | Drag to any historical tick; ▲ purple = dialog event, ▼ red = conflict |
| **Dialog strip** | Conversations that happened at the viewed tick |
| **Conflict strip** | Attacks and thefts at the viewed tick |

The frontend caches the last 60 tick snapshots in `localStorage` so history survives a page refresh.

Clicking **↺ New Sim** sends a `reset` command to the backend (which rebuilds the world to tick=0 in-process), clears the frontend cache, and reloads the page — the setup screen appears immediately, no backend restart needed.

## Architecture Overview

```
Browser (React)
    │  polls /api/history?since=N every 500ms
    ▼
WorldServer (Java HttpServer :8081)
    ├── GET  /api/history?since=N  → buffered tick snapshots since tick N
    ├── GET  /api/state            → current world snapshot
    ├── POST /api/control          → pause / resume / stop / reset
    └── POST /api/npc             → set NPC archetype (setup only)
    │
WorldLoop (300ms tick scheduler)
    │  after each tick: serialize + push to ring buffer (≤500 ticks)
    ▼
World.tick()
    ├── Phase 1 (world lock): regenerate resources, update each NPC
    │       NPC: GoalSystem.score() → DecisionEngine.selectAction() → Action.execute()
    │       StrategyManager: async LLM call if cooldown/emergency triggers
    ├── Phase 2 (no lock): fire dialog HTTP calls concurrently (max 4/tick)
    └── Phase 3 (world lock): apply dialog results, update trust/memory
```

### NPC Decision Pipeline

```
NPC.update()
  └─ StrategyManager.tick()          ← checks if LLM strategy refresh is due
       └─ LLMClient.call()           ← ClaudeClient / RecordingLLMClient / PlaybackLLMClient
  └─ GoalSystem.score()              ← Survival, Explore, Social, Aggression urgencies
  └─ DecisionEngine.selectAction()   ← picks highest utility × strategy multiplier
  └─ Action.execute()                ← Move / Gather / Rest / Interact / Dialog / Attack / Steal
```

## Project Structure

```
EcoSim/
├── src/main/java/com/aiworld/
│   ├── Simulator.java          # entry point, world setup
│   ├── core/                   # World, WorldLoop (tick scheduling + ring buffer)
│   ├── action/                 # Move, Gather, Rest, Interact, Dialog, Attack, Steal
│   ├── decision/               # DecisionEngine (utility-based action selection)
│   ├── goal/                   # Survival, Explore, Social, Aggression goals
│   ├── llm/                    # ClaudeClient, RecordingLLMClient, PlaybackLLMClient,
│   │                           #   StrategyManager, StrategyValidator, prompt builders
│   ├── dialog/                 # DialogSession, DialogPromptBuilder
│   ├── model/                  # Location, Resource, MemoryEvent, NPCImpression
│   ├── npc/                    # AbstractNPC, NPC, NPCState, GoalSystem, Memory
│   └── server/                 # WorldServer, StateSerializer
├── frontend/
│   └── src/
│       ├── App.jsx             # main orchestrator, history buffer, cache
│       └── components/
│           ├── ControlBar.jsx
│           ├── WorldGrid.jsx
│           ├── NPCPanel.jsx
│           ├── SetupScreen.jsx
│           └── TickScrubber.jsx
├── mock-data/                  # recorded LLM responses (gitignored)
├── .env                        # secrets and config (gitignored)
└── start.sh                    # launcher
```

## Troubleshooting

| Problem | Fix |
|---------|-----|
| `mvn: command not found` | Install Maven, add to PATH |
| Java version errors | Ensure JDK 17 is active (`java -version`) |
| `ANTHROPIC_API_KEY not set` | Set it in `.env` or use `./start.sh` (playback needs no key) |
| Frontend shows "Cannot reach server" | Backend isn't running — check terminal for Java errors |
| Playback shows mock fallback responses | No `mock-data/` files yet — run `./start.sh 1` once to record |
| Simulation stops immediately | Check `ECOSIM_MAX_TICKS` in `.env` (0 = infinite) |
| "↺ New Sim" shows simulation instead of setup | Backend may not have received the reset — check the terminal for errors and try again |
