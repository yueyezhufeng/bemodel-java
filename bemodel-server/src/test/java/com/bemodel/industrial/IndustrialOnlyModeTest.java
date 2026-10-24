package com.bemodel.industrial;

import com.bemodel.common.BizException;
import com.bemodel.modeling.entity.Rule;
import com.bemodel.modeling.service.RuleService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.io.ClassPathResource;
import static org.junit.jupiter.api.Assertions.*;

class IndustrialOnlyModeTest {
    @Test void defaultConfigurationUsesIndustrialSchemaAndMigration() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        var props = yaml.getObject();
        assertNotNull(props);
        assertEquals("forgepilot", props.getProperty("spring.profiles.default"));
        assertEquals("classpath:db/industrial",props.getProperty("spring.flyway.locations"));
        assertTrue(props.getProperty("spring.datasource.url").contains("/forgepilot_industrial?"));
        assertEquals("18081}",props.getProperty("server.port").split(":")[1]);
    }
    @Test void removedApplicationsAndSeedsAreAbsentFromClasspath() {
        for (String cls : new String[]{"clinical.QcController","flow.FlowController","cs.CsController","rdf.RdfController","seed.DataSeeder","mcp.McpConfig"})
            assertThrows(ClassNotFoundException.class,()->Class.forName("com.bemodel."+cls),cls);
        assertFalse(new ClassPathResource("db/migration/V3__seed_ontology.sql").exists());
        assertFalse(new ClassPathResource("shacl/clinical-shapes.ttl").exists());
    }
    @Test void alternateProfileCannotTurnOffIndustrialRouteBoundary() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles("medical");
            context.register(IndustrialBoundaryFilter.class, IndustrialSpaController.class);
            context.refresh();
            assertNotNull(context.getBean(IndustrialBoundaryFilter.class));
            assertNotNull(context.getBean(IndustrialSpaController.class));
            assertFalse(IndustrialBoundaryFilter.allowed("/api/clinical/execute"));
        }
    }
    @Test void rulesAcceptContractDefinitionsAndRejectRemovedEngines() {
        Rule rule = new Rule();
        RuleService.requireContract(rule);
        assertEquals("CONTRACT",rule.getEngine());
        rule.setEngine("QC");
        assertThrows(BizException.class,()->RuleService.requireContract(rule));
    }
}
