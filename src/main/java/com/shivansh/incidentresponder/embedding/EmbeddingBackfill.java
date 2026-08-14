package com.shivansh.incidentresponder.embedding;

import com.shivansh.incidentresponder.model.Incident;
import com.shivansh.incidentresponder.repository.IncidentRepository;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.output.TokenUsage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Writes an embedding vector onto every stored incident that does not have one.
 * <p>
 * <b>Running it.</b> Two switches, because this rewrites documents. The {@code embed} profile
 * makes the bean exist at all, and {@code --write} arms it. Without {@code --write} it does
 * the entire job except the database write: it selects the documents, builds the text,
 * embeds it, and prints what it would have stored. That is the intended first run - read the
 * corpus, then run it again armed.
 * <pre>
 *   Active profiles:    embed
 *   Program arguments:  --write
 * </pre>
 * <p>
 * <b>Idempotent by absence, not by a marker.</b> The work list is "embedding is null", so a
 * second run finds nothing and does nothing, in the same spirit as {@code IncidentSeeder}
 * being idempotent by its fixed ids. Nothing has to be remembered between runs.
 * <p>
 * <b>Why the write is a targeted {@code $set}.</b> {@code save()} would rewrite the whole
 * document from an in-memory copy, so any field this application does not know about - or
 * anything changed by someone else since the read - would be silently overwritten. Setting
 * one field touches one field. This is the same hazard {@code IncidentSeeder} avoids by using
 * {@code insert} over {@code save}, seen from the other side.
 * <p>
 * <b>Why all the embedding happens before any of the writing.</b> Embedding is in-process and
 * can throw; the database write is a separate trip that can also fail. Doing all of one and
 * then all of the other means a failure while embedding leaves the collection completely
 * untouched, rather than half-embedded in an order nobody chose.
 */
@Slf4j
@Component
@Profile("embed")
@RequiredArgsConstructor
public class EmbeddingBackfill implements ApplicationRunner {

    /** Program argument that turns the dry run into a real one. */
    static final String WRITE_OPTION = "write";

    private final IncidentRepository incidentRepository;
    private final EmbeddingModel embeddingModel;
    private final MongoTemplate mongoTemplate;

    @Override
    public void run(ApplicationArguments args) {
        List<Incident> pending = incidentRepository.findByEmbeddingIsNull();
        if (pending.isEmpty()) {
            log.info("Every stored incident already has an embedding - nothing to back fill");
            return;
        }

        List<EmbeddedIncident> embedded = embedAll(pending);
        report(embedded);

        if (!args.containsOption(WRITE_OPTION)) {
            log.info("DRY RUN - {} vectors built, none written. Re-run with --write to store them.",
                    embedded.size());
            return;
        }

        for (EmbeddedIncident incident : embedded) {
            mongoTemplate.updateFirst(
                    Query.query(Criteria.where("_id").is(incident.id())),
                    new Update().set("embedding", incident.vector()),
                    Incident.class);
        }
        log.info("Wrote {} embeddings of {} dimensions", embedded.size(), embeddingModel.dimension());
    }

    /**
     * One incident at a time rather than {@code embedAll}, which would be faster and
     * parallel. The batch call returns a single total token count for the whole list, and the
     * per-incident number is the point of the survey - it is what says whether any one text
     * is drifting towards the length where quality starts to fall off. Twenty sequential
     * embeddings take about a second.
     */
    private List<EmbeddedIncident> embedAll(List<Incident> pending) {
        List<EmbeddedIncident> embedded = new ArrayList<>(pending.size());
        for (Incident incident : pending) {
            String text = IncidentEmbeddingText.of(incident);
            Response<Embedding> response = embeddingModel.embed(text);
            embedded.add(new EmbeddedIncident(
                    incident.id(), text, toDoubles(response.content()), inputTokens(response)));
        }
        return embedded;
    }

    /**
     * Prints the whole corpus as one block: every text exactly as it was embedded, with its
     * token and character count, longest first.
     * <p>
     * This is not debug noise. What gets embedded is the one decision that determines whether
     * retrieval can tell two incidents apart, and it is invisible everywhere else - the
     * database holds 384 numbers that mean nothing to a reader. Printing it is the only
     * chance to see the corpus as the model sees it.
     */
    private void report(List<EmbeddedIncident> embedded) {
        List<EmbeddedIncident> byLength = embedded.stream()
                .sorted(Comparator.comparingInt(EmbeddedIncident::tokens).reversed())
                .toList();

        StringBuilder block = new StringBuilder(String.format(
                "%nEmbedding corpus - %d incidents, %d dimensions, longest first%n", embedded.size(),
                embeddingModel.dimension()));
        for (EmbeddedIncident incident : byLength) {
            block.append(String.format("%n--- %s | %d tokens | %d chars ---%n%s%n",
                    incident.id(), incident.tokens(), incident.text().length(), incident.text()));
        }

        int longest = byLength.isEmpty() ? 0 : byLength.getFirst().tokens();
        block.append(String.format("%nLongest text: %d tokens. Quality is documented to hold to "
                + "about 256; input is not truncated above it, it just degrades.%n", longest));
        log.info(block.toString());
    }

    /**
     * The model reports its own token count, so this is measured rather than estimated. A
     * null usage would mean a model that does not report one - worth showing as -1 rather
     * than a plausible-looking zero.
     */
    private static int inputTokens(Response<Embedding> response) {
        TokenUsage usage = response.tokenUsage();
        return usage == null || usage.inputTokenCount() == null ? -1 : usage.inputTokenCount();
    }

    /**
     * The model produces {@code float}s and the document stores {@code Double}s, which is
     * what BSON has - the widening is exact, and cosine similarity over 384 dimensions does
     * not care about the difference.
     */
    private static List<Double> toDoubles(Embedding embedding) {
        float[] vector = embedding.vector();
        List<Double> doubles = new ArrayList<>(vector.length);
        for (float value : vector) {
            doubles.add((double) value);
        }
        return doubles;
    }

    private record EmbeddedIncident(String id, String text, List<Double> vector, int tokens) {
    }
}
