package com.aiworld.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * LLM client that calls a real backend and records every response to disk.
 *
 * Each NPC gets two JSONL files under {@code dataDir}:
 *   <npcName>_strategy.jsonl  — one strategy JSON per line
 *   <npcName>_dialog.jsonl    — one dialog JSON per line
 *
 * Recorded files can be replayed offline with {@link PlaybackLLMClient}.
 *
 * Usage (set ECOSIM_LLM_MODE=record before starting):
 * <pre>
 *   ECOSIM_LLM_MODE=record mvn exec:java
 * </pre>
 */
public class RecordingLLMClient implements LLMClient {

    private static final Logger log = LoggerFactory.getLogger(RecordingLLMClient.class);

    private final LLMClient delegate;
    private final String    npcName;
    private final Path      strategyFile;
    private final Path      dialogFile;

    public RecordingLLMClient(LLMClient delegate, String npcName, String dataDir) {
        this.delegate = delegate;
        this.npcName  = npcName;
        Path dir = Path.of(dataDir);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            log.warn("[RECORD] Cannot create dir {}: {}", dir, e.getMessage());
        }
        this.strategyFile = dir.resolve(npcName + "_strategy.jsonl");
        this.dialogFile   = dir.resolve(npcName + "_dialog.jsonl");
        // Truncate existing files so each record run produces a clean snapshot.
        // Appending across runs would mix responses from different NPC states.
        truncate(strategyFile);
        truncate(dialogFile);
        log.info("[RECORD] {} → {}", npcName, dir.toAbsolutePath());
    }

    @Override
    public Strategy call(String prompt, long currentTick) {
        Strategy s = delegate.call(prompt, currentTick);
        if (s != null) {
            appendLine(strategyFile, toStrategyJson(s));
        }
        return s;
    }

    @Override
    public String callRaw(String prompt) {
        String raw = delegate.callRaw(prompt);
        if (raw != null) {
            // Strip newlines so each response occupies exactly one line
            appendLine(dialogFile, raw.replace("\r", "").replace("\n", " "));
        }
        return raw;
    }

    // ── Helpers ───────────────────────────────────────────────────────

    /** Serialises a Strategy back to the JSON format StrategyValidator expects. */
    private String toStrategyJson(Strategy s) {
        return "{\"strategy\":\"" + s.getType().name() + "\","
             + "\"intent\":\""   + escape(s.getIntent()) + "\","
             + "\"reason\":\""   + escape(s.getReason()) + "\"}";
    }

    private String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private void truncate(Path file) {
        try {
            Files.writeString(file, "", StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            log.warn("[RECORD] {} failed to truncate {}: {}", npcName, file.getFileName(), e.getMessage());
        }
    }

    private void appendLine(Path file, String line) {
        try {
            Files.writeString(file, line + "\n",
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.warn("[RECORD] {} failed to write to {}: {}", npcName, file.getFileName(), e.getMessage());
        }
    }
}
