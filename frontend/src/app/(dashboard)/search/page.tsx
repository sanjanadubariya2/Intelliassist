"use client";

import { Suspense, useEffect, useState } from "react";
import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { Search as SearchIcon, SearchX } from "lucide-react";
import { api, type Bot, type Conversation } from "@/lib/api";
import { useAsync } from "@/lib/hooks";
import {
  Badge,
  Card,
  Empty,
  ErrorNote,
  Loading,
  ResolutionBadge,
  formatRelative,
} from "@/components/ui";

interface Results {
  bots: Bot[];
  conversations: Array<Conversation & { botName: string }>;
}

/**
 * Search across bots and conversations.
 *
 * Filtered client-side, over the pages the API already returns. There is no
 * search endpoint, and inventing one that scans every message would be a real
 * feature — full-text indexing, ranking, and a way to page through it — rather
 * than something to slip in behind a text box. This is honest about its reach:
 * it matches names and titles, and says so.
 */
async function search(term: string): Promise<Results> {
  const query = term.trim().toLowerCase();
  if (!query) return { bots: [], conversations: [] };

  const bots = (await api.bots.list()).items;
  const nameById = new Map(bots.map((bot) => [bot.id, bot.name]));

  const pages = await Promise.all(bots.map((bot) => api.conversations.list(bot.id, 0, 50)));

  const conversations = pages
    .flatMap((page) => page.items)
    .filter((conversation) => (conversation.title ?? "").toLowerCase().includes(query))
    .map((conversation) => ({
      ...conversation,
      botName: nameById.get(conversation.botId) ?? "Unknown bot",
    }))
    .sort(
      (a, b) =>
        new Date(b.lastMessageAt ?? b.startedAt).getTime() -
        new Date(a.lastMessageAt ?? a.startedAt).getTime(),
    )
    .slice(0, 25);

  return {
    bots: bots.filter(
      (bot) =>
        bot.name.toLowerCase().includes(query) ||
        (bot.description ?? "").toLowerCase().includes(query),
    ),
    conversations,
  };
}

export default function SearchPage() {
  // useSearchParams needs a Suspense boundary during prerender.
  return (
    <Suspense fallback={<Loading />}>
      <SearchResults />
    </Suspense>
  );
}

function SearchResults() {
  const params = useSearchParams();
  const term = params.get("q") ?? "";

  const { data, loading, error, reload } = useAsync(() => search(term), [term]);

  const router = useRouter();
  const [draft, setDraft] = useState(term);
  // Follow the query when it changes from the top bar.
  useEffect(() => setDraft(term), [term]);

  const nothing = data && data.bots.length === 0 && data.conversations.length === 0;

  return (
    <div className="mx-auto max-w-[860px]">
      <form
        role="search"
        className="relative mb-7"
        onSubmit={(event) => {
          event.preventDefault();
          const next = draft.trim();
          if (next) router.push(`/search?q=${encodeURIComponent(next)}`);
        }}
      >
        <label htmlFor="search-q" className="mb-2.5 block text-[12px] font-semibold uppercase tracking-[0.06em] text-muted">
          Search
        </label>
        <SearchIcon className="pointer-events-none absolute left-[18px] top-[46px] h-[22px] w-[22px] text-muted" aria-hidden />
        <input
          id="search-q"
          type="search"
          value={draft}
          onChange={(event) => setDraft(event.target.value)}
          className="min-h-[60px] w-full rounded-2xl border-2 border-accent bg-surface pl-[52px] pr-5 text-[18px] font-medium focus:outline-none focus:ring-4 focus:ring-accent/15"
        />
        <p className="mt-2.5 text-[13px] text-muted">
          Matches bot names and conversation titles. Message contents are not searched.
        </p>
      </form>

      {!term ? null : loading ? (
        <Loading />
      ) : error ? (
        <ErrorNote message={error} onRetry={reload} />
      ) : nothing ? (
        <Card>
          <Empty
            icon={<SearchX className="h-7 w-7" />}
            title="Nothing matched"
            description="Try a bot name, or a phrase from a conversation title."
          />
        </Card>
      ) : (
        <div className="space-y-6">
          {data!.bots.length > 0 && (
            <section>
              <h2 className="mb-2.5 text-[12px] font-semibold uppercase tracking-[0.06em] text-muted">
                Bots · {data!.bots.length}
              </h2>
              <Card className="overflow-hidden">
                <ul className="divide-y divide-[#f3ece4]">
                  {data!.bots.map((bot) => (
                    <li key={bot.id}>
                      <Link href={`/bots/${bot.id}`} className="flex items-center gap-3.5 px-5 py-4 hover:bg-cream">
                        <span className="grid h-11 w-11 shrink-0 place-items-center rounded-xl bg-accent-soft font-display text-[18px] font-semibold text-accent">
                          {bot.name.slice(0, 1).toUpperCase()}
                        </span>
                        <span className="min-w-0 flex-1">
                          <span className="block truncate font-semibold">
                            <Highlight text={bot.name} term={term} />
                          </span>
                          {bot.description && (
                            <span className="block truncate text-[13px] text-muted">{bot.description}</span>
                          )}
                        </span>
                        <Badge tone={bot.status === "ACTIVE" ? "ok" : "warn"}>
                          {bot.status === "ACTIVE" ? "Online" : "Paused"}
                        </Badge>
                      </Link>
                    </li>
                  ))}
                </ul>
              </Card>
            </section>
          )}

          {data!.conversations.length > 0 && (
            <section>
              <h2 className="mb-2.5 text-[12px] font-semibold uppercase tracking-[0.06em] text-muted">
                Conversations · {data!.conversations.length}
              </h2>
              <Card className="overflow-hidden">
                <ul className="divide-y divide-[#f3ece4]">
                  {data!.conversations.map((conversation) => (
                    <li key={conversation.id}>
                      <Link
                        href={`/conversations/${conversation.id}`}
                        className="flex flex-wrap items-center gap-3.5 px-5 py-4 hover:bg-cream"
                      >
                        <span className="min-w-0 flex-[1_1_260px]">
                          <span className="block truncate font-semibold">
                            <Highlight text={conversation.title ?? "Untitled conversation"} term={term} />
                          </span>
                          <span className="block text-[13px] text-muted">
                            {conversation.botName} ·{" "}
                            {formatRelative(conversation.lastMessageAt ?? conversation.startedAt)}
                          </span>
                        </span>
                        <ResolutionBadge resolution={conversation.resolution} />
                      </Link>
                    </li>
                  ))}
                </ul>
              </Card>
            </section>
          )}
        </div>
      )}
    </div>
  );
}

/** Marks every case-insensitive occurrence of the term. */
function Highlight({ text, term }: { text: string; term: string }) {
  const needle = term.trim().toLowerCase();
  if (!needle) return <>{text}</>;
  const parts: React.ReactNode[] = [];
  let from = 0;
  const lower = text.toLowerCase();
  let at = lower.indexOf(needle);
  while (at !== -1) {
    if (at > from) parts.push(text.slice(from, at));
    parts.push(
      <mark key={at} className="rounded-[3px] bg-clay-soft px-0.5 text-ink">
        {text.slice(at, at + needle.length)}
      </mark>,
    );
    from = at + needle.length;
    at = lower.indexOf(needle, from);
  }
  if (from < text.length) parts.push(text.slice(from));
  return <>{parts}</>;
}
