package com.bemodel.industrial;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class IndustrialBoundaryFilterTest {
    @Test void permitsIndustrialModelAndIntegrationMetadata() {
        for (String path : new String[]{"/api/industrial/catalog", "/api/ontology/graph", "/api/datasource/factory/connectors", "/api/mapping/list"})
            assertTrue(IndustrialBoundaryFilter.allowed(path), path);
    }
    @Test void rejectsUnadaptedMedicalApplications() {
        for (String path : new String[]{"/api/cs/ask", "/api/architecture/overview", "/api/clinical/execute", "/api/simulation/run"})
            assertFalse(IndustrialBoundaryFilter.allowed(path), path);
    }
    @Test void rejectsMedicalMcpFallbackAndKeepsStaticPages() {
        assertFalse(IndustrialBoundaryFilter.allowed("/mcp"));
        assertFalse(IndustrialBoundaryFilter.allowed("/mcp/tools"));
        assertTrue(IndustrialBoundaryFilter.allowed("/datasource"));
    }
    @Test void deniedRequestNeverReachesDownstream() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/cs/ask");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean downstream = new AtomicBoolean();
        new IndustrialBoundaryFilter().doFilter(request, response, (req,res) -> downstream.set(true));
        assertEquals(403,response.getStatus());
        assertFalse(downstream.get());
        assertTrue(response.getContentAsString().contains("工业本体"));
    }
}
