package com.aiworld.npc;

import com.aiworld.action.DialogAction;
import com.aiworld.action.DialogTask;
import com.aiworld.core.World;
import com.aiworld.decision.DecisionEngine;
import com.aiworld.llm.LLMClient;
import com.aiworld.llm.StrategyManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

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

    private static final Logger log = LoggerFactory.getLogger(AbstractNPC.class);

    protected final String         id;
    protected final NPCState       state;
    protected       GoalSystem     goalSystem;  // mutable: reconfigureGoals() may replace it pre-start
    protected final Memory         memory;
    protected final DecisionEngine decisionEngine;

    /** Raw LLM client — stored so DialogAction can access it directly. */
    private LLMClient llmClient;

    private final DialogAction dialogAction = new DialogAction();

    /** Optional LLM strategic layer. Null until {@link #setLLMClient} is called. */
    private StrategyManager strategyManager;

    /** Rolling log of the last 20 actions taken, with tick numbers. */
    private final Deque<String> actionLog = new ArrayDeque<>();
    private static final int MAX_ACTION_LOG = 20;

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
     *  2. Goal per-tick state update (loneliness/boredom counters)
     *  3. LLM strategic planning (if LLM client is set — skipped otherwise)
     *  4. Pre-update hook (subclass perception/communication)
     *  5. Decision engine selects and executes action (always runs)
     *  6. Memory maintenance (impression decay)
     *  7. Post-update hook
     *
     * Note: dialog is no longer triggered here. {@link World#tick()} calls
     * {@link #prepareDialogTask(World)} after all NPC updates, runs the HTTP
     * calls outside the world lock, then applies results in a second pass.
     * This prevents LLM I/O from blocking the world lock for 20 seconds.
     */
    public final void update(World world) {
        if (state.isDead()) return;

        state.passiveTick();                                  // 1. metabolism
        if (state.isDead()) {
            log.info("[{}] has died from starvation at tick {}", id, world.getCurrentTick());
            // Cancel any in-flight LLM strategy call — avoids wasting API quota
            // and writing a new strategy to a dead NPC's state.
            if (strategyManager != null) strategyManager.cancelPendingCall();
            return;
        }
        goalSystem.tickAll(state, world.getCurrentTick());    // 2. goal tick
        if (strategyManager != null) {
            strategyManager.tick(this, world);                // 3. LLM strategy layer
        }
        onPreUpdate(world);                                   // 4. perception hook
        decisionEngine.decide(this, world);                   // 5. act
        memory.decayImpressions();                            // 6. social drift
        onPostUpdate(world);                                  // 7. logging hook
    }

    /**
     * Phase 1 of the dialog pipeline — called by {@link World#tick()} inside
     * the synchronized block after all NPC updates are complete.
     *
     * Checks dialog preconditions and builds the LLM prompt from the current
     * world snapshot. Returns a {@link DialogTask} ready for HTTP execution,
     * or {@code null} if dialog should not happen this tick.
     */
    public DialogTask prepareDialogTask(World world) {
        if (llmClient == null) return null;
        if (state.isDead()) return null;   // NPC may have died from starvation this tick
        return dialogAction.prepare(this, world);
    }

    /**
     * Called on the listener NPC by {@link com.aiworld.action.DialogTask#applyResult}
     * so the listener's dialog cooldown is updated after participating in a conversation.
     */
    public void markDialogCompleted(long tick) {
        dialogAction.markCompleted(tick);
    }

    /**
     * Attaches an LLM client to this NPC, enabling strategic planning and dialog.
     * Shuts down any existing StrategyManager executor before replacing it to
     * prevent thread leaks when this method is called more than once.
     */
    public void setLLMClient(LLMClient client) {
        if (this.strategyManager != null) this.strategyManager.shutdown();
        this.llmClient       = client;
        this.strategyManager = new StrategyManager(client, this.id);
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

    /** Records an action taken this tick into the rolling action log. */
    public void recordAction(long tick, String actionName) {
        if (actionLog.size() >= MAX_ACTION_LOG) actionLog.pollFirst();
        actionLog.addLast("[T" + tick + "] " + actionName);
    }

    /** Returns the action log as an ordered list (oldest first). */
    public List<String> getActionLog() {
        return Collections.unmodifiableList(new ArrayList<>(actionLog));
    }

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
