package com.aiworld.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * LLM client that calls the Anthropic Claude API.
 *
 * Uses {@code java.net.http.HttpClient} (Java 11+) — no external libraries needed.
 * Set the {@code ANTHROPIC_API_KEY} environment variable before starting the simulation.
 *
 * Usage:
 * <pre>
 *   npc.setLLMClient(ClaudeClient.fromEnv());
 * </pre>
 *
 * Model defaults to claude-haiku for low latency. Override with the
 * single-argument constructor to use a different model ID.
 */
public class ClaudeClient implements LLMClient {

    private static final Logger log = LoggerFactory.getLogger(ClaudeClient.class);

    private static final String API_URL                  = "https://api.anthropic.com/v1/messages";
    private static final String DEFAULT_MODEL            = "claude-haiku-4-5-20251001";
    private static final int    MAX_TOKENS               = 250;
    private static final int    MAX_CONSECUTIVE_FAILURES = 3;
    private static final long   CIRCUIT_BREAKER_MS       = 60_000; // 60 seconds
    private static final String SYSTEM_PROMPT            = "Respond with raw JSON only. No markdown, no prose, no explanation.";

    private final String     apiKey;
    private final String     model;
    private final HttpClient http;

    // [LLM-1] consecutiveFailures is a plain int guarded by synchronized recordFailure/recordSuccess.
    // The previous AtomicInteger + non-atomic compound (increment → check → write circuitOpenUntilMs
    // → compareAndSet) allowed two concurrent callers (per-NPC LLM executor + dialog-worker) to both
    // observe count >= threshold, both open the circuit, but only one reset the counter — leaving the
    // other at a stale value that triggered spurious circuit opens on every subsequent failure.
    // circuitOpenUntilMs remains volatile so isCircuitOpen() can read it without holding the lock
    // (fast-path check that never writes).
    private int          consecutiveFailures = 0;
    private volatile long circuitOpenUntilMs  = 0;

    public ClaudeClient(String apiKey) {
        this(apiKey, DEFAULT_MODEL);
    }

    public ClaudeClient(String apiKey, String model) {
        this.apiKey = apiKey;
        this.model  = model;
        this.http   = HttpClient.newBuilder()
                                .connectTimeout(Duration.ofSeconds(10))
                                .build();
    }

    /**
     * Constructs a ClaudeClient using the {@code ANTHROPIC_API_KEY} environment variable.
     * Throws {@link IllegalStateException} if the variable is not set.
     */
    public static ClaudeClient fromEnv() {
        String key = System.getenv("ANTHROPIC_API_KEY");
        if (key == null || key.isBlank()) {
            throw new IllegalStateException(
                "ANTHROPIC_API_KEY environment variable is not set.");
        }
        return new ClaudeClient(key);
    }

    @Override
    public Strategy call(String prompt, long currentTick) {
        if (isCircuitOpen()) return null;
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(API_URL))
                .header("Content-Type", "application/json")
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .timeout(Duration.ofSeconds(20))
                .POST(HttpRequest.BodyPublishers.ofString(buildRequestBody(prompt)))
                .build();

            HttpResponse<String> response =
                http.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.warn("API error {}: {}", response.statusCode(), abbreviated(response.body()));
                recordFailure();
                return null;
            }

            String text = extractRawText(response.body());
            if (text == null) {
                log.warn("Could not extract text from response.");
                recordFailure();
                return null;
            }

            Strategy strategy = StrategyValidator.parse(text, currentTick);
            if (strategy == null) {
                log.warn("StrategyValidator rejected: {}", abbreviated(text));
                recordFailure();
                return null;
            }
            recordSuccess();
            return strategy;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            recordFailure();
            return null;
        } catch (Exception e) {
            log.warn("Request failed: {}", e.getMessage());
            recordFailure();
            return null;
        }
    }

    @Override
    public String callRaw(String prompt) {
        if (isCircuitOpen()) return null;
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(API_URL))
                .header("Content-Type", "application/json")
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .timeout(Duration.ofSeconds(20))
                .POST(HttpRequest.BodyPublishers.ofString(buildRequestBody(prompt)))
                .build();

            HttpResponse<String> response =
                http.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.warn("callRaw API error {}", response.statusCode());
                recordFailure();
                return null;
            }
            String text = extractRawText(response.body());
            if (text == null) { recordFailure(); return null; }
            recordSuccess();
            return text;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            recordFailure();
            return null;
        } catch (Exception e) {
            log.warn("callRaw failed: {}", e.getMessage());
            recordFailure();
            return null;
        }
    }

    // ── Circuit breaker ───────────────────────────────────────────────

    private boolean isCircuitOpen() {
        if (System.currentTimeMillis() < circuitOpenUntilMs) {
            log.warn("Circuit open — skipping LLM call (API recovering)");
            return true;
        }
        return false;
    }

    // [LLM-1] Both methods synchronized on `this` to make the check-and-act atomic.
    // Without synchronization, two concurrent callers (per-NPC strategy executor +
    // dialog-worker) could both observe count >= threshold and both open the circuit,
    // but only one compareAndSet would succeed — leaving the counter at a stale value
    // and causing every subsequent failure to redundantly reopen the circuit.
    private synchronized void recordFailure() {
        consecutiveFailures++;
        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
            circuitOpenUntilMs = System.currentTimeMillis() + CIRCUIT_BREAKER_MS;
            log.warn("{} consecutive failures — circuit open for {}s",
                consecutiveFailures, CIRCUIT_BREAKER_MS / 1000);
            consecutiveFailures = 0;
        }
    }

    private synchronized void recordSuccess() {
        consecutiveFailures = 0;
    }

    // ── Private helpers ───────────────────────────────────────────────

    private String buildRequestBody(String prompt) {
        // Escape all JSON-special characters in the prompt.
        // Order matters: backslash must be escaped first to avoid double-escaping.
        String escaped = prompt
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
            .replace("\f", "\\f")
            .replace("\b", "\\b");

        // Strip remaining ASCII control characters (U+0000–U+001F) that are
        // illegal unescaped in JSON strings and extremely unlikely to appear in prompts.
        // Also escape Unicode surrogate characters (U+D800–U+DFFF) which are illegal bare
        // in JSON even though Java strings can contain them.
        StringBuilder sb = new StringBuilder(escaped.length());
        for (int i = 0; i < escaped.length(); i++) {
            char c = escaped.charAt(i);
            if (c >= 0xD800 && c <= 0xDFFF) {
                // Escape lone or paired surrogates as JSON unicode escapes (e.g. \\ud800)
                sb.append(String.format("\\u%04x", (int) c));
            } else if (c >= 0x20) {
                sb.append(c);  // normal printable character (backslash is 0x5C, already >= 0x20)
            }
            // else: drop control character (< 0x20, already handled by replacements above)
        }
        escaped = sb.toString();

        return "{"
            + "\"model\":\"" + model + "\","
            + "\"max_tokens\":" + MAX_TOKENS + ","
            + "\"system\":\"" + SYSTEM_PROMPT + "\","
            + "\"messages\":[{\"role\":\"user\",\"content\":\"" + escaped + "\"}]"
            + "}";
    }

    /** Extracts the raw text content from a Claude API response body. */
    private String extractRawText(String body) {
        int textIdx = body.indexOf("\"text\":");
        if (textIdx == -1) return null;
        int quoteStart = body.indexOf('"', textIdx + 7);
        if (quoteStart == -1) return null;
        StringBuilder value = new StringBuilder();
        int i = quoteStart + 1;
        while (i < body.length()) {
            char c = body.charAt(i);
            if (c == '\\' && i + 1 < body.length()) {
                char next = body.charAt(i + 1);
                if (next == '"')  { value.append('"');  i += 2; continue; }
                if (next == '\\') { value.append('\\'); i += 2; continue; }
                if (next == 'n')  { value.append('\n'); i += 2; continue; }
                if (next == 'r')  { value.append('\r'); i += 2; continue; }
                if (next == 't')  { value.append('\t'); i += 2; continue; }
                if (next == 'b')  { value.append('\b'); i += 2; continue; }
                if (next == 'f')  { value.append('\f'); i += 2; continue; }
                if (next == 'u' && i + 5 < body.length()) {
                    String hex = body.substring(i + 2, i + 6);
                    try { value.append((char) Integer.parseInt(hex, 16)); i += 6; continue; }
                    catch (NumberFormatException ignored) {}
                }
            }
            if (c == '"') break;
            value.append(c);
            i++;
        }
        if (i >= body.length()) return null;
        return value.toString();
    }

    private static String abbreviated(String s) {
        return s != null && s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }
}
