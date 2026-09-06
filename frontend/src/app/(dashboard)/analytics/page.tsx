"use client";

import { useState } from "react";
import { BarChart3, MessagesSquare, ShieldCheck, ShieldX, Sparkles } from "lucide-react";
import {
  Area,
  AreaChart,
  Bar,
  BarChart,
  CartesianGrid,
  Legend,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import { api, type Bot, type BotUsage } from "@/lib/api";
import { useAsync } from "@/lib/hooks";
import { AiQuality } from "@/components/ai-quality";
import {
  Card,
  CardHeader,
  Empty,
  ErrorNote,
  Loading,
  Select,
  StatCard,
  formatNumber,
} from "@/components/ui";

/** The ranges the backend will accept without complaint. */
const RANGES = [
  { days: 7, label: "Last 7 days" },
  { days: 30, label: "Last 30 days" },
  { days: 90, label: "Last 90 days" },
] as const;

function isoDaysAgo(days: number) {
  const date = new Date();
  date.setDate(date.getDate() - days);
  return date.toISOString().slice(0, 10);
}

interface Analytics {
  bots: Bot[];
  usage: BotUsage[];
}

async function loadAnalytics(botId: string | "all", days: number): Promise<Analytics> {
  const bots = (await api.bots.list()).items;
  const wanted = botId === "all" ? bots : bots.filter((bot) => bot.id === botId);

  const from = isoDaysAgo(days - 1);
  const to = new Date().toISOString().slice(0, 10);
  const usage = await Promise.all(wanted.map((bot) => api.usage.forBot(bot.id, from, to)));

  return { bots, usage };
}

export default function AnalyticsPage() {
  const [botId, setBotId] = useState<string | "all">("all");
  const [days, setDays] = useState<number>(30);

  const { data, loading, error, reload } = useAsync(
    () => loadAnalytics(botId, days),
    [botId, days],
  );

  return (
    <>
      <div className="mb-5 flex flex-wrap items-end justify-between gap-4">
        <div>
          <h1 className="text-xl font-semibold tracking-tight">Analytics</h1>
          <p className="mt-0.5 text-[13px] text-muted">
            How much your bots are used, and how often they can actually answer.
          </p>
        </div>

        <div className="flex gap-2">
          {data && data.bots.length > 1 && (
            <Select value={botId} onChange={(event) => setBotId(event.target.value)}>
              <option value="all">All bots</option>
              {data.bots.map((bot) => (
                <option key={bot.id} value={bot.id}>
                  {bot.name}
                </option>
              ))}
            </Select>
          )}
          <Select value={days} onChange={(event) => setDays(Number(event.target.value))}>
            {RANGES.map((range) => (
              <option key={range.days} value={range.days}>
                {range.label}
              </option>
            ))}
          </Select>
        </div>
      </div>

      {loading ? (
        <Loading />
      ) : error ? (
        <ErrorNote message={error} onRetry={reload} />
      ) : !data || data.bots.length === 0 ? (
        <Card>
          <Empty
            icon={<BarChart3 className="h-7 w-7" />}
            title="No bots yet"
            description="Analytics appear once you have a bot and someone has talked to it."
          />
        </Card>
      ) : (
        <>
          <AnalyticsBody usage={data.usage} />

          <div className="mt-8">
            <h2 className="text-lg font-semibold tracking-tight">AI quality</h2>
            <p className="mt-0.5 mb-4 text-[13px] text-muted">
              Measured by running a fixed set of test questions with known answers. Separate
              from live traffic, so these numbers describe the pipeline rather than how busy
              it has been.
            </p>

            {botId === "all" ? (
              <Card>
                <Empty
                  icon={<BarChart3 className="h-7 w-7" />}
                  title="Pick a bot"
                  description="Quality is measured per bot, against that bot's own knowledge base."
                />
              </Card>
            ) : (
              <AiQuality botId={botId} />
            )}
          </div>
        </>
      )}
    </>
  );
}

function AnalyticsBody({ usage }: { usage: BotUsage[] }) {
  const totals = usage.reduce(
    (sum, entry) => ({
      conversations: sum.conversations + entry.totalConversations,
      messages: sum.messages + entry.totalMessages,
      answers: sum.answers + entry.totalAnswers,
      grounded: sum.grounded + entry.groundedAnswers,
      ungrounded: sum.ungrounded + entry.ungroundedAnswers,
      tokens: sum.tokens + entry.totalTokens,
    }),
    { conversations: 0, messages: 0, answers: 0, grounded: 0, ungrounded: 0, tokens: 0 },
  );

  // Grounded and ungrounded come from the API. Deriving either one here would
  // mean assuming how `messages` maps to answers, which is exactly the sort of
  // hidden convention that produces a confidently wrong number.
  const groundedRate =
    totals.answers > 0 ? (totals.grounded / totals.answers) * 100 : null;

  const byDate = new Map<
    string,
    { date: string; conversations: number; grounded: number; ungrounded: number }
  >();
  for (const entry of usage) {
    for (const day of entry.days) {
      const row =
        byDate.get(day.date) ??
        { date: day.date, conversations: 0, grounded: 0, ungrounded: 0 };
      row.conversations += day.conversations;
      row.grounded += day.groundedAnswers;
      row.ungrounded += day.ungroundedAnswers;
      byDate.set(day.date, row);
    }
  }
  const series = [...byDate.values()].sort((a, b) => a.date.localeCompare(b.date));

  const axis = { fontSize: 11, fill: "#9a9ab0" };
  const tick = (value: string) =>
    new Date(value).toLocaleDateString(undefined, { day: "numeric", month: "short" });
  const tooltip = { borderRadius: 10, border: "1px solid #e9e9f2", fontSize: 12 };

  return (
    <>
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 xl:grid-cols-4">
        <StatCard
          label="Conversations"
          value={formatNumber(totals.conversations)}
          icon={<Sparkles className="h-4 w-4" />}
        />
        <StatCard
          label="Messages"
          value={formatNumber(totals.messages)}
          hint={`${formatNumber(totals.answers)} answers`}
          icon={<MessagesSquare className="h-4 w-4" />}
        />
        <StatCard
          label="Grounded"
          value={formatNumber(totals.grounded)}
          hint={groundedRate === null ? undefined : `${groundedRate.toFixed(0)}% of answers`}
          icon={<ShieldCheck className="h-4 w-4" />}
        />
        <StatCard
          label="Unanswered"
          value={formatNumber(totals.ungrounded)}
          hint="Nothing in the corpus matched"
          icon={<ShieldX className="h-4 w-4" />}
        />
      </div>

      <Card className="mt-4">
        <CardHeader title="Conversations" description="Daily volume over the range." />
        <div className="p-5">
          {series.length === 0 ? (
            <Empty title="No activity in this range" />
          ) : (
            <ResponsiveContainer width="100%" height={240}>
              <AreaChart data={series} margin={{ top: 4, right: 8, bottom: 0, left: -20 }}>
                <defs>
                  <linearGradient id="fillA" x1="0" y1="0" x2="0" y2="1">
                    <stop offset="0%" stopColor="#7c5aed" stopOpacity={0.28} />
                    <stop offset="100%" stopColor="#7c5aed" stopOpacity={0} />
                  </linearGradient>
                </defs>
                <CartesianGrid stroke="#e9e9f2" vertical={false} />
                <XAxis
                  dataKey="date"
                  tick={axis}
                  tickLine={false}
                  axisLine={false}
                  tickFormatter={tick}
                />
                <YAxis tick={axis} tickLine={false} axisLine={false} allowDecimals={false} />
                <Tooltip contentStyle={tooltip} />
                <Area
                  type="monotone"
                  dataKey="conversations"
                  stroke="#7c5aed"
                  strokeWidth={2}
                  fill="url(#fillA)"
                />
              </AreaChart>
            </ResponsiveContainer>
          )}
        </div>
      </Card>

      <Card className="mt-4">
        <CardHeader
          title="Answer quality"
          description="Grounded answers came from a document. Unanswered means retrieval found nothing — the signal that your corpus has a gap."
        />
        <div className="p-5">
          {series.length === 0 ? (
            <Empty title="No answers in this range" />
          ) : (
            <ResponsiveContainer width="100%" height={240}>
              <BarChart data={series} margin={{ top: 4, right: 8, bottom: 0, left: -20 }}>
                <CartesianGrid stroke="#e9e9f2" vertical={false} />
                <XAxis
                  dataKey="date"
                  tick={axis}
                  tickLine={false}
                  axisLine={false}
                  tickFormatter={tick}
                />
                <YAxis tick={axis} tickLine={false} axisLine={false} allowDecimals={false} />
                <Tooltip contentStyle={tooltip} />
                <Legend wrapperStyle={{ fontSize: 12 }} />
                {/* Stacked, because the pair is the total answers for that day
                    and the eye should read the split, not two separate series. */}
                <Bar
                  dataKey="grounded"
                  name="Grounded"
                  stackId="answers"
                  fill="#7c5aed"
                  radius={[0, 0, 0, 0]}
                />
                <Bar
                  dataKey="ungrounded"
                  name="Unanswered"
                  stackId="answers"
                  fill="#e5ddfb"
                  radius={[4, 4, 0, 0]}
                />
              </BarChart>
            </ResponsiveContainer>
          )}
        </div>
      </Card>
    </>
  );
}
