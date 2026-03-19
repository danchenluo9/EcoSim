package com.aiworld.llm;

/**
 * Pluggable LLM backend for high-level strategic planning and dialog generation.
 *
 * Implement this interface to swap backends:
 *   - MockLLMClient  : deterministic responses for testing (no API calls)
 *   - ClaudeClient   : live Anthropic API
 */
public interface LLMClient {

    /**
     * Sends an NPC context prompt to the model and returns a parsed Strategy.
     *
     * @param prompt      full context string built by LLMPromptBuilder
     * @param currentTick simulation tick at time of call (stamped into Strategy)
     * @return parsed Strategy, or null if the call fails / output is invalid
     */
    Strategy call(String prompt, long currentTick);

    /**
     * Sends a prompt and returns the raw text response from the model.
     * Used by DialogAction which needs its own JSON format distinct from Strategy.
     *
     * @param prompt full prompt string
     * @return raw model text, or null on failure
     */
    String callRaw(String prompt);
}
