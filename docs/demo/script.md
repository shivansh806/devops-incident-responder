# Demo recording script — 2–3 minutes

The one moment that has to land is a **`decidingEvidence` line naming one precedent over
another**. Everything else is setup for it.

---

## Read this first: two corrections to the plan

**1. A repeat form submission is cheaper, not free — and the `decidingEvidence` text changes
every time.**

Only the **Analyzer** is cached. `ResolverService` deliberately is not, because its input
includes the retrieved precedent. So re-submitting identical log text costs ~3,600 tokens for a
fresh Resolver call, and **produces a different `decidingEvidence` sentence each time.**

The consequence matters: **you cannot rehearse the money shot by re-submitting.** If you submit
live on camera, you are rolling for the line you have to narrate, in the take.

**2. So don't create the hero incident on camera. Create it before you record.**

Once it is stored, it sits in the feed permanently and clicking it is **free and identical every
time**. Rehearse the narration against the real, known text as often as you like. The live
moment in the video is then the Kafka event, whose job is only to *appear* — it does not have to
say anything clever.

That decouples "the moment that must land" from "a model call that might come back bland".

---

## Before you hit record

### 1. Bring the stack up

```bash
docker compose --profile app up -d --wait --build
```

### 2. Create the hero incident (~9,800 tokens)

Open http://localhost:8080, paste the whole of [`incident-pool.log`](incident-pool.log) into the
form, **leave the service field blank**, and submit. Wait up to a minute.

### 3. Check the `decidingEvidence` line — this is a casting call, not a formality

Click the new incident. Read the **Deciding evidence** section. You want a sentence that names a
precedent and rules out another. Something in the shape of:

> *"Connection acquisition time rose while statement execution stayed flat and no slow query was
> logged, which matches INC-2331's undersized pool rather than INC-2464's missing index."*

**Re-submit if you get** `PRECEDENTS AGREE`, `NO RELEVANT PRECEDENT`, `NOT STATED`, or a vague
sentence that names nothing. A re-submit costs ~3,600 (the Analyzer is cached) and re-rolls the
line. Budget two or three rolls.

Why this log dump is the right bait: the corpus holds **three** connection-pool incidents with
**three different conclusions**, and the dump contains a discriminator against each one.

| Precedent | Its conclusion | What the dump says to rule it out |
|---|---|---|
| **INC-2103** payment-service | a transaction held a connection across an outbound HTTP call | *"no outbound HTTP calls recorded inside transaction scope"* |
| **INC-2464** inventory-service | a dropped index made queries slow; the pool was a symptom | *"statement execution p99 unchanged at 7ms"*, *"no statement exceeded the 500ms threshold"* |
| **INC-2331** order-service | genuinely undersized pool, traffic tripled | *"Requests/sec 214 (7-day mean 112); no deploy in the last 14 days"* ← the one left standing |

The service is `checkout-service`, which is **not** any seeded service, on purpose. The match has
to come from the symptoms rather than from a matching service name.

### 4. Take the README screenshot now

While rehearsing, with the hero incident's detail panel open. Free, and you are already there.
Frame it so **Deciding evidence** and **Precedents drawn on** are both visible — that pairing is
the whole argument of the project in one image.

### 5. Rehearse

Click between incidents, talk over the detail panel, get the timing. **Costs nothing** — reading
a stored incident makes no model call.

### 6. Reset the view and stage your windows

Reload the page so the feed is freshly sorted and nothing is selected.

**Open and ready:**
- Browser, full screen, http://localhost:8080, hero incident visible in the feed, nothing selected
- A terminal, in the repo root, with the Kafka command **typed but not run**
- Browser zoom at ~125% — the detail panel text has to be readable after video compression
- Notifications off

---

## The script

Times are cumulative. Narration is written to be said out loud, not read.

### 0:00 – 0:20 · The problem

**Show:** the dashboard, feed populated, nothing selected. Don't click yet.

> "An alert fires at two in the morning. The on-call engineer opens ten thousand lines of logs,
> searches Slack for whether this has happened before, and forty minutes later works out what
> broke. The fix takes five minutes.
>
> This is a system that tries to give back that forty minutes. It reads the logs, works out what
> failed, and then — the part I actually care about — it checks the failure against incidents
> this team has already resolved."

### 0:20 – 0:35 · What you're looking at

**Show:** scroll the feed slowly, one pass. Hover a severity badge.

> "This is the live feed. Every incident the system has diagnosed, newest first. The ones with
> the INC numbers are the team's own resolved history — real incidents, with the write-up the
> engineer left after actually fixing them. That history is what the system reasons against."

### 0:35 – 1:00 · Open the hero incident

**Show:** click the `checkout-service` pool incident. Let the detail panel land. Scroll to
**Key evidence**.

> "Here's one it diagnosed. Connection pool exhausted on checkout-service. It pulled out the
> lines that mattered — the pool at forty of forty connections, nineteen requests queued.
>
> Nothing surprising so far. Any language model can read a stack trace."

*(That last line is doing work. It sets up the contrast.)*

### 1:00 – 1:45 · **The moment**

**Show:** scroll to **Deciding evidence**. Pause. Let it sit on screen a beat before speaking.

> "This is the part that isn't a chatbot.
>
> Three past incidents in this company's history look almost identical to this one — same error,
> same symptoms — and they had three completely different causes. One was code holding a
> connection open across a network call. One was a missing database index. One was a pool that
> was simply too small for the traffic it now gets.
>
> Same symptoms. Opposite fixes. Picking the wrong one costs you the night."

**Show:** now read the actual line on screen. Read it verbatim — don't paraphrase.

> "So the system's first output isn't the answer. It's the *reason*. —" *(read the line)*
>
> "That's it naming the observation that ruled the others out. And when there's nothing to
> decide between, it has to say so — it returns 'precedents agree', or 'no relevant precedent',
> rather than inventing a rationale."

**Show:** scroll to **Precedents drawn on** — the cited incidents with their human write-ups.

> "And these are the incidents it drew on. Real ones, with what a human wrote after fixing them.
> The system's own guesses never get written back into that history — otherwise it would start
> quoting itself as evidence."

### 1:45 – 2:15 · Prove it's live

**Show:** cut to the terminal. Run the command, then cut straight back to the browser.

**Test this exact line in your exact shell before recording.** The two shells need different
forms and both fail in ways that would ruin a take.

PowerShell — **PowerShell has no `<` input redirection at all**, so it must be a pipe:

```powershell
Get-Content docs/demo/kafka-event.json | docker compose exec -T kafka /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server localhost:9092 --topic incident-events
```

Git Bash — needs `MSYS_NO_PATHCONV=1`, or Git Bash rewrites `/opt/kafka/...` into
`C:/Program Files/Git/opt/kafka/...` and the command fails with "No such file or directory":

```bash
MSYS_NO_PATHCONV=1 docker compose exec -T kafka /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server localhost:9092 --topic incident-events < docs/demo/kafka-event.json
```

*(Verified: `/opt/kafka/bin/kafka-console-producer.sh` exists in the running container and the
`incident-events` topic is present. The producer prints nothing on success and returns to the
prompt — that silence is correct, and the dashboard is where you look for the result.)*

> "The dashboard isn't the only way in. Alerts arrive on a Kafka topic — that's one now, from a
> monitoring system, and it doesn't say which service is broken. It just carries the logs."

**Show:** the browser. The new incident appears at the top of the feed on its own, no refresh.
Click it.

> "It arrives over a WebSocket, no refresh. Certificate expired on billing-service — and nothing
> in that alert told it which service that was. It read that out of the logs."

*(Roughly ten seconds end to end, measured. Fill with the sentence above; if it's slower, say
"two model calls, so give it a moment.")*

### 2:15 – 2:40 · Close

**Show:** back to the whole dashboard, or cut to the README.

> "The whole thing is one Docker command — Kafka, Redis, the API and the dashboard, all served
> from a single origin. Diagnoses are cached, because the model has a token budget and running
> out of it mid-incident is a real failure mode.
>
> It's honest about what it can't do, and that's in the README: no authentication yet, retrieval
> ranking that I can show you is unreliable inside a cluster, and accuracy numbers that predate a
> model change and haven't been re-measured. The reasoning behind every decision is written down,
> including the ones I got wrong and reversed."

**End.**

---

## Budget

| | Cost |
|---|---|
| Hero incident, first attempt | ~9,800 |
| Each re-roll for a better `decidingEvidence` | ~3,600 |
| Rehearsing — clicking stored incidents | **0** |
| Kafka event on camera | ~9,800 |
| Each re-take of the Kafka moment | ~9,800 |

Against 100,000/day. A comfortable session — hero plus two re-rolls plus two Kafka takes — is
about 37,000. **Do the hero first**, since the Kafka takes are the expensive repeats and you want
budget left for a second one.

## If something goes wrong on camera

**The `decidingEvidence` line is bland on the Kafka incident.** Fine — don't narrate it. That
incident's job is to *appear*. The money shot is the hero, which you already checked.

**The Kafka event takes much longer than ten seconds.** That is the Resolver being rate-limited,
waiting 15s and retrying — designed behaviour, not a hang. Say "two model calls" and wait.

**The new incident shows no resolution at all.** The retry was refused too. It stores the
diagnosis rather than losing it, and the dashboard says so explicitly. It is a legitimate thing
to show — "it degrades instead of failing" — but only if you are comfortable improvising.
Otherwise cut and re-run.

**The feed is empty on load.** The socket didn't connect. Check the status badge in the header
and reload; if it still fails, `docker compose --profile app restart app`.
