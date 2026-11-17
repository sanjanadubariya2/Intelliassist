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
          <section className="mb-5 flex flex-wrap items-start gap-4 rounded-2xl border border-dashed border-[#d9c6b3] bg-cream p-5">
            <span className="grid h-11 w-11 shrink-0 place-items-center rounded-xl bg-clay-soft text-clay-strong">
              <CreditCard className="h-5 w-5" />
            </span>
            <div className="min-w-0 flex-[1_1_360px]">
              <p className="text-[16px] font-semibold">No plan or payment method yet</p>
              <p className="mt-1 text-[14px] leading-normal text-muted">
                No payment provider is wired up, so nothing here pretends to be one. The usage below is real: it
                comes from the same records as Analytics, and it is what a plan would be metered on.
              </p>
            </div>
          </section>

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
              <table className="w-full text-[14px]">
                <thead>
                  <tr className="border-b border-line text-left text-[12px] font-semibold text-muted">
                    <th className="px-5 py-3">Bot</th>
                    <th className="px-5 py-3">Conversations</th>
                    <th className="px-5 py-3">Messages</th>
                    <th className="px-5 py-3">Tokens</th>
                    <th className="px-5 py-3">Share of tokens</th>
                  </tr>
                </thead>
                <tbody>
                  {(data?.bots ?? []).map((bot, index) => {
                    const tokens = data?.usage[index]?.totalTokens ?? 0;
                    const share = totals.tokens > 0 ? Math.round((tokens / totals.tokens) * 100) : 0;
                    return (
                      <tr key={bot.id} className="border-b border-[#f3ece4] last:border-0">
                        <td className="px-5 py-3.5 font-semibold">{bot.name}</td>
                        <td className="px-5 py-3.5">{formatNumber(data?.usage[index]?.totalConversations ?? 0)}</td>
                        <td className="px-5 py-3.5">{formatNumber(data?.usage[index]?.totalMessages ?? 0)}</td>
                        <td className="px-5 py-3.5">{formatNumber(tokens)}</td>
                        <td className="px-5 py-3.5">
                          <span className="flex min-w-[180px] items-center gap-2.5">
                            <span className="h-2 flex-1 overflow-hidden rounded-full bg-[#f3ece4]" aria-hidden>
                              <span className="block h-full rounded-full bg-clay" style={{ width: `${share}%` }} />
                            </span>
                            <span className="w-10 text-[13px] text-muted">{share}%</span>
                          </span>
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          </Card>
        </>
      )}
    </>
  );
}
