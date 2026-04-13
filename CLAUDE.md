# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
# Start full stack (backend + frontend) — recommended for development
./start.sh

# Backend only (loads .env automatically via start.sh; manually:)
set -a; source .env; set +a; mvn exec:java

# Build fat JAR
mvn clean package
java -jar target/ai-npc-world-1.0-SNAPSHOT-jar-with-dependencies.jar

# Frontend only (dev server with hot reload, proxies API to :8081)
cd frontend && npm install && npm run dev

# Build (skip tests)
mvn -DskipTests package
```

## Environment Variables

Configured in `.env` (not committed):
- `ANTHROPIC_API_KEY` — required for LLM features
- `ECOSIM_WIDTH` / `ECOSIM_HEIGHT` — grid size (default: 20×20)
- `ECOSIM_TICK_MS` — simulation speed in ms (default: 300)
- `ECOSIM_MAX_TICKS` — 0 = infinite (default: 100)
- `ECOSIM_PORT` — backend HTTP port (default: 8081)

## Architecture

**EcoSim** is a 2D grid-based NPC simulation where agents use utility-based goal scoring combined with Claude LLM calls for intelligent decision-making.

### Tick Lifecycle (`WorldLoop.java`)
Each simulation tick runs in two phases:
1. **Synchronized phase** (`World.tick()`): update NPC goals, score actions, select behavior, collect pending dialog tasks — all under a world lock
2. **Async phase**: fire parallel HTTP calls to Claude API for dialog tasks (max 4/tick), then apply results back to world state

This split prevents LLM latency from blocking the simulation clock.

### NPC Decision Pipeline
```
NPC.update() → GoalSystem.score() → DecisionEngine.selectAction() → Action.execute()
                     ↑
             StrategyManager (LLM strategy cache — fetched once, reused N ticks)
```

Goals (`SurvivalGoal`, `ExploreGoal`, `SocialGoal`, `AggressionGoal`) each produce a utility score; `DecisionEngine` picks the highest-scoring available action. LLM strategies are cached and validated by `StrategyValidator` before use.

### LLM Integration
- `ClaudeClient` — calls Anthropic API; has circuit-breaker to degrade gracefully on failures
- `StrategyManager` — fetches strategy text for an NPC, caches it, invalidates on state change
- `DialogTask` / `DialogSession` — multi-turn NPC conversations; sessions persist across ticks
- `LLMPromptBuilder` / `DialogPromptBuilder` — prompt construction

### Backend API (`WorldServer.java`)
Minimal built-in Java `HttpServer` (no framework):
- `GET /api/state` → full world JSON snapshot
- `POST /api/control` → `{"action": "pause"|"resume"|"stop"}`
- `POST /api/npc` → configure NPC archetypes before start

### Frontend (`frontend/`)
React 18 + Vite. Polls `/api/state` every 500ms. Key components:
- `SetupScreen` — NPC archetype selection before simulation starts
- `WorldGrid` — 2D cell renderer
- `NPCPanel` — selected NPC details, memory, impressions
- `ControlBar` — play/pause/stop + speed slider

Vite dev server runs on `:5173` and proxies `/api/*` to `:8081`.

### NPC Archetypes
Personality presets that weight goal multipliers: `Forager` (survival), `Diplomat` (social), `Explorer` (explore), `Fighter` (aggression). Configured via `POST /api/npc` or the SetupScreen UI.

### Key Source Locations
- Simulation engine: `src/main/java/com/ecosim/`
- Entry point: `Simulator.java`
- Tick loop: `WorldLoop.java`, `World.java`
- NPC base: `AbstractNPC.java`, `NPC.java`
- All actions: `*Action.java` files
- JSON serialization: `StateSerializer.java`
