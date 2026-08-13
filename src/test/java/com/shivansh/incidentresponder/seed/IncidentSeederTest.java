package com.shivansh.incidentresponder.seed;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shivansh.incidentresponder.model.ErrorType;
import com.shivansh.incidentresponder.model.Incident;
import com.shivansh.incidentresponder.model.Severity;
import com.shivansh.incidentresponder.repository.IncidentRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.toSet;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

/**
 * Two things are under test here and they fail for different reasons.
 * <p>
 * The first group checks the <em>data</em>: the seed set is the Resolver Agent's entire world
 * until real incidents accumulate, and if it is thin or uniform then next week's retrieval will
 * look like it works when it has simply had nothing to distinguish. Those assertions are
 * deliberately about shape - spread, repetition, contrast - rather than about any one incident,
 * so the file can be edited freely as long as it keeps the properties that make retrieval a
 * real test.
 * <p>
 * The second group checks the <em>loader</em>, and the assertion that matters is that a second
 * run writes nothing at all.
 */
@ExtendWith(MockitoExtension.class)
class IncidentSeederTest {

    /** Close enough to what Spring Boot builds at runtime; the part that matters is JavaTimeModule. */
    private static final ObjectMapper OBJECT_MAPPER = Jackson2ObjectMapperBuilder.json().build();

    private static List<Incident> seeds;

    @Mock
    private IncidentRepository incidentRepository;

    @Captor
    private ArgumentCaptor<List<Incident>> inserted;

    @BeforeAll
    static void loadTheSeedFile() throws IOException {
        seeds = IncidentSeeder.loadSeedIncidents(OBJECT_MAPPER);
    }

    // --- the data -------------------------------------------------------------------------

    @Test
    void holdsBetween15And20PastIncidents() {
        assertThat(seeds).hasSizeBetween(15, 20);
    }

    @Test
    void givesEveryIncidentAStableHumanIdSoReloadingCannotDuplicateIt() {
        Set<String> ids = seeds.stream().map(Incident::id).collect(toSet());
        assertThat(ids).hasSameSizeAs(seeds);
        assertThat(ids).allSatisfy(id -> assertThat(id).matches("INC-\\d{4}"));
    }

    @Test
    void resolvesEveryIncidentWithNotesLongEnoughToSayWhatWasActuallyDone() {
        // The length floor is the point of the assertion. A one-line "restarted the service"
        // parses fine and is worth nothing to retrieve, so it has to fail here rather than
        // quietly become the corpus.
        assertThat(seeds).allSatisfy(incident ->
                assertThat(incident.resolutionNotes()).as(incident.id()).isNotNull().hasSizeGreaterThan(200));

        Set<String> notes = seeds.stream().map(Incident::resolutionNotes).collect(toSet());
        assertThat(notes).as("no two incidents were fixed the same way").hasSameSizeAs(seeds);
    }

    @Test
    void quotesEvidenceOnEveryIncident() {
        assertThat(seeds).allSatisfy(incident ->
                assertThat(incident.keyEvidence()).as(incident.id()).isNotEmpty());
    }

    @Test
    void keepsEveryErrorTypeInsideTheVocabulary() {
        // OTHER here can only mean a spelling that fromModel could not place - the parse is
        // lenient by design and will not throw on one.
        assertThat(seeds).allSatisfy(incident ->
                assertThat(incident.errorType()).as(incident.id()).isNotNull().isNotEqualTo(ErrorType.OTHER));
    }

    @Test
    void coversEnoughOfTheVocabularyToProduceClearNonMatches() {
        Set<ErrorType> covered = seeds.stream().map(Incident::errorType).collect(toSet());
        assertThat(covered).hasSizeGreaterThanOrEqualTo(8);
    }

    @Test
    void repeatsTheCommonErrorTypesSoRetrievalHasNearMatchesToSeparate() {
        Map<ErrorType, List<Incident>> byType = seeds.stream().collect(groupingBy(Incident::errorType));

        for (ErrorType common : List.of(
                ErrorType.CONNECTION_POOL_EXHAUSTED,
                ErrorType.OUT_OF_MEMORY,
                ErrorType.UPSTREAM_TIMEOUT,
                ErrorType.CACHE_UNAVAILABLE,
                ErrorType.EXPIRED_CERTIFICATE)) {
            assertThat(byType.get(common)).as("%s incidents", common).hasSizeGreaterThanOrEqualTo(2);
        }
    }

    @Test
    void makesRepeatedErrorTypesDisagreeOnServiceAndOnFix() {
        // This is the property that makes the embedding step worth doing at all. If every
        // ConnectionPoolExhausted incident had the same cause and the same fix, matching on
        // errorType alone would be sufficient and vector search would be decoration.
        Map<ErrorType, List<Incident>> byType = seeds.stream().collect(groupingBy(Incident::errorType));

        byType.forEach((errorType, sameType) -> {
            if (sameType.size() > 1) {
                Set<String> services = sameType.stream().map(Incident::affectedService).collect(toSet());
                assertThat(services).as("%s incidents all hit different services", errorType)
                        .hasSameSizeAs(sameType);
            }
        });
    }

    @Test
    void variesTheServices() {
        Set<String> services = seeds.stream().map(Incident::affectedService).collect(toSet());
        assertThat(services).hasSizeGreaterThanOrEqualTo(10);
    }

    @Test
    void usesEverySeverity() {
        Set<Severity> severities = seeds.stream().map(Incident::severity).collect(toSet());
        assertThat(severities).containsExactlyInAnyOrder(Severity.values());
    }

    @Test
    void spreadsTheIncidentsAcrossRoughlySixMonths() {
        List<Instant> occurrences = seeds.stream().map(Incident::firstOccurrence).sorted().toList();

        // Relative rather than absolute, so the assertion does not rot as the calendar moves.
        assertThat(Duration.between(occurrences.getFirst(), occurrences.getLast()))
                .isGreaterThan(Duration.ofDays(150));
        assertThat(occurrences.getLast()).as("these are past incidents").isBefore(Instant.now());
    }

    @Test
    void analysesEveryIncidentAfterItStartedRatherThanBefore() {
        assertThat(seeds).allSatisfy(incident -> assertThat(incident.analyzedAt()).as(incident.id())
                .isAfter(incident.firstOccurrence()));
    }

    @Test
    void spreadsConfidenceRatherThanStampingEveryIncidentWithTheSameScore() {
        assertThat(seeds).allSatisfy(incident ->
                assertThat(incident.confidence()).as(incident.id()).isBetween(0.5, 1.0));

        double lowest = seeds.stream().mapToDouble(Incident::confidence).min().orElseThrow();
        double highest = seeds.stream().mapToDouble(Incident::confidence).max().orElseThrow();
        assertThat(highest - lowest).isGreaterThan(0.15);
    }

    // --- the loader -----------------------------------------------------------------------

    @Test
    void insertsTheWholeSetIntoAnEmptyDatabase() throws Exception {
        given(incidentRepository.findAllById(anyIterable())).willReturn(List.of());

        seeder().run(new DefaultApplicationArguments());

        then(incidentRepository).should().insert(inserted.capture());
        assertThat(inserted.getValue()).containsExactlyElementsOf(seeds);
    }

    @Test
    void writesNothingOnASecondRun() throws Exception {
        given(incidentRepository.findAllById(anyIterable())).willReturn(seeds);

        seeder().run(new DefaultApplicationArguments());

        then(incidentRepository).should(never()).insert(anyList());
    }

    @Test
    void insertsOnlyWhatIsMissingWhenAnEarlierRunGotPartWayThrough() throws Exception {
        List<Incident> half = seeds.stream()
                .sorted(Comparator.comparing(Incident::id))
                .limit(seeds.size() / 2)
                .toList();
        given(incidentRepository.findAllById(anyIterable())).willReturn(half);

        seeder().run(new DefaultApplicationArguments());

        then(incidentRepository).should().insert(inserted.capture());
        assertThat(inserted.getValue())
                .hasSize(seeds.size() - half.size())
                .doesNotContainAnyElementsOf(half);
    }

    private IncidentSeeder seeder() {
        return new IncidentSeeder(incidentRepository, OBJECT_MAPPER);
    }
}
