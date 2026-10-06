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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import uk.gov.dbt.ndtp.jena.abac.AttributeValueSet;
import uk.gov.dbt.ndtp.jena.abac.Hierarchy;
import uk.gov.dbt.ndtp.jena.abac.attributes.Attribute;
import uk.gov.dbt.ndtp.jena.abac.attributes.AttributeValue;
import uk.gov.dbt.ndtp.jena.abac.attributes.ValueTerm;
import uk.gov.dbt.ndtp.jena.abac.lib.CxtABAC;
import uk.gov.dbt.ndtp.jena.abac.opa.transport.OpaRequest;
import uk.gov.dbt.ndtp.jena.abac.opa.transport.OpaResponse;
import uk.gov.dbt.ndtp.jena.abac.opa.transport.OpaTransport;

/**
 * What {@link OpaDecisionServiceProvider} actually sends to the transport (the SAG request
 * contract), and what {@link OpaRequest} guarantees about its contents.
 */
public class TestOpaDecisionServiceProviderRequest {

    private static class CapturingTransport implements OpaTransport {
        volatile OpaRequest request;
        volatile Duration connectTimeout;
        volatile Duration readTimeout;
        volatile OpaResponse response = new OpaResponse(Set.of("public"), Map.of());

        @Override
        public OpaResponse call(OpaRequest request, Duration connectTimeout, Duration readTimeout) {
            this.request = request;
            this.connectTimeout = connectTimeout;
            this.readTimeout = readTimeout;
            return response;
        }
    }

    private static DecisionContext ctx(String org) {
        AttributeValueSet attributes = AttributeValueSet.of(List.of(
                AttributeValue.of(Attribute.create("permitted_organisations"), ValueTerm.value("Org1")),
                AttributeValue.of(Attribute.create("employee"), ValueTerm.TRUE)));
        CxtABAC cxt = CxtABAC.context(attributes, Hierarchy.noHierarchy, null);
        return new DecisionContext(cxt, "user-1", "query", org, "ds1");
    }

    private static OpaDecisionServiceProvider provider(OpaTransport transport) {
        return new OpaDecisionServiceProvider(transport, Duration.ofMillis(1500), Duration.ofMillis(2500));
    }

    @Test
    void decide_sendsContextVocabularyAndRawSubjectAttributes() {
        CapturingTransport transport = new CapturingTransport();

        provider(transport).decide(ctx("org-a"), Set.of("public", "secret"));

        OpaRequest sent = transport.request;
        assertNotNull(sent);
        assertEquals("user-1", sent.subjectId());
        assertEquals("query", sent.action());
        assertEquals("org-a", sent.organisationId());
        assertEquals("ds1", sent.datasetName());
        assertEquals(Set.of("public", "secret"), sent.vocabulary());
        assertEquals("Org1", sent.subjectAttributes().get("permitted_organisations"));
        assertTrue(sent.subjectAttributes().containsKey("employee"));
        assertEquals(2, sent.subjectAttributes().size());
    }

    @Test
    void decide_nullOrganisation_isForwardedAsNull() {
        CapturingTransport transport = new CapturingTransport();
        provider(transport).decide(ctx(null), Set.of("public"));
        assertNull(transport.request.organisationId());
    }

    @Test
    void decide_passesConfiguredTimeoutsToTransport() {
        CapturingTransport transport = new CapturingTransport();
        provider(transport).decide(ctx("org-a"), Set.of("public"));
        assertEquals(Duration.ofMillis(1500), transport.connectTimeout);
        assertEquals(Duration.ofMillis(2500), transport.readTimeout);
    }

    @Test
    void decide_propagatesAuditMetadataFromResponse() {
        CapturingTransport transport = new CapturingTransport();
        transport.response = new OpaResponse(Set.of("public"), Map.of("policyId", "v7"));
        DecisionResult result = provider(transport).decide(ctx("org-a"), Set.of("public"));
        assertEquals(Map.of("policyId", "v7"), result.auditMetadata());
    }

    @Test
    void constructorAndDecide_rejectNullArguments() {
        OpaTransport transport = new CapturingTransport();
        Duration d = Duration.ofSeconds(1);
        assertThrows(NullPointerException.class, () -> new OpaDecisionServiceProvider(null, d, d));
        assertThrows(NullPointerException.class, () -> new OpaDecisionServiceProvider(transport, null, d));
        assertThrows(NullPointerException.class, () -> new OpaDecisionServiceProvider(transport, d, null));
        OpaDecisionServiceProvider provider = provider(transport);
        assertThrows(NullPointerException.class, () -> provider.decide(null, Set.of("public")));
        assertThrows(NullPointerException.class, () -> provider.decide(ctx("org-a"), null));
    }

    @Test
    void opaRequest_copiesItsInputsAndExposesImmutableViews() {
        Set<String> vocabulary = new HashSet<>(Set.of("public"));
        Map<String, String> attributes = new HashMap<>(Map.of("employee", "true"));

        OpaRequest request = OpaRequest.of(ctx("org-a"), vocabulary, attributes);
        vocabulary.add("secret");
        attributes.put("clearance", "high");

        assertEquals(Set.of("public"), request.vocabulary());
        assertEquals(Map.of("employee", "true"), request.subjectAttributes());
        assertThrows(UnsupportedOperationException.class, () -> request.vocabulary().add("x"));
        assertThrows(UnsupportedOperationException.class, () -> request.subjectAttributes().put("x", "y"));
    }
}
