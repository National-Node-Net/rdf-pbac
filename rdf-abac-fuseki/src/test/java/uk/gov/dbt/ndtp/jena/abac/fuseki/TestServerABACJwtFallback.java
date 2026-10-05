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

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.jena.fuseki.servlets.ActionErrorException;
import org.apache.jena.fuseki.servlets.HttpAction;
import org.apache.jena.web.HttpSC;
import org.junit.jupiter.api.Test;
import uk.gov.dbt.ndtp.servlet.auth.jwt.JwtServletConstants;

/**
 * Edge cases of {@link ServerABAC#userForRequestFromJwt()}: precedence of the verified JWT,
 * and every situation in which it must fall back to the legacy user resolution.
 */
public class TestServerABACJwtFallback {

    private static HttpAction action(Object jwtAttribute, String remoteUser, String authHeader) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getAttribute(JwtServletConstants.REQUEST_ATTRIBUTE_RAW_JWT)).thenReturn(jwtAttribute);
        when(request.getRemoteUser()).thenReturn(remoteUser);
        HttpAction action = mock(HttpAction.class);
        when(action.getRequest()).thenReturn(request);
        when(action.getRequestHeader(anyString())).thenReturn(authHeader);
        return action;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Jws jwsWithPayload(Object payload) {
        Jws jws = mock(Jws.class);
        when(jws.getPayload()).thenReturn(payload);
        return jws;
    }

    private static Jws<?> jwsWithEmail(String email) {
        Claims claims = mock(Claims.class);
        when(claims.get("email", String.class)).thenReturn(email);
        return jwsWithPayload(claims);
    }

    @Test
    void verifiedJwt_takesPrecedenceOverLegacyBearerHeader() {
        HttpAction action = action(jwsWithEmail("alice@example.com"), null, "Bearer user:bob");
        assertEquals("alice@example.com", ServerABAC.userForRequestFromJwt().apply(action));
    }

    @Test
    void jwtWithoutEmailClaim_fallsBackToLegacyBearerHeader() {
        HttpAction action = action(jwsWithEmail(null), null, "Bearer user:bob");
        assertEquals("bob", ServerABAC.userForRequestFromJwt().apply(action));
    }

    @Test
    void jwtAttributeOfUnexpectedType_fallsBackToLegacy() {
        HttpAction action = action("not-a-jws", null, "Bearer user:bob");
        assertEquals("bob", ServerABAC.userForRequestFromJwt().apply(action));
    }

    @Test
    void jwtPayloadNotClaims_fallsBackToLegacy() {
        HttpAction action = action(jwsWithPayload("plain string payload"), null, "Bearer user:bob");
        assertEquals("bob", ServerABAC.userForRequestFromJwt().apply(action));
    }

    @Test
    void noJwt_remoteUserFromContainer_isUsed() {
        HttpAction action = action(null, "carol", null);
        assertEquals("carol", ServerABAC.userForRequestFromJwt().apply(action));
    }

    @Test
    void noJwt_malformedAuthorizationHeader_isRejectedWith400() {
        HttpAction action = action(null, null, "garbage");
        ActionErrorException ex = assertThrows(ActionErrorException.class,
                () -> ServerABAC.userForRequestFromJwt().apply(action));
        assertEquals(HttpSC.BAD_REQUEST_400, ex.getRC());
    }

    @Test
    void noJwt_blankAuthorizationHeader_meansNoUser() {
        HttpAction action = action(null, null, "   ");
        assertNull(ServerABAC.userForRequestFromJwt().apply(action));
    }
}
