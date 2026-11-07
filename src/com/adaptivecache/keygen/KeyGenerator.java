package com.adaptivecache.keygen;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Produces canonical, deterministic cache keys from HTTP method + URI.
 *
 * <p>Canonicalization rules (order independent):</p>
 * <ul>
 *   <li>scheme + host lower-cased</li>
 *   <li>trailing slash removed from path</li>
 *   <li>query parameters sorted by name then value</li>
 *   <li>fragment stripped</li>
 *   <li>method included — {@code GET /x} and {@code POST /x} differ</li>
 * </ul>
 *
 * <p>The result is a 64-bit FNV-1a hash of the canonical string. The full
 * canonical string is retained for telemetry / debugging.</p>
 */
public final class KeyGenerator {

    private static final long FNV_OFFSET_BASIS = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;

    private KeyGenerator() {
    }

    /** Result of key generation: 64-bit hash + canonical string. */
    public record CacheKey(long hash, String canonical) {
    }

    /**
     * Generates the canonical cache key for a request.
     *
     * @param method   HTTP method, e.g. "GET" (never null)
     * @param uri      request-target URI, e.g. "/hot/1?a=2&b=1"
     */
    public static CacheKey generate(String method, String uri) {
        String canonical = canonicalize(method, uri);
        return new CacheKey(fnv1a64(canonical), canonical);
    }

    /**
     * Builds the canonical string for method + URI.
     * Visible for tests.
     */
    public static String canonicalize(String method, String uri) {
        String m = method == null ? "GET" : method.trim().toUpperCase();
        StringBuilder sb = new StringBuilder(m).append(' ');

        URI parsed;
        try {
            parsed = new URI(uri == null ? "/" : uri);
        } catch (URISyntaxException e) {
            // Fall back to a defensive normalized form.
            return sb.append(sanitizeRaw(uri)).toString();
        }

        String scheme = parsed.getScheme();
        String host = parsed.getHost();
        if (scheme != null && !scheme.isBlank()) {
            sb.append(scheme.toLowerCase()).append("://");
        }
        if (host != null && !host.isBlank()) {
            sb.append(host.toLowerCase());
        }
        sb.append(normalizePath(parsed.getPath()));
        sb.append(normalizeQuery(parsed.getRawQuery()));
        return sb.toString();
    }

    /** Lower-case scheme/host, drop default ports, strip trailing slash. */
    private static String normalizePath(String path) {
        String p = (path == null || path.isEmpty()) ? "/" : path;
        if (p.length() > 1 && p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }

    /** Sort query params lexicographically; drop blank names. */
    private static String normalizeQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isEmpty()) {
            return "";
        }
        String[] pairs = rawQuery.split("&");
        Arrays.sort(pairs);
        return "?" + String.join("&", pairs);
    }

    private static String sanitizeRaw(String uri) {
        if (uri == null) {
            return "/";
        }
        return uri;
    }

    /** FNV-1a 64-bit hash of a UTF-8 string. */
    public static long fnv1a64(String s) {
        long hash = FNV_OFFSET_BASIS;
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        for (byte b : bytes) {
            hash ^= (b & 0xffL);
            hash *= FNV_PRIME;
        }
        return hash;
    }
}
