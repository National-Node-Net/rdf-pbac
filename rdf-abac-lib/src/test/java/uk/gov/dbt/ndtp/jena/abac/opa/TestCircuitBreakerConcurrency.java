// SPDX-License-Identifier: Apache-2.0
// Originally developed by Telicent Ltd.; subsequently adapted, enhanced, and maintained by the National Digital Twin Programme.
/*
 *  Copyright (c) Telicent Ltd.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
/*
 *  Modifications made by the National Digital Twin Programme (NDTP)
 *  © Crown Copyright 2025. This work has been developed by the National Digital Twin Programme
 *  and is legally attributed to the Department for Business and Trade (UK) as the governing entity.
 */
package uk.gov.dbt.ndtp.jena.abac.opa;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import uk.gov.dbt.ndtp.jena.abac.opa.resilience.CircuitBreaker;

/**
 * {@link CircuitBreaker} is shared by every request thread in Fuseki, so its state
 * transitions must not lose updates or throw under contention.
 */
public class TestCircuitBreakerConcurrency {

    private static final int THREADS = 16;

    /** Runs the task on THREADS threads released at the same instant; fails on any exception. */
    private static <T> List<T> runConcurrently(Callable<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            CountDownLatch ready = new CountDownLatch(THREADS);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for ( int i = 0 ; i < THREADS ; i++ ) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return task.call();
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            go.countDown();
            List<T> results = new ArrayList<>();
            for ( Future<T> f : futures )
                results.add(f.get(10, TimeUnit.SECONDS));
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentFailuresAtThreshold_openTheCircuit() throws Exception {
        CircuitBreaker cb = new CircuitBreaker(THREADS, Duration.ofMinutes(10));

        runConcurrently(() -> {
            cb.recordFailure();
            return null;
        });

        assertEquals(CircuitBreaker.State.OPEN, cb.state());
        assertFalse(cb.allowRequest());
    }

    @Test
    void concurrentFailuresBelowThreshold_noUpdateIsLost_thenOneMoreOpens() throws Exception {
        CircuitBreaker cb = new CircuitBreaker(THREADS + 1, Duration.ofMinutes(10));

        runConcurrently(() -> {
            cb.recordFailure();
            return null;
        });
        assertEquals(CircuitBreaker.State.CLOSED, cb.state(), "THREADS failures are still below the threshold");

        cb.recordFailure();   // fails only if every one of the concurrent failures was counted
        assertEquals(CircuitBreaker.State.OPEN, cb.state());
    }

    @Test
    void openCircuit_rejectsEveryConcurrentRequest() throws Exception {
        CircuitBreaker cb = new CircuitBreaker(1, Duration.ofMinutes(10));
        cb.recordFailure();

        List<Boolean> allowed = runConcurrently(cb::allowRequest);

        assertEquals(THREADS, allowed.size());
        assertTrue(allowed.stream().noneMatch(Boolean::booleanValue));
    }

    @Test
    void closedCircuit_allowsEveryConcurrentRequest() throws Exception {
        CircuitBreaker cb = new CircuitBreaker(5, Duration.ofSeconds(30));

        List<Boolean> allowed = runConcurrently(cb::allowRequest);

        assertTrue(allowed.stream().allMatch(Boolean::booleanValue));
    }

    @Test
    void mixedSuccessAndFailure_neverThrows_andEndsInAValidState() throws Exception {
        CircuitBreaker cb = new CircuitBreaker(3, Duration.ofMillis(5));
        java.util.concurrent.atomic.AtomicInteger n = new java.util.concurrent.atomic.AtomicInteger();

        runConcurrently(() -> {
            for ( int i = 0 ; i < 500 ; i++ ) {
                cb.allowRequest();
                if ( n.incrementAndGet() % 3 == 0 )
                    cb.recordSuccess();
                else
                    cb.recordFailure();
            }
            return null;
        });

        assertNotNull(cb.state());
    }
}
