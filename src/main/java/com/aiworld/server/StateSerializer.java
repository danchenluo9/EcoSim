package com.aiworld.server;

import com.aiworld.core.World;
import com.aiworld.core.WorldLoop;
import com.aiworld.dialog.DialogSession;
import com.aiworld.llm.Strategy;
import com.aiworld.llm.StrategyManager;
import com.aiworld.model.MemoryEvent;
import com.aiworld.model.NPCImpression;
import com.aiworld.model.Resource;
import com.aiworld.npc.AbstractNPC;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Serializes world state to JSON without external dependencies.
 * Called by WorldServer on every /api/state request.
 */
public class StateSerializer {

    public static String serialize(World world, WorldLoop loop) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"tick\":").append(world.getCurrentTick()).append(",");
        sb.append("\"running\":").append(loop.isRunning()).append(",");
        sb.append("\"paused\":").append(loop.isPaused()).append(",");
        sb.append("\"width\":").append(world.getWidth()).append(",");
        sb.append("\"height\":").append(world.getHeight()).append(",");

        // NPCs — live first, then dead (dead appear at the end with reduced opacity in frontend)
        sb.append("\"npcs\":[");
        List<AbstractNPC> npcs = new ArrayList<>(world.getNpcs());
        npcs.addAll(world.getDeadNpcs());
        for (int i = 0; i < npcs.size(); i++) {
            if (i > 0) sb.append(",");
            appendNPC(sb, npcs.get(i));
        }
        sb.append("],");

        // Resources
        sb.append("\"resources\":[");
        List<Resource> resources = new ArrayList<>(world.getResources());
        for (int i = 0; i < resources.size(); i++) {
            if (i > 0) sb.append(",");
            appendResource(sb, resources.get(i));
        }
        sb.append("]");

        sb.append("}");
        return sb.toString();
    }

    private static void appendNPC(StringBuilder sb, AbstractNPC npc) {
        sb.append("{");
        sb.append("\"id\":\"").append(esc(npc.getId())).append("\",");
        sb.append("\"archetype\":\"").append(esc(npc.getArchetype())).append("\",");
        sb.append("\"x\":").append(npc.getState().getLocation().getX()).append(",");
        sb.append("\"y\":").append(npc.getState().getLocation().getY()).append(",");
        sb.append("\"health\":").append(npc.getState().getHealth()).append(",");
        sb.append("\"maxHealth\":").append(npc.getState().getMaxHealth()).append(",");
        sb.append("\"food\":").append(npc.getState().getFood()).append(",");
        sb.append("\"maxFood\":").append(npc.getState().getMaxFood()).append(",");
        sb.append("\"energy\":").append(npc.getState().getEnergy()).append(",");
        sb.append("\"maxEnergy\":").append(npc.getState().getMaxEnergy()).append(",");
        sb.append("\"age\":").append(npc.getState().getAge()).append(",");
        sb.append("\"dead\":").append(npc.getState().isDead()).append(",");

        // Strategy
        StrategyManager sm = npc.getStrategyManager();
        if (sm != null) {
            Strategy s = sm.getCurrentStrategy();
            sb.append("\"strategy\":\"").append(s.getType().name()).append("\",");
            sb.append("\"strategyIntent\":\"").append(esc(s.getIntent())).append("\",");
            sb.append("\"strategyReason\":\"").append(esc(s.getReason())).append("\",");
        } else {
            sb.append("\"strategy\":\"\",");
            sb.append("\"strategyIntent\":\"\",");
            sb.append("\"strategyReason\":\"\",");
        }

        // Recent memory events (last 5) — iterate tail of deque without copying all 50
        sb.append("\"recentEvents\":[");
        Deque<MemoryEvent> allEvents = npc.getMemory().getAllEvents();
        ArrayDeque<MemoryEvent> recent = new ArrayDeque<>(5);
        Iterator<MemoryEvent> descIt = allEvents.descendingIterator();
        int count = 0;
        while (descIt.hasNext() && count++ < 5) recent.addFirst(descIt.next());
        boolean firstEvent = true;
        for (MemoryEvent e : recent) {
            if (!firstEvent) sb.append(",");
            firstEvent = false;
            sb.append("{");
            sb.append("\"tick\":").append(e.getTick()).append(",");
            sb.append("\"type\":\"").append(e.getType().name()).append("\",");
            sb.append("\"description\":\"").append(esc(e.getDescription())).append("\"");
            sb.append("}");
        }
        sb.append("],");

        // Action log (last 20, most recent last)
        sb.append("\"actionLog\":[");
        List<String> actionLog = npc.getActionLog();
        for (int i = 0; i < actionLog.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(esc(actionLog.get(i))).append("\"");
        }
        sb.append("],");

        // Recent conversations (last 5)
        sb.append("\"conversations\":[");
        List<DialogSession> convs = npc.getMemory().getRecentConversations(5);
        for (int i = 0; i < convs.size(); i++) {
            if (i > 0) sb.append(",");
            DialogSession c = convs.get(i);
            sb.append("{");
            sb.append("\"tick\":").append(c.getTick()).append(",");
            sb.append("\"speakerId\":\"").append(esc(c.getSpeakerId())).append("\",");
            sb.append("\"listenerId\":\"").append(esc(c.getListenerId())).append("\",");
            sb.append("\"speakerLine\":\"").append(esc(c.getSpeakerLine())).append("\",");
            sb.append("\"listenerLine\":\"").append(esc(c.getListenerLine())).append("\",");
            sb.append("\"valence\":").append(String.format("%.2f", c.getValence()));
            sb.append("}");
        }
        sb.append("],");

        // Impressions
        sb.append("\"impressions\":[");
        Map<String, NPCImpression> impressions = npc.getMemory().getAllImpressions();
        boolean first = true;
        for (Map.Entry<String, NPCImpression> entry : impressions.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            NPCImpression imp = entry.getValue();
            sb.append("{");
            sb.append("\"npcId\":\"").append(esc(entry.getKey())).append("\",");
            sb.append("\"trust\":").append(String.format("%.2f", imp.getTrust())).append(",");
            sb.append("\"hostility\":").append(String.format("%.2f", imp.getHostility())).append(",");
            sb.append("\"interactions\":").append(imp.getInteractionCount());
            sb.append("}");
        }
        sb.append("]");

        sb.append("}");
    }

    private static void appendResource(StringBuilder sb, Resource r) {
        sb.append("{");
        sb.append("\"type\":\"").append(r.getType().name()).append("\",");
        sb.append("\"x\":").append(r.getLocation().getX()).append(",");
        sb.append("\"y\":").append(r.getLocation().getY()).append(",");
        sb.append("\"quantity\":").append(r.getQuantity()).append(",");
        sb.append("\"maxQuantity\":").append(r.getMaxQuantity()).append(",");
        sb.append("\"depleted\":").append(r.isDepleted());
        sb.append("}");
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")
                .replace("\b", "\\b")
                .replace("\f", "\\f");
    }
}
