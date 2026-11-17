"use client";

import { Suspense, useState } from "react";
import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { MessagesSquare, Search } from "lucide-react";
import {
  api,
  type Bot,
  type Conversation,
  type ConversationResolution,
} from "@/lib/api";
import { useAsync } from "@/lib/hooks";
import {
  Card,
  CardHeader,
  Empty,
  ErrorNote,
  Loading,
  PageHeader,
  ResolutionBadge,
  Select,
  cx,
  formatRelative,
} from "@/components/ui";

const FILTERS = [
  { id: "all", label: "All" },
  { id: "UNRESOLVED", label: "Open" },
  { id: "RESOLVED", label: "Resolved" },
  { id: "ESCALATED", label: "Escalated" },
] as const;

type FilterId = (typeof FILTERS)[number]["id"];

interface Feed {
  bots: Bot[];
  conversations: Array<Conversation & { botName: string }>;
}

/**
 * Conversations across every bot.
 *
 * Conversations are listed per bot, so this fans out and merges. Same trade as
 * the overview: fine at this scale, and the honest fix is an org-wide endpoint
 * rather than more client-side stitching.
 */
async function loadFeed(botId: string | "all"): Promise<Feed> {
  const bots = (await api.bots.list()).items;
  const wanted = botId === "all" ? bots : bots.filter((bot) => bot.id === botId);

  const pages = await Promise.all(wanted.map((bot) => api.conversations.list(bot.id, 0, 50)));
  const nameById = new Map(bots.map((bot) => [bot.id, bot.name]));

  const conversations = pages
    .flatMap((page) => page.items)
    .map((conversation) => ({
      ...conversation,
      botName: nameById.get(conversation.botId) ?? "Unknown bot",
    }))
    .sort(
      (a, b) =>
        new Date(b.lastMessageAt ?? b.startedAt).getTime() -
        new Date(a.lastMessageAt ?? a.startedAt).getTime(),
    );

  return { bots, conversations };
}

export default function ConversationsPage() {
  return (
    <Suspense fallback={<Loading />}>
      <Feed />
    </Suspense>
  );
}

function Feed() {
  const params = useSearchParams();

  const [botId, setBotId] = useState<string | "all">("all");
  // Seeded from the query string so other screens can deep-link to a filter.
  // Validated: an unknown value used to select no tab and match nothing.
  const requested = params.get("resolution");
  const [filter, setFilter] = useState<FilterId>(
    FILTERS.find((entry) => entry.id === requested)?.id ?? "all",
  );
  const [term, setTerm] = useState("");

  const { data, loading, error, reload } = useAsync(() => loadFeed(botId), [botId]);

  const query = term.trim().toLowerCase();
  const shown = (data?.conversations ?? []).filter((conversation) => {
    if (filter !== "all" && conversation.resolution !== (filter as ConversationResolution)) {
      return false;
    }
    if (!query) return true;
    return (conversation.title ?? "").toLowerCase().includes(query);
  });

  const counts = (data?.conversations ?? []).reduce<Record<string, number>>(
    (totals, conversation) => {
      totals.all = (totals.all ?? 0) + 1;
      totals[conversation.resolution] = (totals[conversation.resolution] ?? 0) + 1;
      return totals;
    },
    {},
  );

  return (
    <>
      <PageHeader
        title="Conversations"
        description="Every conversation across your bots. Escalated ones also appear in the Handoff Inbox."
      />

      <Card>
        <CardHeader
          title="All conversations"
          action={
            data && data.bots.length > 1 ? (
              <Select
                value={botId}
                onChange={(event) => setBotId(event.target.value)}
                className="w-auto"
                aria-label="Bot"
              >
                <option value="all">All bots</option>
                {data.bots.map((bot) => (
                  <option key={bot.id} value={bot.id}>
                    {bot.name}
                  </option>
                ))}
              </Select>
            ) : undefined
          }
        />

        <div className="flex flex-wrap items-center gap-3 border-b border-line px-5 py-3">
          <div className="relative min-w-48 flex-1">
            <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-faint" />
            <input
              value={term}
              onChange={(event) => setTerm(event.target.value)}
              placeholder="Search conversations…"
              aria-label="Search conversations"
              className="w-full rounded-xl border border-line py-2 pl-9 pr-3 text-sm placeholder:text-faint focus:border-clay/60 focus:outline-none focus:ring-2 focus:ring-clay/15"
            />
          </div>

          <div className="flex gap-1">
            {FILTERS.map((entry) => (
              <button
                key={entry.id}
                onClick={() => setFilter(entry.id)}
                className={cx(
                  "rounded-lg px-3 py-1.5 text-[13px] transition-colors",
                  filter === entry.id
                    ? "bg-clay-soft font-medium text-ink"
                    : "text-muted hover:bg-canvas hover:text-ink",
                )}
              >
                {entry.label}
                {counts[entry.id] !== undefined && (
                  <span className="ml-1.5 text-[11.5px] opacity-70">{counts[entry.id]}</span>
                )}
              </button>
            ))}
          </div>
        </div>

        {loading ? (
          <Loading />
        ) : error ? (
          <div className="p-5">
            <ErrorNote message={error} onRetry={reload} />
          </div>
        ) : shown.length === 0 ? (
          <Empty
            icon={<MessagesSquare className="h-7 w-7" />}
            title={
              (data?.conversations.length ?? 0) === 0
                ? "No conversations yet"
                : "Nothing matches those filters"
            }
            description={
              (data?.conversations.length ?? 0) === 0
                ? "They appear here as soon as someone talks to your widget."
                : undefined
            }
          />
        ) : (
          <ul className="divide-y divide-line">
            {shown.map((conversation) => (
              <li key={conversation.id}>
                <Link
                  href={`/conversations/${conversation.id}`}
                  className="flex items-center justify-between gap-4 px-5 py-3.5 hover:bg-cream"
                >
                  <div className="min-w-0">
                    <p className="truncate text-sm font-medium">
                      {conversation.title ?? "Untitled conversation"}
                    </p>
                    <p className="mt-0.5 text-[12px] text-muted">
                      {conversation.visitorId
                        ? `Visitor ${conversation.visitorId.slice(0, 10)}`
                        : "Dashboard"}{" "}
                      · {conversation.botName} · {conversation.messageCount} messages
                    </p>
                  </div>
                  <div className="flex shrink-0 items-center gap-3">
                    {conversation.assigneeName && (
                      <span className="hidden text-[12px] text-muted sm:inline">
                        {conversation.assigneeName}
                      </span>
                    )}
                    <ResolutionBadge resolution={conversation.resolution} />
                    <span className="w-16 text-right text-[12px] text-faint">
                      {formatRelative(conversation.lastMessageAt ?? conversation.startedAt)}
                    </span>
                  </div>
                </Link>
              </li>
            ))}
          </ul>
        )}
      </Card>
    </>
  );
}
