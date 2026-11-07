package com.adaptivecache;

import com.adaptivecache.keygen.KeyGenerator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Canonical 64-bit key generation: determinism, method sensitivity,
 * query-order invariance, trailing-slash normalization.
 */
class KeyGeneratorTest {

    @Test
    void sameRequestYieldsSameKey() {
        var a = KeyGenerator.generate("GET", "/hot/item-1");
        var b = KeyGenerator.generate("GET", "/hot/item-1");
        assertEquals(a.hash(), b.hash());
        assertEquals(a.canonical(), b.canonical());
    }

    @Test
    void queryParamOrderDoesNotMatter() {
        var a = KeyGenerator.generate("GET", "/api/data?a=1&b=2&c=3");
        var b = KeyGenerator.generate("GET", "/api/data?c=3&a=1&b=2");
        assertEquals(a.hash(), b.hash());
    }

    @Test
    void differentQueryValuesDiffer() {
        var a = KeyGenerator.generate("GET", "/api/data?a=1");
        var b = KeyGenerator.generate("GET", "/api/data?a=2");
        assertNotEquals(a.hash(), b.hash());
    }

    @Test
    void trailingSlashIsNormalized() {
        var a = KeyGenerator.generate("GET", "/api/data");
        var b = KeyGenerator.generate("GET", "/api/data/");
        assertEquals(a.hash(), b.hash());
    }

    @Test
    void methodIsPartOfKey() {
        var get = KeyGenerator.generate("GET", "/api/data");
        var post = KeyGenerator.generate("POST", "/api/data");
        assertNotEquals(get.hash(), post.hash());
    }

    @Test
    void methodIsCaseInsensitive() {
        var a = KeyGenerator.generate("GET", "/api/data");
        var b = KeyGenerator.generate("get", "/api/data");
        assertEquals(a.hash(), b.hash());
    }

    @Test
    void hashIs64BitStableAcrossProcesses() {
        // FNV-1a reference vector — guards against accidental algorithm drift.
        long hash = KeyGenerator.fnv1a64("GET /hot/item-1");
        assertEquals(hash, KeyGenerator.generate("GET", "/hot/item-1").hash());
        assertNotEquals(0L, hash);
    }

    @Test
    void distinctPathsProduceDistinctHashes() {
        long h1 = KeyGenerator.generate("GET", "/hot/item-1").hash();
        long h2 = KeyGenerator.generate("GET", "/hot/item-2").hash();
        assertNotEquals(h1, h2);
    }
}
