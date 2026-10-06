package uk.gov.dbt.ndtp.jena.abac.opa;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Set;

import org.apache.jena.atlas.json.JSON;
import org.apache.jena.atlas.json.JsonValue;
import org.junit.jupiter.api.Test;

import uk.gov.dbt.ndtp.jena.abac.AttributeValueSet;
import uk.gov.dbt.ndtp.jena.abac.Hierarchy;
import uk.gov.dbt.ndtp.jena.abac.lib.CxtABAC;
import uk.gov.dbt.ndtp.jena.abac.opa.transport.HttpOpaTransport;
import uk.gov.dbt.ndtp.jena.abac.opa.transport.OpaRequest;
import uk.gov.dbt.ndtp.jena.abac.opa.transport.OpaResponse;
import uk.gov.dbt.ndtp.jena.abac.opa.transport.OpaTransportException;

/**
 * Tests HttpOpaTransport's JSON building and parsing logic in isolation —
 * no live OPA required, no network calls.
 */
public class TestHttpOpaTransport {

    private static DecisionContext ctx() {
        CxtABAC cxt = CxtABAC.context(AttributeValueSet.of("employee"), Hierarchy.noHierarchy, null);
        return new DecisionContext(cxt, "user-1", "read", "org-a", "dataset1");
    }

    @Test
    void parseResponse_allowTrueWithLabels_returnsPermitted() throws OpaTransportException {
        JsonValue json = JSON.parseAny(
                "{\"allow\": true, \"permitted_labels\": [\"public\", \"employee\"]}");

        OpaResponse response = HttpOpaTransport.parseResponse(json);

        assertEquals(Set.of("public", "employee"), response.permittedLabels());
    }

    @Test
    void parseResponse_wrappedInResult_unwrapsCorrectly() throws OpaTransportException {
        JsonValue json = JSON.parseAny(
                "{\"result\": {\"allow\": true, \"permitted_labels\": [\"public\"]}}");

        OpaResponse response = HttpOpaTransport.parseResponse(json);

        assertEquals(Set.of("public"), response.permittedLabels());
    }

    @Test
    void parseResponse_allowFalse_returnsEmptyPermittedSet() throws OpaTransportException {
        JsonValue json = JSON.parseAny("{\"allow\": false}");

        OpaResponse response = HttpOpaTransport.parseResponse(json);

        assertTrue(response.permittedLabels().isEmpty());
    }

    @Test
    void parseResponse_allowFalse_ignoresStrayLabelsField() throws OpaTransportException {
        JsonValue json = JSON.parseAny(
                "{\"allow\": false, \"permitted_labels\": [\"public\"]}");

        OpaResponse response = HttpOpaTransport.parseResponse(json);

        assertTrue(response.permittedLabels().isEmpty());
    }

    @Test
    void parseResponse_missingAllowField_throwsTransportException() {
        JsonValue json = JSON.parseAny("{\"permitted_labels\": [\"public\"]}");

        assertThrows(OpaTransportException.class, () -> HttpOpaTransport.parseResponse(json));
    }

    @Test
    void parseResponse_allowNotBoolean_throwsTransportException() {
        JsonValue json = JSON.parseAny("{\"allow\": \"yes\"}");

        assertThrows(OpaTransportException.class, () -> HttpOpaTransport.parseResponse(json));
    }

    @Test
    void parseResponse_allowTrueButMissingPermittedLabels_throwsTransportException() {
        JsonValue json = JSON.parseAny("{\"allow\": true}");

        assertThrows(OpaTransportException.class, () -> HttpOpaTransport.parseResponse(json));
    }

    @Test
    void parseResponse_permittedLabelsNotAnArray_throwsTransportException() {
        JsonValue json = JSON.parseAny("{\"allow\": true, \"permitted_labels\": \"not-an-array\"}");

        assertThrows(OpaTransportException.class, () -> HttpOpaTransport.parseResponse(json));
    }

    @Test
    void parseResponse_notAJsonObject_throwsTransportException() {
        JsonValue json = JSON.parseAny("[\"just\", \"an\", \"array\"]");

        assertThrows(OpaTransportException.class, () -> HttpOpaTransport.parseResponse(json));
    }

    @Test
    void toJson_includesAllExpectedFields() {
        OpaRequest request = OpaRequest.of(ctx(), Set.of("public"), java.util.Map.of("employee", "true"));

        String json = HttpOpaTransport.toJson(request);

        // Parse rather than string-match: Jena's flat JSON output puts spaces around ':' and ','.
        assertTrue(json.startsWith("{"));
        org.apache.jena.atlas.json.JsonObject root = JSON.parse(json);
        assertEquals(1, root.size());
        org.apache.jena.atlas.json.JsonObject input = root.get("input").getAsObject();
        assertEquals("user-1", input.get("subject_id").getAsString().value());
        assertEquals("read", input.get("action").getAsString().value());
        assertEquals(1, input.get("vocabulary").getAsArray().size());
        assertEquals("public", input.get("vocabulary").getAsArray().get(0).getAsString().value());
        assertEquals("true", input.get("subject_attributes").getAsObject().get("employee").getAsString().value());
    }

    @Test
    void toJson_escapesQuotesAndBackslashes() {
        OpaRequest request = OpaRequest.of(ctx(), Set.of("public"), java.util.Map.of("weird", "value\"with\\backslash"));

        String json = HttpOpaTransport.toJson(request);

        JsonValue reparsed = JSON.parseAny(json);
        assertTrue(reparsed.isObject());
    }
}
