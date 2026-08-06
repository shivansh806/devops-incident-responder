package com.shivansh.incidentresponder;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

// A dummy key is pinned so the context loads without a real GROQ_API_KEY in the
// environment. No network call happens here - the model bean is only constructed.
@SpringBootTest(properties = "langchain4j.open-ai.chat-model.api-key=test-key")
class IncidentResponderApplicationTests {

    @Test
    void contextLoads() {
    }
}
