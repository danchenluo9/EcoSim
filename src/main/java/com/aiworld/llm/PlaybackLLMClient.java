package com.aiworld.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * LLM client that replays responses recorded by {@link RecordingLLMClient}.
 *
 * Responses are served round-robin from the per-NPC JSONL files so the
 * simulation runs indefinitely without repeating the exact same response
 * on every tick. Falls back to {@link MockLLMClient} if files are absent.
 *
 * Usage (set ECOSIM_LLM_MODE=playback before starting):
 * <pre>
 *   ECOSIM_LLM_MODE=playback mvn exec:java
 * </pre>
 */
public class PlaybackLLMClient implements LLMClient {

    private static final Logger log = LoggerFactory.getLogger(PlaybackLLMClient.class);

    private final String        npcName;
    private final List<String>  strategyLines;
    private final List<String>  dialogLines;
    private final AtomicInteger strategyIdx = new AtomicInteger(0);
    private final AtomicInteger dialogIdx   = new AtomicInteger(0);
    private final LLMClient     fallback    = new MockLLMClient();

    public PlaybackLLMClient(String npcName, String dataDir) {
        this.npcName       = npcName;
        Path dir           = Path.of(dataDir);
        this.strategyLines = loadLines(dir.resolve(npcName + "_strategy.jsonl"));
        this.dialogLines   = loadLines(dir.resolve(npcName + "_dialog.jsonl"));
        log.info("[PLAYBACK] {} → {} strategy, {} dialog responses loaded",
            npcName, strategyLines.size(), dialogLines.size());
    }

    @Override
    public Strategy call(String prompt, long currentTick) {
        if (strategyLines.isEmpty()) {
            log.warn("[PLAYBACK] {} no strategy recordings — falling back to mock", npcName);
            return fallback.call(prompt, currentTick);
        }
        int      idx  = strategyIdx.getAndIncrement() % strategyLines.size();
        String   line = strategyLines.get(idx);
        Strategy s    = StrategyValidator.parse(line, currentTick);
        if (s == null) {
            log.warn("[PLAYBACK] {} unparseable strategy line #{}: {}", npcName, idx, line);
            return fallback.call(prompt, currentTick);
        }
        return s;
    }

    @Override
    public String callRaw(String prompt) {
        if (dialogLines.isEmpty()) {
            log.warn("[PLAYBACK] {} no dialog recordings — falling back to mock", npcName);
            return fallback.callRaw(prompt);
        }
        int idx = dialogIdx.getAndIncrement() % dialogLines.size();
        return dialogLines.get(idx);
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private List<String> loadLines(Path file) {
        if (!Files.exists(file)) {
            log.warn("[PLAYBACK] Recording not found: {}", file.toAbsolutePath());
            return Collections.emptyList();
        }
        try {
            return Files.readAllLines(file).stream()
                .filter(l -> !l.isBlank())
                .collect(Collectors.toList());
        } catch (IOException e) {
            log.warn("[PLAYBACK] Failed to read {}: {}", file.getFileName(), e.getMessage());
            return Collections.emptyList();
        }
    }
}
