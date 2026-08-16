package com.shivansh.incidentresponder.simulator;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The simulator only exists under its own profile, so no other test ever constructs it - which
 * means its constructor injection is unproven by everything else in the suite. In particular
 * {@code interval} is a {@link java.time.Duration} bound from the string {@code "40s"}, and a
 * conversion failure there is a startup failure that would only ever appear on the one run
 * someone was about to demo.
 * <p>
 * {@code count=0} so the emit loop does nothing: this checks wiring, not behaviour, and must
 * not try to reach a broker.
 */
@SpringBootTest(properties = {
        "langchain4j.open-ai.chat-model.api-key=test-key",
        "incident.simulator.count=0"
})
@ActiveProfiles("simulator")
class IncidentSimulatorContextTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void theSimulatorProfileWiresAndTheDurationBinds() {
        assertThat(context.getBeanNamesForType(IncidentSimulator.class))
                .as("the simulator profile activates the bean")
                .hasSize(1);
    }

    @Test
    void theSimulatorIsAbsentWithoutItsProfile() {
        // Guards the gate itself: a @Profile typo would leave this thing producing events - and
        // therefore Groq calls - on every ordinary boot.
        assertThat(IncidentSimulator.class.getAnnotation(org.springframework.context.annotation.Profile.class)
                .value())
                .containsExactly("simulator");
    }
}
