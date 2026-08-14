package com.shivansh.incidentresponder.config;

import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.allminilml6v2.AllMiniLmL6V2EmbeddingModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The embedding model, which turns incident text into the vector Atlas searches on.
 * <p>
 * Separate from {@code AgentConfig} because this is not an agent. There is no prompt, no
 * reasoning and no output to parse - the same text always produces the same 384 numbers.
 * <p>
 * <b>It runs inside this JVM.</b> No HTTP call, no API key, no rate limit, and nothing
 * charged to the Groq budget - Groq serves no embeddings endpoint, so the choice was a local
 * model or a fourth provider. Local also keeps every test that touches embeddings offline,
 * and makes retrieval quality deterministic: unlike the Analyzer baseline, where three runs
 * are needed before a change counts as real, the same query here returns the same ranking
 * every time. A retrieval result can be trusted after one run.
 * <p>
 * <b>Cost of that choice:</b> roughly 200MB of jars, and the ONNX weights load on first
 * touch - measured at about 5 seconds, then ~65ms per embedding. The bean is eager on
 * purpose so that 5 seconds is paid during startup rather than by whichever incident happens
 * to arrive first.
 * <p>
 * Declared as the {@link EmbeddingModel} interface rather than the concrete class so that
 * swapping models is a change to this file alone. Note the trap that comes with swapping:
 * the Atlas index has {@code numDimensions} baked into it, so a model of a different size
 * fails loudly, while a different 384-dimension model does not fail at all - the old vectors
 * stay queryable and quietly rank as noise. Re-backfill in the same change. See CLAUDE.md.
 */
@Configuration
public class EmbeddingConfig {

    @Bean
    public EmbeddingModel embeddingModel() {
        return new AllMiniLmL6V2EmbeddingModel();
    }
}
