# IntelliAssist dashboard

The operator-facing app: create bots, feed them documents, test their answers,
read transcripts, and get the embed snippet.

```bash
npm install
cp -n .env.local.example .env.local   # points at http://localhost:8080
npm run dev
```

| Script | Does |
| --- | --- |
| `npm run dev` | Development server on :3000 |
| `npm run build` | Production build |
| `npm run lint` | ESLint |
| `npm run typecheck` | `tsc --noEmit` |
| `npm test` | Unit tests (Node's built-in runner, no extra dependencies) |
| `npm run clean` | Delete `.next` |

**Stale-build errors are handled automatically.** `next build` and `next dev`
write incompatible output into the same `.next` directory, so a dev server
started after a production build tries to load production chunks and throws

```
TypeError: Cannot read properties of undefined (reading 'call')
TypeError: __webpack_modules__[moduleId] is not a function
```

from `webpack.js` — an error that names webpack, looks fatal, and has nothing
to do with your code. `predev` and `prebuild` run `scripts/reset-stale-build.mjs`,
which clears the other mode's artefacts (keeping `.next/cache`, which is valid
for both and expensive to lose). `npm run clean` removes `.next` entirely if you
ever need to.

## Layout

```
src/
  app/
    welcome/         the product page signed-out visitors land on
    (auth)/          sign in, sign up: redirects away if already signed in
    (dashboard)/     everything behind a session
      page.tsx                       overview
      ask/                           Ask Intelli, the setup guide
      bots/                          list, then per-bot tabs
        [botId]/knowledge.tsx        upload and index documents
        [botId]/playground.tsx       streamed test chat
        [botId]/embed.tsx            API keys, allowed domains, snippet
        [botId]/settings.tsx         name, status, language, delete
      playground/                    Chat Playground for any bot
      knowledge/                     documents across every bot
      health/                        Knowledge Base Health Monitor
      studio/                        Widget Studio: style, preview, install
      conversations/                 feed, then transcript
      inbox/                         Human Handoff Inbox
      insights/                      Conversation Intelligence
  lib/
    api.ts           typed client: mirrors the backend DTOs
    auth.tsx         session context
    hooks.ts         useAsync / usePolling
    intelli.ts       Ask Intelli's request parser (pure, unit-tested)
    dates.ts         UTC date helpers, matching how the backend buckets days
    theme.ts         chart colours (Recharts cannot read CSS variables)
  components/
    ui.tsx           the shared primitives, toasts and dialogs
    knowledge-health.tsx, conversation-insights.tsx, widget-studio.tsx
```

`/customization` and `/embed` redirect to Widget Studio, so old links still land.

## The five newer tools

| Tool | Backed by |
| --- | --- |
| **KB Health Monitor** | `GET /bots/{id}/knowledge/health`. Failed, stuck, empty, outdated, duplicate and conflicting-version documents, scored out of 100, plus suggested FAQs from questions the bot could not answer. Works from metadata; document text is never compared, and the screen says so. |
| **Widget Studio** | The existing appearance and credential endpoints, on one screen with a live preview. |
| **Human Handoff Inbox** | `GET /handoff/conversations?view=`, `GET /handoff/summary`, `PATCH /conversations/{id}/assignee`. Org-wide queue, assignment to members, median time to resolution. |
| **Conversation Intelligence** | `GET /bots/{id}/insights`. Common and unanswered questions (grouped by normalised text), answer rate, resolution trend. "Satisfaction" is resolved ÷ (resolved + escalated): there is no visitor rating yet, and the label says so. |
| **Ask Intelli** | Deterministic: patterns rather than a language model, so it never invents a feature or misreads "pause" as "delete". Every change is a proposal that waits for approval, and is then made through the ordinary API. |

## Notes

**The API client mirrors Java records.** Every type in `lib/api.ts` matches a
record in `com.intelliassist.backend.dto`. Fields the API does not return are
not in the client, because a UI built on invented fields looks finished and
works for nobody.

**The browser talks to Spring Boot only.** There is no `AI_URL` to configure:
the Python service sits on a private network with no route from a browser.

**Streaming uses `fetch`, not `EventSource`,** which cannot POST and cannot set
an `Authorization` header. SSE frames are parsed out of the response body.

**Indexing is asynchronous.** An upload returns `202` with the document
`PENDING`, so document lists poll while anything is still indexing and stop as
soon as everything settles.

**Fan-out where the backend has no aggregate.** Usage and conversations are
per-bot endpoints, so the overview and the conversation feed call once per bot
and merge. That is fine at a handful of bots and wrong at a hundred — the fix is
an org-wide endpoint on the backend, not a cleverer client.

## Where the design and the backend disagree

Called out rather than faked:

| Design element | Status |
| --- | --- |
| Knowledge tabs: FAQs, Websites | Shown disabled. The AI service ingests PDF/DOCX/TXT/MD files only, and it is frozen. |

The system prompt field is the one place a saved value does nothing today: it
persists on the bot, but the AI service's query contract has no parameter to
carry it. The settings screen says so on the screen rather than in a comment.
