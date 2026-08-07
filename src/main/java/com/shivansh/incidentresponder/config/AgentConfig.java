package com.shivansh.incidentresponder.config;

import com.shivansh.incidentresponder.agent.AnalyzerAgent;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Builds the LangChain4j AI Service proxies and registers them as Spring beans.
 * <p>
 * {@code AiServices.create} generates a dynamic proxy for the interface: it reads the
 * annotations, renders the prompts, calls the model, and deserialises the reply into the
 * declared return type. Wiring it explicitly here (rather than via the
 * {@code @AiService} annotation) keeps the one place where agents are constructed visible,
 * and is where retries, memory or tools would later be attached.
 */
@Configuration
public class AgentConfig {

    @Bean
    public AnalyzerAgent analyzerAgent(ChatModel chatModel) {
        return AiServices.create(AnalyzerAgent.class, chatModel);
    }
}
