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

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import uk.gov.dbt.ndtp.jena.abac.AttributeValueSet;
import uk.gov.dbt.ndtp.jena.abac.Hierarchy;
import uk.gov.dbt.ndtp.jena.abac.lib.CxtABAC;

/**
 * Value-object contracts of {@link DecisionResult} and {@link DecisionContext}:
 * immutability, defensive copies and null handling.
 */
public class TestDecisionModel {

    private static CxtABAC cxt() {
        return CxtABAC.context(AttributeValueSet.of(List.of()), Hierarchy.noHierarchy, null);
    }

    // ---- DecisionResult

    @Test
    void decisionResult_empty_hasNoLabelsAndNoMetadata() {
        DecisionResult result = DecisionResult.empty();
        assertTrue(result.isEmpty());
        assertTrue(result.permittedLabels().isEmpty());
        assertTrue(result.auditMetadata().isEmpty());
    }

    @Test
    void decisionResult_withLabels_isNotEmpty() {
        assertFalse(new DecisionResult(Set.of("public"), Map.of()).isEmpty());
    }

    @Test
    void decisionResult_nullAuditMetadata_becomesEmptyMap() {
        DecisionResult result = new DecisionResult(Set.of("public"), null);
        assertNotNull(result.auditMetadata());
        assertTrue(result.auditMetadata().isEmpty());
    }

    @Test
    void decisionResult_nullPermittedLabels_isRejected() {
        assertThrows(NullPointerException.class, () -> new DecisionResult(null, Map.of()));
    }

    @Test
    void decisionResult_defensivelyCopiesItsInputs() {
        Set<String> labels = new HashSet<>(Set.of("public"));
        Map<String, Object> audit = new HashMap<>(Map.of("policyId", "v1"));

        DecisionResult result = new DecisionResult(labels, audit);
        labels.add("secret");
        audit.put("extra", "x");

        assertEquals(Set.of("public"), result.permittedLabels());
        assertEquals(Map.of("policyId", "v1"), result.auditMetadata());
    }

    @Test
    void decisionResult_exposedCollectionsAreImmutable() {
        DecisionResult result = new DecisionResult(Set.of("public"), Map.of("k", "v"));
        assertThrows(UnsupportedOperationException.class, () -> result.permittedLabels().add("secret"));
        assertThrows(UnsupportedOperationException.class, () -> result.auditMetadata().put("x", "y"));
    }

    // ---- DecisionContext

    @Test
    void decisionContext_exposesAllValues() {
        CxtABAC cxt = cxt();
        DecisionContext ctx = new DecisionContext(cxt, "user-1", "query", "org-a", "ds1");
        assertSame(cxt, ctx.cxt());
        assertEquals("user-1", ctx.subjectId());
        assertEquals("query", ctx.action());
        assertEquals("org-a", ctx.organisationId());
        assertEquals("ds1", ctx.datasetName());
        assertEquals(cxt.requestId(), ctx.requestId());
    }

    @Test
    void decisionContext_organisationIsOptional() {
        DecisionContext ctx = new DecisionContext(cxt(), "user-1", "query", null, "ds1");
        assertNull(ctx.organisationId());
    }

    @Test
    void decisionContext_requiredArgumentsAreRejectedWhenNull() {
        CxtABAC cxt = cxt();
        assertThrows(NullPointerException.class, () -> new DecisionContext(null, "u", "a", "o", "d"));
        assertThrows(NullPointerException.class, () -> new DecisionContext(cxt, null, "a", "o", "d"));
        assertThrows(NullPointerException.class, () -> new DecisionContext(cxt, "u", null, "o", "d"));
        assertThrows(NullPointerException.class, () -> new DecisionContext(cxt, "u", "a", "o", null));
    }
}
