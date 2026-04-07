package com.aiworld.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

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

    private static final Logger log = LoggerFactory.getLogger(StrategyValidator.class);

    // Derived from the enum — adding a new Strategy.Type automatically becomes valid here
    private static final Set<String> VALID_TYPES = Arrays.stream(Strategy.Type.values())
        .map(Enum::name)
        .collect(Collectors.toUnmodifiableSet());

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
            log.warn("No 'strategy' key in: {}", abbreviated(text));
            return null;
        }

        int braceStart = text.lastIndexOf('{', strategyKeyIdx);
        if (braceStart == -1) {
            log.warn("Could not find opening brace in: {}", abbreviated(text));
            return null;
        }
        // Find matching closing brace, accounting for nested braces
        int depth = 0, braceEnd = -1;
        for (int i = braceStart; i < text.length(); i++) {
            if (text.charAt(i) == '{') depth++;
            else if (text.charAt(i) == '}') { depth--; if (depth == 0) { braceEnd = i; break; } }
        }
        if (braceEnd == -1) {
            log.warn("Could not find closing brace in: {}", abbreviated(text));
            return null;
        }

        String json = text.substring(braceStart, braceEnd + 1);

        String strategyRaw = extractField(json, "strategy");
        String intent      = extractField(json, "intent");
        String reason      = extractField(json, "reason");

        if (strategyRaw == null) {
            log.warn("Missing 'strategy' value in: {}", json);
            return null;
        }

        String strategyNorm = strategyRaw.trim().toUpperCase();

        if (!VALID_TYPES.contains(strategyNorm)) {
            log.warn("Unknown strategy type '{}'. Valid: {}", strategyNorm, VALID_TYPES);
            return null;
        }

        Strategy.Type type = Strategy.Type.valueOf(strategyNorm);
        return new Strategy(
            type,
            intent != null ? intent.trim() : "",
            reason != null ? reason.trim() : "",
            currentTick
        );
    }

    // ── Helpers ───────────────────────────────────────────────────────

    /**
     * Extracts a JSON string field value, correctly handling escape sequences
     * (\" \\ \n) without any pre-processing tricks.
     */
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
            }
            if (c == '"') break;
            value.append(c);
            i++;
        }
        if (i >= json.length()) return null;  // unterminated string — malformed JSON
        return value.toString();
    }

    private static String abbreviated(String s) {
        return s.length() > 120 ? s.substring(0, 120) + "..." : s;
    }
}
