package com.bemodel.industrial;

import com.bemodel.common.Result;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/** Industrial baseline contract. Runtime business facts stay in ForgePilot. */
@RestController
@RequestMapping("/api/industrial")
public class IndustrialController {
    private final JsonNode catalog;

    public IndustrialController(ObjectMapper mapper) throws IOException {
        try (var input = new ClassPathResource("industrial/catalog.json").getInputStream()) {
            catalog = mapper.readTree(input);
        }
    }

    @GetMapping("/catalog")
    public Result<JsonNode> catalog() {
        // Immutable seed reference; editable drafts are served by the original ontology APIs.
        return Result.ok(catalog.deepCopy());
    }

    @GetMapping("/status")
    public Result<Map<String, Object>> status() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("industry", "industrial_supply_chain");
        status.put("releaseId", catalog.path("release_id").asText());
        status.put("approval", "draft");
        status.put("businessExecutionEnabled", false);
        status.put("applicationMode", "forgepilot");
        status.put("availableModes", java.util.List.of("forgepilot"));
        status.put("source", "versioned baseline contract; editable model uses /api/concept and /api/ontology");
        status.put("businessSnapshot", "Use ForgePilot authenticated /api/demo/ontology/snapshot; no business facts copied into MySQL");
        return Result.ok(status);
    }
}
