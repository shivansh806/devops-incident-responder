package com.shivansh.incidentresponder.seed;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shivansh.incidentresponder.model.ErrorType;
import com.shivansh.incidentresponder.model.Incident;
import com.shivansh.incidentresponder.repository.IncidentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Loads a set of already-resolved past incidents into MongoDB, so the Resolver Agent has a
 * history to retrieve from before a single real incident has been recorded.
 * <p>
 * <b>Running it.</b> Gated behind the {@code seed} profile, so it never fires on an ordinary
 * boot. In IntelliJ, put {@code seed} in the run configuration's <em>Active profiles</em>
 * field and start the app once; the log line at the end says what it did. Leaving the profile
 * on afterwards is harmless - the second run inserts nothing.
 * <p>
 * <b>Why the data is a JSON resource</b> rather than a {@code List.of(...)} in this class:
 * twenty incidents of postmortem prose is content, not logic, and putting it in
 * {@code seed/incidents.json} means it can be edited and extended without touching Java. The
 * cost is that a mistyped enum constant is no longer a compile error, which is what
 * {@link #validate} exists to catch.
 * <p>
 * <b>Why the ids are fixed.</b> Seeded incidents carry human ids ({@code INC-2103}) instead of
 * letting Mongo assign an {@code ObjectId}. That is what makes this idempotent without any
 * marker field or bookkeeping collection - the id in the file <em>is</em> the identity, so
 * "have I already loaded this one" is a lookup rather than a guess. It also keeps seeded
 * history visually distinct from real incidents, which carry 24-character hex ids, and it
 * matches the {@code similarIncidents: ["INC-2847", ...]} shape in the project brief.
 * <p>
 * <b>Why {@code insert} and not {@code save}.</b> {@code save()} on a document whose id already
 * exists is an upsert, so re-running would overwrite whatever is there. The next step writes an
 * embedding vector onto these same documents; an upsert-based seeder would silently wipe every
 * embedding on the next boot. {@code insert()} over the filtered list can only ever add.
 */
@Slf4j
@Component
@Profile("seed")
@RequiredArgsConstructor
public class IncidentSeeder implements ApplicationRunner {

    private static final String SEED_RESOURCE = "seed/incidents.json";

    private final IncidentRepository incidentRepository;
    private final ObjectMapper objectMapper;

    @Override
    public void run(ApplicationArguments args) throws IOException {
        List<Incident> seeds = loadSeedIncidents(objectMapper);
        validate(seeds);

        // One round trip for the whole set rather than an existsById per incident. findAllById
        // returns only the ids that are actually there, so the difference is what to insert.
        Set<String> alreadyStored = new HashSet<>();
        incidentRepository.findAllById(seeds.stream().map(Incident::id).toList())
                .forEach(stored -> alreadyStored.add(stored.id()));

        List<Incident> missing = seeds.stream()
                .filter(incident -> !alreadyStored.contains(incident.id()))
                .toList();

        if (missing.isEmpty()) {
            log.info("Incident seed already loaded - {} of {} present, nothing to do",
                    alreadyStored.size(), seeds.size());
            return;
        }

        incidentRepository.insert(missing);
        log.info("Seeded {} past incidents ({} were already present)", missing.size(), alreadyStored.size());
    }

    /**
     * Reads the seed file off the classpath. Static and public so the data can be checked by a
     * test without standing up a Spring context or a database.
     */
    public static List<Incident> loadSeedIncidents(ObjectMapper objectMapper) throws IOException {
        try (InputStream json = new ClassPathResource(SEED_RESOURCE).getInputStream()) {
            return objectMapper.readValue(json, new TypeReference<List<Incident>>() {
            });
        }
    }

    /**
     * Fails the boot on seed data that is wrong in a way Jackson will not complain about.
     * <p>
     * The two that matter are silent. {@link ErrorType#fromModel} is deliberately lenient so a
     * model returning an unknown class degrades to {@link ErrorType#OTHER} instead of throwing -
     * correct for LLM output, wrong here, where {@code OTHER} can only mean a typo. And a
     * duplicate id would make {@code insert} throw halfway through, leaving a partial seed.
     * Better to refuse to start than to fill the Resolver's history with junk it will retrieve.
     */
    private static void validate(List<Incident> seeds) {
        if (seeds.isEmpty()) {
            throw new IllegalStateException(SEED_RESOURCE + " contains no incidents");
        }

        Set<String> ids = new HashSet<>();
        for (Incident incident : seeds) {
            if (incident.id() == null || incident.id().isBlank()) {
                throw new IllegalStateException(SEED_RESOURCE + " has an incident with no id");
            }
            if (!ids.add(incident.id())) {
                throw new IllegalStateException(SEED_RESOURCE + " has duplicate id " + incident.id());
            }
            if (incident.errorType() == ErrorType.OTHER) {
                throw new IllegalStateException(
                        incident.id() + " has errorType OTHER - check the spelling against the ErrorType vocabulary");
            }
            if (incident.errorType() == null || incident.severity() == null) {
                throw new IllegalStateException(incident.id() + " is missing errorType or severity");
            }
            // These are resolved incidents by definition - the resolution is the only part the
            // Resolver Agent can actually learn from, so an unresolved one has no business here.
            if (incident.resolutionNotes() == null || incident.resolutionNotes().isBlank()) {
                throw new IllegalStateException(incident.id() + " has no resolutionNotes");
            }
            if (incident.keyEvidence() == null || incident.keyEvidence().isEmpty()) {
                throw new IllegalStateException(incident.id() + " has no keyEvidence");
            }
        }
    }
}
