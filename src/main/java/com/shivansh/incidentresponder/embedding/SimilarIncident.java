package com.shivansh.incidentresponder.embedding;

import com.shivansh.incidentresponder.model.Incident;

/**
 * One retrieval hit: a past incident, and how close it was.
 * <p>
 * The score is carried alongside rather than discarded because retrieval is a judgement, not
 * a lookup. A top match at 0.91 and a top match at 0.42 both arrive as "the closest one", and
 * only the number distinguishes "this happened before" from "nothing here resembles it". The
 * Resolver Agent needs that distinction to avoid presenting an unrelated incident as
 * precedent, and it is what makes retrieval quality measurable at all.
 * <p>
 * Cosine similarity as Atlas reports it: 1.0 is identical, and values here run roughly 0.4
 * to 0.9. Note that Atlas normalises the score into 0-1, so it is not the raw cosine value of
 * -1 to 1 and should not be compared against one computed by hand elsewhere.
 *
 * @param incident the stored past incident, with its embedding stripped
 * @param score    similarity to the query, higher being closer
 */
public record SimilarIncident(Incident incident, double score) {
}
