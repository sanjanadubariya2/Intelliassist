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
    (auth)/          sign in, sign up — redirects away if already signed in
    (dashboard)/     everything behind a session
      page.tsx                       overview
      bots/                          list, then per-bot tabs
        [botId]/knowledge.tsx        upload and index documents
        [botId]/playground.tsx       streamed test chat
        [botId]/embed.tsx            API keys, allowed domains, snippet
        [botId]/settings.tsx         name, status, language, delete
      knowledge/                     documents across every bot
      conversations/                 feed, then transcript
  lib/
    api.ts           typed client — mirrors the backend DTOs
    auth.tsx         session context
    hooks.ts         useAsync / usePolling
  components/ui.tsx  the shared primitives
```

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
| "Resolved %" tile | Replaced with **grounded answers**. Nothing marks a conversation resolved; grounding is what the backend tracks, and for a RAG assistant it is the more useful number. |
| "Avg response time" tile | Replaced with **tokens used**. `latencyMs` exists per message but there is no aggregate endpoint. |
| Bot customization (colour, position, welcome/fallback text, launcher icon) | Not in the dashboard. `Bot` has no such columns. The widget takes them as embed-snippet attributes instead, which needs no schema change. |
| Knowledge tabs: FAQs, Websites | Shown disabled. The AI service ingests PDF/DOCX/TXT/MD files only, and it is frozen. |
| Conversation "Resolved / Escalated", internal notes | Not built. `ConversationStatus` is `ACTIVE` or `CLOSED`, and there is no notes table. |

The system prompt field is the one place a saved value does nothing today: it
persists on the bot, but the AI service's query contract has no parameter to
carry it. The settings screen says so on the screen rather than in a comment.
