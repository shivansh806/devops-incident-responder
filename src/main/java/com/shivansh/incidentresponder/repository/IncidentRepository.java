package com.shivansh.incidentresponder.repository;

import com.shivansh.incidentresponder.model.Incident;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Storage for analysed incidents.
 * <p>
 * Spring Data generates the implementation at startup from this interface -
 * {@code MongoRepository} already supplies {@code save}, {@code findById}, {@code findAll}
 * and the rest, so there is nothing to write. Query methods get added by declaring their
 * signature, as {@link #findByEmbeddingIsNull()} does.
 * <p>
 * The id type is {@code String} rather than {@code ObjectId}: it keeps the driver's types
 * out of the model and out of URLs, and Spring Data converts a valid hex string to an
 * {@code ObjectId} on the way in. A non-hex id is simply not found, which is the right
 * answer anyway.
 */
@Repository
public interface IncidentRepository extends MongoRepository<Incident, String> {

    /**
     * Incidents that have not been embedded yet - the backfill's work list.
     * <p>
     * Spring Data turns this into {@code {embedding: null}}, and in MongoDB that matches a
     * field explicitly set to null <em>and</em> a field that is absent entirely. Both cases
     * are real here: seeded documents have no {@code embedding} key at all, because Spring
     * Data omits nulls when writing, while a document could later be written with one. Asking
     * for null covers both, which is what makes re-running the backfill safe - anything
     * already embedded simply stops being returned.
     */
    List<Incident> findByEmbeddingIsNull();

    /**
     * The most recently analysed incidents, for the backlog a dashboard is replayed on connect.
     * <p>
     * Ordering comes from the {@link Pageable}, not from the method name, so the caller states
     * the direction it wants at the call site - {@code IncidentWebSocketHandler} queries
     * newest-first and then reverses, and the two halves of that decision are better read
     * together than split across a method name here.
     * <p>
     * <b>{@code fields} drops the embedding.</b> Ten documents carry 3,840 doubles between them,
     * none of which can ever reach the wire - {@link com.shivansh.incidentresponder.model.IncidentResponse#of}
     * does not expose it. Excluding it in the query means it is not fetched, not deserialised
     * and not sitting in heap next to a serialiser; the projection is the cheap place to
     * enforce what the API type already enforces.
     * <p>
     * Sorted on {@code analyzedAt} rather than {@code firstOccurrence} on purpose. The latter is
     * read out of the logs by the model, can be null and can be wrong; {@code analyzedAt} is
     * taken from the server clock and is the only field in the document trustworthy enough to
     * order by. Note this means the <em>seeded</em> incidents sort by when they were written up,
     * which is what makes them the whole backlog on a database with no live traffic yet.
     *
     * @param pageable page size caps the backlog, and its sort supplies the order
     */
    @Query(value = "{}", fields = "{ embedding: 0 }")
    List<Incident> findRecent(Pageable pageable);
}
