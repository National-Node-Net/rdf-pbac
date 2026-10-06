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

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.jena.atlas.json.JSON;
import org.apache.jena.atlas.json.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uk.gov.dbt.ndtp.jena.abac.AttributeValueSet;
import uk.gov.dbt.ndtp.jena.abac.Hierarchy;
import uk.gov.dbt.ndtp.jena.abac.lib.CxtABAC;
import uk.gov.dbt.ndtp.jena.abac.opa.transport.HttpOpaTransport;
import uk.gov.dbt.ndtp.jena.abac.opa.transport.OpaConnectException;
import uk.gov.dbt.ndtp.jena.abac.opa.transport.OpaRequest;
import uk.gov.dbt.ndtp.jena.abac.opa.transport.OpaResponse;
import uk.gov.dbt.ndtp.jena.abac.opa.transport.OpaTransportException;

/**
 * {@link HttpOpaTransport#call} against a throw-away in-process HTTP server standing in for
 * OPA: request shape on the wire, and how every kind of bad response or network failure is
 * reported. No live OPA needed (unlike {@code TestHttpOpaTransportLive}).
 */
public class TestHttpOpaTransportHttp {

    private static final Duration TIMEOUT = Duration.ofSeconds(2);

    private HttpServer server;
    private volatile HttpHandler handler;
    private URI baseUri;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", ex -> handler.handle(ex));
        server.start();
        baseUri = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private static void respond(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if ( bytes.length > 0 )
            ex.getResponseBody().write(bytes);
        ex.close();
    }

    private static OpaRequest request() {
        CxtABAC cxt = CxtABAC.context(AttributeValueSet.of(List.of()), Hierarchy.noHierarchy, null);
        DecisionContext ctx = new DecisionContext(cxt, "user-1", "query", "org-a", "ds1");
        return OpaRequest.of(ctx, Set.of("public"), Map.of("employee", "true"));
    }

    private OpaResponse call(HttpOpaTransport transport) throws Exception {
        return transport.call(request(), TIMEOUT, TIMEOUT);
    }

    private HttpOpaTransport transport() {
        return new HttpOpaTransport(baseUri, "sag/test", TIMEOUT);
    }

    // ---- happy path and wire format

    @Test
    void call_success_parsesResultEnvelope() throws Exception {
        handler = ex -> respond(ex, 200,
                "{\"result\":{\"allow\":true,\"permitted_labels\":[\"public\",\"employee\"]}}");
        OpaResponse response = call(transport());
        assertEquals(Set.of("public", "employee"), response.permittedLabels());
    }

    @Test
    void call_sendsJsonPostToTheDataApiPath() throws Exception {
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> contentType = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        handler = ex -> {
            method.set(ex.getRequestMethod());
            path.set(ex.getRequestURI().getPath());
            contentType.set(ex.getRequestHeaders().getFirst("Content-Type"));
            body.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(ex, 200, "{\"allow\":false}");
        };

        OpaResponse response = call(transport());

        assertTrue(response.permittedLabels().isEmpty());
        assertEquals("POST", method.get());
        assertEquals("/v1/data/sag/test", path.get());
        assertTrue(contentType.get().contains("application/json"));
        JsonObject input = JSON.parse(body.get()).get("input").getAsObject();
        assertEquals("user-1", input.get("subject_id").getAsString().value());
        assertEquals("org-a", input.get("organisation_id").getAsString().value());
        assertEquals("ds1", input.get("dataset_name").getAsString().value());
    }

    // ---- bad responses

    @Test
    void call_http500_isATransportExceptionCarryingTheStatus() {
        handler = ex -> respond(ex, 500, "{\"error\":\"boom\"}");
        OpaTransportException e = assertThrows(OpaTransportException.class, () -> call(transport()));
        assertTrue(e.getMessage().contains("500"));
    }

    @Test
    void call_http404_unknownPolicyPath_isATransportException() {
        handler = ex -> respond(ex, 404, "{}");
        assertThrows(OpaTransportException.class, () -> call(transport()));
    }

    @Test
    void call_invalidJsonBody_isATransportException() {
        handler = ex -> respond(ex, 200, "this is not json");
        assertThrows(OpaTransportException.class, () -> call(transport()));
    }

    @Test
    void call_emptyBody_isATransportException() {
        handler = ex -> respond(ex, 200, "");
        assertThrows(OpaTransportException.class, () -> call(transport()));
    }

    @Test
    void call_validJsonButNoAllowField_isATransportException() {
        // OPA returns "{}" when the policy is undefined - must not be read as "deny all" silently.
        handler = ex -> respond(ex, 200, "{}");
        assertThrows(OpaTransportException.class, () -> call(transport()));
    }

    @Test
    void call_redirect_isNotFollowed() {
        AtomicInteger redirectTargetHits = new AtomicInteger();
        server.createContext("/elsewhere", ex -> {
            redirectTargetHits.incrementAndGet();
            respond(ex, 200, "{\"allow\":true,\"permitted_labels\":[\"public\"]}");
        });
        handler = ex -> {
            ex.getResponseHeaders().add("Location", "/elsewhere");
            respond(ex, 302, "");
        };

        assertThrows(OpaTransportException.class, () -> call(transport()));
        assertEquals(0, redirectTargetHits.get());
    }

    // ---- network failures: all must surface as OPA-unavailable style exceptions

    @Test
    void call_connectionRefused_isReportedAsTransportOrConnectFailure() {
        server.stop(0);
        Exception e = assertThrows(Exception.class, () -> call(transport()));
        assertTrue(e instanceof OpaTransportException || e instanceof OpaConnectException,
                "unexpected exception type: " + e);
    }

    @Test
    void call_readTimeout_isReportedAsTransportOrConnectFailure() {
        handler = ex -> {
            try {
                Thread.sleep(1500);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            try {
                respond(ex, 200, "{\"allow\":false}");
            } catch (IOException ignored) {
                // client already gave up
            }
        };
        HttpOpaTransport transport = transport();
        Exception e = assertThrows(Exception.class,
                () -> transport.call(request(), TIMEOUT, Duration.ofMillis(200)));
        assertTrue(e instanceof OpaTransportException || e instanceof OpaConnectException,
                "unexpected exception type: " + e);
    }

    // ---- request serialisation edge cases

    @Test
    void toJson_nullOrganisation_isSerialisedAsJsonNull() {
        CxtABAC cxt = CxtABAC.context(AttributeValueSet.of(List.of()), Hierarchy.noHierarchy, null);
        OpaRequest req = OpaRequest.of(new DecisionContext(cxt, "u", "query", null, "ds"), Set.of("public"), Map.of());
        JsonObject input = JSON.parse(HttpOpaTransport.toJson(req)).get("input").getAsObject();
        assertTrue(input.hasKey("organisation_id"), "key must be present, not omitted");
        assertTrue(input.get("organisation_id").isNull());
    }

    @Test
    void toJson_noSubjectAttributes_isAnEmptyObject_notAnArray() {
        CxtABAC cxt = CxtABAC.context(AttributeValueSet.of(List.of()), Hierarchy.noHierarchy, null);
        OpaRequest req = OpaRequest.of(new DecisionContext(cxt, "u", "query", "o", "ds"), Set.of("public"), Map.of());
        JsonObject input = JSON.parse(HttpOpaTransport.toJson(req)).get("input").getAsObject();
        assertTrue(input.get("subject_attributes").isObject(), "must be an object, not an array");
        assertEquals(0, input.get("subject_attributes").getAsObject().size());
    }

    @Test
    void toJson_vocabularyWithSeveralLabels_containsEachExactlyOnce() {
        CxtABAC cxt = CxtABAC.context(AttributeValueSet.of(List.of()), Hierarchy.noHierarchy, null);
        OpaRequest req = OpaRequest.of(new DecisionContext(cxt, "u", "query", "o", "ds"),
                Set.of("a", "b", "c"), Map.of());
        var array = JSON.parse(HttpOpaTransport.toJson(req)).get("input").getAsObject().get("vocabulary").getAsArray();
        assertEquals(3, array.size());
    }
}
