"use client";

import { useState } from "react";
import Link from "next/link";
import { ArrowDownRight, ArrowUpRight, Bot as BotIcon, Check, Plus } from "lucide-react";
import { Bar, BarChart, CartesianGrid, Cell, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";
import { api, type Bot, type BotUsage, type Conversation } from "@/lib/api";
import { useAsync } from "@/lib/hooks";
import { useAuth } from "@/lib/auth";
import { shortDay, utcDaysAgo, utcToday } from "@/lib/dates";
import { chart, chartAxis, chartTooltip } from "@/lib/theme";
import {
  buttonLink,
  Card,
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
  { days: 14, label: "Last 14 days" },
  { days: 30, label: "Last 30 days" },
  { days: 90, label: "Last 90 days" },
] as const;

interface Setup {
  hasDocuments: boolean;
  hasConversation: boolean;
  hasDomain: boolean;
  hasTeammate: boolean;
}

interface Overview {
  bots: Bot[];
  usageByBot: Map<string, BotUsage>;
  /** The same window immediately before, for the "vs previous" deltas. */
  previousByBot: Map<string, BotUsage>;
  recent: Array<Conversation & { botName: string }>;
  setup: Setup;
}

/** A failed optional lookup counts as "not done yet" rather than breaking the page. */
function orElse<T>(promise: Promise<T>, fallback: T): Promise<T> {
  return promise.catch(() => fallback);
}

/**
 * Loads the organization's picture from per-bot endpoints.
 *
 * There is no org-wide analytics endpoint, so this fans out: two usage calls
 * and one conversation call per bot, plus the setup-guide checks. Fine for the
 * handful of bots a workspace has, and honest about it: the fix is an
 * aggregate endpoint on the backend, not a cleverer client.
 */
async function loadOverview(days: number): Promise<Overview> {
  const bots = (await api.bots.list()).items;

  // UTC dates throughout: the backend buckets usage by UTC day.
  const to = utcToday();
  const from = utcDaysAgo(days - 1);
  const previousTo = utcDaysAgo(days);
  const previousFrom = utcDaysAgo(days * 2 - 1);

  const [usages, previous, conversations, documents, domains, members] = await Promise.all([
    Promise.all(bots.map((bot) => api.usage.forBot(bot.id, from, to))),
    Promise.all(bots.map((bot) => api.usage.forBot(bot.id, previousFrom, previousTo))),
    Promise.all(bots.map((bot) => api.conversations.list(bot.id, 0, 5))),
    // Setup guide: each check is a real lookup, never a guess.
    Promise.all(bots.map((bot) => orElse(api.knowledge.listDocuments(bot.id, 0, 1).then((p) => p.totalItems), 0))),
    Promise.all(bots.map((bot) => orElse(api.credentials.listDomains(bot.id).then((d) => d.length), 0))),
    orElse(api.members.list().then((m) => m.length), 0),
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
    setup: {
      hasDocuments: documents.some((count) => count > 0),
      hasConversation: conversations.some((page) => page.totalItems > 0),
      hasDomain: domains.some((count) => count > 0),
      hasTeammate: members > 1,
    },
  };
}

export default function OverviewPage() {
  const { user } = useAuth();
  const [days, setDays] = useState<number>(14);
  const { data, loading, error, reload } = useAsync(() => loadOverview(days), [days]);

  const firstName = user?.user.name.split(/\s+/)[0] ?? "there";
  const today = new Date().toLocaleDateString(undefined, { weekday: "long", day: "numeric", month: "long" });

  return (
    <>
      <PageHeader
        eyebrow={today}
        title={`${greeting()}, ${firstName}`}
        description={data ? summaryLine(data) : "Here’s what’s happening with your assistants."}
        action={
          <div className="flex flex-wrap gap-2.5">
            <Link
              href="/inbox"
              className="inline-flex min-h-11 items-center rounded-xl border border-[#e2d4c5] bg-surface px-4 text-sm font-semibold text-ink hover:bg-cream"
            >
              Open inbox
            </Link>
            <Link href="/bots" className={buttonLink}>
              <Plus className="h-4 w-4" />
              New bot
            </Link>
          </div>
        }
      />

      {loading ? (
        <Loading />
      ) : error ? (
        <ErrorNote message={error} onRetry={reload} />
      ) : !data || data.bots.length === 0 ? (
        <div className="grid gap-4 lg:grid-cols-3">
          <Card className="lg:col-span-2">
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
          <SetupGuide hasBot={false} setup={null} />
        </div>
      ) : (
        <Body data={data} days={days} onDaysChange={setDays} />
      )}
    </>
  );
}

/** "Your assistants answered 214 questions today." Built only from real counts. */
function summaryLine(data: Overview) {
  const today = utcToday();
  const answeredToday = [...data.usageByBot.values()].reduce(
    (total, usage) => total + (usage.days.find((day) => day.date === today)?.messages ?? 0),
    0,
  );
  const escalated = data.recent.filter((c) => c.resolution === "ESCALATED").length;
  const first = `Your assistants handled ${formatNumber(answeredToday)} message${answeredToday === 1 ? "" : "s"} today.`;
  return escalated > 0 ? `${first} Some customers are waiting for a person.` : first;
}

function Body({ data, days, onDaysChange }: { data: Overview; days: number; onDaysChange: (days: number) => void }) {
  const { bots, usageByBot, previousByBot, recent, setup } = data;

  const totals = sum([...usageByBot.values()]);
  const before = sum([...previousByBot.values()]);

  const groundedRate = totals.answers > 0 ? (totals.grounded / totals.answers) * 100 : null;
  const groundedRateBefore = before.answers > 0 ? (before.grounded / before.answers) * 100 : null;

  // A mean of per-bot means would weight a quiet bot the same as a busy one,
  // so this weights each bot's average by the answers it actually produced.
  const responseMs = weightedAverageResponse([...usageByBot.values()]);
  const responseMsBefore = weightedAverageResponse([...previousByBot.values()]);

  const series = mergeDays([...usageByBot.values()]);
  const busiest = series.reduce((max, day) => Math.max(max, day.conversations), 0);

  return (
    <div className="flex flex-col gap-5">
      <section aria-label={`Last ${days} days`} className="grid grid-cols-[repeat(auto-fit,minmax(220px,1fr))] gap-4">
        <Tile
          label={`Conversations · ${days} days`}
          value={formatNumber(totals.conversations)}
          delta={percentChange(totals.conversations, before.conversations)}
        />
        <Tile label="Messages" value={formatNumber(totals.messages)} delta={percentChange(totals.messages, before.messages)} />
        <Tile
          label="Grounded answers"
          value={groundedRate === null ? "—" : `${groundedRate.toFixed(0)}%`}
          delta={groundedRate !== null && groundedRateBefore !== null ? groundedRate - groundedRateBefore : null}
          deltaUnit="pp"
        />
        <Tile
          label="Avg. response time"
          value={responseMs === null ? "—" : `${(responseMs / 1000).toFixed(1)} s`}
          delta={responseMs !== null && responseMsBefore !== null ? percentChange(responseMs, responseMsBefore) : null}
          // Faster is better, so a fall is good news here.
          lowerIsBetter
        />
      </section>

      <div className="grid gap-4 lg:grid-cols-3">
        <Card className="min-w-0 p-6 lg:col-span-2">
          <div className="flex flex-wrap items-center gap-3">
            <h2 className="font-display text-[20px] font-semibold">Conversations, {RANGES.find((r) => r.days === days)?.label.toLowerCase()}</h2>
            <span className="text-[13px] text-muted">{formatNumber(totals.conversations)} total</span>
            <div className="ml-auto flex items-center gap-3">
              <Select
                aria-label="Date range"
                value={days}
                onChange={(event) => onDaysChange(Number(event.target.value))}
                className="w-auto text-[13px]"
              >
                {RANGES.map((range) => (
                  <option key={range.days} value={range.days}>
                    {range.label}
                  </option>
                ))}
              </Select>
              <Link href="/analytics" className="text-[14px] font-semibold text-accent hover:underline">
                Analytics
              </Link>
            </div>
          </div>
          <div className="mt-5">
            {series.length === 0 || busiest === 0 ? (
              <Empty title="No activity yet" description="The chart fills in once your bots are used." />
            ) : (
              <ResponsiveContainer width="100%" height={220}>
                <BarChart data={series} margin={{ top: 4, right: 4, bottom: 0, left: -20 }}>
                  <CartesianGrid stroke={chart.grid} vertical={false} />
                  <XAxis dataKey="date" tick={chartAxis} tickLine={false} axisLine={false} tickFormatter={shortDay} />
                  <YAxis tick={chartAxis} tickLine={false} axisLine={false} allowDecimals={false} />
                  <Tooltip
                    contentStyle={chartTooltip}
                    cursor={{ fill: "#fbf6f0" }}
                    labelFormatter={(value) => shortDay(String(value))}
                  />
                  <Bar dataKey="conversations" name="Conversations" radius={[6, 6, 0, 0]} maxBarSize={30}>
                    {/* Today in terracotta, so the latest day stands out. */}
                    {series.map((day, index) => (
                      <Cell key={day.date} fill={index === series.length - 1 ? chart.clay : chart.primary} />
                    ))}
                  </Bar>
                </BarChart>
              </ResponsiveContainer>
            )}
          </div>
        </Card>

        <SetupGuide hasBot setup={setup} />
      </div>

      <div className="grid gap-4 lg:grid-cols-3">
        <Card className="min-w-0 overflow-hidden lg:col-span-2">
          <div className="flex items-center px-6 pb-2 pt-5">
            <h2 className="font-display text-[20px] font-semibold">Recent conversations</h2>
            <Link href="/conversations" className="ml-auto text-[14px] font-semibold text-accent hover:underline">
              View all
            </Link>
          </div>
          {recent.length === 0 ? (
            <Empty title="No conversations yet" description="Try your bot in the Chat Playground to start one." />
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full text-[14px]">
                <thead>
                  <tr className="border-b border-line text-left text-[12px] font-semibold text-muted">
                    <th className="px-6 py-3">Conversation</th>
                    <th className="px-4 py-3">Bot</th>
                    <th className="px-4 py-3">Status</th>
                    <th className="px-6 py-3 text-right">When</th>
                  </tr>
                </thead>
                <tbody>
                  {recent.map((conversation) => (
                    <tr key={conversation.id} className="border-b border-[#f3ece4] last:border-0 hover:bg-cream/60">
                      <td className="max-w-[320px] truncate px-6 py-3.5">
                        <Link href={`/conversations/${conversation.id}`} className="font-medium text-ink hover:underline">
                          {conversation.title ?? "Untitled conversation"}
                        </Link>
                      </td>
                      <td className="whitespace-nowrap px-4 py-3.5 text-muted">{conversation.botName}</td>
                      <td className="px-4 py-3.5">
                        <ResolutionBadge resolution={conversation.resolution} />
                      </td>
                      <td className="whitespace-nowrap px-6 py-3.5 text-right text-muted">
                        {formatRelative(conversation.lastMessageAt ?? conversation.startedAt)}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </Card>

        <Card className="p-6">
          <div className="mb-2 flex items-center">
            <h2 className="font-display text-[20px] font-semibold">Bot status</h2>
            <Link href="/bots" className="ml-auto text-[14px] font-semibold text-accent hover:underline">
              View all
            </Link>
          </div>
          <ul>
            {bots.map((bot) => {
              const conversationsCount = usageByBot.get(bot.id)?.totalConversations ?? 0;
              const online = bot.status === "ACTIVE";
              return (
                <li key={bot.id}>
                  <Link
                    href={`/bots/${bot.id}`}
                    className="flex items-center gap-3 border-b border-[#f3ece4] py-3 last:border-0"
                  >
                    <span className="grid h-10 w-10 shrink-0 place-items-center rounded-xl bg-accent-soft font-display font-semibold text-accent">
                      {bot.name.slice(0, 1).toUpperCase()}
                    </span>
                    <span className="min-w-0 flex-1">
                      <span className="block truncate text-[14px] font-semibold">{bot.name}</span>
                      <span className="block text-[13px] text-muted">
                        {formatNumber(conversationsCount)} conversation{conversationsCount === 1 ? "" : "s"}
                      </span>
                    </span>
                    <span
                      className={cx(
                        "rounded-full px-2.5 py-0.5 text-[12px] font-semibold",
                        online ? "bg-ok-soft text-ok" : "bg-warn-soft text-warn",
                      )}
                    >
                      {online ? "Online" : "Paused"}
                    </span>
                  </Link>
                </li>
              );
            })}
          </ul>
        </Card>
      </div>
    </div>
  );
}

// ------------------------------------------------------------------ pieces --

/** Every step is checked against real data; nothing is ticked by assumption. */
function SetupGuide({ hasBot, setup }: { hasBot: boolean; setup: Setup | null }) {
  const steps = [
    { label: "Create your first bot", href: "/bots", done: hasBot },
    { label: "Upload documents", href: "/knowledge", done: setup?.hasDocuments ?? false },
    { label: "Test it in the playground", href: "/playground", done: setup?.hasConversation ?? false },
    { label: "Allow your website’s domain", href: "/domains", done: setup?.hasDomain ?? false },
    { label: "Invite a teammate", href: "/members", done: setup?.hasTeammate ?? false },
  ];
  const done = steps.filter((step) => step.done).length;
  const percent = Math.round((done / steps.length) * 100);

  return (
    <Card className="flex flex-col gap-3.5 p-6">
      <h2 className="font-display text-[20px] font-semibold">Your setup guide</h2>
      <div
        className="h-2 overflow-hidden rounded-full bg-[#f3ece4]"
        role="progressbar"
        aria-valuenow={percent}
        aria-valuemin={0}
        aria-valuemax={100}
        aria-label="Setup progress"
      >
        <div className="h-full rounded-full bg-accent" style={{ width: `${percent}%` }} />
      </div>
      <p className="text-[13px] text-muted">
        {done} of {steps.length} steps done
      </p>
      <ul className="flex flex-col">
        {steps.map((step) => (
          <li key={step.label}>
            <Link href={step.href} className="flex min-h-10 items-center gap-3 text-[14px] hover:text-accent">
              {step.done ? (
                <span className="grid h-[22px] w-[22px] shrink-0 place-items-center rounded-full bg-accent text-white">
                  <Check className="h-3.5 w-3.5" strokeWidth={3} />
                </span>
              ) : (
                <span className="h-[22px] w-[22px] shrink-0 rounded-full border-2 border-clay" aria-hidden />
              )}
              <span className={cx("flex-1", step.done && "text-muted line-through decoration-line")}>
                {step.label}
                <span className="sr-only">{step.done ? " (done)" : " (to do)"}</span>
              </span>
            </Link>
          </li>
        ))}
      </ul>
    </Card>
  );
}

function Tile({
  label,
  value,
  delta,
  deltaUnit,
  lowerIsBetter,
}: {
  label: string;
  value: string;
  delta: number | null;
  deltaUnit?: "pp";
  lowerIsBetter?: boolean;
}) {
  return (
    <Card className="flex flex-col gap-1.5 px-5 py-5">
      <span className="text-[14px] font-medium text-muted">{label}</span>
      <span className="font-display text-[32px] font-semibold leading-tight tracking-tight">{value}</span>
      <span className="flex items-center gap-1.5">
        <Delta value={delta} unit={deltaUnit} lowerIsBetter={lowerIsBetter} />
        <span className="text-[12px] text-muted">vs previous period</span>
      </span>
    </Card>
  );
}

/**
 * A period-over-period change.
 *
 * Renders nothing measurable when the previous period was empty: "+100%"
 * against a baseline of zero is arithmetic, not information.
 */
function Delta({ value, unit, lowerIsBetter }: { value: number | null; unit?: "pp"; lowerIsBetter?: boolean }) {
  if (value === null || !Number.isFinite(value)) {
    return <span className="text-[13px] text-muted">—</span>;
  }

  const improved = lowerIsBetter ? value < 0 : value > 0;
  const flat = Math.abs(value) < 0.05;

  return (
    <span
      className={cx(
        "flex items-center gap-0.5 text-[13px] font-semibold",
        flat ? "text-muted" : improved ? "text-ok" : "text-bad",
      )}
    >
      {!flat && (value > 0 ? <ArrowUpRight className="h-3.5 w-3.5" /> : <ArrowDownRight className="h-3.5 w-3.5" />)}
      {Math.abs(value).toFixed(1)}
      {unit === "pp" ? " pts" : "%"}
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
