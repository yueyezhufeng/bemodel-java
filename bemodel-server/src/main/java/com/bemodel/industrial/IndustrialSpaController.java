package com.bemodel.industrial;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Serve the built industrial management app from the same local origin. */
@Controller
public class IndustrialSpaController {
    @GetMapping({"/", "/login", "/industrial", "/ontology", "/datasource", "/glossary", "/knowledge"})
    public String index() {
        return "forward:/index.html";
    }
}
