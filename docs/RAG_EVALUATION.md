# RAG evaluation

Measures how well retrieval and answering actually work, against a fixed set of
questions with known answers, separately from live traffic.

Without this, "is the bot any good?" can only be answered by asking it a few
things and forming an impression. That impression cannot be compared between
two weeks, and it will not tell you whether the reranker you just enabled made
things better or worse.

---

## Dataset format

JSONL — one JSON object per line. `.json` holding a list also works.

```jsonl
# leave questions
{"id": "leave-001", "question": "How many days of annual leave do full-time employees get?", "expected_sources": ["HR Policy.pdf"], "reference_answer": "Full-time employees receive 25 days of paid annual leave per calendar year."}
{"id": "leave-002", "question": "What about part-time staff?", "expected_sources": ["HR Policy.pdf"]}

# the corpus cannot answer these
{"id": "abstain-001", "question": "What is the CEO's home address?", "should_abstain": true}
```

| Field | Required | Meaning |
| --- | --- | --- |
| `question` | **yes** | What to ask |
| `id` | no | A stable handle. Generated if absent, but set it so two runs can be compared case by case |
| `expected_sources` | no | Filenames that should be cited. Matched on basename, case-insensitively |
| `reference_answer` | no | A known-good answer, for the token-overlap score |
| `should_abstain` | no | `true` when declining is the correct behaviour |

Everything except `question` is optional. A case with only a question still
measures groundedness and latency, and lowering the cost of writing the first
case is how a dataset gets started at all.

**Sources are filenames, not chunk ids.** That is deliberate. Chunk ids are
assigned at ingestion, change on every reindex, and cannot be looked up without
querying the database — nobody maintains a dataset built from them. A filename
is something the author already knows while writing the question.

Blank lines and `#` comments are ignored, so cases can be grouped by topic.

### The abstain cases matter most

A dataset of answerable questions rewards a bot that answers everything
confidently. The questions your documents *cannot* answer are what catch a
model inventing things, and no retrieval metric detects that on its own.

---

## Running

```bash
cd ai-service
python -m app.services.evaluation \
  --dataset evaluation/acme.example.jsonl \
  --knowledge-base-id <ai-service-knowledge-base-uuid> \
  --output evaluation/results.json
```

| Flag | |
| --- | --- |
| `--dataset` | `.json` or `.jsonl` |
| `--knowledge-base-id` | The **AI service's** id, not the dashboard's. Read it from `knowledge_bases.ai_knowledge_base_id` in the backend database |
| `--output` | Write full results as JSON |
| `--k` | Cutoff for hit@k and ranking (default 5, matching `final_top_n`) |

Exit code is non-zero when a question **errored**, so CI notices. Unmet
expectations do not fail the command — a quality threshold belongs in whatever
reads the JSON, not in the runner.

Output:

```
IntelliAssist RAG evaluation
====================================================
  dataset            evaluation/acme.example.jsonl
  model              gemini-3.1-flash-lite
  questions          6  (6 ran, 0 errored)

  hit_at_k             1.000
  mrr                  1.000
  source_recall        1.000
  source_precision     1.000
  answer_token_f1      0.852
  groundedness         1.000
  answer_rate          0.667
  abstention_accuracy  1.000

  mean_ms                2221.1
  p50_ms                 1914.1
  p95_ms                 3176.1
```

---

## Metrics, and exactly how each is calculated

### Retrieval

**`hit_at_k`** — of the cases naming expected sources, the fraction where at
least one expected filename appears among the **first k** cited documents.
Answers "did it find the right document at all", which is the question that
matters first: no amount of ranking rescues a miss.

**`mrr`** — mean of `1/rank` of the first expected document. Rank 1 scores 1.0,
rank 2 scores 0.5, rank 5 scores 0.2; absent scores 0. A high hit rate with a
low MRR means the bot finds the answer but buries it.

**`source_recall`** — fraction of the expected documents that were cited
anywhere. Differs from hit@k when an answer needs two documents: citing one of
two is `hit_at_k = 1.0` but `source_recall = 0.5`.

**`source_precision`** — of the documents cited, the fraction that were
expected. Low means answers are padded with sources that did not contribute.

Filenames are compared on basename, case-insensitively, so `HR Policy.pdf`
matches `uploads/2026/hr policy.pdf`.

### Behaviour

**`groundedness`** — of the questions the bot **chose to answer**, the fraction
that cited at least one source.

> This is deliberately **not** the pipeline's own `grounded` flag. That flag
> means retrieval cleared its relevance threshold, and it stays true even when
> the model then declines and cites nothing. A live run scored 100%
> groundedness while a third of its answers cited no source at all. Citations
> are the observable evidence that an answer rests on the corpus, so citations
> are what this measures. The raw flag is still stored per case as
> `retrieval_grounded`.

**`answer_rate`** — fraction that were answered rather than declined. Read it
next to groundedness: **a bot that declines everything scores a perfect
groundedness and helps nobody.**

**`abstention_accuracy`** — over cases stating `should_abstain`, whether the
bot's behaviour matched. Both directions count: answering a question it should
have declined, and declining one it should have answered.

### Answer content

**`answer_token_f1`** — token-overlap F1 against `reference_answer`, the
SQuAD-style metric. Content words, stopwords removed, with multiplicity.

**This is lexical, not semantic. It does not measure correctness.**

| Answer | Reference | Score | Reality |
| --- | --- | --- | --- |
| "25 days of annual leave" | "annual leave is 25 days" | 1.00 | correct |
| "Employees get 25 days" | "Staff receive twenty-five days" | 0.22 | **also correct** |

A correct answer in different words scores badly, and a fluent answer reusing
the reference's vocabulary while stating the opposite can score well. Treat it
as a **regression signal** — a sharp drop between runs is worth investigating —
and never quote it to anyone as accuracy. Nothing here understands meaning.

### Latency

`mean_ms`, `p50_ms`, `p95_ms`, `max_ms`, from the pipeline's own
`timings.total_ms`, over cases that ran. Numbers vary a great deal with
provider load: one run measured a 2.2s mean and p95 of 3.2s, a later run of the
same dataset measured 17s and 65s. Compare runs taken close together.

### Nulls are not zeros

A metric is `null` when no case stated an expectation for it. A dataset with no
`expected_sources` reports `hit_at_k: null`, not `0.0` — averaging absent
expectations as zero would report a perfect run as a failing one. The dashboard
renders these as "n/a".

---

## Isolation

Evaluation cannot touch production data:

- It calls `RagService.answer` — the same code path production uses, so the
  numbers describe the real pipeline rather than a reimplementation.
- It creates no conversation and stores no message. The AI service does neither;
  conversations live entirely in Spring Boot, which is not involved in a run.
- It records no usage, so evaluation traffic **cannot appear in a customer's
  analytics**.
- It only reads the knowledge base. Nothing is ingested, updated or deleted.

It does make real model calls, which are billed.

---

## Storing and viewing results

Evaluation runs separately and takes minutes. Nothing triggers it from the
dashboard — a page load has no business starting a job that costs money.

```
POST /api/v1/bots/{botId}/analytics/rag-evaluation    # upload a results file
GET  /api/v1/bots/{botId}/analytics/rag-evaluation    # the latest run, or 204
```

Both require a dashboard JWT and are scoped to the caller's organization; a bot
belonging to another organization returns 404, not 403.

Upload the runner's output file verbatim:

```bash
curl -X POST http://localhost:8080/api/v1/bots/$BOT/analytics/rag-evaluation \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  --data-binary @evaluation/results.json
```

Refused with `400` if `schema_version` is not 1, or if `successful + failed`
does not equal `total`. A silently misread metric is worse than no metric,
because someone will act on it.

Runs accumulate rather than replacing one another: the direction a metric moved
is more useful than its absolute value. `GET` returns the most recent, and
**204** when the bot has never been evaluated — "never run" and "ran and scored
nothing" must not look the same.

The dashboard shows this under **Analytics → AI quality**, per bot, with each
metric expandable into a plain-language explanation and a list of the questions
that missed their expected document, declined, or errored.

---

## Limitations

Stated plainly, because a quality tool that overstates itself is worse than none.

1. **No semantic correctness.** `answer_token_f1` compares words, not meaning.
   Nothing here can tell you an answer is factually right.
2. **Groundedness is citation-based.** It tells you an answer cited a document,
   not that the document actually supports the claim. A model can cite a real
   source for a sentence that source does not contain.
3. **Small datasets are noisy.** Six cases is a smoke test. Metric movements
   below a few percent on a small set mean nothing.
4. **The reranker is off in the runner.** It uses `NoopReranker`, matching the
   CLI's existing wiring, so scores reflect fusion ranking rather than
   cross-encoder ranking. Compare runs to each other, not to production with
   reranking enabled.
5. **Latency is provider-dependent** and varies by an order of magnitude with
   load. It measures the pipeline under whatever conditions applied that day.
6. **Expected sources are per document, not per passage.** A case passes if the
   right file was cited, even if the cited passage was the wrong part of it.

---

## Tests

`ai-service/tests/unit/test_evaluation_dataset.py` — 28 cases covering JSON and JSONL
loading, generated ids, comments, every malformed-dataset rejection with its
line number, duplicate ids, each source metric on perfect and failed retrieval,
the k cutoff, partial recall, the lexical limitation of token F1, and the runner
on: a perfect case, a missed source, an uncited answer, a correct decline, a
wrongly-answered abstain case, a failing question not discarding the rest,
latency statistics, and null-not-zero metrics.

`RagEvaluationIntegrationTest` — 9 cases covering storing and reading a run,
runs accumulating, nulls surviving the round trip, refusing a future schema
version and inconsistent totals, 204 for a never-evaluated bot, and
cross-organization isolation on both read and write.
