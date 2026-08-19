package com.shivansh.incidentresponder.agent;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Reports, does not assert. Answers one question before any configuration is changed:
 * <b>does the existing Analyzer prompt still produce a parseable {@link AnalyzerOutput} on a
 * candidate replacement model?</b>
 * <p>
 * Groq retired {@code llama-3.3-70b-versatile}. This probe exists because a replacement that
 * cannot drive LangChain4j's prompt-based JSON path is not a candidate at all, and that is a
 * property of the model's output habits rather than of its benchmark scores - {@code
 * qwen/qwen3.6-27b}, for instance, emits a {@code <think>} block inline in {@code content} and
 * fails outright.
 * <p>
 * <b>Deliberately does not read {@code application.yml}.</b> The model is constructed here so
 * this can be run against a candidate without changing the configuration first - the whole
 * point is to decide whether to change it.
 * <p>
 * <b>One billable call.</b> Tagged {@code llm}, so {@code mvn test} never runs it. Run it from
 * the IDE, or:
 * <pre>
 * mvn test -Dtest=ModelCandidateProbe -Dsurefire.excludedGroups= -DfailIfNoSpecifiedTests=false
 * </pre>
 */
@Tag("llm")
class ModelCandidateProbe {

    /** Overridable so a candidate can be tried without editing the file. */
    private static final String CANDIDATE =
            System.getProperty("probe.model", "openai/gpt-oss-120b");

    private static final String REASONING_EFFORT =
            System.getProperty("probe.reasoning-effort", "medium");

    private static final String SAMPLE = "src/test/resources/logs/connection-pool-exhaustion.log";

    @Test
    void doesTheAnalyzerPromptSurviveOnThisModel() throws IOException {
        String apiKey = groqApiKey();
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank(),
                "No GROQ_API_KEY resolved from .env - skipping the model probe");

        String logs = Files.readString(Path.of(SAMPLE), StandardCharsets.UTF_8);

        // Token usage is the input every pacing decision downstream depends on, and it is
        // discarded by AiServices when the return type is a plain record. A listener is the
        // only way to see it without changing the agent interface.
        AtomicReference<String> usage = new AtomicReference<>("not reported");
        ChatModelListener usageListener = new ChatModelListener() {
            @Override
            public void onResponse(ChatModelResponseContext context) {
                var t = context.chatResponse().metadata().tokenUsage();
                usage.set(t == null ? "not reported"
                        : "input=%d output=%d total=%d".formatted(
                                t.inputTokenCount(), t.outputTokenCount(), t.totalTokenCount()));
            }
        };

        ChatModel model = OpenAiChatModel.builder()
                .baseUrl("https://api.groq.com/openai/v1")
                .apiKey(apiKey)
                .modelName(CANDIDATE)
                .reasoningEffort(REASONING_EFFORT)
                .temperature(0.2)
                .timeout(Duration.ofSeconds(90))
                .listeners(java.util.List.of(usageListener))
                .build();

        AnalyzerAgent agent = AiServices.create(AnalyzerAgent.class, model);

        System.out.println("\n================ model candidate probe ================");
        System.out.printf("  candidate : %s (reasoning-effort=%s)%n", CANDIDATE, REASONING_EFFORT);
        System.out.printf("  sample    : %s (%,d chars)%n", SAMPLE, logs.length());

        long start = System.currentTimeMillis();
        AnalyzerOutput output;
        try {
            output = agent.analyze(logs);
        } catch (RuntimeException e) {
            System.out.printf("  RESULT    : FAILED - %s: %s%n",
                    e.getClass().getSimpleName(), e.getMessage());
            System.out.println("=======================================================\n");
            return;
        }
        long ms = System.currentTimeMillis() - start;

        System.out.printf("  RESULT    : parsed into AnalyzerOutput in %,dms%n", ms);
        System.out.printf("  TOKENS    : %s%n", usage.get());
        System.out.printf("    errorType       : %s%n", output.errorType());
        System.out.printf("    affectedService : %s%n", output.affectedService());
        System.out.printf("    severity        : %s%n", output.severity());
        System.out.printf("    firstOccurrence : %s%n", output.firstOccurrence());
        System.out.printf("    confidence      : %s%n", output.confidence());
        System.out.printf("    keyEvidence     : %d line(s)%n",
                output.keyEvidence() == null ? 0 : output.keyEvidence().size());
        if (output.keyEvidence() != null) {
            output.keyEvidence().forEach(line -> System.out.printf("      | %s%n", line));
        }
        System.out.println("=======================================================\n");
    }

    /** Reads .env directly - no Spring context, so the probe stays a single cheap call. */
    private static String groqApiKey() throws IOException {
        String fromEnv = System.getenv("GROQ_API_KEY");
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv;
        }
        Path dotenv = Path.of(".env");
        if (!Files.exists(dotenv)) {
            return null;
        }
        for (String line : Files.readAllLines(dotenv, StandardCharsets.UTF_8)) {
            if (line.startsWith("GROQ_API_KEY=")) {
                return line.substring("GROQ_API_KEY=".length()).trim();
            }
        }
        return null;
    }
}
