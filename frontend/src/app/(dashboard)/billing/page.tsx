"use client";

import { CreditCard, MessagesSquare, Sparkles } from "lucide-react";
import { api, type Bot, type BotUsage } from "@/lib/api";
import { useAsync } from "@/lib/hooks";
import { utcMonthStart, utcToday } from "@/lib/dates";
import {
  Card,
  CardHeader,
  ErrorNote,
  Loading,
  PageHeader,
  StatCard,
  formatNumber,
} from "@/components/ui";

/**
 * Usage this month.
 *
 * There is no payment provider, no plan and no invoice, so this does not
 * pretend otherwise. What it can show honestly is consumption — the number a
 * plan would actually be metered on — read from the same usage records the
 * analytics screen uses.
 */
async function loadBilling(): Promise<{ bots: Bot[]; usage: BotUsage[] }> {
  const bots = (await api.bots.list()).items;

  // UTC month boundaries, as the backend counts usage. The local-time version
  // turned "1 November" into "31 October" east of Greenwich.
  const from = utcMonthStart();
  const to = utcToday();

  const usage = await Promise.all(bots.map((bot) => api.usage.forBot(bot.id, from, to)));
  return { bots, usage };
}

export default function BillingPage() {
  const { data, loading, error, reload } = useAsync(loadBilling);

  const month = new Date().toLocaleDateString(undefined, {
    month: "long",
    year: "numeric",
    timeZone: "UTC",
  });

  const totals = (data?.usage ?? []).reduce(
    (sum, usage) => ({
      conversations: sum.conversations + usage.totalConversations,
      messages: sum.messages + usage.totalMessages,
      tokens: sum.tokens + usage.totalTokens,
    }),
    { conversations: 0, messages: 0, tokens: 0 },
  );

  return (
    <>
      <PageHeader title="Billing" description={`Your usage so far in ${month} (UTC).`} />

      {loading ? (
        <Loading />
      ) : error ? (
        <ErrorNote message={error} onRetry={reload} />
      ) : (
        <>
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
            <StatCard
              label="Conversations"
              value={formatNumber(totals.conversations)}
              icon={<Sparkles className="h-4 w-4" />}
            />
            <StatCard
              label="Messages"
              value={formatNumber(totals.messages)}
              icon={<MessagesSquare className="h-4 w-4" />}
            />
            <StatCard
              label="Tokens"
              value={formatNumber(totals.tokens)}
              hint="A floor — streamed answers report none"
              icon={<CreditCard className="h-4 w-4" />}
            />
          </div>

          <Card className="mt-4">
            <CardHeader title="Usage by bot" />
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b border-line text-left text-[12px] text-muted">
                    <th className="px-5 py-3 font-medium">Bot</th>
                    <th className="px-5 py-3 font-medium">Conversations</th>
                    <th className="px-5 py-3 font-medium">Messages</th>
                    <th className="px-5 py-3 font-medium">Tokens</th>
                  </tr>
                </thead>
                <tbody>
                  {(data?.bots ?? []).map((bot, index) => (
                    <tr key={bot.id} className="border-b border-line last:border-0">
                      <td className="px-5 py-3.5 font-medium">{bot.name}</td>
                      <td className="px-5 py-3.5 text-muted">
                        {formatNumber(data?.usage[index]?.totalConversations ?? 0)}
                      </td>
                      <td className="px-5 py-3.5 text-muted">
                        {formatNumber(data?.usage[index]?.totalMessages ?? 0)}
                      </td>
                      <td className="px-5 py-3.5 text-muted">
                        {formatNumber(data?.usage[index]?.totalTokens ?? 0)}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </Card>

          <Card className="mt-4">
            <div className="flex items-start gap-3 p-5">
              <CreditCard className="mt-0.5 h-5 w-5 shrink-0 text-faint" />
              <div className="text-[13px] leading-relaxed text-muted">
                <p className="font-medium text-ink">No plan or payment method yet</p>
                <p className="mt-1">
                  No payment provider is wired up, so there is nothing here pretending to be
                  one. The consumption above is real: it comes from the same usage records
                  the analytics screen reads, and it is what a plan would be metered on.
                </p>
              </div>
            </div>
          </Card>
        </>
      )}
    </>
  );
}
