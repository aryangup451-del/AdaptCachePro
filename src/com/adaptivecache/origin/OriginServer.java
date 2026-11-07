package com.adaptivecache.origin;

import com.adaptivecache.config.AdaptCacheConfig;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Origin server simulating a backend API with three workload profiles
 * (deck slide 5, module 1): /hot, /bursty, /oneshot — plus /static content.
 *
 * <p>Supports RFC 7232 conditional requests (ETag / If-None-Match → 304) and
 * exposes a chaos admin endpoint under /_chaos.</p>
 */
public final class OriginServer {

    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ISO_INSTANT;

    private final int port;
    private final ChaosController chaos = new ChaosController();
    private final AtomicLong servedResponses = new AtomicLong();
    private HttpServer server;

    public OriginServer(int port) {
        this.port = port;
    }

    public ChaosController chaos() {
        return chaos;
    }

    public long servedResponses() {
        return servedResponses.get();
    }

    /** Starts the origin server on its configured port. */
    public OriginServer start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", this::handle);
        server.start();
        System.out.println("--------------------------------");
        System.out.println("Origin Server Started");
        System.out.println("Listening on : http://localhost:" + port);
        System.out.println("--------------------------------");
        return this;
    }

    /** Stops the origin server. */
    public void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    public int port() {
        return server == null ? port : server.getAddress().getPort();
    }

    // ------------------------------------------------------------------
    // Request handling
    // ------------------------------------------------------------------

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();

        if (path.startsWith("/_chaos")) {
            handleChaosAdmin(exchange);
            return;
        }

        if (path.startsWith("/missing/")) {
            sendText(exchange, 404, "application/json",
                    ("{\"error\":\"not found\",\"path\":\"" + path + "\"}")
                            .getBytes(StandardCharsets.UTF_8),
                    null, true);
            return;
        }

        // Chaos injection happens before any work.
        int inject = chaos.inject();
        if (inject == -1) {
            // Drop: close without response.
            exchange.close();
            return;
        }
        if (inject == -2) {
            sendText(exchange, 500, "text/plain",
                    ("Origin fault injected: 500 Internal Server Error")
                            .getBytes(StandardCharsets.UTF_8),
                    null, true);
            return;
        }
        if (inject > 0) {
            try {
                Thread.sleep(inject);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                exchange.close();
                return;
            }
        }

        byte[] body = buildBody(path);
        String etag = etagFor(body);

        String inm = exchange.getRequestHeaders().getFirst("If-None-Match");
        if (inm != null && inm.equals(etag)) {
            sendText(exchange, 304, "application/json", new byte[0],
                    etag, false);
            return;
        }

        servedResponses.incrementAndGet();
        sendText(exchange, 200, "application/json", body, etag, true);
    }

    /** Builds deterministic per-path JSON payloads. */
    private byte[] buildBody(String path) {
        String type;
        if (path.startsWith("/hot/")) {
            type = "hot";
        } else if (path.startsWith("/bursty/")) {
            type = "bursty";
        } else if (path.startsWith("/oneshot/")) {
            type = "oneshot";
        } else {
            type = "static";
        }

        String id = path.substring(path.lastIndexOf('/') + 1);
        String ts = TS_FMT.format(Instant.now());
        String payload = ".".repeat(payloadSize(type));

        return ("{\n"
                + "  \"source\": \"origin\",\n"
                + "  \"workload\": \"" + type + "\",\n"
                + "  \"id\": \"" + id + "\",\n"
                + "  \"generatedAt\": \"" + ts + "\",\n"
                + "  \"payload\": \"" + payload + "\"\n"
                + "}").getBytes(StandardCharsets.UTF_8);
    }

    /** Different response sizes per workload profile. */
    private static int payloadSize(String type) {
        return switch (type) {
            case "hot" -> 256;
            case "bursty" -> 1024;
            case "oneshot" -> 64;
            default -> 512;
        };
    }

    private static String etagFor(byte[] body) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(body);
            return "\"" + HexFormat.of().formatHex(digest, 0, 8) + "\"";
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is required by the JDK; unreachable in practice.
            return "\"" + body.hashCode() + "\"";
        }
    }

    // ------------------------------------------------------------------
    // Chaos admin endpoint: GET /_chaos (status), POST /_chaos (set)
    // ------------------------------------------------------------------

    private void handleChaosAdmin(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();

        if ("GET".equalsIgnoreCase(method)) {
            sendText(exchange, 200, "application/json",
                    chaos.statusJson().getBytes(StandardCharsets.UTF_8),
                    null, true);
            return;
        }

        if ("POST".equalsIgnoreCase(method)) {
            Map<String, String> params = parseForm(
                    new String(exchange.getRequestBody().readAllBytes(),
                            StandardCharsets.UTF_8));

            if (params.containsKey("mode")) {
                try {
                    chaos.applyMode(ChaosController.Mode.valueOf(
                            params.get("mode").toUpperCase()));
                } catch (IllegalArgumentException e) {
                    sendText(exchange, 400, "text/plain",
                            ("Bad mode: " + params.get("mode"))
                                    .getBytes(StandardCharsets.UTF_8),
                            null, true);
                    return;
                }
            }
            if (params.containsKey("latencyMs")) {
                chaos.setLatencyMs(Long.parseLong(params.get("latencyMs")));
            }
            if (params.containsKey("failRate")) {
                chaos.setFailRate(Double.parseDouble(params.get("failRate")));
            }
            if (params.containsKey("dropRate")) {
                chaos.setDropRate(Double.parseDouble(params.get("dropRate")));
            }

            sendText(exchange, 200, "application/json",
                    chaos.statusJson().getBytes(StandardCharsets.UTF_8),
                    null, true);
            return;
        }

        exchange.sendResponseHeaders(405, -1);
        exchange.close();
    }

    private static Map<String, String> parseForm(String body) {
        Map<String, String> out = new HashMap<>();
        if (body == null || body.isBlank()) {
            return out;
        }
        for (String pair : body.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String k = URLDecoder.decode(pair.substring(0, eq),
                    StandardCharsets.UTF_8);
            String v = URLDecoder.decode(pair.substring(eq + 1),
                    StandardCharsets.UTF_8);
            out.put(k, v);
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Response helpers
    // ------------------------------------------------------------------

    private static void sendText(HttpExchange exchange, int status,
                                 String contentType, byte[] body,
                                 String etag, boolean withBody)
            throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        if (etag != null) {
            exchange.getResponseHeaders().set("ETag", etag);
        }
        exchange.getResponseHeaders().set("Server", "AdaptCache-Origin/1.0");

        if (withBody) {
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        } else {
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        }
    }

    // ------------------------------------------------------------------
    // Entry points
    // ------------------------------------------------------------------

    /** Standalone launcher (also used by Main in server mode). */
    public static void main(String[] args) throws IOException {
        new OriginServer(AdaptCacheConfig.ORIGIN_PORT).start();
    }
}
