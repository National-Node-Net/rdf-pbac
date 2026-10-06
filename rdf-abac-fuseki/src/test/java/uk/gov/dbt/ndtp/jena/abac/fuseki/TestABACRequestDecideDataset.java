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
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.jena.fuseki.server.Endpoint;
import org.apache.jena.fuseki.server.Operation;
import org.apache.jena.fuseki.servlets.ActionErrorException;
import org.apache.jena.fuseki.servlets.HttpAction;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.sparql.core.DatasetGraphFactory;
import org.apache.jena.web.HttpSC;
import org.junit.jupiter.api.Test;
import uk.gov.dbt.ndtp.jena.abac.ABAC;
import uk.gov.dbt.ndtp.jena.abac.AttributeValueSet;
import uk.gov.dbt.ndtp.jena.abac.Hierarchy;
import uk.gov.dbt.ndtp.jena.abac.attributes.Attribute;
import uk.gov.dbt.ndtp.jena.abac.attributes.AttributeValue;
import uk.gov.dbt.ndtp.jena.abac.attributes.ValueTerm;
import uk.gov.dbt.ndtp.jena.abac.labels.Labels;
import uk.gov.dbt.ndtp.jena.abac.labels.LabelsStore;
import uk.gov.dbt.ndtp.jena.abac.lib.AttributesStore;
import uk.gov.dbt.ndtp.jena.abac.lib.CxtABAC;
import uk.gov.dbt.ndtp.jena.abac.lib.DatasetFilterProvider;
import uk.gov.dbt.ndtp.jena.abac.lib.DatasetGraphABAC;

/**
 * {@link ABAC_Request#decideDataset} request handling that sits in front of the filter
 * provider: how the {@link CxtABAC} handed to the provider is populated (SAG-01/03), and
 * which HTTP status each admission failure maps to. The provider-failure mapping (403/503)
 * lives in {@code TestABAC_Request}.
 */
public class TestABACRequestDecideDataset {

    /** AttributesStore that knows one user with the given attributes (null = unknown user). */
    private static class FixedUserStore implements AttributesStore {
        private final String user;
        private final AttributeValueSet attributes;
        FixedUserStore(String user, AttributeValueSet attributes) {
            this.user = user;
            this.attributes = attributes;
        }
        @Override public AttributeValueSet attributes(String u) { return u.equals(user) ? attributes : null; }
        @Override public Set<String> users() { return Set.of(user); }
        @Override public boolean hasHierarchy(Attribute attribute) { return false; }
        @Override public Hierarchy getHierarchy(Attribute attribute) { return null; }
    }

    /** Records the CxtABAC it is asked to filter with, and returns the base dataset. */
    private static class CapturingProvider implements DatasetFilterProvider {
        final AtomicReference<CxtABAC> seen = new AtomicReference<>();
        @Override
        public DatasetGraph filterDataset(DatasetGraphABAC dsgAuthz, CxtABAC cxt) {
            seen.set(cxt);
            return dsgAuthz.getData();
        }
        @Override
        public DatasetGraph filterDataset(DatasetGraph dsgBase, LabelsStore labels, String defaultLabel, CxtABAC cxt) {
            seen.set(cxt);
            return dsgBase;
        }
    }

    private static AttributeValueSet attrs(String... names) {
        return AttributeValueSet.of(List.of(names).stream()
                .map(n -> AttributeValue.of(Attribute.create(n), ValueTerm.TRUE)).toList());
    }

    private static DatasetGraphABAC dataset(String accessAttributes, AttributesStore store, DatasetFilterProvider provider) {
        DatasetGraphABAC dsgz = ABAC.authzDataset(DatasetGraphFactory.createTxnMem(), accessAttributes,
                Labels.createLabelsStoreMem(), "dflt", store);
        dsgz.setFilterProvider(provider);
        return dsgz;
    }

    private static HttpAction mockAction() throws Exception {
        HttpAction action = mock(HttpAction.class);
        java.lang.reflect.Field logField = HttpAction.class.getField("log");
        logField.setAccessible(true);
        logField.set(action, org.slf4j.LoggerFactory.getLogger("test"));
        when(action.getDatasetName()).thenReturn("/ds");
        when(action.getEndpoint()).thenReturn(null);
        when(action.getRequestParameter("debug")).thenReturn(null);
        return action;
    }

    private static int statusOf(HttpAction action, DatasetGraph dsg, String user) {
        ActionErrorException ex = assertThrows(ActionErrorException.class,
                () -> ABAC_Request.decideDataset(action, dsg, a -> user));
        return ex.getRC();
    }

    // ---- context populated for the filter provider

    @Test
    void cxtHandedToProvider_carriesSubjectDatasetActionAndOrganisation() throws Exception {
        CapturingProvider provider = new CapturingProvider();
        AttributeValueSet attributes = AttributeValueSet.of(List.of(
                AttributeValue.of(Attribute.create("permitted_organisations"), ValueTerm.value("Org1"))));
        DatasetGraphABAC dsgz = dataset(null, new FixedUserStore("alice", attributes), provider);

        DatasetGraph result = ABAC_Request.decideDataset(mockAction(), dsgz, a -> "alice");

        assertNotNull(result);
        CxtABAC cxt = provider.seen.get();
        assertNotNull(cxt, "the filter provider must have been invoked");
        assertEquals("alice", cxt.subjectId());
        assertEquals("/ds", cxt.datasetName());
        assertEquals("unknown", cxt.action());
        assertEquals("Org1", cxt.organisationId());
    }

    @Test
    void cxtHandedToProvider_usesFusekiOperationNameAsAction() throws Exception {
        CapturingProvider provider = new CapturingProvider();
        DatasetGraphABAC dsgz = dataset(null, new FixedUserStore("alice", attrs()), provider);
        HttpAction action = mockAction();
        Operation operation = mock(Operation.class);
        when(operation.getName()).thenReturn("query");
        Endpoint endpoint = mock(Endpoint.class);
        when(endpoint.getOperation()).thenReturn(operation);
        when(action.getEndpoint()).thenReturn(endpoint);

        ABAC_Request.decideDataset(action, dsgz, a -> "alice");

        assertEquals("query", provider.seen.get().action());
    }

    // ---- admission failures

    @Test
    void noUser_isRejectedWith400_andProviderIsNotCalled() throws Exception {
        CapturingProvider provider = new CapturingProvider();
        DatasetGraphABAC dsgz = dataset(null, new FixedUserStore("alice", attrs()), provider);

        assertEquals(HttpSC.BAD_REQUEST_400, statusOf(mockAction(), dsgz, null));
        assertNull(provider.seen.get());
    }

    @Test
    void userWithoutAttributes_isRejectedWith403_andProviderIsNotCalled() throws Exception {
        CapturingProvider provider = new CapturingProvider();
        DatasetGraphABAC dsgz = dataset(null, new FixedUserStore("alice", attrs()), provider);

        assertEquals(HttpSC.FORBIDDEN_403, statusOf(mockAction(), dsgz, "mallory"));
        assertNull(provider.seen.get());
    }

    @Test
    void nonAbacDataset_isRejectedWith400() throws Exception {
        assertEquals(HttpSC.BAD_REQUEST_400,
                statusOf(mockAction(), DatasetGraphFactory.createTxnMem(), "alice"));
    }

    @Test
    void accessAttributesNotSatisfied_isRejectedWith403_beforeTheProviderRuns() throws Exception {
        CapturingProvider provider = new CapturingProvider();
        DatasetGraphABAC dsgz = dataset("employee", new FixedUserStore("alice", attrs("visitor")), provider);

        assertEquals(HttpSC.FORBIDDEN_403, statusOf(mockAction(), dsgz, "alice"));
        assertNull(provider.seen.get());
    }

    @Test
    void accessAttributesSatisfied_reachesTheProvider() throws Exception {
        CapturingProvider provider = new CapturingProvider();
        DatasetGraphABAC dsgz = dataset("employee", new FixedUserStore("alice", attrs("employee")), provider);

        assertNotNull(ABAC_Request.decideDataset(mockAction(), dsgz, a -> "alice"));
        assertNotNull(provider.seen.get());
    }
}
