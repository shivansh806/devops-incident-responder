package com.shivansh.incidentresponder.service;

import dev.langchain4j.model.chat.ChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class LlmService {

    private static final String TEST_PROMPT = """
            You are an SRE assistant. In two sentences, explain what a sustained \
            "high memory usage" alert on a Java service usually indicates.""";

    private final ChatModel chatModel;

    /**
     * Sends a fixed prompt to the configured model and returns the raw text response.
     * Temporary - exists only to verify the Groq/LangChain4j wiring end to end.
     */
    public String runTestPrompt() {
        log.info("Sending test prompt to LLM");
        String response = chatModel.chat(TEST_PROMPT);
        log.info("LLM returned {} characters", response.length());
        return response;
    }
}
