package com.aiworld.action;

import com.aiworld.core.World;
import com.aiworld.dialog.DialogSession;
import com.aiworld.goal.SocialGoal;
import com.aiworld.llm.LLMClient;
import com.aiworld.model.MemoryEvent;
import com.aiworld.npc.AbstractNPC;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * A prepared, not-yet-executed LLM dialog between two NPCs.
 *
 * The three-phase execution model keeps the world lock free during HTTP I/O:
 *
 *  Phase 1 — {@link DialogAction#prepare} (inside world lock)
 *             Checks all preconditions, picks the listener, builds the prompt.
 *             Returns this object if dialog should happen; null otherwise.
 *
 *  Phase 2 — {@link #executeCall()} (outside world lock)
 *             Makes the LLM HTTP call via the per-NPC {@link LLMClient}.
 *             Multiple tasks run concurrently on {@link #EXECUTOR}.
 *             The world lock is free throughout, so the frontend can poll
 *             state without waiting up to 20 seconds for API responses.
 *
 *  Phase 3 — {@link #applyResult(World)} (inside world lock, second brief pass)
 *             Parses the response and applies trust changes, memory events,
 *             and conversation log entries to both NPCs.
 *
 * This mirrors the async pattern used by {@link com.aiworld.llm.StrategyManager}
 * while preserving same-tick immediacy: results land in the tick that triggered
 * them rather than one tick later.
 */
public class DialogTask {

    private static final Logger log = LoggerFactory.getLogger(DialogTask.class);

    /**
     * Shared daemon thread pool for dialog HTTP calls.
     * CachedThreadPool grows on demand and idles down when not needed.
     * In practice bounded by NPC count × dialog cooldown rate (very low).
     *
     * Call {@link #shutdownExecutor()} on simulation end to release threads cleanly.
     * The executor is recreated after each shutdown so that a second simulation run
     * (e.g., test harness or UI restart) can still submit tasks without getting
     * RejectedExecutionException from a terminated pool.
     */
    private static final Object EXECUTOR_LOCK = new Object();
    public static volatile ExecutorService EXECUTOR = newExecutor();

    private static ExecutorService newExecutor() {
        return Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "dialog-worker");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Shuts down the shared dialog executor thread pool and immediately replaces it
     * with a fresh one. Safe to call multiple times — each call drains the current
     * pool and leaves EXECUTOR in a usable state for the next simulation run.
     */
    public static void shutdownExecutor() {
        ExecutorService old;
        synchronized (EXECUTOR_LOCK) {
            old = EXECUTOR;
            EXECUTOR = newExecutor();
        }
        old.shutdown();
        try {
            if (!old.awaitTermination(2, TimeUnit.SECONDS)) {
                old.shutdownNow();
            }
        } catch (InterruptedException e) {
            old.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private static final double TRUST_SCALE = 0.08;

    final  AbstractNPC speaker;
    final  AbstractNPC listener;
    private final String    prompt;
    private final LLMClient client;

    /**
     * The speaker's lastDialogTick value captured BEFORE Phase 1 consumed it.
     * Restored in Phase 3 if the HTTP call fails, so the speaker is not locked
     * out for 15 ticks after a conversation that never happened.
     */
    private final long previousSpeakerDialogTick;

    private String rawResult; // written by executeCall(), read by applyResult()

    DialogTask(AbstractNPC speaker, AbstractNPC listener, String prompt,
               LLMClient client, long previousSpeakerDialogTick) {
        this.speaker                  = speaker;
        this.listener                 = listener;
        this.prompt                   = prompt;
        this.client                   = client;
        this.previousSpeakerDialogTick = previousSpeakerDialogTick;
    }

    // ── Phase 2: HTTP call ────────────────────────────────────────────

    /**
     * Makes the LLM HTTP call. Must be called OUTSIDE the world lock.
     *
     * [Fix 3.2] Any unchecked exception from the LLMClient is caught and treated
     * as a null result rather than propagating through CompletableFuture.allOf().join()
     * in World.tick(). Without this guard, one task throwing causes join() to throw a
     * CompletionException, which skips Phase 3 (applyResult) for ALL dialog tasks that
     * tick — including successful ones — and leaves every speaker's cooldown consumed
     * for a conversation that never landed.
     */
    public void executeCall() {
        try {
            rawResult = client.callRaw(prompt);
        } catch (Exception e) {
            log.warn("[{}] Unexpected exception in callRaw — treating as null result: {}",
                speaker.getId(), e.getMessage());
            rawResult = null;
        }
    }

    // ── Phase 3: apply results ────────────────────────────────────────

    /**
     * Parses the LLM response and updates both NPCs' state and memory.
     * Must be called with the world lock held.
     * Guards against the listener dying while the HTTP call was in-flight.
     */
    public void applyResult(World world) {
        if (rawResult == null) {
            // Revert the speaker's cooldown — Phase 1 consumed it preemptively,
            // but since no conversation happened the speaker should be free to try again.
            // The listener's cooldown was never set, so no revert is needed there.
            speaker.markDialogCompleted(previousSpeakerDialogTick);
            log.warn("[{}] Dialog LLM call failed — speaker cooldown reverted to tick {}",
                speaker.getId(), previousSpeakerDialogTick);
            return;
        }
        // Either participant dying mid-flight means the conversation context is stale.
        // The speaker check mirrors the existing listener check for symmetry.
        if (speaker.getState().isDead()) {
            log.warn("[{}] Speaker died during dialog HTTP call — discarding result",
                speaker.getId());
            return;
        }
        if (listener.getState().isDead()) {
            log.warn("[{}] Listener [{}] died during dialog HTTP call — discarding result",
                speaker.getId(), listener.getId());
            return;
        }

        ParsedDialog parsed = parseDialogJson(rawResult);
        if (parsed == null) {
            // Revert speaker cooldown — HTTP call succeeded but LLM returned unparseable
            // JSON, so no conversation happened. Mirrors the rawResult == null revert above.
            speaker.markDialogCompleted(previousSpeakerDialogTick);
            log.warn("[{}] Dialog JSON parse failed — speaker cooldown reverted to tick {}",
                speaker.getId(), previousSpeakerDialogTick);
            return;
        }

        String speakerLine  = parsed.speakerLine();
        String listenerLine = parsed.listenerLine();
        double valence      = parsed.valence();

        // ── Log conversation ──────────────────────────────────────────
        log.info("  [{} -> {}]", speaker.getId(), listener.getId());
        log.info("  {}: \"{}\"", speaker.getId(),  speakerLine);
        log.info("  {}: \"{}\"", listener.getId(), listenerLine);

        // ── Trust update ──────────────────────────────────────────────
        double delta = valence * TRUST_SCALE;
        if (delta > 0) {
            speaker.getMemory().recordPositiveInteraction(listener.getId(), delta);
            listener.getMemory().recordPositiveInteraction(speaker.getId(), delta);
        } else if (delta < 0) {
            double absDelta = Math.abs(delta);
            speaker.getMemory().recordNegativeInteraction(listener.getId(), absDelta);
            listener.getMemory().recordNegativeInteraction(speaker.getId(), absDelta);
        }

        // ── Memory events ─────────────────────────────────────────────
        long tick = world.getCurrentTick();
        speaker.getMemory().addEvent(new MemoryEvent(tick,
            MemoryEvent.EventType.HAD_CONVERSATION,
            "Talked with " + listener.getId() + ": \"" + speakerLine + "\"",
            speaker.getState().getLocation(), valence, listener.getId()));
        listener.getMemory().addEvent(new MemoryEvent(tick,
            MemoryEvent.EventType.HAD_CONVERSATION,
            "Talked with " + speaker.getId() + ": \"" + listenerLine + "\"",
            listener.getState().getLocation(), valence, speaker.getId()));

        // ── Conversation logs ─────────────────────────────────────────
        DialogSession session = new DialogSession(
            tick, speaker.getId(), listener.getId(),
            speakerLine, listenerLine, valence);
        speaker.getMemory().addConversation(session);
        listener.getMemory().addConversation(session);

        // ── Reset loneliness for both participants ────────────────────
        speaker.getGoalSystem().getGoalByType(SocialGoal.class).ifPresent(SocialGoal::onInteracted);
        listener.getGoalSystem().getGoalByType(SocialGoal.class).ifPresent(SocialGoal::onInteracted);

        // ── Update listener's dialog cooldown ─────────────────────────
        // The speaker's cooldown is set in DialogAction.prepare(); the listener's
        // must be set here so both parties observe the same 15-tick cooldown.
        listener.markDialogCompleted(tick);
    }

    // ── JSON parsing ──────────────────────────────────────────────────

    /** Parsed result of a dialog LLM response. */
    private record ParsedDialog(String speakerLine, String listenerLine, double valence) {}

    /**
     * Parses {"speaker_line":"...","listener_line":"...","valence":0.3}
     * Returns a ParsedDialog or null on failure.
     */
    private static ParsedDialog parseDialogJson(String text) {
        if (text == null || text.isBlank()) return null;
        String speakerLine  = extractField(text, "speaker_line");
        String listenerLine = extractField(text, "listener_line");
        String valenceStr   = extractNumber(text, "valence");
        if (speakerLine == null || listenerLine == null) return null;
        double valence;
        try {
            valence = valenceStr != null ? Double.parseDouble(valenceStr) : 0.0;
            valence = Math.max(-1.0, Math.min(1.0, valence));
        } catch (NumberFormatException e) {
            valence = 0.0;
        }
        return new ParsedDialog(speakerLine, listenerLine, valence);
    }

    private static String extractField(String json, String key) {
        String marker = "\"" + key + "\"";
        int keyIdx = json.indexOf(marker);
        if (keyIdx == -1) return null;
        int colonIdx = json.indexOf(':', keyIdx + marker.length());
        if (colonIdx == -1) return null;
        int quoteStart = json.indexOf('"', colonIdx + 1);
        if (quoteStart == -1) return null;
        StringBuilder value = new StringBuilder();
        int i = quoteStart + 1;
        while (i < json.length()) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                char next = json.charAt(i + 1);
                if (next == '"')  { value.append('"');  i += 2; continue; }
                if (next == '\\') { value.append('\\'); i += 2; continue; }
                if (next == 'n')  { value.append('\n'); i += 2; continue; }
                if (next == 'r')  { value.append('\r'); i += 2; continue; }
                if (next == 't')  { value.append('\t'); i += 2; continue; }
                if (next == 'b')  { value.append('\b'); i += 2; continue; }
                if (next == 'f')  { value.append('\f'); i += 2; continue; }
                if (next == 'u' && i + 5 < json.length()) {
                    String hex = json.substring(i + 2, i + 6);
                    try { value.append((char) Integer.parseInt(hex, 16)); i += 6; continue; }
                    catch (NumberFormatException ignored) {}
                }
            }
            if (c == '"') break;
            value.append(c);
            i++;
        }
        if (i >= json.length()) return null;
        return value.toString();
    }

    private static String extractNumber(String json, String key) {
        String marker = "\"" + key + "\"";
        int keyIdx = json.indexOf(marker);
        if (keyIdx == -1) return null;
        int colonIdx = json.indexOf(':', keyIdx + marker.length());
        if (colonIdx == -1) return null;
        int start = colonIdx + 1;
        while (start < json.length() && json.charAt(start) == ' ') start++;
        int end = start;
        while (end < json.length() && "0123456789.-".indexOf(json.charAt(end)) >= 0) end++;
        if (end == start) return null;
        return json.substring(start, end);
    }
}
