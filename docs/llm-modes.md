# LLM Modes

EcoSim supports three LLM modes so you can develop and debug without spending API credits on every run.

## The three modes

| Mode | `ECOSIM_LLM_MODE` | Needs API key | Writes files | Reads files |
|------|-------------------|---------------|--------------|-------------|
| **live** | *(unset)* | Yes | No | No |
| **record** | `record` | Yes | Yes | No |
| **playback** | `playback` | No | No | Yes |

### Live

The default. Every strategy refresh and every NPC dialogue calls the real Anthropic API.

```bash
set -a; source .env; set +a
mvn exec:java
```

### Record

Calls the real API exactly like live mode, but also writes every response to disk. Use this once to build up a set of mock data.

```bash
./start.sh 1
# or manually:
ECOSIM_LLM_MODE=record mvn exec:java
```

Each record run **overwrites** the previous files (files are truncated at startup), so you always end up with a clean snapshot from one simulation session. Running it for more ticks produces more diverse responses, which makes playback more varied.

To get better coverage, record a longer run:

```bash
ECOSIM_MAX_TICKS=300 ./start.sh 1
```

### Playback

Replays saved responses from disk. No API calls, no API key required.

```bash
./start.sh
# or manually:
ECOSIM_LLM_MODE=playback mvn exec:java
```

If a file is missing (e.g. you added a new NPC), that NPC falls back to `MockLLMClient` — a deterministic rule-based client that returns contextually appropriate responses based on keywords in the prompt.

## File format

Mock data lives in `mock-data/` (gitignored). Each NPC gets two files:

```
mock-data/
  Alice_strategy.jsonl    ← strategy calls
  Alice_dialog.jsonl      ← dialogue calls
  Bob_strategy.jsonl
  Bob_dialog.jsonl
  ...
```

Each line is one JSON response. Strategy lines match the format `StrategyValidator` expects:

```json
{"strategy":"GATHER_FOOD","intent":"Collect food before others take it","reason":"Food ratio critically low"}
```

Dialog lines match the format `DialogTask` expects:

```json
{"speaker_line":"Do you have food to spare?","listener_line":"I can lead you to a resource.","valence":0.5}
```

You can hand-edit these files to craft specific scenarios.

## Round-robin cycling

If a playback run needs more calls than there are recorded responses, the responses cycle round-robin. With 3 recorded strategy responses, calls go: 0 → 1 → 2 → 0 → 1 → 2 → …

The `StrategyManager` uses a randomized cooldown (30–60 ticks) to decide when to call the LLM. A different random seed means playback runs may trigger more (or fewer) calls than the recording run. Cycling handles this gracefully — the simulation never stalls.

## Why calls don't depend on each other

Both call types are **stateless HTTP**: each call sends a fresh prompt built from the current NPC state. No conversation history is sent to the API. The only "dependency" is that the dialog prompt includes the last 3 recorded conversations between the NPC pair (from local memory), so responses from different sessions may feel slightly inconsistent — but for debugging purposes this is irrelevant.

## Recommended workflow

1. **First time** — run `./start.sh 1` with a real API key to populate `mock-data/`
2. **Daily development** — run `./start.sh` (playback); zero API cost, fast startup
3. **After adding new NPCs or changing prompts significantly** — run `./start.sh 1` again to refresh `mock-data/`
4. **Production / demo** — run with no `ECOSIM_LLM_MODE` set (live mode)
