package com.aiworld.llm;

import java.util.Set;

/**
 * Parses and validates raw JSON text from the LLM into a {@link Strategy}.
 *
 * Expected model output format:
 * <pre>
 * {
 *   "strategy": "GATHER_FOOD",
 *   "intent":   "Collect food before starvation sets in",
 *   "target":   "",
 *   "reason":   "Food ratio is critically low at 0.12"
 * }
 * </pre>
 *
 * Returns null on any parse or validation failure — callers must fall back
 * to the existing rule-based logic rather than crashing.
 *
 * Uses no external libraries; implemented with simple string operations
 * so it adds zero dependencies to the project.
 */
public class StrategyValidator {

    private static final Set<String> VALID_TYPES = Set.of(
        "GATHER_FOOD", "SEEK_ALLIES", "EXPLORE",
        "AVOID_CONFLICT", "CONSERVE_ENERGY", "SURVIVE"
    );

    /**
     * Parses the model's text output into a validated Strategy.
     *
     * @param text       raw text from the LLM (may contain surrounding prose)
     * @param currentTick tick at which the LLM was called — stamped into the Strategy
     * @return Strategy, or null if text is invalid or strategy type is unrecognised
     */
    public static Strategy parse(String text, long currentTick) {
        if (text == null || text.isBlank()) return null;

        // Find the first JSON object that contains a "strategy" key
        int strategyKeyIdx = text.indexOf("\"strategy\"");
        if (strategyKeyIdx == -1) {
            System.err.println("[StrategyValidator] No 'strategy' key in: " + abbreviated(text));
            return null;
        }

        int braceStart = text.lastIndexOf('{', strategyKeyIdx);
        int braceEnd   = text.indexOf('}',  strategyKeyIdx);
        if (braceStart == -1 || braceEnd == -1) {
            System.err.println("[StrategyValidator] Could not bound JSON object in: " + abbreviated(text));
            return null;
        }

        // Normalise escaped quotes so extractField works cleanly
        String json = text.substring(braceStart, braceEnd + 1)
                          .replace("\\\"", "\u0000")
                          .replace("\\n",  " ");

        String strategyRaw = extractField(json, "strategy");
        String intent      = extractField(json, "intent");
        String target      = extractField(json, "target");
        String reason      = extractField(json, "reason");

        if (strategyRaw == null) {
            System.err.println("[StrategyValidator] Missing 'strategy' value in: " + json);
            return null;
        }

        // Restore any escaped quotes and normalise to upper case
        String strategyNorm = strategyRaw.replace("\u0000", "\"").trim().toUpperCase();

        if (!VALID_TYPES.contains(strategyNorm)) {
            System.err.println("[StrategyValidator] Unknown strategy type '" + strategyNorm
                + "'. Valid: " + VALID_TYPES);
            return null;
        }

        Strategy.Type type = Strategy.Type.valueOf(strategyNorm);
        return new Strategy(
            type,
            restoreQuotes(intent),
            restoreQuotes(target),
            restoreQuotes(reason),
            currentTick
        );
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private static String extractField(String json, String key) {
        String marker   = "\"" + key + "\"";
        int    keyIdx   = json.indexOf(marker);
        if (keyIdx == -1) return null;

        int colonIdx = json.indexOf(':', keyIdx + marker.length());
        if (colonIdx == -1) return null;

        int quoteStart = json.indexOf('"', colonIdx + 1);
        if (quoteStart == -1) return null;

        int quoteEnd = quoteStart + 1;
        while (quoteEnd < json.length() && json.charAt(quoteEnd) != '"') {
            quoteEnd++;
        }
        if (quoteEnd >= json.length()) return null;

        return json.substring(quoteStart + 1, quoteEnd);
    }

    private static String restoreQuotes(String s) {
        return s == null ? "" : s.replace("\u0000", "\"").trim();
    }

    private static String abbreviated(String s) {
        return s.length() > 120 ? s.substring(0, 120) + "..." : s;
    }
}
