"use client";

import { useState } from "react";
import Link from "next/link";
import {
  ArrowDownRight,
  ArrowRight,
  ArrowUpRight,
  BarChart3,
  Bot as BotIcon,
  Clock,
  HeartPulse,
  Inbox,
  MessagesSquare,
  Plus,
  ShieldCheck,
  SlidersHorizontal,
  Sparkles,
} from "lucide-react";
import {
  Area,
  AreaChart,
  CartesianGrid,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import { api, type Bot, type BotUsage, type Conversation } from "@/lib/api";
import { useAsync } from "@/lib/hooks";
import { useAuth } from "@/lib/auth";
import { shortDay, utcDaysAgo, utcToday } from "@/lib/dates";
import { chart, chartAxis, chartTooltip } from "@/lib/theme";
import {
  buttonLink,
  Card,
  CardHeader,
  Empty,
  ErrorNote,
  Loading,
  PageHeader,
  ResolutionBadge,
  Select,
  cx,
  formatNumber,
  formatRelative,
} from "@/components/ui";

const RANGES = [
  { days: 7, label: "Last 7 days" },
  { days: 30, label: "Last 30 days" },
  { days: 90, label: "Last 90 days" },
] as const;

interface Overview {
  bots: Bot[];
  usageByBot: Map<string, BotUsage>;
  /** The same window immediately before, for the "vs last N days" deltas. */
  previousByBot: Map<string, BotUsage>;
  recent: Array<Conversation & { botName: string }>;
}

/**
 * Loads the organization's picture from per-bot endpoints.
 *
 * There is no org-wide analytics endpoint, so this fans out: two usage calls
 * and one conversation call per bot. Fine for the handful of bots a workspace
 * has, and honest about it — the fix is an aggregate endpoint on the backend,
 * not a cleverer client.
 */
async function loadOverview(days: number): Promise<Overview> {
  const bots = (await api.bots.list()).items;

  // UTC dates throughout: the backend buckets usage by UTC day.
  const to = utcToday();
  const from = utcDaysAgo(days - 1);
  // The window immediately before this one, same length.
  const previousTo = utcDaysAgo(days);
  const previousFrom = utcDaysAgo(days * 2 - 1);

  const [usages, previous, conversations] = await Promise.all([
    Promise.all(bots.map((bot) => api.usage.forBot(bot.id, from, to))),
    Promise.all(bots.map((bot) => api.usage.forBot(bot.id, previousFrom, previousTo))),
    Promise.all(bots.map((bot) => api.conversations.list(bot.id, 0, 5))),
  ]);

  const nameById = new Map(bots.map((bot) => [bot.id, bot.name]));

  const recent = conversations
    .flatMap((page) => page.items)
    .map((conversation) => ({
      ...conversation,
      botName: nameById.get(conversation.botId) ?? "Unknown bot",
    }))
    .sort(
      (a, b) =>
        new Date(b.lastMessageAt ?? b.startedAt).getTime() -
        new Date(a.lastMessageAt ?? a.startedAt).getTime(),
    )
    .slice(0, 5);

  return {
    bots,
    usageByBot: new Map(bots.map((bot, index) => [bot.id, usages[index]])),
    previousByBot: new Map(bots.map((bot, index) => [bot.id, previous[index]])),
    recent,
  };
}

export default function OverviewPage() {
  const { user } = useAuth();
  const [days, setDays] = useState<number>(7);
  const { data, loading, error, reload } = useAsync(() => loadOverview(days), [days]);

  const firstName = user?.user.name.split(/\s+/)[0] ?? "there";

  return (
    <>
      <PageHeader
        title={
          <>
            {greeting()}, <em className="font-medium text-clay">{firstName}</em>
          </>
        }
        description="Here's what's happening with your assistants today."
        action={
          <Link href="/bots" className={buttonLink}>
            <Plus className="h-4 w-4" />
            Create bot
          </Link>
        }
      />

      <FeatureStrip />

      {loading ? (
        <Loading />
      ) : error ? (
        <ErrorNote message={error} onRetry={reload} />
      ) : !data || data.bots.length === 0 ? (
        <Card>
          <Empty
            icon={<BotIcon className="h-7 w-7" />}
            title="No bots yet"
            description="Create a bot, give it a document to read, and it can start answering."
            action={
              <Link href="/bots" className={buttonLink}>
                Create your first bot
              </Link>
            }
          />
        </Card>
      ) : (
        <Body data={data} days={days} onDaysChange={setDays} />
      )}
    </>
  );
}

function Body({
  data,
  days,
  onDaysChange,
}: {
  data: Overview;
  days: number;
  onDaysChange: (days: number) => void;
}) {
  const { bots, usageByBot, previousByBot, recent } = data;

  const totals = sum([...usageByBot.values()]);
  const before = sum([...previousByBot.values()]);

  const groundedRate =
    totals.answers > 0 ? (totals.grounded / totals.answers) * 100 : null;
  const groundedRateBefore =
    before.answers > 0 ? (before.grounded / before.answers) * 100 : null;

  // A mean of per-bot means would weight a quiet bot the same as a busy one,
  // so this weights each bot's average by the answers it actually produced.
  const responseMs = weightedAverageResponse([...usageByBot.values()]);
  const responseMsBefore = weightedAverageResponse([...previousByBot.values()]);

  const series = mergeDays([...usageByBot.values()]);

  const topBots = bots
    .map((bot) => ({
      bot,
      usage: usageByBot.get(bot.id),
      previous: previousByBot.get(bot.id),
    }))
    .filter((row) => (row.usage?.totalMessages ?? 0) > 0)
    .sort((a, b) => (b.usage?.totalMessages ?? 0) - (a.usage?.totalMessages ?? 0))
    .slice(0, 4);

  return (
    <>
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 xl:grid-cols-4">
        <Tile
          label="Messages"
          value={formatNumber(totals.messages)}
          delta={percentChange(totals.messages, before.messages)}
          days={days}
          icon={<MessagesSquare className="h-4 w-4" />}
        />
        <Tile
          label="Sessions"
          value={formatNumber(totals.conversations)}
          delta={percentChange(totals.conversations, before.conversations)}
          days={days}
          icon={<Sparkles className="h-4 w-4" />}
        />
        <Tile
          label="Grounded"
          value={groundedRate === null ? "—" : `${groundedRate.toFixed(1)}%`}
          delta={
            groundedRate !== null && groundedRateBefore !== null
              ? groundedRate - groundedRateBefore
              : null
          }
          deltaUnit="pp"
          days={days}
          icon={<ShieldCheck className="h-4 w-4" />}
        />
        <Tile
          label="Avg. Response Time"
          value={responseMs === null ? "—" : `${(responseMs / 1000).toFixed(2)}s`}
          delta={
            responseMs !== null && responseMsBefore !== null
              ? percentChange(responseMs, responseMsBefore)
              : null
          }
          days={days}
          // Faster is better, so a fall is good news here.
          lowerIsBetter
          icon={<Clock className="h-4 w-4" />}
        />
      </div>

      <div className="mt-4 grid grid-cols-1 gap-4 xl:grid-cols-3">
        <Card className="xl:col-span-2">
          <CardHeader
            title="Conversations"
            action={
              <Select
                value={days}
                onChange={(event) => onDaysChange(Number(event.target.value))}
                className="w-auto py-1 text-[13px]"
              >
                {RANGES.map((range) => (
                  <option key={range.days} value={range.days}>
                    {range.label}
                  </option>
                ))}
              </Select>
            }
          />
          <div className="p-5">
            {series.length === 0 ? (
              <Empty title="No activity yet" description="Charts appear once your bots are used." />
            ) : (
              <ResponsiveContainer width="100%" height={260}>
                <AreaChart data={series} margin={{ top: 4, right: 8, bottom: 0, left: -20 }}>
                  <defs>
                    <linearGradient id="overviewFill" x1="0" y1="0" x2="0" y2="1">
                      <stop offset="0%" stopColor={chart.primary} stopOpacity={0.25} />
                      <stop offset="100%" stopColor={chart.primary} stopOpacity={0} />
                    </linearGradient>
                  </defs>
                  <CartesianGrid stroke={chart.grid} vertical={false} />
                  <XAxis
                    dataKey="date"
                    tick={chartAxis}
                    tickLine={false}
                    axisLine={false}
                    tickFormatter={shortDay}
                  />
                  <YAxis
                    tick={chartAxis}
                    tickLine={false}
                    axisLine={false}
                    allowDecimals={false}
                  />
                  <Tooltip
                    contentStyle={chartTooltip}
                    labelFormatter={(value) => shortDay(String(value))}
                  />
                  <Area
                    type="monotone"
                    dataKey="conversations"
                    stroke={chart.primary}
                    strokeWidth={2}
                    fill="url(#overviewFill)"
                    dot={{ r: 2.5, fill: chart.primary }}
                  />
                </AreaChart>
              </ResponsiveContainer>
            )}
          </div>
        </Card>

        <Card>
          <CardHeader
            title="Top Bots"
            action={
              <Link href="/bots" className="text-[13px] font-medium text-clay hover:underline">
                View all
              </Link>
            }
          />
          <div className="p-2">
            {topBots.length === 0 ? (
              <Empty title="Nothing to rank yet" />
            ) : (
              <ul>
                {topBots.map(({ bot, usage, previous }) => {
                  const delta = percentChange(
                    usage?.totalMessages ?? 0,
                    previous?.totalMessages ?? 0,
                  );
                  return (
                    <li key={bot.id}>
                      <Link
                        href={`/bots/${bot.id}`}
                        className="flex items-center gap-3 rounded-lg px-3 py-2.5 hover:bg-canvas"
                      >
                        <span className="grid h-8 w-8 shrink-0 place-items-center rounded-xl bg-clay-soft text-clay">
                          <BotIcon className="h-4 w-4" />
                        </span>
                        <span className="min-w-0 flex-1 truncate text-sm">{bot.name}</span>
                        <span className="text-sm font-semibold">
                          {formatNumber(usage?.totalMessages ?? 0)}
                        </span>
                        <Delta value={delta} />
                      </Link>
                    </li>
                  );
                })}
              </ul>
            )}
          </div>
        </Card>
      </div>

      <div className="mt-4 grid grid-cols-1 gap-4 xl:grid-cols-3">
        <Card className="xl:col-span-2">
          <CardHeader
            title="Recent Conversations"
            action={
              <Link
                href="/conversations"
                className="text-[13px] font-medium text-clay hover:underline"
              >
                View all
              </Link>
            }
          />
          <div className="p-2">
            {recent.length === 0 ? (
              <Empty title="No conversations yet" />
            ) : (
              <ul>
                {recent.map((conversation) => (
                  <li key={conversation.id}>
                    <Link
                      href={`/conversations/${conversation.id}`}
                      className="flex items-center justify-between gap-3 rounded-lg px-3 py-2.5 hover:bg-canvas"
                    >
                      <span className="min-w-0 flex-1 truncate text-sm">
                        {conversation.title ?? "Untitled conversation"}
                      </span>
                      <ResolutionBadge resolution={conversation.resolution} />
                      <span className="w-16 shrink-0 text-right text-[12px] text-faint">
                        {formatRelative(conversation.lastMessageAt ?? conversation.startedAt)}
                      </span>
                    </Link>
                  </li>
                ))}
              </ul>
            )}
          </div>
        </Card>

        <Card>
          <CardHeader
            title="Bot Status"
            action={
              <Link href="/bots" className="text-[13px] font-medium text-clay hover:underline">
                View all
              </Link>
            }
          />
          <div className="p-2">
            <ul>
              {bots.map((bot) => (
                <li
                  key={bot.id}
                  className="flex items-center gap-2.5 px-3 py-2.5 text-sm"
                >
                  <BotIcon className="h-4 w-4 shrink-0 text-faint" />
                  <span className="min-w-0 flex-1 truncate">{bot.name}</span>
                  <span
                    className={cx(
                      "flex items-center gap-1.5 text-[12.5px]",
                      bot.status === "ACTIVE" ? "text-ok" : "text-warn",
                    )}
                  >
                    <span
                      className={cx(
                        "h-1.5 w-1.5 rounded-full",
                        bot.status === "ACTIVE" ? "bg-ok" : "bg-warn",
                      )}
                    />
                    {bot.status === "ACTIVE" ? "Online" : "Maintenance"}
                  </span>
                </li>
              ))}
            </ul>
          </div>
        </Card>
      </div>
    </>
  );
}

// ------------------------------------------------------------------ pieces --

const FEATURES = [
  { href: "/health", label: "KB Health", body: "Find outdated or conflicting content", icon: HeartPulse, tint: "bg-sage-soft text-accent" },
  { href: "/studio", label: "Widget Studio", body: "Style it, preview it, embed it", icon: SlidersHorizontal, tint: "bg-clay-soft text-clay" },
  { href: "/inbox", label: "Handoff Inbox", body: "Escalations, owners, resolution", icon: Inbox, tint: "bg-warn-soft text-warn" },
  { href: "/insights", label: "Insights", body: "Common and unanswered questions", icon: BarChart3, tint: "bg-sage-soft text-accent" },
  { href: "/ask", label: "Ask Intelli", body: "Your AI setup guide", icon: Sparkles, tint: "bg-clay-soft text-clay" },
];

/** Shortcuts to the newer tools, so they are found without hunting the sidebar. */
function FeatureStrip() {
  return (
    <div className="mb-5 grid grid-cols-2 gap-3 md:grid-cols-3 xl:grid-cols-5">
      {FEATURES.map(({ href, label, body, icon: Icon, tint }) => (
        <Link
          key={href}
          href={href}
          className="group flex items-center gap-3 rounded-2xl border border-line bg-surface p-3 shadow-soft transition-transform hover:-translate-y-0.5"
        >
          <span className={cx("grid h-10 w-10 shrink-0 place-items-center rounded-xl", tint)}>
            <Icon className="h-5 w-5" />
          </span>
          <span className="min-w-0 flex-1">
            <span className="block text-[13.5px] font-semibold">{label}</span>
            <span className="block truncate text-[12px] text-muted">{body}</span>
          </span>
          <ArrowRight className="h-3.5 w-3.5 shrink-0 text-faint transition-transform group-hover:translate-x-0.5" />
        </Link>
      ))}
    </div>
  );
}

function Tile({
  label,
  value,
  delta,
  deltaUnit,
  days,
  icon,
  lowerIsBetter,
}: {
  label: string;
  value: string;
  delta: number | null;
  deltaUnit?: "pp";
  days: number;
  icon: React.ReactNode;
  lowerIsBetter?: boolean;
}) {
  return (
    <Card className="p-5">
      <div className="flex items-center justify-between">
        <span className="text-[13px] text-muted">{label}</span>
        <span className="grid h-8 w-8 place-items-center rounded-xl bg-clay-soft text-clay">
          {icon}
        </span>
      </div>
      <p className="mt-2 font-display text-[30px] font-semibold leading-tight tracking-tight">{value}</p>
      <div className="mt-1 flex items-center gap-1.5">
        <Delta value={delta} unit={deltaUnit} lowerIsBetter={lowerIsBetter} />
        <span className="text-[12px] text-faint">vs last {days} days</span>
      </div>
    </Card>
  );
}

/**
 * A period-over-period change.
 *
 * Renders nothing measurable when the previous period was empty: "+100%"
 * against a baseline of zero is arithmetic, not information.
 */
function Delta({
  value,
  unit,
  lowerIsBetter,
}: {
  value: number | null;
  unit?: "pp";
  lowerIsBetter?: boolean;
}) {
  if (value === null || !Number.isFinite(value)) {
    return <span className="text-[12px] text-faint">—</span>;
  }

  const improved = lowerIsBetter ? value < 0 : value > 0;
  const flat = Math.abs(value) < 0.05;

  return (
    <span
      className={cx(
        "flex items-center gap-0.5 text-[12px] font-medium",
        flat ? "text-muted" : improved ? "text-ok" : "text-bad",
      )}
    >
      {!flat &&
        (value > 0 ? (
          <ArrowUpRight className="h-3 w-3" />
        ) : (
          <ArrowDownRight className="h-3 w-3" />
        ))}
      {Math.abs(value).toFixed(1)}
      {unit === "pp" ? "pp" : "%"}
    </span>
  );
}

// ------------------------------------------------------------------- maths --

function sum(usages: BotUsage[]) {
  return usages.reduce(
    (total, usage) => ({
      conversations: total.conversations + usage.totalConversations,
      messages: total.messages + usage.totalMessages,
      answers: total.answers + usage.totalAnswers,
      grounded: total.grounded + usage.groundedAnswers,
      tokens: total.tokens + usage.totalTokens,
    }),
    { conversations: 0, messages: 0, answers: 0, grounded: 0, tokens: 0 },
  );
}

/** Null when the previous period was empty — see Delta for why. */
function percentChange(now: number, before: number): number | null {
  if (before === 0) return null;
  return ((now - before) / before) * 100;
}

/** Weighted by answers, so a quiet bot cannot skew the organization's average. */
function weightedAverageResponse(usages: BotUsage[]): number | null {
  let weighted = 0;
  let answers = 0;

  for (const usage of usages) {
    if (usage.averageResponseMs === null || usage.totalAnswers === 0) continue;
    weighted += usage.averageResponseMs * usage.totalAnswers;
    answers += usage.totalAnswers;
  }

  return answers === 0 ? null : weighted / answers;
}

function mergeDays(usages: BotUsage[]) {
  const byDate = new Map<string, { date: string; conversations: number; messages: number }>();

  for (const usage of usages) {
    for (const day of usage.days) {
      const row = byDate.get(day.date) ?? { date: day.date, conversations: 0, messages: 0 };
      row.conversations += day.conversations;
      row.messages += day.messages;
      byDate.set(day.date, row);
    }
  }

  return [...byDate.values()].sort((a, b) => a.date.localeCompare(b.date));
}

function greeting() {
  const hour = new Date().getHours();
  if (hour < 12) return "Good morning";
  if (hour < 18) return "Good afternoon";
  return "Good evening";
}
