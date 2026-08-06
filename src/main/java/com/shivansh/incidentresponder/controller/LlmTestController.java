package com.shivansh.incidentresponder.controller;

import com.shivansh.incidentresponder.service.LlmService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * TEMPORARY smoke-test endpoint for the Groq/LangChain4j wiring.
 * Remove once real incident-analysis endpoints exist.
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class LlmTestController {

    private final LlmService llmService;

    @GetMapping("/test-llm")
    public String testLlm() {
        return llmService.runTestPrompt();
    }
}
