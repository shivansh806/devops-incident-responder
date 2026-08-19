package com.shivansh.incidentresponder.cache;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Offline. The cache key is the one place where a bug is both silent and expensive - a stale
 * answer served confidently is indistinguishable from a fresh one - so every input that should
 * change it gets a case.
 */
class AnalysisCacheKeyTest {

    private static final String MODEL = "openai/gpt-oss-120b";
    private static final String EFFORT = "medium";
    private static final String LOGS = "ERROR pool exhausted";

    @Test
    void isStableForIdenticalInputs() {
        assertThat(AnalysisCacheKey.build(MODEL, EFFORT, LOGS, "payment-service"))
                .isEqualTo(AnalysisCacheKey.build(MODEL, EFFORT, LOGS, "payment-service"));
    }

    /**
     * The reason this class exists. Groq retired llama-3.3-70b-versatile mid-project; without
     * the model id in the key, every entry cached before that swap would have been served
     * afterwards as though it described the current model.
     */
    @Test
    void changesWhenTheModelChanges() {
        assertThat(AnalysisCacheKey.build("openai/gpt-oss-120b", EFFORT, LOGS, null))
                .isNotEqualTo(AnalysisCacheKey.build("openai/gpt-oss-20b", EFFORT, LOGS, null));
    }

    /** A hidden input that changes the output has to change the key, or pinning it is theatre. */
    @Test
    void changesWhenReasoningEffortChanges() {
        assertThat(AnalysisCacheKey.build(MODEL, "medium", LOGS, null))
                .isNotEqualTo(AnalysisCacheKey.build(MODEL, "low", LOGS, null));
    }

    @Test
    void changesWhenTheLogsChange() {
        assertThat(AnalysisCacheKey.build(MODEL, EFFORT, "ERROR a", null))
                .isNotEqualTo(AnalysisCacheKey.build(MODEL, EFFORT, "ERROR b", null));
    }

    /**
     * Absent and present are different questions - absent asks the model to infer the origin,
     * present asserts it and overrides the model - so they must not share an entry. An empty
     * string is a third thing again and is not treated as absent here, because
     * {@code AnalyzerService} has already normalised blanks to null before building the key.
     */
    @Test
    void changesWhenTheServiceNameChangesOrDisappears() {
        String absent = AnalysisCacheKey.build(MODEL, EFFORT, LOGS, null);
        String present = AnalysisCacheKey.build(MODEL, EFFORT, LOGS, "payment-service");
        String other = AnalysisCacheKey.build(MODEL, EFFORT, LOGS, "order-service");

        assertThat(absent).isNotEqualTo(present);
        assertThat(present).isNotEqualTo(other);
        assertThat(absent).isNotEqualTo(other);
    }

    /**
     * Guards against a subtle collision: concatenating the parts without a separator would let
     * ("ab", "c") and ("a", "bc") hash to the same key.
     */
    @Test
    void doesNotLetFieldBoundariesBlur() {
        assertThat(AnalysisCacheKey.build(MODEL, EFFORT, "ab", "c"))
                .isNotEqualTo(AnalysisCacheKey.build(MODEL, EFFORT, "a", "bc"));
    }

    /**
     * The fingerprint is derived from prompts, schema and enum vocabularies read reflectively.
     * Reflection order is undefined, so if the derivation did not sort, the key would differ
     * between JVM runs - missing every time while looking like it worked. Cheap to state,
     * impossible to notice otherwise.
     */
    @Test
    void isStableAcrossRepeatedDerivation() {
        String first = AnalysisCacheKey.build(MODEL, EFFORT, LOGS, null);
        for (int i = 0; i < 50; i++) {
            assertThat(AnalysisCacheKey.build(MODEL, EFFORT, LOGS, null)).isEqualTo(first);
        }
    }

    @Test
    void isNamespacedAndVersionedSoOldFormatsCannotBeMisread() {
        assertThat(AnalysisCacheKey.build(MODEL, EFFORT, LOGS, null)).startsWith("analysis:v1:");
    }

    /** Keys go in log lines and Redis; an unbounded one would be neither. */
    @Test
    void staysShortRegardlessOfInputSize() {
        String huge = "x".repeat(50_000);
        assertThat(AnalysisCacheKey.build(MODEL, EFFORT, huge, null)).hasSizeLessThan(120);
    }

    @Test
    void toleratesNullModelAndEffortWithoutColliding() {
        assertThat(AnalysisCacheKey.build(null, null, LOGS, null))
                .isNotEqualTo(AnalysisCacheKey.build(MODEL, EFFORT, LOGS, null));
    }
}
