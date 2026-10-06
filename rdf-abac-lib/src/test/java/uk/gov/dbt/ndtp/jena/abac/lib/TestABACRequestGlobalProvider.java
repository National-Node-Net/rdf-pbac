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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.Triple;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.sparql.core.DatasetGraphFactory;
import org.apache.jena.sparql.core.Quad;
import org.apache.jena.system.Txn;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import uk.gov.dbt.ndtp.jena.abac.ABAC;
import uk.gov.dbt.ndtp.jena.abac.AttributeValueSet;
import uk.gov.dbt.ndtp.jena.abac.Hierarchy;
import uk.gov.dbt.ndtp.jena.abac.labels.Labels;
import uk.gov.dbt.ndtp.jena.abac.labels.LabelsStore;
import uk.gov.dbt.ndtp.jena.abac.opa.DecisionResult;
import uk.gov.dbt.ndtp.jena.abac.opa.PolicyDeniedException;

/**
 * Behaviour of {@link ABAC#filterDataset} once a provider is registered through
 * {@link ABACRequest}: what the provider is handed, precedence and isolation between
 * datasets, and error propagation. (Resolution rules themselves are in
 * {@code TestABACRequestResolution}.)
 */
public class TestABACRequestGlobalProvider {

    @AfterEach
    void resetRegistrations() {
        ABACRequest.reset();
    }

    private static CxtABAC ctx(DatasetGraph dsg) {
        return CxtABAC.context(AttributeValueSet.of(List.of()), Hierarchy.noHierarchy, dsg);
    }

    private static DatasetGraphABAC authzDataset(String defaultLabel) {
        return ABAC.authzDataset(DatasetGraphFactory.createTxnMem(), Labels.createLabelsStoreMem(), defaultLabel, null);
    }

    /** Counts calls and records arguments; returns the base dataset unchanged. */
    private static class RecordingProvider implements DatasetFilterProvider {
        final AtomicInteger calls = new AtomicInteger();
        final AtomicReference<DatasetGraphABAC> dsgAuthz = new AtomicReference<>();
        final AtomicReference<CxtABAC> cxt = new AtomicReference<>();
        final AtomicReference<LabelsStore> labels = new AtomicReference<>();
        final AtomicReference<String> defaultLabel = new AtomicReference<>();

        @Override
        public DatasetGraph filterDataset(DatasetGraphABAC d, CxtABAC c) {
            calls.incrementAndGet();
            dsgAuthz.set(d);
            cxt.set(c);
            return d.getData();
        }

        @Override
        public DatasetGraph filterDataset(DatasetGraph b, LabelsStore l, String def, CxtABAC c) {
            calls.incrementAndGet();
            labels.set(l);
            defaultLabel.set(def);
            cxt.set(c);
            return b;
        }
    }

    @Test
    void globalProvider_receivesTheDatasetAndTheRequestContext() {
        RecordingProvider global = new RecordingProvider();
        ABACRequest.setFilterProvider(global);
        DatasetGraphABAC dsgz = authzDataset("dflt");
        CxtABAC cxt = ctx(dsgz.getData());

        ABAC.filterDataset(dsgz, cxt);

        assertEquals(1, global.calls.get());
        assertSame(dsgz, global.dsgAuthz.get());
        assertSame(cxt, global.cxt.get());
    }

    @Test
    void globalProvider_receivesRawPartsUnchanged_inTheNoWrapperOverload() {
        RecordingProvider global = new RecordingProvider();
        ABACRequest.setFilterProvider(global);
        LabelsStore store = Labels.createLabelsStoreMem();
        DatasetGraph base = DatasetGraphFactory.createTxnMem();
        CxtABAC cxt = ctx(base);

        ABAC.filterDataset(base, store, "my-default", cxt);

        assertSame(store, global.labels.get());
        assertEquals("my-default", global.defaultLabel.get());
        assertSame(cxt, global.cxt.get());
    }

    @Test
    void perDatasetProvider_winsOverGlobal_globalIsNotCalledForThatDataset() {
        RecordingProvider global = new RecordingProvider();
        RecordingProvider perDataset = new RecordingProvider();
        ABACRequest.setFilterProvider(global);
        DatasetGraphABAC dsgz = authzDataset("dflt");
        ABACRequest.setDatasetFilterProvider(dsgz, perDataset);

        ABAC.filterDataset(dsgz, ctx(dsgz.getData()));

        assertEquals(1, perDataset.calls.get());
        assertEquals(0, global.calls.get());
    }

    @Test
    void perDatasetProvider_doesNotLeakToOtherDatasets() {
        RecordingProvider global = new RecordingProvider();
        RecordingProvider forA = new RecordingProvider();
        ABACRequest.setFilterProvider(global);
        DatasetGraphABAC a = authzDataset("dflt");
        DatasetGraphABAC b = authzDataset("dflt");
        ABACRequest.setDatasetFilterProvider(a, forA);

        ABAC.filterDataset(b, ctx(b.getData()));

        assertEquals(0, forA.calls.get());
        assertEquals(1, global.calls.get());
        assertSame(b, global.dsgAuthz.get());
    }

    @Test
    void reset_afterRegistering_restoresTheDefaultProvider() {
        ABACRequest.setFilterProvider(new RecordingProvider());
        ABACRequest.reset();
        assertSame(DefaultDatasetFilterProvider.INSTANCE, ABACRequest.getFilterProvider());
        assertSame(DefaultDatasetFilterProvider.INSTANCE, ABACRequest.resolveProvider(authzDataset("dflt")));
    }

    @Test
    void providerException_propagatesUnchangedThroughAbacFilterDataset() {
        PolicyDeniedException denied = new PolicyDeniedException("nope");
        ABACRequest.setFilterProvider(new DatasetFilterProvider() {
            @Override
            public DatasetGraph filterDataset(DatasetGraphABAC d, CxtABAC c) { throw denied; }
            @Override
            public DatasetGraph filterDataset(DatasetGraph b, LabelsStore l, String def, CxtABAC c) { throw denied; }
        });
        DatasetGraphABAC dsgz = authzDataset("dflt");
        CxtABAC cxt = ctx(dsgz.getData());

        assertSame(denied, assertThrows(PolicyDeniedException.class, () -> ABAC.filterDataset(dsgz, cxt)));
    }

    @Test
    void opaProviderRegisteredGlobally_filtersEveryDatasetWithoutAnOverride() {
        ABACRequest.setFilterProvider(new OpaDatasetFilterProvider(
                (context, vocabulary) -> new DecisionResult(Set.of("public"), Map.of())));
        Triple visible = Triple.create(NodeFactory.createURI("http://example/a"),
                                       NodeFactory.createURI("http://example/p"), NodeFactory.createLiteralString("1"));
        Triple hidden = Triple.create(NodeFactory.createURI("http://example/b"),
                                      NodeFactory.createURI("http://example/p"), NodeFactory.createLiteralString("2"));
        for ( int i = 0 ; i < 2 ; i++ ) {
            DatasetGraphABAC dsgz = authzDataset("dflt");
            dsgz.getData().add(Quad.create(NodeFactory.createURI("http://example/g"), visible));
            dsgz.getData().add(Quad.create(NodeFactory.createURI("http://example/g"), hidden));
            dsgz.labelsStore().add(visible, "public");
            dsgz.labelsStore().add(hidden, "secret");
            CxtABAC cxt = ctx(dsgz.getData());
            cxt.subjectId("u");
            cxt.action("query");
            cxt.datasetName("ds");

            DatasetGraph view = ABAC.filterDataset(dsgz, cxt);

            Set<Triple> seen = Txn.calculateRead(view, () -> {
                Set<Triple> s = new java.util.HashSet<>();
                view.find().forEachRemaining(q -> s.add(q.asTriple()));
                return s;
            });
            assertEquals(Set.of(visible), seen, "dataset #" + i);
        }
    }
}
