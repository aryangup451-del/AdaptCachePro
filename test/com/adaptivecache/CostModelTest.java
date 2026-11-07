package com.adaptivecache;

import com.adaptivecache.stats.CostModel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cost model: exact per-serve accounting, deck aggregate formula and the
 * percentage comparison against a no-cache baseline.
 */
class CostModelTest {

    @Test
    void exactSavingsAccumulatePerServe() {
        CostModel model = new CostModel(0.08, 0.00002, 64 * 1024);
        model.recordCacheServe(1024); // compute + egress(1024B)
        model.recordCacheServe(1024);

        CostModel.Report r = model.report(10, 0.2);
        double expectedPerServe = 0.00002 + 1024 * 0.08 / 1e9;
        assertEquals(2 * expectedPerServe, r.exactSavingsUsd(), 1e-12);
        assertEquals(2, r.cacheServedRequests());
        assertEquals(2048, r.cacheServedBytes());
    }

    @Test
    void aggregateFormulaMatchesDeckEquation() {
        CostModel model = new CostModel(0.08, 0.00002, 64 * 1024);
        // Total 1000 requests, 60% hit ratio.
        CostModel.Report r = model.report(1000, 0.6);
        double unitCost = 0.00002 + (64 * 1024 * 0.08 / 1e9);
        assertEquals(1000 * 0.6 * unitCost, r.aggregateSavingsUsd(), 1e-12);
    }

    @Test
    void savingsPercentIsBoundedAndSane() {
        CostModel model = new CostModel(0.08, 0.00002, 64 * 1024);
        model.recordCacheServe(1024);
        CostModel.Report r = model.report(10, 0.1);
        assertTrue(r.savingsPercentOfNoCache() > 0);
        assertTrue(r.savingsPercentOfNoCache() <= 100);
    }

    @Test
    void zeroActivityYieldsZeroSavings() {
        CostModel model = new CostModel(0.08, 0.00002, 64 * 1024);
        CostModel.Report r = model.report(0, 0.0);
        assertEquals(0.0, r.exactSavingsUsd(), 1e-12);
        assertEquals(0.0, r.savingsPercentOfNoCache(), 1e-12);
    }

    @Test
    void higherEgressPriceRaisesSavings() {
        CostModel cheap = new CostModel(0.08, 0.00002, 64 * 1024);
        CostModel pricey = new CostModel(0.12, 0.00002, 64 * 1024);
        cheap.recordCacheServe(1024);
        pricey.recordCacheServe(1024);
        assertTrue(pricey.report(1, 1.0).exactSavingsUsd()
                > cheap.report(1, 1.0).exactSavingsUsd());
    }
}
