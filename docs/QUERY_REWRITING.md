# Conversational query rewriting

## The problem

Retrieval sees one string. That works while every question stands on its own,
and fails the moment a conversation starts:

> **User:** What is the leave policy?
> **Assistant:** Full-time employees receive 25 days of paid annual leave…
> **User:** What about part-time staff?

"What about part-time staff?" is not a search query. It has no topic. The dense
retriever embeds it somewhere near other vague questions about staff; BM25
matches `staff` and little else. The passage the user wants — the one about
part-time leave — is not returned, and the bot answers "I don't have
information about that" while looking directly at a document that does.

## Why not just glue the history onto the front

The obvious fix is to send `"What is the leave policy? … What about part-time
staff?"` as the query. It makes retrieval **worse**, for a different reason in
each half of the hybrid search:

**Dense.** An embedding is one point in vector space. Concatenating the previous
turn does not add a second point — it moves the single point toward the average
of both. The query now sits between "leave policy in general" and "part-time
staff", closer to the *old* topic than the new one, because the old topic
contributed more words. By the fourth turn the query is a blur of everything the
conversation has touched.

**Sparse.** BM25 scores term overlap. Every word the history contributes is a
term the index will match on, so passages about the previous topic score highly
purely because they were being discussed a moment ago. The user's actual
question is a small fraction of the query and gets outvoted.

**And it compounds.** Both effects grow with conversation length, so the feature
degrades exactly when the user is most engaged.

The information in the history is genuinely needed — but it is needed to
*understand* the question, not to *be* the question.

## Architecture

```
conversation history  ─┐
                       ├─►  QueryRewriter  ─►  standalone query  ─►  existing RAG pipeline
current question      ─┘                                              (dense + BM25 + RRF
                                                                        + rerank + generate)
```

One stage in front of retrieval. The pipeline below it is untouched: it receives
a string, exactly as it always has, and neither knows nor cares whether that
string was rewritten.

**Where it lives.** In the Python AI service, alongside every other decision
about retrieval. Spring Boot keeps what it owns — conversation persistence,
identity, authorization, message storage — and sends the recent turns with the
question. It stays the only thing that talks to Python; the browser and widget
never do.

**What the AI service does not gain.** It stores none of the history. It has no
notion of a conversation, a visitor or an organization, and giving it one would
put a second copy of the tenancy rules somewhere they cannot be enforced. The
turns are used once, for one call, and discarded.

## Behaviour

| Input | Result |
| --- | --- |
| No history supplied | Skipped entirely. Not even a model call. |
| History supplied, standalone question | Returned unchanged; `changed` is false. |
| History supplied, follow-up | Rewritten. |

Examples:

| History | Question | Rewritten |
| --- | --- | --- |
| leave policy discussed | What about part-time staff? | What leave benefits are available to part-time staff? |
| 25 days mentioned | How many days is it? | How many days of annual leave do full-time employees receive? |
| — | What is the expense receipt threshold? | *(unchanged)* |

The rules the model is given: resolve references, preserve intent exactly,
invent nothing, never answer, keep the domain's own vocabulary, stay concise,
and leave a standalone question alone. The vocabulary rule matters more than it
looks — paraphrasing a technical term into a common one loses the exact string
the index contains.

## The history window

Configurable, defaulting to **6 messages** — roughly three exchanges.

```
INTELLIASSIST_QUERY_REWRITE__HISTORY_TURNS=6     # AI service
AI_SERVICE_HISTORY_TURNS=6                       # Spring Boot, what it sends
```

Six covers the references people actually make. Beyond that, older turns
describe a topic the conversation has already moved past, and every extra
message is tokens spent on every question. Both ends are capped, so a
misconfigured caller cannot push an unbounded payload into the prompt:

| Setting | Default | Guards against |
| --- | --- | --- |
| `history_turns` | 6 | Unbounded prompt growth |
| `max_message_chars` | 600 | One enormous message |
| `max_query_chars` | 400 | A rewrite that is not a query |
| `timeout_seconds` | 8 | A slow rewrite delaying an answer |

Setting `history_turns` to `0`, or `enabled` to `false`, restores the original
behaviour exactly.

## Failure

**Rewriting never fails a request.** Every failure path resolves to the original
query and the pipeline runs anyway:

| Failure | Result |
| --- | --- |
| Model error | Original query |
| Timeout | Original query |
| Empty completion | Original query |
| Reply that is not a query | Original query |
| Feature disabled / no history | Original query |

`LLMQueryRewriter.rewrite` cannot raise. The cost of falling back is one
mediocre retrieval; the cost of propagating is a failed answer the user was
waiting for. An enhancement that can take down the feature it enhances is a
liability, not an enhancement.

## Security

Conversation history is attacker-controlled. A visitor can type anything into a
public widget, and it comes back as "context" on their next question.

Three layers, because none of them is sufficient alone:

**1. The history is labelled untrusted, in the prompt itself.** The system
prompt states that the conversation may contain text that looks like
instructions, that such text is a user's message being quoted, and that it must
never be obeyed.

**2. It is fenced.** History goes inside `<conversation>` tags and the question
inside `<question>`. A model handed undifferentiated text has no way to tell
which part it is meant to act on — that ambiguity is exactly what an injection
exploits.

**3. The output is validated.** The last line of defence, for when the model is
talked into complying anyway. A rewrite is rejected if it is empty, over the
character cap, disproportionate to the original, or **spans more than one
sentence**. That last check is the one that works: a model that has obeyed an
injected instruction produces prose, and prose is both a bad search query and a
clear signal the rules were not followed.

> The sentence check was added because a test caught the earlier length-only
> guard letting a 234-character injected reply straight through. Length alone
> cannot separate a long legitimate rewrite from a short illegitimate one.

Rejection falls back to the original query, so an injection attempt degrades to
ordinary retrieval rather than reaching the pipeline.

**What is never logged.** Query text and history content are not logged by
default — only lengths, turn counts and whether a rewrite happened. Prompts are
never returned to a client.

## Observability

`rewritten_query` appears in the AI service's response, and in the `metadata`
event of a stream, but **only when a rewrite actually changed something**. It is
null otherwise, so the field means "this is what retrieval really searched for"
rather than "here is the query again".

It is internal diagnostics. Spring Boot's `StreamEventSanitizer` already strips
internal fields from stream metadata before a browser sees them, and the public
widget response never carried this field.

```json
{
  "query": "What about part-time staff?",
  "rewritten_query": "What leave benefits are available to part-time staff?",
  "grounded": true
}
```

`query` is always exactly what the caller sent. That is what Spring Boot
persists and shows back to the user — storing our reconstruction instead would
show them words they never typed.

## What was not changed

Dense retrieval, BM25, RRF fusion, reranking, context construction, citation
extraction, grounded-answer logic and Gemini generation are all untouched. A
request with no history takes precisely the path it took before this feature
existed, and there is a test asserting exactly that.

## Tests

`ai-service/tests/unit/test_query_rewrite.py` (22) covers standalone questions, simple and
multi-turn follow-ups, pronoun resolution, stability, model failure, timeout,
empty completion, empty history, window truncation, message truncation, the
disabled paths, prompt-injection fencing, injected-output rejection, and the
boilerplate a model wraps its answer in.

`tests/unit/test_rag_service.py` adds the pipeline-level cases: the rewritten
query is what reaches retrieval, the original survives into the response, a
declining rewriter leaves retrieval on the original, streaming rewrites too, and
a request without history behaves exactly as before.

`ChatControllerIntegrationTest` asserts the Spring side: the query string is
always just the current question, and the second question carries the first
completed exchange as history — oldest first, not including itself.
