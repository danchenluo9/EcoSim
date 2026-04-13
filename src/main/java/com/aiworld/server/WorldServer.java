package com.aiworld.server;

import com.aiworld.core.WorldLoop;
import com.aiworld.npc.AbstractNPC;
import com.aiworld.npc.NPC;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.Executors;

/**
 * Embedded HTTP server exposing the simulation state as a REST API.
 *
 * Endpoints:
 *   GET  /api/state          → JSON snapshot of the current world state
 *   POST /api/control        → body: "pause" | "resume" | "stop"
 *
 * Uses only java.net.httpserver (built into the JDK since Java 6).
 * No external dependencies required.
 *
 * The React dev server proxies /api/* to this server on port 8081.
 */
public class WorldServer {

    private static final Logger log = LoggerFactory.getLogger(WorldServer.class);

    private static final int PORT = Integer.parseInt(
        System.getenv().getOrDefault("ECOSIM_PORT", "8081"));

    /** Valid archetype names accepted by POST /api/npc (case-insensitive). */
    private static final Set<String> VALID_ARCHETYPES =
        Set.of("default", "forager", "diplomat", "explorer", "fighter");

    private final WorldLoop loop;
    private HttpServer      server;

    public WorldServer(WorldLoop loop) {
        this.loop = loop;
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.createContext("/api/state",   this::handleState);
        server.createContext("/api/control", this::handleControl);
        server.createContext("/api/npc",     this::handleNpc);
        server.setExecutor(Executors.newFixedThreadPool(2));
        server.start();
        log.info("WorldServer started on http://localhost:{}", PORT);
    }

    public void stop() {
        if (server != null) server.stop(1);
    }

    // ── Handlers ──────────────────────────────────────────────────────

    private void handleState(HttpExchange exchange) throws IOException {
        // [Fix 1.1] CORS headers must be present on every response, including 405 and OPTIONS,
        // so the browser can read error details and preflight requests succeed.
        addCors(exchange);
        if (exchange.getRequestMethod().equalsIgnoreCase("OPTIONS")) {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            return;
        }
        if (!exchange.getRequestMethod().equalsIgnoreCase("GET")) {
            send(exchange, 405, "Method Not Allowed");
            return;
        }
        String json;
        synchronized (loop.getWorld()) {
            json = StateSerializer.serialize(loop.getWorld(), loop);
        }
        send(exchange, 200, json, "application/json");
    }

    private void handleControl(HttpExchange exchange) throws IOException {
        addCors(exchange);

        // Handle CORS preflight
        if (exchange.getRequestMethod().equalsIgnoreCase("OPTIONS")) {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            return;
        }

        if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
            send(exchange, 405, "Method Not Allowed");
            return;
        }

        String body = new String(
            exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8).trim();

        switch (body.toLowerCase()) {
            case "start":
                try {
                    loop.start();
                    send(exchange, 200, "{\"status\":\"started\"}", "application/json");
                } catch (IllegalStateException e) {
                    send(exchange, 400, "{\"error\":\"" + esc(e.getMessage()) + "\"}", "application/json");
                }
                break;
            case "pause":
                loop.pause();
                send(exchange, 200, "{\"status\":\"paused\"}", "application/json");
                break;
            case "resume":
                loop.resume();
                send(exchange, 200, "{\"status\":\"running\"}", "application/json");
                break;
            case "stop":
                loop.stop();
                send(exchange, 200, "{\"status\":\"stopped\"}", "application/json");
                break;
            default:
                send(exchange, 400, "{\"error\":\"unknown action: " + esc(body) + "\"}", "application/json");
        }
    }

    /**
     * POST /api/npc — change an NPC's personality archetype before the simulation starts.
     * Body: {"id":"Alice","archetype":"Fighter"}
     */
    private void handleNpc(HttpExchange exchange) throws IOException {
        addCors(exchange);
        if (exchange.getRequestMethod().equalsIgnoreCase("OPTIONS")) {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            return;
        }
        if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
            send(exchange, 405, "Method Not Allowed");
            return;
        }

        // [Issue 4] Read and validate the body BEFORE acquiring the world lock so that
        // I/O and input validation are never performed while holding the lock.
        String body = new String(
            exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8).trim();
        String id        = extractJsonString(body, "id");
        String archetype = extractJsonString(body, "archetype");

        if (id == null || archetype == null) {
            send(exchange, 400,
                "{\"error\":\"Body must be JSON with 'id' and 'archetype' fields\"}",
                "application/json");
            return;
        }
        if (!VALID_ARCHETYPES.contains(archetype.toLowerCase())) {
            send(exchange, 400,
                "{\"error\":\"Unknown archetype '" + esc(archetype) + "'. Valid: Default, Forager, Diplomat, Explorer, Fighter\"}",
                "application/json");
            return;
        }

        // [Issue 4] The isRunning() check and reconfigureGoals() are now performed
        // atomically under the world lock. Previously, isRunning() was checked before
        // the synchronized block — a concurrent loop.start() between the guard and the
        // lock could allow archetype reconfiguration on a running simulation.
        // I/O (send()) is intentionally kept outside the lock; flags capture the outcome.
        boolean wasRunning = false;
        boolean found      = false;
        synchronized (loop.getWorld()) {
            if (loop.isRunning()) {
                wasRunning = true;
            } else {
                for (AbstractNPC npc : loop.getWorld().getNpcs()) {
                    if (npc.getId().equals(id) && npc instanceof NPC) {
                        ((NPC) npc).reconfigureGoals(archetype);
                        found = true;
                        break;
                    }
                }
            }
        }

        if (wasRunning) {
            send(exchange, 400,
                "{\"error\":\"Cannot change archetype while simulation is running\"}",
                "application/json");
            return;
        }
        if (!found) {
            send(exchange, 404,
                "{\"error\":\"NPC not found: " + esc(id) + "\"}",
                "application/json");
            return;
        }

        send(exchange, 200, "{\"status\":\"ok\"}", "application/json");
    }

    // ── Helpers ───────────────────────────────────────────────────────

    /** Extracts a plain string value from a simple JSON object (no nesting). */
    private static String extractJsonString(String json, String key) {
        String marker = "\"" + key + "\"";
        int keyIdx = json.indexOf(marker);
        if (keyIdx == -1) return null;
        int colonIdx = json.indexOf(':', keyIdx + marker.length());
        if (colonIdx == -1) return null;
        int quoteStart = json.indexOf('"', colonIdx + 1);
        if (quoteStart == -1) return null;
        int quoteEnd = json.indexOf('"', quoteStart + 1);
        if (quoteEnd == -1) return null;
        return json.substring(quoteStart + 1, quoteEnd);
    }

    private static void addCors(HttpExchange ex) {
        ex.getResponseHeaders().add("Access-Control-Allow-Origin",  "*");
        ex.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        ex.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");
    }

    private static void send(HttpExchange ex, int status, String body) throws IOException {
        send(ex, status, body, "text/plain");
    }

    private static void send(HttpExchange ex, int status, String body, String contentType)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", contentType + "; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
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
