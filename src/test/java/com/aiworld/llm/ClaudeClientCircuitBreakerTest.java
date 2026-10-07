package com.aiworld.llm;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the ClaudeClient circuit breaker against a local stub HTTP server.
 *
 * No real API calls are made: the client is pointed at an in-process server
 * (com.sun.net.httpserver, built into the JDK) whose status code each test controls.
 * Time is controlled by a fake clock so the 60s open window is tested instantly.
 */
class ClaudeClientCircuitBreakerTest {

    private static final String OK_BODY = "{\"content\":[{\"text\":\"ok\"}]}";

    private HttpServer server;
    private ExecutorService serverThreads;
    private final AtomicInteger hits   = new AtomicInteger();   // requests that reached the server
    private volatile int        status = 500;                    // status the stub returns
    private volatile CountDownLatch holdResponses = null;        // if set, server waits on it before replying

    private final AtomicLong now = new AtomicLong(1_000_000);    // fake clock (ms)
    private ClaudeClient client;

    @BeforeEach
    void startStubServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", exchange -> {
            hits.incrementAndGet();
            CountDownLatch hold = holdResponses;
            if (hold != null) {
                hold.countDown();
                try { hold.await(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
            }
            byte[] body = (status == 200 ? OK_BODY : "{\"error\":\"boom\"}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(body); }
        });
        serverThreads = Executors.newCachedThreadPool();
        server.setExecutor(serverThreads);
        server.start();

        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/messages";
        HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        client = new ClaudeClient("test-key", "test-model", url, http, now::get);
    }

    @AfterEach
    void stopStubServer() {
        server.stop(0);
        serverThreads.shutdownNow();
    }

    @Test
    void twoFailuresDoNotOpenTheCircuit() {
        status = 500;
        assertNull(client.callRaw("p"));
        assertNull(client.callRaw("p"));

        status = 200;
        assertEquals("ok", client.callRaw("p"), "circuit should still be closed after only 2 failures");
        assertEquals(3, hits.get());
    }

    @Test
    void threeConsecutiveFailuresOpenTheCircuitAndSkipNetworkCalls() {
        status = 500;
        for (int i = 0; i < 3; i++) assertNull(client.callRaw("p"));
        assertEquals(3, hits.get());

        status = 200; // even though the API has recovered...
        assertNull(client.callRaw("p"), "...an open circuit must short-circuit to null");
        assertNull(client.call("p", 1), "call() and callRaw() share the same breaker");
        assertEquals(3, hits.get(), "no request may reach the server while the circuit is open");
    }

    @Test
    void circuitStaysOpenFor60SecondsThenCloses() {
        status = 500;
        for (int i = 0; i < 3; i++) client.callRaw("p");

        status = 200;
        now.addAndGet(59_999);
        assertNull(client.callRaw("p"), "still open 1ms before the window ends");
        assertEquals(3, hits.get());

        now.addAndGet(1);
        assertEquals("ok", client.callRaw("p"), "closed exactly at 60s");
        assertEquals(4, hits.get());
    }

    @Test
    void successResetsTheConsecutiveFailureCount() {
        status = 500;
        client.callRaw("p");
        client.callRaw("p");          // 2 failures
        status = 200;
        client.callRaw("p");          // success -> counter back to 0
        status = 500;
        client.callRaw("p");
        client.callRaw("p");          // 2 failures again (not 4)

        status = 200;
        assertEquals("ok", client.callRaw("p"), "failures separated by a success must not accumulate");
        assertEquals(6, hits.get());
    }

    @Test
    void failuresFromCallAndCallRawCountTowardTheSameBreaker() {
        status = 500;
        client.call("p", 1);
        client.callRaw("p");
        client.call("p", 2);   // 3rd failure overall -> open

        status = 200;
        assertNull(client.callRaw("p"));
        assertEquals(3, hits.get());
    }

    /**
     * Regression test for the [LLM-1] race fix.
     *
     * Six requests pass the open-check and then fail at the same moment (the stub
     * holds every response until all six have arrived). Under the old non-atomic
     * increment/check/reset, the counter could be left at a stale non-zero value, so a
     * single later failure would reopen the circuit. With synchronized recordFailure(),
     * 6 failures = exactly two full trips, and the counter must end at 0.
     */
    @Test
    void concurrentFailuresDoNotLeaveAStaleFailureCount() throws Exception {
        status = 500;
        int n = 6;
        holdResponses = new CountDownLatch(n);

        ExecutorService callers = Executors.newFixedThreadPool(n);
        List<Future<String>> results = new ArrayList<>();
        for (int i = 0; i < n; i++) results.add(callers.submit(() -> client.callRaw("p")));
        for (Future<String> f : results) assertNull(f.get(10, TimeUnit.SECONDS));
        callers.shutdown();
        assertEquals(n, hits.get());
        holdResponses = null;

        // Let the open window expire, then fail twice: a correct counter is now 2, not >= 3.
        now.addAndGet(60_000);
        client.callRaw("p");
        client.callRaw("p");

        status = 200;
        assertEquals("ok", client.callRaw("p"),
            "circuit reopened early: concurrent failures left a stale failure count");
    }
}
