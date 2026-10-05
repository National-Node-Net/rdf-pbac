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
package uk.gov.dbt.ndtp.jena.abac.lib;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.Triple;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.sparql.core.DatasetGraphFactory;
import org.apache.jena.sparql.core.Quad;
import org.apache.jena.system.Txn;
import org.junit.jupiter.api.Test;
import uk.gov.dbt.ndtp.jena.abac.ABAC;
import uk.gov.dbt.ndtp.jena.abac.AttributeValueSet;
import uk.gov.dbt.ndtp.jena.abac.Hierarchy;
import uk.gov.dbt.ndtp.jena.abac.labels.Labels;
import uk.gov.dbt.ndtp.jena.abac.labels.LabelsStore;
import uk.gov.dbt.ndtp.jena.abac.opa.DecisionContext;
import uk.gov.dbt.ndtp.jena.abac.opa.DecisionResult;
import uk.gov.dbt.ndtp.jena.abac.opa.DecisionServiceProvider;

/**
 * Behavioural tests for {@link OpaDatasetFilterProvider}: the returned view must contain
 * exactly the quads whose labels the decision service permitted, and the decision service
 * must be handed the right vocabulary and request context.
 */
public class TestOpaDatasetFilterProviderFiltering {

    private static final Node G = NodeFactory.createURI("http://example/g");
    private static final String DEFAULT_LABEL = "dflt";

    private static Triple triple(String name) {
        return Triple.create(NodeFactory.createURI("http://example/" + name),
                             NodeFactory.createURI("http://example/p"),
                             NodeFactory.createLiteralString("v"));
    }

    private static Quad quad(Triple t) {
        return Quad.create(G, t);
    }

    private static CxtABAC ctx(DatasetGraph dsg) {
        CxtABAC cxt = CxtABAC.context(AttributeValueSet.of(List.of()), Hierarchy.noHierarchy, dsg);
        cxt.subjectId("test-user");
        cxt.action("query");
        cxt.organisationId("org-1");
        cxt.datasetName("test-dataset");
        return cxt;
    }

    private static DecisionServiceProvider permitting(String... labels) {
        return (context, vocabulary) -> new DecisionResult(Set.of(labels), Map.of());
    }

    private static Set<Triple> visibleTriples(DatasetGraph view) {
        return Txn.calculateRead(view, () -> {
            Set<Triple> found = new java.util.HashSet<>();
            view.find().forEachRemaining(q -> found.add(q.asTriple()));
            return found;
        });
    }

    /** t-public: "public"; t-secret: "secret"; t-none: no label (default applies); t-both: public+secret. */
    private static class Fixture {
        final Triple tPublic = triple("t-public");
        final Triple tSecret = triple("t-secret");
        final Triple tNone = triple("t-none");
        final Triple tBoth = triple("t-both");
        final LabelsStore store = Labels.createLabelsStoreMem();
        final DatasetGraph base = DatasetGraphFactory.createTxnMem();
        final DatasetGraphABAC dsgz;

        Fixture() {
            for ( Triple t : List.of(tPublic, tSecret, tNone, tBoth) )
                base.add(quad(t));
            store.add(tPublic, "public");
            store.add(tSecret, "secret");
            store.add(tBoth, List.of("public", "secret"));
            dsgz = ABAC.authzDataset(base, store, DEFAULT_LABEL, null);
        }
    }

    @Test
    void view_containsOnlyQuadsWithPermittedLabels() {
        Fixture f = new Fixture();
        OpaDatasetFilterProvider provider = new OpaDatasetFilterProvider(permitting("public"));

        DatasetGraph view = provider.filterDataset(f.dsgz, ctx(f.dsgz.getData()));

        assertEquals(Set.of(f.tPublic), visibleTriples(view));
    }

    @Test
    void view_unlabelledData_isVisibleOnlyWhenDefaultLabelIsPermitted() {
        Fixture f = new Fixture();

        DatasetGraph withoutDefault = new OpaDatasetFilterProvider(permitting("public"))
                .filterDataset(f.dsgz, ctx(f.dsgz.getData()));
        DatasetGraph withDefault = new OpaDatasetFilterProvider(permitting("public", DEFAULT_LABEL))
                .filterDataset(f.dsgz, ctx(f.dsgz.getData()));

        assertFalse(visibleTriples(withoutDefault).contains(f.tNone));
        assertEquals(Set.of(f.tPublic, f.tNone), visibleTriples(withDefault));
    }

    @Test
    void view_tripleWithSeveralLabels_needsAllOfThemPermitted() {
        Fixture f = new Fixture();

        DatasetGraph onlyPublic = new OpaDatasetFilterProvider(permitting("public"))
                .filterDataset(f.dsgz, ctx(f.dsgz.getData()));
        DatasetGraph both = new OpaDatasetFilterProvider(permitting("public", "secret"))
                .filterDataset(f.dsgz, ctx(f.dsgz.getData()));

        assertFalse(visibleTriples(onlyPublic).contains(f.tBoth));
        assertTrue(visibleTriples(both).contains(f.tBoth));
        assertTrue(visibleTriples(both).contains(f.tSecret));
    }

    @Test
    void decisionService_receivesStoreLabelsPlusDefaultAsVocabulary() {
        Fixture f = new Fixture();
        AtomicReference<Set<String>> seen = new AtomicReference<>();
        DecisionServiceProvider capturing = (context, vocabulary) -> {
            seen.set(vocabulary);
            return new DecisionResult(Set.of("public"), Map.of());
        };

        new OpaDatasetFilterProvider(capturing).filterDataset(f.dsgz, ctx(f.dsgz.getData()));

        assertEquals(Set.of("public", "secret", DEFAULT_LABEL), seen.get());
    }

    @Test
    void decisionService_receivesRequestContextFromCxt() {
        Fixture f = new Fixture();
        AtomicReference<DecisionContext> seen = new AtomicReference<>();
        DecisionServiceProvider capturing = (context, vocabulary) -> {
            seen.set(context);
            return new DecisionResult(Set.of("public"), Map.of());
        };

        new OpaDatasetFilterProvider(capturing).filterDataset(f.dsgz, ctx(f.dsgz.getData()));

        DecisionContext dc = seen.get();
        assertEquals("test-user", dc.subjectId());
        assertEquals("query", dc.action());
        assertEquals("org-1", dc.organisationId());
        assertEquals("test-dataset", dc.datasetName());
    }

    @Test
    void rawParts_view_isFilteredTheSameWay() {
        Fixture f = new Fixture();
        OpaDatasetFilterProvider provider = new OpaDatasetFilterProvider(permitting("secret"));

        DatasetGraph view = provider.filterDataset(f.base, f.store, DEFAULT_LABEL, ctx(f.base));

        assertEquals(Set.of(f.tSecret), visibleTriples(view));
    }

    @Test
    void rawParts_noLabelsStore_leavesDataUnfiltered() {
        Fixture f = new Fixture();
        OpaDatasetFilterProvider provider = new OpaDatasetFilterProvider(permitting("anything"));

        DatasetGraph view = provider.filterDataset(f.base, null, DEFAULT_LABEL, ctx(f.base));

        assertEquals(Set.of(f.tPublic, f.tSecret, f.tNone, f.tBoth), visibleTriples(view));
    }

    @Test
    void decisionServiceFailure_propagates_andNoViewIsReturned() {
        Fixture f = new Fixture();
        DecisionServiceProvider failing = (context, vocabulary) -> {
            throw new uk.gov.dbt.ndtp.jena.abac.opa.DecisionServiceUnavailableException("down");
        };
        OpaDatasetFilterProvider provider = new OpaDatasetFilterProvider(failing);
        CxtABAC cxt = ctx(f.dsgz.getData());

        assertThrows(uk.gov.dbt.ndtp.jena.abac.opa.DecisionServiceUnavailableException.class,
                () -> provider.filterDataset(f.dsgz, cxt));
    }
}
