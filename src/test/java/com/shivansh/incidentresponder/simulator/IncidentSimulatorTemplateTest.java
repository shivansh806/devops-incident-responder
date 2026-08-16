package com.shivansh.incidentresponder.simulator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Offline. Renders the simulator's templates without Kafka, Spring or a model call, so the
 * demo content can be checked without spending any of it.
 */
class IncidentSimulatorTemplateTest {

    /** Anything left looking like a token means the renderer did not understand it. */
    private static final Pattern UNRENDERED = Pattern.compile(
            "\\{(?:svc|caller|host|id|t[+-]\\d+(?:\\|[+-]\\d{2}:\\d{2})?|#\\d+-\\d+|=[a-z]+:\\d+-\\d+)}");

    private static final Instant BASE = Instant.parse("2026-08-16T10:00:00Z");

    @ParameterizedTest
    @EnumSource(IncidentScenario.class)
    void everyTemplateRendersWithNothingLeftBehind(IncidentScenario scenario) throws IOException {
        String rendered = render(scenario, new Random(42));

        Matcher leftovers = UNRENDERED.matcher(rendered);
        assertThat(leftovers.find())
                .as("unrendered token in %s: %s", scenario.resource(),
                        leftovers.reset().results().map(r -> r.group()).toList())
                .isFalse();

        assertThat(rendered).doesNotContain("{svc}", "{caller}", "{host}", "{id}");
        assertThat(rendered.length())
                .as("a log dump the Analyzer can work with, under its 50,000 char limit")
                .isBetween(400, 50_000);
    }

    @ParameterizedTest
    @EnumSource(IncidentScenario.class)
    void everyTemplateNamesItsServiceAndCarriesATimestamp(IncidentScenario scenario) throws IOException {
        String rendered = render(scenario, new Random(7));

        assertThat(rendered).contains("svc-under-test");
        assertThat(rendered)
                .as("at least one ISO-8601 timestamp, which is what firstOccurrence is read from")
                .containsPattern("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}");
    }

    /**
     * The whole reason the renderer exists. Two events of one failure class must not be the
     * same text - identical logs embed to an identical vector, and retrieval would start
     * handing the Resolver the simulator's own previous output as precedent.
     */
    @ParameterizedTest
    @EnumSource(IncidentScenario.class)
    void twoEventsOfTheSameScenarioDiffer(IncidentScenario scenario) throws IOException {
        assertThat(render(scenario, new Random(1)))
                .isNotEqualTo(render(scenario, new Random(2)));
    }

    /**
     * A job id in "started", "loaded" and "failed" is one job. Per-occurrence randomness there
     * would read as three unrelated jobs and quietly make the log incoherent.
     */
    @Test
    void namedValuesRepeatWithinOneEventWhilePlainOnesDoNot() {
        String stable = IncidentLogRenderer.render(
                "a={=job:1000-9999} b={=job:1000-9999} c={=job:1000-9999}",
                BASE, "svc", "caller", new Random(3));
        List<String> values = List.of(stable.replaceAll("[a-c]=", "").split(" "));
        assertThat(new HashSet<>(values)).as("one job id, three mentions").hasSize(1);

        String varying = IncidentLogRenderer.render(
                "{#1-1000000} {#1-1000000} {#1-1000000} {#1-1000000}",
                BASE, "svc", "caller", new Random(3));
        assertThat(new HashSet<>(List.of(varying.split(" "))))
                .as("independent metric readings")
                .hasSizeGreaterThan(1);
    }

    /** The disk-full template's trap: timestamps arrive at +05:30 and need converting to UTC. */
    @Test
    void offsetTimestampsRenderAtTheirOffsetNotUtc() {
        String rendered = IncidentLogRenderer.render(
                "{t+0} and {t+0|+05:30}", BASE, "svc", "caller", new Random(1));

        assertThat(rendered).isEqualTo("2026-08-16T10:00:00Z and 2026-08-16T15:30:00+05:30");
    }

    @Test
    void negativeShiftsGoBackwards() {
        assertThat(IncidentLogRenderer.render("{t-3600}", BASE, "svc", "caller", new Random(1)))
                .isEqualTo("2026-08-16T09:00:00Z");
    }

    /**
     * Guards the property the demo is sold on. Round-robin over a shuffled list, not random
     * draws: with eight scenarios, eight random draws repeat a class about 97% of the time.
     */
    @Test
    void theScenarioSetCoversDistinctFailureClasses() {
        Set<String> resources = new HashSet<>();
        for (IncidentScenario scenario : IncidentScenario.values()) {
            resources.add(scenario.resource());
        }
        assertThat(resources).hasSize(IncidentScenario.values().length);
        assertThat(IncidentScenario.values()).hasSizeGreaterThanOrEqualTo(8);
    }

    /**
     * Two scenarios deliberately withhold the service field because their logs are tagged with
     * the victim. If this reaches zero, nothing is exercising origin inference any more.
     */
    @Test
    void someScenariosWithholdTheServiceSoTheModelMustInferIt() {
        long withheld = List.of(IncidentScenario.values()).stream()
                .filter(s -> !s.declaresService())
                .count();

        assertThat(withheld).isGreaterThanOrEqualTo(2);
        assertThat(withheld).isLessThan(IncidentScenario.values().length);
    }

    private static String render(IncidentScenario scenario, Random random) throws IOException {
        try (InputStream in = new org.springframework.core.io.ClassPathResource(
                "simulator/" + scenario.resource()).getInputStream()) {
            String template = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return IncidentLogRenderer.render(template, BASE, "svc-under-test", "caller-under-test", random);
        }
    }
}
