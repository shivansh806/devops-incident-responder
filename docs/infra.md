# Local infrastructure

Kafka and Redis for the Week 3 event pipeline, in `docker-compose.yml` at the repo root.
It exists to answer one question: **what does a Spring Boot app running on the host need
from a broker running in a container?**

Mongo is not here. It stays on Atlas — the vector index has no plain community-Mongo
equivalent, and `docs/retrieval.md` measures against the live `incident_embedding_index`.

## Running it

```
docker compose up -d --wait     # up, and block until both are healthy
docker compose down             # stop, keep the data
docker compose down -v          # stop and wipe the volumes
```

`--wait` is the part worth keeping. Without it `up -d` returns when the container process
has started, which for Kafka is roughly ten seconds before it can serve a client. The app
then starts, fails to connect, and the cause looks like a config error rather than a race.

## The advertised-listener problem

**A Kafka client does not keep talking to the address you gave it.** It connects to
`bootstrap.servers`, requests cluster metadata, receives *the addresses the broker
advertises for itself*, drops that connection and reconnects to those. The broker tells the
client where to go next.

So if the broker advertises `kafka:29092` — its name on the Docker network — a client on
the host resolves `kafka` to nothing. The symptom is misleading: the initial connection
**succeeds**, and then producing hangs and times out. It looks like a broker fault. It is a
name-resolution failure on the second connection.

The fix is two listeners, on two ports, advertising two different names:

| Listener | Binds | Advertises | Published to host | For |
|---|---|---|---|---|
| `PLAINTEXT_HOST` | 9092 | `localhost:9092` | **yes** | the app in IntelliJ |
| `PLAINTEXT` | 29092 | `kafka:29092` | no | containers (week 4) |
| `CONTROLLER` | 9093 | *never advertised* | no | KRaft quorum |

A listener binds one port and the advertised name is per-listener, which is exactly why
this needs two ports rather than one. Each client is told an address that is correct from
where it is standing.

Nothing runs in a container yet, so only the host listener is load-bearing today. The
internal one costs nothing and means week 4's containerised app does not require changing
broker metadata that consumer groups already hold offsets against.

> **The published port and the advertised port must be the same number.** Publishing
> `9094:9092` while advertising `localhost:9092` is the trap: the client fetches metadata
> from this broker and then reconnects to whatever else owns 9092 on the host. If the host
> port ever has to change, change `KAFKA_LISTENERS`, `KAFKA_ADVERTISED_LISTENERS` and the
> `ports:` entry together.

`CONTROLLER` is deliberately absent from `advertised.listeners`. It speaks the KRaft Raft
protocol, not the client protocol; advertising it hands clients an address that will never
answer them.

**Redis needs none of this.** A Redis client talks to the socket it opened and is never
redirected, so publishing the port is sufficient. The equivalent problem exists only in
Redis Cluster mode, via `MOVED` — part of why single-node is the right shape for a cache.

## Port and binding choices

Kafka's host listener is on **9092** and Redis on **6379** because those are Spring Boot's
defaults for `spring.kafka.bootstrap-servers` and `spring.data.redis.port`. Step 2 needs no
connection configuration at all.

Both are published as `127.0.0.1:PORT:PORT`, not `PORT:PORT`. The short form binds every
interface, which puts an unauthenticated broker and an unauthenticated Redis on whatever
network this laptop is attached to. The long form is identical for a host-run app and
invisible to the LAN.

Note that 9092 and 6379 are popular. If another project's stack is up, `up` fails with
`port is already allocated` — stop the other stack rather than renumbering this one, or
renumber following the warning above.

## KRaft, and why the cluster id is pinned

No ZooKeeper: the single node runs as both broker and controller
(`KAFKA_PROCESS_ROLES=broker,controller`), with the quorum voter list pointing at itself on
9093.

`CLUSTER_ID` is a fixed literal rather than generated, because it is written into
`meta.properties` on the data volume the first time the broker formats storage. A freshly
generated id on the next `up` would not match the volume and the broker would refuse to
start.

`KAFKA_LOG_DIRS` is set explicitly to `/var/lib/kafka/data`. The image defaults to
`/tmp/kraft-combined-logs`, which lives in the container filesystem — every recreate would
silently lose topics, consumer offsets and the formatted cluster id. That directory exists
in the image owned by `appuser` (uid 1000), so the named volume inherits usable ownership;
a path that does not exist in the image would be created root-owned and the broker could
not write to it.

The four replication settings default to 3 and would leave the internal `__consumer_offsets`
topic permanently under-replicated on a one-node cluster.

## Persistence, and the tension in it

Both services get named volumes, and Redis runs with `--appendonly yes`.

The reason is the Groq budget. `docs/baseline.md` records 100k tokens/day and four full
baseline runs exhausting it; a response cache that evaporates on `docker compose down`
costs real quota. Snapshotting alone would be lossy across a restart, so AOF.

**The tension, recorded before it bites:** a persistent cache can serve a stale response
after a prompt edit, which is exactly the failure that would corrupt a baseline run. That
is a cache-key problem — the key needs to include something that changes when the prompt or
model changes — and it belongs to the step that builds the cache, not here. Until then,
`docker compose down -v` is the clean slate.

## Verified 2026-08-16

Four checks. The first three were run **from the host**, not via `docker exec`, because a
container-side check is precisely what would hide an advertised-address bug.

| check | method | result |
|---|---|---|
| Redis reachable | raw socket to `127.0.0.1:6379`, sent `PING` | `+PONG` |
| Kafka reachable | raw Metadata v1 request to `127.0.0.1:9092` | 37-byte response |
| **Advertised address** | brokers array in that response | `node 1 -> localhost:9092` |
| Broker actually works | real client, `--network host`, create/produce/consume/delete | 3 of 3 records |

The third is the one that matters and the one the first two cannot catch: a broker
advertising `kafka:29092` passes both reachability checks and still leaves a host client
hanging.

Persistence was checked separately with a full `down` / `up --wait` cycle — which destroys
the containers, so only the volumes carry state. A Redis key and a Kafka topic both
survived. Cold start to both healthy is ~12s.
