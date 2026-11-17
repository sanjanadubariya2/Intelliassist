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

        <div className="flex flex-wrap items-center gap-3 border-b border-line px-5 py-4">
          <div className="relative min-w-48 max-w-[360px] flex-1">
            <Search className="pointer-events-none absolute left-3.5 top-1/2 h-4 w-4 -translate-y-1/2 text-muted" />
            <input
              value={term}
              onChange={(event) => setTerm(event.target.value)}
              placeholder="Search conversation titles"
              aria-label="Search conversations"
              className="min-h-11 w-full rounded-xl border border-[#e2d4c5] bg-surface py-2 pl-10 pr-3 text-sm placeholder:text-muted focus:border-clay/60 focus:outline-none focus:ring-2 focus:ring-clay/15"
            />
          </div>

          <div role="group" aria-label="Status" className="flex flex-wrap gap-1 rounded-xl bg-[#f3ece4] p-1">
            {FILTERS.map((entry) => (
              <button
                key={entry.id}
                onClick={() => setFilter(entry.id)}
                aria-pressed={filter === entry.id}
                className={cx(
                  "min-h-9 rounded-[9px] px-3.5 text-[13px] transition-colors",
                  filter === entry.id
                    ? "bg-surface font-semibold text-ink shadow-sm"
                    : "font-medium text-[#5f5249] hover:text-ink",
                )}
              >
                {entry.label}
                {counts[entry.id] !== undefined && <span className="ml-1"> · {counts[entry.id]}</span>}
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
          <div className="overflow-x-auto">
            <table className="w-full text-[14px]">
              <thead>
                <tr className="border-b border-line text-left text-[12px] font-semibold text-muted">
                  <th className="px-5 py-3">Conversation</th>
                  <th className="px-4 py-3">Bot</th>
                  <th className="px-4 py-3">Messages</th>
                  <th className="px-4 py-3">Assignee</th>
                  <th className="px-4 py-3">Status</th>
                  <th className="px-5 py-3 text-right">Last message</th>
                </tr>
              </thead>
              <tbody>
                {shown.map((conversation) => (
                  <tr key={conversation.id} className="border-b border-[#f3ece4] last:border-0 hover:bg-cream/60">
                    <td className="max-w-[380px] px-5 py-3.5">
                      <Link href={`/conversations/${conversation.id}`} className="block min-w-0">
                        <span className="block truncate font-semibold hover:underline">
                          {conversation.title ?? "Untitled conversation"}
                        </span>
                        <span className="block text-[13px] text-muted">
                          {conversation.visitorId ? `Visitor ${conversation.visitorId.slice(0, 10)}` : "Dashboard test"}
                        </span>
                      </Link>
                    </td>
                    <td className="whitespace-nowrap px-4 py-3.5 text-muted">{conversation.botName}</td>
                    <td className="px-4 py-3.5">{conversation.messageCount}</td>
                    <td className="whitespace-nowrap px-4 py-3.5 text-muted">{conversation.assigneeName ?? "—"}</td>
                    <td className="px-4 py-3.5">
                      <ResolutionBadge resolution={conversation.resolution} />
                    </td>
                    <td className="whitespace-nowrap px-5 py-3.5 text-right text-muted">
                      {formatRelative(conversation.lastMessageAt ?? conversation.startedAt)}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>
    </>
  );
}
