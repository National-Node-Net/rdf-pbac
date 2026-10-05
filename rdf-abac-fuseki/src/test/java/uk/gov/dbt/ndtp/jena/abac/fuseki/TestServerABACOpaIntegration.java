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

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.jena.atlas.json.JSON;
import org.apache.jena.atlas.json.JsonObject;
import org.apache.jena.atlas.lib.FileOps;
import org.apache.jena.fuseki.main.FusekiServer;
import org.apache.jena.fuseki.main.sys.FusekiModules;
import org.apache.jena.fuseki.system.FusekiLogging;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.sparql.exec.RowSetOps;
import org.apache.jena.sparql.exec.http.DSP;
import org.apache.jena.sparql.exec.http.QueryExecHTTPBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uk.gov.dbt.ndtp.jena.abac.lib.DatasetGraphABAC;
import uk.gov.dbt.ndtp.jena.abac.lib.OpaDatasetFilterProvider;
import uk.gov.dbt.ndtp.jena.abac.opa.transport.HttpOpaTransport;

/**
 * End-to-end: a real Fuseki server with the ABAC module, the OPA-backed filter provider wired
 * onto the dataset exactly as {@link FMod_ABAC} does when the policy engine is enabled, and a
 * local HTTP server standing in for OPA. Covers the whole request path:
 * HTTP request -> user resolution -> ABAC_Request -> OPA call -> filtered SPARQL results.
 * <p>
 * Data (src/test/files/server/data-and-labels.trig), triple -> labels:
 * 123 -> level-1; 456 -> manager+level-1; 789 -> manager; 1234 -> manager; 2345 -> engineer;
 * :q "No label" -> unlabelled.
 */
public class TestServerABACOpaIntegration {
    static {
        FusekiLogging.setLogging();
    }

    private static final String DIR = "src/test/files/server/";
    private static final String ALL = "SELECT * { ?s ?p ?o }";

    private HttpServer opa;
    private final List<String> opaRequestBodies = new CopyOnWriteArrayList<>();
    private final AtomicInteger opaCalls = new AtomicInteger();
    private volatile boolean opaFailing = false;
    /** subject_id -> labels OPA will permit; a subject not in the map is denied (allow=false). */
    private volatile Map<String, Set<String>> policy = Map.of();
    private FusekiServer fuseki;

    @BeforeEach
    void startOpa() throws IOException {
        opa = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        opa.createContext("/", this::handleOpa);
        opa.start();
    }

    @AfterEach
    void stopAll() {
        if ( fuseki != null )
            fuseki.stop();
        opa.stop(0);
    }

    private void handleOpa(HttpExchange ex) throws IOException {
        opaCalls.incrementAndGet();
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        opaRequestBodies.add(body);
        int status = 200;
        String response;
        if ( opaFailing ) {
            status = 500;
            response = "{\"error\":\"down\"}";
        } else {
            String subject = JSON.parse(body).get("input").getAsObject().get("subject_id").getAsString().value();
            Set<String> permitted = policy.get(subject);
            if ( permitted == null ) {
                response = "{\"result\":{\"allow\":false}}";
            } else {
                String labels = String.join(",", permitted.stream().map(l -> "\"" + l + "\"").toList());
                response = "{\"result\":{\"allow\":true,\"permitted_labels\":[" + labels + "]}}";
            }
        }
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    /** Fuseki with ABAC module; OPA provider attached to the dataset, pointing at the fake OPA. */
    private String startFusekiWithOpa() {
        fuseki = FusekiServer.create()
                .port(0)
                .fusekiModules(FusekiModules.create(new FMod_ABAC()))
                .parseConfigFile(FileOps.concatPaths(DIR, "config-server.ttl"))
                .build();
        DatasetGraph dsg = fuseki.getDataAccessPointRegistry().get("/ds").getDataService().getDataset();
        DatasetGraphABAC dsgz = (DatasetGraphABAC)dsg;
        URI opaUri = URI.create("http://127.0.0.1:" + opa.getAddress().getPort());
        dsgz.setFilterProvider(new OpaDatasetFilterProvider(
                FMod_ABAC.buildDecisionServiceChain(new HttpOpaTransport(opaUri, "sag/test", Duration.ofSeconds(2)))));
        fuseki.start();
        String url = "http://localhost:" + fuseki.getPort() + "/ds";
        DSP.service(url + "/upload").POST(DIR + "data-and-labels.trig");
        opaCalls.set(0);
        opaRequestBodies.clear();
        return url;
    }

    private static long count(String url, String user, String query) {
        return RowSetOps.count(QueryExecHTTPBuilder.service(url)
                .query(query)
                .httpHeader("Authorization", "Bearer user:" + user)
                .select()
                .rewindable());
    }

    private static int failureStatus(String url, String user) {
        // The SPARQL client wraps the HTTP failure (QueryExceptionHTTP); the status is on the HttpException cause.
        RuntimeException ex = assertThrows(RuntimeException.class, () -> count(url, user, ALL));
        // Jena has more than one HttpException class (atlas.web / http), so look the status up by method.
        for ( Throwable t = ex ; t != null ; t = t.getCause() ) {
            try {
                Object status = t.getClass().getMethod("getStatusCode").invoke(t);
                if ( status instanceof Integer code )
                    return code;
            } catch (ReflectiveOperationException ignored) {
                // this exception in the chain carries no status; try its cause
            }
        }
        return fail("No HTTP status found in exception chain: " + ex);
    }

    // ---- filtering follows what OPA permits, per user

    @Test
    void results_areFilteredByTheLabelsOpaPermitsForEachUser() {
        policy = Map.of("u1", Set.of("manager", "level-1"), "u2", Set.of("engineer"));
        String url = startFusekiWithOpa();

        assertEquals(4, count(url, "u1", ALL));   // 123, 456, 789, 1234
        assertEquals(1, count(url, "u2", ALL));   // 2345
    }

    @Test
    void tripleWithSeveralLabels_isHiddenUnlessAllOfItsLabelsArePermitted() {
        policy = Map.of("u1", Set.of("manager"));
        String url = startFusekiWithOpa();

        // 456 is manager+level-1, 123 is level-1: only 789 and 1234 are manager-only.
        assertEquals(2, count(url, "u1", ALL));
    }

    @Test
    void unlabelledData_isNotVisibleUnlessOpaPermitsTheDefaultLabel() {
        policy = Map.of("u1", Set.of("manager", "level-1", "engineer"));
        String url = startFusekiWithOpa();

        assertEquals(0, count(url, "u1", "PREFIX : <http://example/> SELECT * { ?s :q ?o }"));
    }

    @Test
    void policyChangeInOpa_takesEffectOnTheNextRequest() {
        policy = Map.of("u1", Set.of("engineer"));
        String url = startFusekiWithOpa();
        assertEquals(1, count(url, "u1", ALL));

        policy = Map.of("u1", Set.of("engineer", "manager", "level-1"));

        assertEquals(5, count(url, "u1", ALL));
    }

    // ---- failure mapping

    @Test
    void opaDeniesEverything_gives403() {
        policy = Map.of("u1", Set.of("manager"));   // u3 exists in the attribute store but OPA denies it
        String url = startFusekiWithOpa();

        assertEquals(403, failureStatus(url, "u3"));
    }

    @Test
    void opaDown_gives503_notAnEmptyResult() {
        policy = Map.of("u1", Set.of("manager"));
        String url = startFusekiWithOpa();
        opaFailing = true;

        assertEquals(503, failureStatus(url, "u1"));
    }

    @Test
    void opaRecovers_afterAnOutage() {
        policy = Map.of("u1", Set.of("manager"));
        String url = startFusekiWithOpa();
        opaFailing = true;
        assertEquals(503, failureStatus(url, "u1"));

        opaFailing = false;

        assertEquals(2, count(url, "u1", ALL));
    }

    @Test
    void userUnknownToAttributeStore_isRejectedBeforeOpaIsAsked() {
        policy = Map.of("nobody", Set.of("manager"));
        String url = startFusekiWithOpa();

        assertEquals(403, failureStatus(url, "nobody"));
        assertEquals(0, opaCalls.get());
    }

    // ---- what OPA is asked

    @Test
    void oneSparqlQuery_meansExactlyOneOpaCall() {
        policy = Map.of("u1", Set.of("manager"));
        String url = startFusekiWithOpa();

        count(url, "u1", ALL);

        assertEquals(1, opaCalls.get());
    }

    @Test
    void opaRequest_carriesSubjectDatasetVocabularyAndUserAttributes() {
        policy = Map.of("u1", Set.of("manager"));
        String url = startFusekiWithOpa();

        count(url, "u1", ALL);

        assertEquals(1, opaRequestBodies.size());
        JsonObject input = JSON.parse(opaRequestBodies.get(0)).get("input").getAsObject();
        assertEquals("u1", input.get("subject_id").getAsString().value());
        assertEquals("/ds", input.get("dataset_name").getAsString().value());
        assertFalse(input.get("action").getAsString().value().isBlank());
        assertTrue(input.get("subject_attributes").getAsObject().hasKey("manager"),
                "user attributes must be forwarded raw: " + input);
        Set<String> vocabulary = new java.util.HashSet<>();
        input.get("vocabulary").getAsArray().forEach(v -> vocabulary.add(v.getAsString().value()));
        assertTrue(vocabulary.containsAll(Set.of("manager", "engineer", "level-1")),
                "vocabulary must list every label in the store: " + vocabulary);
    }
}
