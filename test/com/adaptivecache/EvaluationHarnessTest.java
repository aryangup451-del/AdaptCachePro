package com.adaptivecache;

import com.adaptivecache.eval.EvaluationHarness;
import com.adaptivecache.eval.EvaluationHarness.Result;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Evaluation harness smoke test: deterministic head-to-head over a small
 * trace produces four ranked results and a markdown table.
 */
class EvaluationHarnessTest {

    @Test
    void fourPoliciesRankedDeterministically() {
        EvaluationHarness harness = new EvaluationHarness(16, 2000, 100, 7L);
        List<Result> first = harness.run();
        List<Result> second = harness.run();

        assertEquals(4, first.size());
        assertEquals(4, second.size());
        for (int i = 0; i < 4; i++) {
            assertEquals(first.get(i).name(), second.get(i).name());
            assertEquals(first.get(i).hitRatio(),
                    second.get(i).hitRatio(), 0.05);
        }
        for (int i = 1; i < 4; i++) {
            assertTrue(first.get(i - 1).hitRatio()
                    >= first.get(i).hitRatio());
        }
    }

    @Test
    void markdownTableMentionsAllPolicies() {
        EvaluationHarness harness = new EvaluationHarness(16, 2000, 100, 7L);
        String md = harness.markdownTable(harness.run());
        assertTrue(md.contains("priority-lru"));
        assertTrue(md.contains("lfu"));
        assertTrue(md.contains("cacs"));
        assertTrue(md.contains("tinylfu"));
        assertTrue(md.contains("hit ratio"));
    }
}
