package com.shivansh.incidentresponder.controller;

import com.shivansh.incidentresponder.service.LlmService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(LlmTestController.class)
class LlmTestControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LlmService llmService;

    @Test
    void testLlmReturnsServiceResponse() throws Exception {
        given(llmService.runTestPrompt()).willReturn("a stubbed model reply");

        mockMvc.perform(get("/api/test-llm"))
                .andExpect(status().isOk())
                .andExpect(content().string("a stubbed model reply"));
    }
}
