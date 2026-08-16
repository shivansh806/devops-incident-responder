package com.shivansh.incidentresponder.agent;

import com.shivansh.incidentresponder.embedding.SimilarIncident;
import com.shivansh.incidentresponder.model.ErrorType;
import com.shivansh.incidentresponder.model.Incident;
import com.shivansh.incidentresponder.model.LogAnalysis;
import com.shivansh.incidentresponder.model.Severity;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.AiServices;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

/**
 * Prints the exact messages LangChain4j sends for a Resolver call, without calling Groq.
 * <p>
 * The point is what this project cannot otherwise see. Half of what the model reads is not
 * in {@link ResolverAgent} at all - LangChain4j derives format instructions from the return
 * type and appends them itself, and where they land relative to the reasoning rules is a
 * property of the library, not of anything written here. When the agent misbehaves, the
 * first question is whether the prompt says what we think it says.
 * <p>
 * Not a test, and named to stay out of {@code mvn test}. Run it with:
 * <pre>
 *   mvn test -Dtest=ResolverPromptDump -DfailIfNoSpecifiedTests=false
 * </pre>
 */
class ResolverPromptDump {

    @Test
    void printWhatTheModelActuallyReceives() {
        CapturingChatModel captor = new CapturingChatModel();
        ResolverAgent agent = AiServices.create(ResolverAgent.class, captor);

        LogAnalysis analysis = new LogAnalysis(
                ErrorType.CONNECTION_POOL_EXHAUSTED,
                "subscription-service",
                Severity.CRITICAL,
                Instant.parse("2026-08-15T13:49:55Z"),
                List.of("HikariPool-1 - Connection is not available, request timed out after 25000ms",
                        "HikariPool-1 - Pool stats (total=25, active=25, idle=0, waiting=147)",
                        "HikariPool-1 - Connection acquisition p99 12800ms; statement execution p99 unchanged at 7ms"),
                0.91);

        try {
            agent.resolve(
                    ResolverPromptText.analysis(analysis),
                    ResolverPromptText.pastIncidents(List.of(
                            match("INC-2331", "2026-06-03T19:05:27Z", 0.9614),
                            match("INC-2103", "2026-03-09T14:22:07Z", 0.9476),
                            match("INC-2464", "2026-07-28T08:51:22Z", 0.9172))));
        } catch (RuntimeException e) {
            // The canned reply is not valid JSON for the return type, so deserialisation
            // throws. The messages were already captured by then, which is all we want.
        }

        for (ChatMessage message : captor.captured) {
            System.out.println("\n================ " + message.type() + " ================");
            System.out.println(text(message));
        }
    }

    private static String text(ChatMessage message) {
        return switch (message) {
            case dev.langchain4j.data.message.SystemMessage system -> system.text();
            case dev.langchain4j.data.message.UserMessage user -> user.singleText();
            default -> message.toString();
        };
    }

    private static SimilarIncident match(String id, String firstOccurrence, double score) {
        return new SimilarIncident(new Incident(
                id,
                ErrorType.CONNECTION_POOL_EXHAUSTED,
                "some-service",
                Severity.HIGH,
                Instant.parse(firstOccurrence),
                List.of("HikariPool-1 - Connection is not available, request timed out after 10000ms",
                        "HikariPool-1 - Pool stats (total=20, active=20, idle=0, waiting=88)"),
                0.84,
                "Resolution notes for " + id + " would be here.",
                null,
                Instant.parse(firstOccurrence),
                null), score);
    }

    /** Captures the request and answers with something deliberately unusable. */
    private static final class CapturingChatModel implements ChatModel {

        private List<ChatMessage> captured = List.of();

        @Override
        public ChatResponse chat(ChatRequest chatRequest) {
            captured = chatRequest.messages();
            return ChatResponse.builder().aiMessage(AiMessage.from("captured")).build();
        }
    }
}
