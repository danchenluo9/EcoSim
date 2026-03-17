# AI Integration Plan — EcoSim

## Overview

This document describes how to replace or augment the two rule-based systems in EcoSim with LLM-powered AI agents:

1. **NPC Decision Agent** — replaces `DecisionEngine.decide()` with an LLM that picks actions based on NPC state, goals, memory, and world context.
2. **NPC Dialog Agent** — replaces the static string output in `InteractAction.execute()` with LLM-generated in-character dialog for each social interaction.

---

## Current Architecture (Rule-Based)

```
World.tick()
  └── AbstractNPC.update(world)
        ├── state.passiveTick()            // metabolism
        ├── goalSystem.updateAll()         // urgency recalculation
        ├── onPreUpdate(world)             // perception / memory logging
        ├── DecisionEngine.decide()        // [TARGET 1] picks action via utility scores
        │     ├── buildCandidateActions()
        │     ├── filter: canExecute()
        │     ├── rank: estimatedUtility()
        │     └── best.execute(npc, world)
        │           └── InteractAction    // [TARGET 2] static dialog strings
        └── memory.decayImpressions()
```

---

## Integration Architecture

### Principle: AI as a Drop-In Layer

Keep the existing utility-based system as a **fallback**. Add an `AIDecisionEngine` and an `AIDialogGenerator` that implement the same interfaces / hook points. This preserves determinism when AI is unavailable and allows A/B comparison.

```
AbstractNPC
  └── decisionEngine: DecisionEngine   ← swap to AIDecisionEngine (same interface)

InteractAction.execute()
  └── dialogGenerator.generate(ctx)   ← new injectable AIDialogGenerator
```

### Target 1 — AI Decision Engine

**Hook point:** `DecisionEngine.decide(AbstractNPC npc, World world)`

**Proposed flow:**
1. Call `buildCandidateActions()` and `canExecute()` filter as now — this keeps physics/rules valid.
2. Serialize NPC context → prompt (see `npc-decision.md`).
3. Call LLM API with the prompt.
4. Parse JSON response to select the action name.
5. Execute the chosen action. If the LLM response is invalid or times out, fall back to utility ranking.

**What the LLM gains over the rule engine:**
- Can weigh nuanced memory (e.g., "Bob betrayed me twice recently — avoid even if he's nearby").
- Can exhibit personality drift (e.g., a greedy NPC will compete even when trust is borderline).
- Can reason across multiple goals simultaneously rather than picking the single max-urgency goal.
- Produces a `reasoning` field that can be surfaced for debugging or storytelling.

**New class:** `AIDecisionEngine implements DecisionEngine` (or wraps existing DecisionEngine as fallback).

---

### Target 2 — AI Dialog Generator

**Hook point:** `InteractAction.execute(AbstractNPC npc, World world)` — after the mechanical outcome is resolved (food transfer, trust delta already applied), before (or instead of) the `System.out.println(...)` log line.

**Proposed flow:**
1. Resolve interaction outcome mechanically as now (cooperative vs. competitive, stat changes).
2. Collect context for both NPCs.
3. Call LLM API with dialog prompt (see `npc-dialog.md`).
4. Print / emit returned dialog exchanges.
5. If LLM unavailable, fall back to the current static summary line.

**What the LLM gains over the rule engine:**
- Character voice (gruff vs. friendly vs. desperate).
- Reflects the specific history between the two NPCs (grudges, alliances).
- Dialogue can evolve even if the mechanical outcome is the same.
- Enables downstream features: quest hooks, relationship narratives, audio/TTS.

**New interface:** `DialogGenerator` with a single method `generate(InteractionContext ctx): List<DialogLine>`.

---

## Suggested Implementation Phases

### Phase 1 — Dialog Agent (lower risk, high narrative value)
- Implement `DialogGenerator` interface.
- Implement `AIDialogGenerator` that calls LLM with `npc-dialog.md` prompt template.
- Implement `StaticDialogGenerator` (current behavior) as fallback.
- Wire into `InteractAction` via dependency injection (pass through `AbstractNPC` or `World`).

### Phase 2 — Decision Agent (higher impact, requires careful fallback)
- Implement `AIDecisionEngine` wrapping existing `DecisionEngine`.
- Add timeout + retry logic with graceful fallback.
- Add `reasoning` field to NPC state for debug output.
- Validate that the returned action name is in the eligible candidate set.

### Phase 3 — Agent Memory Compression
- Periodically summarize the NPC's `Memory` event log using an LLM summarization prompt.
- Replace oldest events with a compressed summary event (type: `SUMMARY`, high-valence composite).
- Enables longer-horizon reasoning within the fixed 50-event memory window.

---

## API Considerations

| Concern | Recommendation |
|---|---|
| Latency | Cache LLM calls keyed on a hash of the prompt context; many ticks produce identical contexts. |
| Cost | Call the Decision Agent at most every N ticks per NPC (configurable). Use utility engine in between. |
| Model | GPT-4o-mini or Claude Haiku for decisions (cheap, fast). GPT-4o or Claude Sonnet for dialog (richer output). |
| Rate limits | Queue NPC requests and process async; `WorldLoop` tick interval (default 500ms) gives headroom. |
| Structured output | Use JSON mode / `response_format: { type: "json_object" }` for decision output. |
| Prompt caching | OpenAI and Anthropic both support prompt prefix caching — put the static system prompt first to maximize cache hits. |

---

## Prompt Files

| File | Purpose |
|---|---|
| `npc-decision.md` | Full prompt spec (system prompt + user prompt template + expected JSON output) for the Decision Agent |
| `npc-dialog.md` | Full prompt spec (system prompt + user prompt template + expected JSON output) for the Dialog Agent |
