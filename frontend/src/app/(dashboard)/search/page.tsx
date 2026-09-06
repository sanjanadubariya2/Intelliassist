"use client";

import { Suspense } from "react";
import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { Bot as BotIcon, MessagesSquare, SearchX } from "lucide-react";
import { api, type Bot, type Conversation } from "@/lib/api";
import { useAsync } from "@/lib/hooks";
import {
  Card,
  CardHeader,
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

  const nothing = data && data.bots.length === 0 && data.conversations.length === 0;

  return (
    <>
      <div className="mb-5">
        <h1 className="text-xl font-semibold tracking-tight">
          Results for <span className="text-accent">{term}</span>
        </h1>
        <p className="mt-0.5 text-[13px] text-muted">
          Matches bot names and conversation titles. Message contents are not searched.
        </p>
      </div>

      {loading ? (
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
        <div className="space-y-4">
          {data!.bots.length > 0 && (
            <Card>
              <CardHeader title={`Bots (${data!.bots.length})`} />
              <ul className="divide-y divide-line">
                {data!.bots.map((bot) => (
                  <li key={bot.id}>
                    <Link
                      href={`/bots/${bot.id}`}
                      className="flex items-center gap-3 px-5 py-3.5 hover:bg-canvas"
                    >
                      <BotIcon className="h-4 w-4 shrink-0 text-faint" />
                      <span className="min-w-0 flex-1">
                        <span className="block truncate text-sm font-medium">{bot.name}</span>
                        {bot.description && (
                          <span className="block truncate text-[12px] text-muted">
                            {bot.description}
                          </span>
                        )}
                      </span>
                    </Link>
                  </li>
                ))}
              </ul>
            </Card>
          )}

          {data!.conversations.length > 0 && (
            <Card>
              <CardHeader title={`Conversations (${data!.conversations.length})`} />
              <ul className="divide-y divide-line">
                {data!.conversations.map((conversation) => (
                  <li key={conversation.id}>
                    <Link
                      href={`/conversations/${conversation.id}`}
                      className="flex items-center gap-3 px-5 py-3.5 hover:bg-canvas"
                    >
                      <MessagesSquare className="h-4 w-4 shrink-0 text-faint" />
                      <span className="min-w-0 flex-1">
                        <span className="block truncate text-sm">
                          {conversation.title ?? "Untitled conversation"}
                        </span>
                        <span className="block text-[12px] text-muted">
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
          )}
        </div>
      )}
    </>
  );
}
