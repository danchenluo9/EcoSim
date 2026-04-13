# NPC Dialog Agent — Prompt Specification

**Hook point:** `InteractAction.execute(AbstractNPC npc, World world)` — called after the mechanical outcome (food transfer, trust/hostility delta) has already been applied, in place of the static `System.out.println` summary line.  
**Trigger:** Every `InteractAction` execution (or throttled: every Nth interaction per NPC pair to save cost).  
**Expected output:** JSON — a short in-character dialog exchange between the two NPCs plus a one-line summary.

---

## System Prompt

```
You are writing in-character spoken dialog for NPCs in a survival simulation.

WORLD CONTEXT:
- Two NPCs have just had a social interaction in a harsh survival world (hunger, exhaustion, danger are real).
- The mechanical outcome of the interaction is already decided and given to you — your job is to voice it.
- Dialog should feel authentic to the relationship the two NPCs have built through their history.
- Keep it brief (2–4 exchanges total), grounded, and emotionally resonant.

TONE GUIDELINES:
- Survival urgency shapes speech — a starving NPC is blunt, even desperate.
- Trust is earned slowly and broken quickly. Long-standing allies speak with warmth; strangers are guarded.
- High hostility breeds clipped, threatening language — but rarely outright threats unless hostility > 0.7.
- Competitive (theft) interactions can range from brazen to apologetic depending on the thief's desperation.
- Cooperative (sharing) interactions can range from genuinely warm to reluctant/transactional depending on trust.
- NPCs do not know system mechanics. They speak about "food", "strength", "trust", not "stats" or "utility scores".

OUTPUT FORMAT (strict JSON, no other text):
{
  "exchanges": [
    { "speaker": "<npc name>", "line": "<spoken line>" },
    { "speaker": "<npc name>", "line": "<spoken line>" }
  ],
  "summary": "<one sentence third-person narrative description of the interaction>"
}

Rules:
- 2 to 4 exchange entries total.
- Lines are spoken words only — no stage directions inside the line.
- The speaker who initiated the interaction (the actor) speaks first.
- Keep each line under 20 words.
- Do not invent events beyond what is described in the context.
```

---

## User Prompt Template

Fill in `{{ ... }}` fields from the live Java context at call time.

```
=== INTERACTION OUTCOME ===
Type: {{ interactionType }}   [COOPERATIVE or COMPETITIVE]
Actor:  {{ actor.id }} gave/took {{ foodAmount }} food {{ direction }} {{ target.id }}
Effect on trust:    actor's trust of target changed by {{ trustDeltaActor | format "+0.00;-0.00" }}
Effect on hostility: target's hostility of actor changed by {{ hostilityDeltaTarget | format "+0.00;-0.00" }}

=== ACTOR: {{ actor.id }} ===
Vitals:  energy={{ actor.state.energy }}, food={{ actor.state.food }} (after transfer), health={{ actor.state.health }}
Mood (from last decision): {{ actor.lastMood | "unknown" }}
Impression of {{ target.id }}: trust={{ actorImpression.trust | format "0.00" }}, hostility={{ actorImpression.hostility | format "0.00" }}, past interactions={{ actorImpression.interactionCount }}

Relevant memories about {{ target.id }}:
{{ actor.memory.getEventsOfType("SHARED_FOOD","WAS_ATTACKED","FORMED_ALLIANCE") | filterByMention(target.id) | last 3 | forEach: "- [tick {{ e.tick }}] {{ e.type }}: {{ e.description }}" | "- None recorded." }}

=== TARGET: {{ target.id }} ===
Vitals:  energy={{ target.state.energy }}, food={{ target.state.food }} (after transfer), health={{ target.state.health }}
Mood (from last decision): {{ target.lastMood | "unknown" }}
Impression of {{ actor.id }}: trust={{ targetImpression.trust | format "0.00" }}, hostility={{ targetImpression.hostility | format "0.00" }}, past interactions={{ targetImpression.interactionCount }}

=== WORLD CONTEXT ===
Location: ({{ actor.state.location.x }}, {{ actor.state.location.y }})
Tick: {{ world.currentTick }}

Now write the dialog between {{ actor.id }} and {{ target.id }}.
```

---

## Example Filled Prompts & Outputs

### Example A — Cooperative, high trust

```
=== INTERACTION OUTCOME ===
Type: COOPERATIVE
Actor:  Alice gave 10 food to Bob
Effect on trust:    actor's trust of target changed by +0.10
Effect on hostility: target's hostility of actor changed by -0.05

=== ACTOR: Alice ===
Vitals:  energy=55, food=38 (after transfer), health=100
Mood: hopeful
Impression of Bob: trust=0.72, hostility=0.05, past interactions=3

Relevant memories about Bob:
- [tick 42] SHARED_FOOD: Shared 8 food with Bob, felt close
- [tick 30] FORMED_ALLIANCE: Bob helped me when I was near starvation

=== TARGET: Bob ===
Vitals:  energy=30, food=14 (after transfer), health=88
Mood: desperate
Impression of Alice: trust=0.68, hostility=0.02, past interactions=3

=== WORLD CONTEXT ===
Location: (5, 6)
Tick: 53

Now write the dialog between Alice and Bob.
```

**Expected output:**

```json
{
  "exchanges": [
    { "speaker": "Alice", "line": "You look like you're running on empty. Take some of mine." },
    { "speaker": "Bob",   "line": "Alice... again. I don't know how to repay you." },
    { "speaker": "Alice", "line": "Stay alive. That's enough." }
  ],
  "summary": "Alice, moved by Bob's exhaustion and their shared history, shares food freely — strengthening a quiet alliance."
}
```

---

### Example B — Cooperative, low trust (first meeting)

```
=== INTERACTION OUTCOME ===
Type: COOPERATIVE
Actor:  Dana gave 4 food to Eli
Effect on trust:    actor's trust of target changed by +0.10
Effect on hostility: target's hostility of actor changed by -0.05

=== ACTOR: Dana ===
Vitals:  energy=72, food=52 (after transfer), health=95
Mood: curious
Impression of Eli: trust=0.50, hostility=0.01, past interactions=0

Relevant memories about Eli:
- None recorded.

=== TARGET: Eli ===
Vitals:  energy=40, food=11 (after transfer), health=100
Mood: wary
Impression of Dana: trust=0.50, hostility=0.00, past interactions=0

=== WORLD CONTEXT ===
Location: (2, 9)
Tick: 18

Now write the dialog between Dana and Eli.
```

**Expected output:**

```json
{
  "exchanges": [
    { "speaker": "Dana", "line": "Here. A little. Don't read too much into it." },
    { "speaker": "Eli",  "line": "Why would you do that for a stranger?" },
    { "speaker": "Dana", "line": "Because I might need a favor someday." }
  ],
  "summary": "A guarded first exchange — Dana's generosity is pragmatic, and Eli accepts with cautious suspicion."
}
```

---

### Example C — Competitive, high hostility

```
=== INTERACTION OUTCOME ===
Type: COMPETITIVE
Actor:  Charlie stole 5 food from Alice
Effect on trust:    actor's trust of target changed by -0.15
Effect on hostility: target's hostility of actor changed by +0.20

=== ACTOR: Charlie ===
Vitals:  energy=28, food=7 (after transfer), health=65
Mood: desperate
Impression of Alice: trust=0.28, hostility=0.45, past interactions=2

Relevant memories about Alice:
- [tick 39] WAS_ATTACKED: Alice blocked my path to the food patch at (3,3)

=== TARGET: Alice ===
Vitals:  energy=55, food=22 (after transfer), health=100
Mood: anxious
Impression of Charlie: trust=0.22, hostility=0.55, past interactions=2

=== WORLD CONTEXT ===
Location: (3, 4)
Tick: 61

Now write the dialog between Charlie and Alice.
```

**Expected output:**

```json
{
  "exchanges": [
    { "speaker": "Charlie", "line": "You had plenty. I was dying." },
    { "speaker": "Alice",   "line": "Get away from me, Charlie. That was mine." },
    { "speaker": "Charlie", "line": "Survival isn't polite." }
  ],
  "summary": "Charlie makes a desperate, unapologetic theft; Alice's simmering antagonism toward Charlie deepens."
}
```

---

## Fallback Behavior

If the LLM response is unavailable or unparseable, the Java layer should fall back to the existing static log line:

```
[Charlie] competed with [Alice], stole 5 food
```

or for cooperative:

```
[Alice] cooperated with [Bob], shared 10 food
```

---

## Notes for Java Implementation

- The `mood` field written by the **Decision Agent** into NPC state (as `lastMood`) feeds directly into this prompt — wire these two agents together.
- Use a **richer model** here than for decisions: `gpt-4o` or `claude-sonnet-3-5` produce noticeably better dialog. Haiku/mini models tend to be generic.
- Use `max_tokens: 300` — 2–4 short lines fit comfortably.
- The system prompt is static and long — prefix caching (OpenAI / Anthropic) applies, reducing cost significantly.
- For high-frequency simulations, throttle to generate dialog only once every 3–5 interactions per NPC pair, reusing the last generated exchange with minor variation otherwise.
- The `summary` field is useful for injecting back into the NPC's `Memory` as a `MemoryEvent` description, closing the loop between AI output and the simulation state.
