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

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import uk.gov.dbt.ndtp.jena.abac.AttributeValueSet;
import uk.gov.dbt.ndtp.jena.abac.Hierarchy;
import uk.gov.dbt.ndtp.jena.abac.lib.CxtABAC;
import uk.gov.dbt.ndtp.jena.abac.opa.resilience.CachingDecisionServiceProvider;

/** Per-request caching semantics of {@link CachingDecisionServiceProvider}. */
public class TestCachingDecisionServiceProvider {

    private static CxtABAC newCxt() {
        return CxtABAC.context(AttributeValueSet.of(List.of()), Hierarchy.noHierarchy, null);
    }

    private static DecisionContext ctx(CxtABAC cxt) {
        return new DecisionContext(cxt, "user-1", "read", "org-a", "ds1");
    }

    @Test
    void nullDelegate_isRejected() {
        assertThrows(NullPointerException.class, () -> new CachingDecisionServiceProvider(null));
    }

    @Test
    void firstCall_delegatesAndStoresResultOnTheRequestContext() {
        DecisionResult expected = new DecisionResult(Set.of("public"), java.util.Map.of());
        CachingDecisionServiceProvider caching =
                new CachingDecisionServiceProvider((context, vocabulary) -> expected);
        CxtABAC cxt = newCxt();

        DecisionResult actual = caching.decide(ctx(cxt), Set.of("public"));

        assertSame(expected, actual);
        assertSame(expected, cxt.attachment());
    }

    @Test
    void emptyResult_isCachedToo() {
        AtomicInteger calls = new AtomicInteger();
        CachingDecisionServiceProvider caching = new CachingDecisionServiceProvider((context, vocabulary) -> {
            calls.incrementAndGet();
            return DecisionResult.empty();
        });
        CxtABAC cxt = newCxt();

        caching.decide(ctx(cxt), Set.of("public"));
        caching.decide(ctx(cxt), Set.of("public"));

        assertEquals(1, calls.get(), "a denial must not trigger a second OPA call for the same request");
    }

    @Test
    void delegateFailure_isNotCached_soTheNextCallRetries() {
        AtomicInteger calls = new AtomicInteger();
        CachingDecisionServiceProvider caching = new CachingDecisionServiceProvider((context, vocabulary) -> {
            if ( calls.incrementAndGet() == 1 )
                throw new DecisionServiceUnavailableException("first attempt fails");
            return new DecisionResult(Set.of("public"), java.util.Map.of());
        });
        CxtABAC cxt = newCxt();

        assertThrows(DecisionServiceUnavailableException.class, () -> caching.decide(ctx(cxt), Set.of("public")));
        assertNull(cxt.attachment(), "a failure must leave nothing behind in the request context");

        DecisionResult second = caching.decide(ctx(cxt), Set.of("public"));
        assertEquals(Set.of("public"), second.permittedLabels());
        assertEquals(2, calls.get());
    }

    @Test
    void foreignAttachment_isIgnoredAndReplaced() {
        DecisionResult expected = new DecisionResult(Set.of("public"), java.util.Map.of());
        CachingDecisionServiceProvider caching =
                new CachingDecisionServiceProvider((context, vocabulary) -> expected);
        CxtABAC cxt = newCxt();
        cxt.attachment("something unrelated to OPA");

        assertSame(expected, caching.decide(ctx(cxt), Set.of("public")));
        assertSame(expected, cxt.attachment());
    }
}
