package com.bemodel.industrial;

import com.bemodel.common.BizException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.HashSet;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class ConnectorFactoryServiceTest {
    final ObjectMapper json = new ObjectMapper();
    ConnectorFactoryService service;
    ObjectNode demo;
    Set<String> targets;
    @BeforeEach void setup() throws Exception {
        service = new ConnectorFactoryService(null,json);
        targets = new HashSet<>();
        for (JsonNode t : service.templates()) {
            for (JsonNode m : t.path("mappings")) targets.add(m.path("entity").asText()+"."+m.path("target").asText());
            if (t.path("code").asText().equals("FP_DEMO")) demo = t.deepCopy();
        }
        demo.put("version",1);
    }
    @Test void baselineKeepsDemonstratedAndUnknownSourcesSeparate() {
        assertEquals(11,service.templates().size());
        assertEquals(287,targets.size());
        assertEquals(126,demo.path("mappings").size());
        assertTrue(service.check(demo,targets).path("valid").asBoolean());
        for (JsonNode t : service.templates()) if (!t.path("demo").asBoolean()) {
            assertFalse(service.check(t,targets).path("valid").asBoolean());
            for (JsonNode m : t.path("mappings")) assertTrue(m.path("field").asText().isEmpty());
        }
    }
    @Test void credentialsCannotBeStoredInlineOrHiddenInEndpoint() {
        ObjectNode patch=demo.deepCopy();
        ((ObjectNode)patch.path("config")).put("password","must-not-be-stored");
        ObjectNode secretPatch=patch;
        assertThrows(BizException.class,()->service.normalized(demo,secretPatch));
        patch=demo.deepCopy();
        ((ObjectNode)patch.path("config")).put("credentialRef","raw-token");
        ObjectNode finalPatch=patch;
        assertThrows(BizException.class,()->service.normalized(demo,finalPatch));
        assertThrows(BizException.class,()->ConnectorFactoryService.checkEndpoint("https://user:password@example.test/api","HTTP_API"));
        assertThrows(BizException.class,()->ConnectorFactoryService.checkEndpoint("https://example.test/api?token=secret","HTTP_API"));
    }
    @Test void readOnlyAndFiniteParametersAreEnforced() {
        ObjectNode patch=demo.deepCopy();
        ((ObjectNode)patch.path("config")).put("readOnly",false);
        assertThrows(BizException.class,()->service.normalized(demo,patch));
        ObjectNode huge=demo.deepCopy();
        ((ObjectNode)huge.path("config")).put("pageSize",10001);
        assertThrows(BizException.class,()->service.normalized(demo,huge));
    }
    @Test void staleTargetsUnitDriftAndAdapterDriftFailValidation() {
        targets.remove("Lot.quantity_kg");
        assertFalse(service.check(demo,targets).path("valid").asBoolean());
        targets.add("Lot.quantity_kg");
        for(JsonNode m:demo.path("mappings")) if(m.path("entity").asText().equals("Lot")&&m.path("target").asText().equals("quantity_kg")) ((ObjectNode)m).put("unit","tonne");
        assertFalse(service.check(demo,targets).path("valid").asBoolean());
        ((ObjectNode)demo.path("mappings").get(0)).put("field","invented_column");
        assertTrue(service.check(demo,targets).path("errors").asInt()>=2);
    }
    @Test void identityAndRequiredMappingsCannotBeExcluded() {
        ((ObjectNode)demo.path("mappings").get(0)).put("enabled",false);
        assertFalse(service.check(demo,targets).path("valid").asBoolean());
    }
    @Test void proposedWatermarkNeedsCursor() {
        ((ObjectNode)demo.path("config")).put("syncMode","WATERMARK");
        assertFalse(service.check(demo,targets).path("valid").asBoolean());
    }
    @Test void strictSourceTypesDoNotCoerceUnknownOrDates() {
        assertFalse(ConnectorFactoryService.matches(json.valueToTree(true),"integer"));
        assertFalse(ConnectorFactoryService.matches(json.valueToTree("2000"),"integer"));
        assertFalse(ConnectorFactoryService.matches(json.valueToTree("2026-10-24T10:00:00"),"datetime"));
        assertTrue(ConnectorFactoryService.matches(json.valueToTree("2026-10-24T10:00:00+08:00"),"datetime"));
        assertTrue(ConnectorFactoryService.matches(json.valueToTree(2000),"integer"));
    }
    @Test void mappingTargetsCannotBeDroppedOrDuplicated() {
        ObjectNode patch=demo.deepCopy();
        ((com.fasterxml.jackson.databind.node.ArrayNode)patch.path("mappings")).remove(0);
        assertThrows(BizException.class,()->service.normalized(demo,patch));
    }
}
