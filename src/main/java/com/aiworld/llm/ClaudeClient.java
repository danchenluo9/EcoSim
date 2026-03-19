package com.aiworld.llm;

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

    private static final String API_URL       = "https://api.anthropic.com/v1/messages";
    private static final String DEFAULT_MODEL = "claude-haiku-4-5-20251001";
    private static final int    MAX_TOKENS    = 250;

    private final String     apiKey;
    private final String     model;
    private final HttpClient http;

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
                System.err.printf("[ClaudeClient] API error %d: %s%n",
                    response.statusCode(), abbreviated(response.body()));
                return null;
            }

            String text = extractRawText(response.body());
            if (text == null) {
                System.err.println("[ClaudeClient] Could not extract text from response.");
                return null;
            }

            Strategy strategy = StrategyValidator.parse(text, currentTick);
            if (strategy == null) {
                System.err.println("[ClaudeClient] StrategyValidator rejected: " + abbreviated(text));
            }
            return strategy;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("[ClaudeClient] Request interrupted.");
            return null;
        } catch (Exception e) {
            System.err.println("[ClaudeClient] Request failed: " + e.getMessage());
            return null;
        }
    }

    @Override
    public String callRaw(String prompt) {
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
                System.err.printf("[ClaudeClient] API error %d%n", response.statusCode());
                return null;
            }
            return extractRawText(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            System.err.println("[ClaudeClient] callRaw failed: " + e.getMessage());
            return null;
        }
    }

    // ── Private helpers ───────────────────────────────────────────────

    private String buildRequestBody(String prompt) {
        // Escape the prompt string for embedding in JSON
        String escaped = prompt
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "");

        return "{"
            + "\"model\":\"" + model + "\","
            + "\"max_tokens\":" + MAX_TOKENS + ","
            + "\"messages\":[{\"role\":\"user\",\"content\":\"" + escaped + "\"}]"
            + "}";
    }

    /** Extracts the raw text content from a Claude API response body. */
    private String extractRawText(String body) {
        int textIdx = body.indexOf("\"text\":");
        if (textIdx == -1) return null;
        int quoteStart = body.indexOf('"', textIdx + 7);
        if (quoteStart == -1) return null;
        int quoteEnd = quoteStart + 1;
        while (quoteEnd < body.length()) {
            if (body.charAt(quoteEnd) == '"' && body.charAt(quoteEnd - 1) != '\\') break;
            quoteEnd++;
        }
        if (quoteEnd >= body.length()) return null;
        return body.substring(quoteStart + 1, quoteEnd)
                   .replace("\\\"", "\"")
                   .replace("\\n", "\n");
    }

    private static String abbreviated(String s) {
        return s != null && s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }
}
