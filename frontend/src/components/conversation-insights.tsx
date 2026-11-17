"use client";

import { useState } from "react";
import Link from "next/link";
import {
  CheckCircle2,
  HelpCircle,
  MessageCircleQuestion,
  MessagesSquare,
  Smile,
  TrendingUp,
} from "lucide-react";
import {
  Bar,
  BarChart,
  CartesianGrid,
  Legend,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import { api, type ConversationInsights, type QuestionCluster } from "@/lib/api";
import { useAsync } from "@/lib/hooks";
import { shortDay, utcDaysAgo, utcToday } from "@/lib/dates";
import { chart, chartAxis, chartTooltip } from "@/lib/theme";
import {
  Badge,
  Card,
  CardHeader,
  Empty,
  ErrorNote,
  Loading,
  Select,
  StatCard,
  formatNumber,
  formatPercent,
  formatRelative,
} from "@/components/ui";

const RANGES = [
  { days: 7, label: "Last 7 days" },
  { days: 30, label: "Last 30 days" },
  { days: 90, label: "Last 90 days" },
];

/** Conversation intelligence for one bot. */
export function ConversationInsightsView({ botId }: { botId: string }) {
  const [days, setDays] = useState(30);
  const { data, loading, error, reload } = useAsync(
    () => api.insights.forBot(botId, utcDaysAgo(days - 1), utcToday()),
    [botId, days],
  );

  return (
    <div className="space-y-4">
      <div className="flex justify-end">
        <Select
          value={days}
          onChange={(event) => setDays(Number(event.target.value))}
          className="w-auto"
          aria-label="Date range"
        >
          {RANGES.map((range) => (
            <option key={range.days} value={range.days}>
              {range.label}
            </option>
          ))}
        </Select>
      </div>

      {loading ? (
        <Loading label="Analysing conversations" />
      ) : error ? (
        <ErrorNote message={error} onRetry={reload} />
      ) : data ? (
        <Body data={data} botId={botId} />
      ) : null}
    </div>
  );
}

function Body({ data, botId }: { data: ConversationInsights; botId: string }) {
  const hasTraffic = data.totalQuestions > 0 || data.totalConversations > 0;

  if (!hasTraffic) {
    return (
      <Card>
        <Empty
          icon={<MessagesSquare className="h-6 w-6" />}
          title="No conversations in this range"
          description="Insights appear once visitors start talking to this bot. Try it yourself in the Chat Playground."
          action={
            <Link href={`/playground?bot=${botId}`} className="text-[13px] font-medium text-clay hover:underline">
              Open the playground →
            </Link>
          }
        />
      </Card>
    );
  }

  const trend = data.trend.filter((day) => day.started > 0).length > 0 ? data.trend : [];

  return (
    <>
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 xl:grid-cols-4">
        <StatCard
          label="Questions asked"
          value={formatNumber(data.totalQuestions)}
          hint={`across ${formatNumber(data.totalConversations)} conversations`}
          icon={<MessagesSquare className="h-4 w-4" />}
        />
        <StatCard
          label="Answer rate"
          value={formatPercent(data.answerRate)}
          hint={`${formatNumber(data.unansweredQuestions)} unanswered`}
          icon={<CheckCircle2 className="h-4 w-4" />}
        />
        <StatCard
          label="Satisfaction"
          value={formatPercent(data.satisfactionRate)}
          hint="Resolved ÷ (resolved + escalated)"
          icon={<Smile className="h-4 w-4" />}
        />
        <StatCard
          label="Escalated to a person"
          value={formatNumber(data.escalatedConversations)}
          hint={`${formatNumber(data.resolvedConversations)} resolved · ${formatNumber(data.openConversations)} open`}
          icon={<TrendingUp className="h-4 w-4" />}
        />
      </div>

      <div className="grid grid-cols-1 gap-4 xl:grid-cols-2">
        <QuestionList
          title="Common questions"
          description="What visitors ask most, grouped when only case or punctuation differs."
          icon={<HelpCircle className="h-4 w-4 text-accent" />}
          items={data.topQuestions}
          empty="No questions yet."
          show="count"
        />
        <QuestionList
          title="Unanswered queries"
          description="Asked, and nothing in your documents matched. Each is a gap worth filling."
          icon={<MessageCircleQuestion className="h-4 w-4 text-clay" />}
          items={data.unansweredTopics}
          empty="Every question was answered from your documents."
          show="unanswered"
          footer={
            data.unansweredTopics.length > 0 ? (
              <Link href={`/health?bot=${botId}`} className="font-medium text-clay hover:underline">
                Turn these into FAQs in KB Health →
              </Link>
            ) : undefined
          }
        />
      </div>

      <Card>
        <CardHeader
          title="Resolution trend"
          description="Conversations by the day they started, and where each one stands now."
        />
        <div className="p-5">
          {trend.length === 0 ? (
            <Empty title="No conversations started in this range" />
          ) : (
            <ResponsiveContainer width="100%" height={260}>
              <BarChart data={trend} margin={{ top: 4, right: 8, bottom: 0, left: -20 }}>
                <CartesianGrid stroke={chart.grid} vertical={false} />
                <XAxis dataKey="date" tick={chartAxis} tickLine={false} axisLine={false} tickFormatter={shortDay} minTickGap={16} />
                <YAxis tick={chartAxis} tickLine={false} axisLine={false} allowDecimals={false} />
                <Tooltip contentStyle={chartTooltip} labelFormatter={(value) => shortDay(String(value))} />
                <Legend wrapperStyle={{ fontSize: 12 }} />
                <Bar dataKey="resolved" name="Resolved" stackId="s" fill={chart.primary} />
                <Bar dataKey="escalated" name="Escalated" stackId="s" fill={chart.clay} />
                <Bar dataKey="open" name="Open" stackId="s" fill={chart.claySoft} radius={[4, 4, 0, 0]} />
              </BarChart>
            </ResponsiveContainer>
          )}
        </div>
      </Card>

      {data.truncated && (
        <p className="text-[12px] text-faint">
          This range holds more messages than are analysed at once, so the question lists
          describe the most recent ones. Narrow the range for a complete picture.
        </p>
      )}
    </>
  );
}

function QuestionList({
  title,
  description,
  icon,
  items,
  empty,
  show,
  footer,
}: {
  title: string;
  description: string;
  icon: React.ReactNode;
  items: QuestionCluster[];
  empty: string;
  show: "count" | "unanswered";
  footer?: React.ReactNode;
}) {
  const max = Math.max(1, ...items.map((item) => (show === "count" ? item.count : item.unansweredCount)));

  return (
    <Card className="flex flex-col">
      <CardHeader title={<span className="flex items-center gap-2">{icon}{title}</span>} description={description} />
      {items.length === 0 ? (
        <Empty title={empty} />
      ) : (
        <ol className="flex-1 divide-y divide-line">
          {items.map((item, index) => {
            const value = show === "count" ? item.count : item.unansweredCount;
            return (
              <li key={`${item.question}-${index}`} className="px-5 py-3">
                <div className="flex items-start justify-between gap-3">
                  <p className="min-w-0 text-[13.5px]">
                    <span className="mr-2 text-faint">{index + 1}.</span>
                    {item.question}
                  </p>
                  <span className="shrink-0 font-display text-[15px] font-semibold">{value}×</span>
                </div>
                <div className="mt-1.5 flex items-center gap-2">
                  <div className="h-1.5 flex-1 overflow-hidden rounded-full bg-cream ring-1 ring-line">
                    <div
                      className="h-full rounded-full"
                      style={{ width: `${(value / max) * 100}%`, background: show === "count" ? chart.primary : chart.clay }}
                    />
                  </div>
                  {show === "count" && item.unansweredCount > 0 && (
                    <Badge tone="warn">{item.unansweredCount} unanswered</Badge>
                  )}
                  <span className="text-[11px] text-faint">{formatRelative(item.lastAskedAt)}</span>
                </div>
              </li>
            );
          })}
        </ol>
      )}
      {footer && <div className="border-t border-line px-5 py-3 text-[13px]">{footer}</div>}
    </Card>
  );
}
