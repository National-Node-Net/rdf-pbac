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
package uk.gov.dbt.ndtp.jena.abac.fuseki;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import uk.gov.dbt.ndtp.jena.abac.AttributeValueSet;
import uk.gov.dbt.ndtp.jena.abac.Hierarchy;
import uk.gov.dbt.ndtp.jena.abac.lib.CxtABAC;
import uk.gov.dbt.ndtp.jena.abac.opa.DecisionContext;
import uk.gov.dbt.ndtp.jena.abac.opa.DecisionResult;
import uk.gov.dbt.ndtp.jena.abac.opa.DecisionServiceProvider;
import uk.gov.dbt.ndtp.jena.abac.opa.DecisionServiceUnavailableException;
import uk.gov.dbt.ndtp.jena.abac.opa.transport.OpaRequest;
import uk.gov.dbt.ndtp.jena.abac.opa.transport.OpaResponse;
import uk.gov.dbt.ndtp.jena.abac.opa.transport.OpaTransport;
import uk.gov.dbt.ndtp.jena.abac.opa.transport.OpaTransportException;

/**
 * The "policy engine enabled" path of {@link FMod_ABAC}: the Caching -> CircuitBreaking -> OPA
 * chain it builds, exercised with a fake transport so no live OPA is needed.
 */
public class TestFModABACDecisionChain {

    private static DecisionContext request() {
        CxtABAC cxt = CxtABAC.context(AttributeValueSet.of(List.of()), Hierarchy.noHierarchy, null);
        return new DecisionContext(cxt, "user-1", "query", "org-a", "ds1");
    }

    private static DecisionContext sameRequestAgain(DecisionContext first) {
        return new DecisionContext(first.cxt(), "user-1", "query", "org-a", "ds1");
    }

    @Test
    void chain_returnsPermittedLabelsFromTransport_intersectedWithVocabulary() {
        OpaTransport transport = (req, connect, read) -> new OpaResponse(Set.of("public", "military"), Map.of());
        DecisionServiceProvider chain = FMod_ABAC.buildDecisionServiceChain(transport);

        DecisionResult result = chain.decide(request(), Set.of("public", "employee"));

        assertEquals(Set.of("public"), result.permittedLabels());
    }

    @Test
    void chain_callsOpaOncePerRequest() {
        AtomicInteger calls = new AtomicInteger();
        OpaTransport transport = (req, connect, read) -> {
            calls.incrementAndGet();
            return new OpaResponse(Set.of("public"), Map.of());
        };
        DecisionServiceProvider chain = FMod_ABAC.buildDecisionServiceChain(transport);
        DecisionContext first = request();

        chain.decide(first, Set.of("public"));
        chain.decide(sameRequestAgain(first), Set.of("public"));

        assertEquals(1, calls.get());
    }

    @Test
    void chain_transportFailure_surfacesAsServiceUnavailable() {
        OpaTransport transport = (req, connect, read) -> {
            throw new OpaTransportException("OPA down");
        };
        DecisionServiceProvider chain = FMod_ABAC.buildDecisionServiceChain(transport);

        assertThrows(DecisionServiceUnavailableException.class, () -> chain.decide(request(), Set.of("public")));
    }

    @Test
    void chain_circuitOpensAfterRepeatedFailures_andStopsHammeringOpa() {
        AtomicInteger calls = new AtomicInteger();
        OpaTransport transport = (req, connect, read) -> {
            calls.incrementAndGet();
            throw new OpaTransportException("OPA down");
        };
        DecisionServiceProvider chain = FMod_ABAC.buildDecisionServiceChain(transport);

        // Each request has its own CxtABAC, so nothing is served from the per-request cache.
        for ( int i = 0 ; i < 10 ; i++ ) {
            assertThrows(DecisionServiceUnavailableException.class, () -> chain.decide(request(), Set.of("public")));
        }

        int callsAfterTenRequests = calls.get();
        assertTrue(callsAfterTenRequests < 10,
                "circuit breaker should have opened and short-circuited later requests, but OPA was called "
                        + callsAfterTenRequests + " times");
        assertTrue(callsAfterTenRequests >= 1);
    }
}
