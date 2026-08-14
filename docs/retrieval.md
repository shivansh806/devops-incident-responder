# Retrieval measurement

Vector search over past incidents, and what it can and cannot be trusted to do. It exists to
answer one question: **when a new incident arrives, does similarity search return the past
incidents that actually help?**

The alternative it has to beat is filtering on `errorType`. That would be simpler, free, and
already possible — so if retrieval cannot do something filtering cannot, it is not worth its
200MB.

- Query side: `src/main/java/com/shivansh/incidentresponder/embedding/SimilarIncidentSearch.java`
- Text recipe: `src/main/java/com/shivansh/incidentresponder/embedding/IncidentEmbeddingText.java`
- Backfill: `src/main/java/com/shivansh/incidentresponder/embedding/EmbeddingBackfill.java`

## Setup these numbers came from

| | |
|---|---|
| Corpus | 21 documents — the 20 seeded incidents plus one test incident from the Mongo step |
| Model | all-MiniLM-L6-v2, in-process ONNX, 384 dimensions |
| Index | `incident_embedding_index` on `incidents.embedding`, cosine |
| Search | `ENN` (exact), not approximate |
| Embedded text | 46–85 tokens, mean 71 |

Scores are Atlas's normalised cosine, `(1 + cos) / 2`: 1.0 is identical, and anything below
about 0.5 is unrelated. They are **not** raw cosine and should not be compared against a
cosine computed elsewhere.

## What it costs, and why that matters here

**Nothing, and it is deterministic.** The model runs locally, so a measurement run makes no
API calls and consumes no Groq quota. The same query returns the same vector and the same
ranking every time.

That is a different epistemic situation from `baseline.md`, and worth being explicit about.
The Analyzer baseline needs three runs before a change counts as a regression, because the
model's output is a distribution and three runs is the entire daily budget. Retrieval has no
distribution. **One run is the answer**, and any number in this document can be regenerated
exactly and for free. Where a claim here is uncertain it is because the *corpus* is small or
unrepresentative, never because the measurement is noisy.

## The three questions

Each pool incident was used as its own query, with itself excluded from the results. All
numbers are live, from `SimilarIncidentSearch` against the real index.

### 1. Does rank order inside the cluster mean anything? No.

| query | #1 | #2 | #3 |
|---|---|---|---|
| **INC-2103** leak | INC-2331 `0.9434` | INC-2464 `0.9157` | INC-2378 `0.8283` |
| **INC-2331** undersized | INC-2464 `0.9434` | INC-2103 `0.9434` | INC-2378 `0.8393` |
| **INC-2464** downstream | INC-2331 `0.9434` | INC-2103 `0.9157` | INC-2378 `0.8082` |

Recall into the cluster is perfect — every pool query returns the other two pool incidents
ahead of everything else in the corpus.

The ordering inside it is worth nothing. The spread is **0.03** (0.9157 to 0.9434). The shared
boilerplate — the identical `Connection is not available` line, the `Pool stats (...)` format,
the headline — pins them at roughly 0.92, and the one evidence line that actually distinguishes
the three causes moves the score by a rounding error on top of that.

The sharper version: by *conclusion*, INC-2103 and INC-2464 belong together. Both found the
pool was a symptom and explicitly refused to grow it; INC-2331 grew it. That pair scores
`0.9157` — **the lowest of the three**, with INC-2331 sitting in the middle as everyone's
nearest neighbour. Symptom similarity does not track conclusion similarity.

This is not a defect in the recipe and tuning will not fix it. What separates the three causes
is written in `resolutionNotes`, which cannot be embedded — a new incident does not have one,
so putting it on the stored side would compare postmortem prose against raw symptoms.
Retrieval's job is to deliver the candidate set; choosing within it is the Resolver's, and
INC-2331's notes already state the discriminator in words ("acquisition time versus query time
is what separates the two").

> **Nothing downstream may trust intra-cluster rank order.** Do not take "the top match" as
> the answer, do not weight by rank, and do not drop candidates because they came third. A
> 0.03 margin is not a signal. Treat a tight cluster as an unordered set.

### 2. What `affectedService` costs: nothing measurable.

Every ranking was recomputed with `affectedService` removed from the embedded text. **Top-3
membership and order were identical in all four queries.** The single difference is INC-2331's
#1/#2 changing places, and those were an exact tie (`0.9434` vs `0.9434`) with it — a tie
breaking, not a reordering.

It moves scores in exactly the predicted directions, never far enough to matter:

| pair type | with | without | delta |
|---|---|---|---|
| same service, unrelated failures (6 pairs) | 0.65–0.76 | 0.57–0.69 | **+0.035 … +0.091** |
| cross-service pool pairs (3 pairs) | 0.92–0.94 | 0.95–0.96 | **−0.018 … −0.031** |

The same-service pull is two to three times stronger than the cross-service push. But the
cross-service penalty of ~0.02 is dwarfed by the gap between the pool cluster (0.92) and the
next tier (0.83), so the risk it was flagged for — burying exactly the cross-service matches
the trio exists to test — did not materialise. Nor did the boost corrupt anything in the other
direction: INC-2507 shares a service with INC-2378 and still ranked #4 at `0.7618`, below all
three pool incidents.

**Decision: keep it.** Measured cost is zero rankings changed.

The caveat, recorded rather than acted on: every same-service pair in this corpus is
*unrelated failures that happen to share a service*. There is no genuine same-service
recurrence — the case `affectedService` is actually for. So it is measured to pull same-service
history together, and not yet measured to be useful in doing so.

### 3. Does INC-2378 pull into the pool cluster? Yes — and that is the result worth having.

INC-2378 is `THREAD_POOL_EXHAUSTED`, but its evidence says its threads were `BLOCKED state
inside HikariPool.getConnection`. It ranks **#3 in all three pool queries** (0.8082–0.8393),
clearly separated from #4 (0.7278–0.7675). Its own query returns the pool trio as its entire
top 3, ahead of INC-2507, which shares its service.

It is a connection-pool incident presenting as thread-pool exhaustion, and it is retrieved as
one. **`errorType` filtering could never produce this match** — different value, excluded by
construction. This single result is the concrete answer to "why not just filter?"

## How many results to return

**Three is enough. The fourth slot does not buy a third contrasting resolution.**

This was nearly changed on a misreading of the table above. Those queries exclude self, because
the query *was* a stored incident — so a pool query appears to return only two of the three
contrasting resolutions, with INC-2378 taking the third slot.

Production has no self to exclude. A new incident is stored with a null embedding and Atlas
does not index a document whose vector field is missing, so it cannot retrieve itself. Queried
with a synthetic new pool incident — different service, different numbers, different third
evidence line — against the live index:

```
  #1 0.9602  INC-2103  CONNECTION_POOL_EXHAUSTED    payment-service
  #2 0.9454  INC-2331  CONNECTION_POOL_EXHAUSTED    order-service
  #3 0.9202  INC-2464  CONNECTION_POOL_EXHAUSTED    inventory-service
  #4 0.8484  INC-2378  THREAD_POOL_EXHAUSTED        notification-service
  #5 0.7479  INC-2146  UPSTREAM_TIMEOUT             checkout-service
```

All three contrasting resolutions arrive at `limit = 3`. Raising to 4 buys INC-2378 — the
cross-type match — not the third pool incident, and that is a real but speculative benefit
paid for on every Resolver call in prompt tokens.

Also visible here: INC-2146 is on `checkout-service`, the same service as the query, and still
ranks below a different-service incident. Service does not dominate.

`findSimilar` takes `limit` as a parameter and hardcodes nothing, so this is a decision for the
Resolver's call site, to be made when there is evidence about what the Resolver actually needs.

## Known limitations

Deliberately left, not overlooked.

**The corpus is 21 documents and one person wrote 20 of them.** Every number here is measured
on a set built to make retrieval look like a fair test, by the same person who then judged the
results. That is enough to establish that the mechanism works and to catch gross failures. It
is not enough to claim a quality level.

**No genuine same-service recurrence exists**, so the main argument for `affectedService` is
untested. See question 2.

**The 21st document is a test incident**, not seeded data — an out-of-shape record with one
evidence line and no service name. It has never appeared in any top-5 of any query run so far.
Kept deliberately: it is the only irregular document in the collection.

**Intra-cluster ordering is unreliable and this will not improve with tuning.** It is a
property of what can be embedded, not of the model or the recipe. The Resolver has to do the
discriminating.

## Status: verified 2026-08-14

Measured against the live `incident_embedding_index` with all 21 documents embedded. The
with-`affectedService` figures were independently recomputed offline by exact cosine and match
the Atlas scores to four decimal places, which is what makes the counterfactual — run offline,
since it needs vectors that do not exist in Atlas — trustworthy.
