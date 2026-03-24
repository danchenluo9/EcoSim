package com.aiworld.npc;

import com.aiworld.core.World;
import com.aiworld.decision.DecisionEngine;
import com.aiworld.action.DialogAction;
import com.aiworld.llm.LLMClient;
import com.aiworld.llm.StrategyManager;

/**
 * AbstractNPC is the base class for all NPC agents in the simulation.
 *
 * Subclasses can override {@link #onPreUpdate} and {@link #onPostUpdate}
 * to inject specialized behavior (e.g., a LeaderNPC that broadcasts
 * goals to followers before taking its own action).
 *
 * The three core systems — state, goals, memory — are wired together here,
 * with the decision engine orchestrating them each tick.
 *
 * An optional LLM strategic layer can be attached via {@link #setLLMClient}.
 * When present, {@link StrategyManager} runs before the DecisionEngine each
 * tick and updates the NPC's high-level {@link com.aiworld.llm.Strategy},
 * which biases — but does NOT replace — the rule-based action selection.
 */
public abstract class AbstractNPC {

    protected final String         id;
    protected final NPCState       state;
    protected final GoalSystem     goalSystem;
    protected final Memory         memory;
    protected final DecisionEngine decisionEngine;

    /** Raw LLM client — stored so DialogAction can access it directly. */
    private LLMClient llmClient;

    private final DialogAction dialogAction = new DialogAction();

    /** Optional LLM strategic layer. Null until {@link #setLLMClient} is called. */
    private StrategyManager strategyManager;

    protected AbstractNPC(String id, NPCState state,
                          GoalSystem goalSystem, Memory memory) {
        this.id             = id;
        this.state          = state;
        this.goalSystem     = goalSystem;
        this.memory         = memory;
        this.decisionEngine = new DecisionEngine();
    }

    /**
     * Main update entry point called by {@link World} each tick.
     *
     * Sequence:
     *  1. Passive state effects (metabolism, aging)
     *  2. Goal weight updates
     *  3. LLM strategic planning (if LLM client is set — skipped otherwise)
     *  4. Pre-update hook (subclass perception/communication)
     *  5. Decision engine selects and executes action (always runs)
     *  6. Dialog (secondary action — only if LLM client is set and conditions allow)
     *  7. Memory maintenance (impression decay)
     *  8. Post-update hook
     */
    public final void update(World world) {
        if (state.isDead()) return;

        state.passiveTick();                                  // 1. metabolism
        goalSystem.updateAll(state, world.getCurrentTick());  // 2. goal updates
        if (strategyManager != null) {
            strategyManager.tick(this, world);                // 3. LLM strategy layer
        }
        onPreUpdate(world);                                   // 4. perception hook
        decisionEngine.decide(this, world);                   // 5. act
        if (llmClient != null) {
            dialogAction.tryExecute(this, world);             // 6. secondary dialog
        }
        memory.decayImpressions();                            // 7. social drift
        onPostUpdate(world);                                  // 8. logging hook
    }

    /**
     * Attaches an LLM client to this NPC, enabling strategic planning.
     * Can be called at any time (including mid-simulation).
     *
     * <pre>
     *   npc.setLLMClient(new MockLLMClient());          // for testing
     *   npc.setLLMClient(ClaudeClient.fromEnv());       // live API
     * </pre>
     */
    public void setLLMClient(LLMClient client) {
        this.llmClient       = client;
        this.strategyManager = new StrategyManager(client);
    }

    /**
     * Called before decision-making each tick.
     * Override to perceive the environment, update beliefs, or
     * communicate with group members.
     */
    protected void onPreUpdate(World world) { /* default: no-op */ }

    /**
     * Called after action execution each tick.
     * Override to emit events, update group state, or log telemetry.
     */
    protected void onPostUpdate(World world) { /* default: no-op */ }

    /** Returns the NPC's archetype label (e.g., "Forager", "Leader"). */
    public abstract String getArchetype();

    // ── Accessors ────────────────────────────────────────────────────

    public String          getId()              { return id; }
    public NPCState        getState()           { return state; }
    public GoalSystem      getGoalSystem()      { return goalSystem; }
    public Memory          getMemory()          { return memory; }
    public LLMClient       getLLMClient()       { return llmClient; }
    public StrategyManager getStrategyManager() { return strategyManager; }

    @Override
    public String toString() {
        return "[" + getArchetype() + ":" + id + "] " + state;
    }
}
