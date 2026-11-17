"use client";

import { useState } from "react";
import Link from "next/link";
import {
  AlertTriangle,
  CalendarClock,
  CheckCircle2,
  Copy,
  FileQuestion,
  FileText,
  FileWarning,
  GitCompareArrows,
  Hourglass,
  MessageCircleQuestion,
  RefreshCw,
  ShieldCheck,
  Trash2,
} from "lucide-react";
import {
  api,
  type HealthIssue,
  type HealthIssueType,
  type KnowledgeHealth,
} from "@/lib/api";
import { useAsync } from "@/lib/hooks";
import {
  Badge,
  Button,
  Card,
  CardHeader,
  ConfirmModal,
  Empty,
  ErrorNote,
  Loading,
  cx,
  formatNumber,
  formatRelative,
  notify,
  type Tone,
} from "@/components/ui";

const ICON: Record<HealthIssueType, React.ComponentType<{ className?: string }>> = {
  EMPTY_KNOWLEDGE_BASE: FileQuestion,
  FAILED: FileWarning,
  STUCK: Hourglass,
  NO_CONTENT: FileText,
  CONFLICTING_VERSIONS: GitCompareArrows,
  DUPLICATE: Copy,
  OUTDATED: CalendarClock,
};

const SEVERITY: Record<HealthIssue["severity"], { tone: Tone; label: string }> = {
  HIGH: { tone: "bad", label: "High" },
  MEDIUM: { tone: "warn", label: "Medium" },
  LOW: { tone: "neutral", label: "Low" },
};

/** What each kind of document can be fixed by: deleting the extra copies. */
const REMOVABLE: HealthIssueType[] = ["FAILED", "DUPLICATE", "CONFLICTING_VERSIONS", "OUTDATED", "NO_CONTENT"];

/**
 * The knowledge base health monitor for one bot.
 *
 * Findings come from document metadata and from questions the bot failed to
 * answer; the backend never reads document text. That limit is stated on the
 * screen rather than left for someone to discover.
 */
export function KnowledgeHealthView({ botId }: { botId: string }) {
  const { data, loading, refreshing, error, reload } = useAsync(
    // Listing documents first reconciles any still-indexing ones with the AI
    // service, so the check never reports a finished upload as "stuck".
    () => api.knowledge.listDocuments(botId, 0, 100).then(() => api.insights.health(botId)),
    [botId],
  );

  if (loading) return <Loading label="Checking your knowledge base" />;
  if (error) return <ErrorNote message={error} onRetry={reload} />;
  if (!data) return null;

  return (
    <div className="space-y-4">
      <Summary health={data} botId={botId} refreshing={refreshing} onRefresh={reload} />

      <div className="grid grid-cols-1 gap-4 xl:grid-cols-[minmax(0,1fr)_380px]">
        <Card>
          <CardHeader
            title="Findings"
            description="Ranked by how much each one can hurt an answer."
          />
          {data.issues.length === 0 ? (
            <Empty
              icon={<CheckCircle2 className="h-6 w-6" />}
              title="Nothing to fix"
              description="No failed, stuck, outdated, duplicate or conflicting documents."
            />
          ) : (
            <ul className="divide-y divide-line">
              {data.issues.map((issue, index) => (
                <IssueRow
                  key={`${issue.type}-${issue.documentIds.join(",")}-${index}`}
                  issue={issue}
                  onChanged={reload}
                />
              ))}
            </ul>
          )}
        </Card>

        <SuggestedFaqs faqs={data.suggestedFaqs} botId={botId} />
      </div>

      <p className="text-[12px] text-faint">
        Checked {formatRelative(data.checkedAt)}. Findings come from document names, sizes,
        dates and statuses, plus questions your bot could not answer. Document text is not
        compared, so &ldquo;conflicting&rdquo; means several live versions of the same document.
      </p>
    </div>
  );
}

function Summary({
  health,
  botId,
  refreshing,
  onRefresh,
}: {
  health: KnowledgeHealth;
  botId: string;
  refreshing: boolean;
  onRefresh: () => void;
}) {
  const counts = { HIGH: 0, MEDIUM: 0, LOW: 0 };
  for (const issue of health.issues) counts[issue.severity]++;

  const grade =
    health.score >= 85
      ? { label: "Healthy", tone: "ok" as Tone, colour: "#2f7d4f" }
      : health.score >= 60
        ? { label: "Needs attention", tone: "warn" as Tone, colour: "#d39b2a" }
        : { label: "At risk", tone: "bad" as Tone, colour: "#c2412d" };

  return (
    <Card className="p-5">
      <div className="flex flex-wrap items-center gap-6">
        <ScoreRing score={health.score} colour={grade.colour} />

        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-center gap-2">
            <h2 className="font-display text-[22px] font-semibold">Knowledge base health</h2>
            <Badge tone={grade.tone}>{grade.label}</Badge>
          </div>
          <p className="mt-1 text-[13px] text-muted">
            {health.issues.length === 0
              ? "Everything your bot answers from looks current and consistent."
              : `${health.issues.length} finding${health.issues.length === 1 ? "" : "s"}: ${counts.HIGH} high, ${counts.MEDIUM} medium, ${counts.LOW} low.`}
          </p>

          <dl className="mt-4 grid grid-cols-3 gap-3 sm:max-w-md">
            <Metric label="Documents" value={formatNumber(health.documentCount)} />
            <Metric label="Ready" value={formatNumber(health.indexedCount)} />
            <Metric label="Chunks" value={formatNumber(health.totalChunks)} />
          </dl>
        </div>

        <div className="flex gap-2">
          <Link
            href={`/bots/${botId}`}
            className="inline-flex items-center gap-2 rounded-xl border border-line bg-surface px-3 py-1.5 text-[13px] font-medium hover:border-clay/40"
          >
            Manage documents
          </Link>
          <Button size="sm" variant="secondary" onClick={onRefresh} loading={refreshing}>
            {!refreshing && <RefreshCw className="h-3.5 w-3.5" />}
            Re-check
          </Button>
        </div>
      </div>
    </Card>
  );
}

function Metric({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-xl bg-cream px-3 py-2 ring-1 ring-line">
      <dt className="text-[11.5px] text-muted">{label}</dt>
      <dd className="font-display text-lg font-semibold">{value}</dd>
    </div>
  );
}

/** The score as a ring. Plain SVG: one arc does not justify a chart library. */
function ScoreRing({ score, colour }: { score: number; colour: string }) {
  const radius = 38;
  const circumference = 2 * Math.PI * radius;
  const filled = (Math.max(0, Math.min(100, score)) / 100) * circumference;

  return (
    <div className="relative h-24 w-24 shrink-0" role="img" aria-label={`Health score ${score} out of 100`}>
      <svg viewBox="0 0 96 96" className="h-24 w-24 -rotate-90">
        <circle cx="48" cy="48" r={radius} fill="none" stroke="#eee2d5" strokeWidth="9" />
        <circle
          cx="48"
          cy="48"
          r={radius}
          fill="none"
          stroke={colour}
          strokeWidth="9"
          strokeLinecap="round"
          strokeDasharray={`${filled} ${circumference}`}
        />
      </svg>
      <span className="absolute inset-0 grid place-items-center">
        <span className="text-center">
          <span className="block font-display text-[26px] font-semibold leading-none">{score}</span>
          <span className="text-[10.5px] text-muted">/ 100</span>
        </span>
      </span>
    </div>
  );
}

function IssueRow({ issue, onChanged }: { issue: HealthIssue; onChanged: () => void }) {
  const Icon = ICON[issue.type] ?? AlertTriangle;
  const severity = SEVERITY[issue.severity];
  const [removing, setRemoving] = useState<{ id: string; name: string } | null>(null);

  // For versions and duplicates, the first listed is the newest upload: the
  // backend lists documents newest first. Offer to delete the others.
  const removable = REMOVABLE.includes(issue.type);
  const keepFirst = issue.type === "DUPLICATE" || issue.type === "CONFLICTING_VERSIONS";

  return (
    <li className="flex gap-3 px-5 py-4">
      <span
        className={cx(
          "grid h-9 w-9 shrink-0 place-items-center rounded-xl",
          issue.severity === "HIGH"
            ? "bg-bad-soft text-bad"
            : issue.severity === "MEDIUM"
              ? "bg-warn-soft text-warn"
              : "bg-sage-soft text-sage",
        )}
      >
        <Icon className="h-4.5 w-4.5" />
      </span>

      <div className="min-w-0 flex-1">
        <div className="flex flex-wrap items-center gap-2">
          <p className="text-sm font-medium">{issue.title}</p>
          <Badge tone={severity.tone}>{severity.label}</Badge>
        </div>
        <p className="mt-0.5 text-[13px] text-muted">{issue.detail}</p>

        {issue.filenames.length > 0 && (
          <ul className="mt-2 flex flex-wrap gap-1.5">
            {issue.filenames.map((name, index) => {
              const id = issue.documentIds[index];
              const offer = removable && (!keepFirst || index > 0);
              return (
                <li
                  key={id ?? name}
                  className="inline-flex max-w-full items-center gap-1.5 rounded-lg bg-cream py-1 pl-2 pr-1 text-[12px] ring-1 ring-line"
                >
                  <FileText className="h-3.5 w-3.5 shrink-0 text-faint" />
                  <span className="truncate">{name}</span>
                  {keepFirst && index === 0 && <Badge tone="ok">newest</Badge>}
                  {offer && id && (
                    <button
                      onClick={() => setRemoving({ id, name })}
                      aria-label={`Delete ${name}`}
                      title="Delete this document"
                      className="rounded-md p-0.5 text-faint hover:bg-bad-soft hover:text-bad"
                    >
                      <Trash2 className="h-3.5 w-3.5" />
                    </button>
                  )}
                </li>
              );
            })}
          </ul>
        )}

        <p className="mt-2 flex items-start gap-1.5 text-[12.5px] text-ink">
          <ShieldCheck className="mt-0.5 h-3.5 w-3.5 shrink-0 text-accent" />
          {issue.recommendation}
        </p>
      </div>

      <ConfirmModal
        open={removing !== null}
        title="Delete document"
        confirmLabel="Delete"
        body={
          <>
            Remove <strong className="text-ink">{removing?.name}</strong> from this bot&apos;s
            knowledge base? It stops being used in answers straight away.
          </>
        }
        onClose={() => setRemoving(null)}
        onConfirm={async () => {
          if (!removing) return;
          try {
            await api.knowledge.removeDocument(removing.id);
            notify.success(`Deleted ${removing.name}`);
            onChanged();
          } catch (caught) {
            notify.failure(caught, "Could not delete the document.");
          }
        }}
      />
    </li>
  );
}

function SuggestedFaqs({
  faqs,
  botId,
}: {
  faqs: KnowledgeHealth["suggestedFaqs"];
  botId: string;
}) {
  const [copied, setCopied] = useState(false);

  async function copyAll() {
    // A ready-to-fill FAQ document: upload it once the answers are written in.
    const text = faqs.map((faq) => `Q: ${faq.question}\nA: \n`).join("\n");
    try {
      await navigator.clipboard.writeText(text);
      setCopied(true);
      setTimeout(() => setCopied(false), 1600);
    } catch {
      notify.error("Copying needs a secure (https) page. Select the questions instead.");
    }
  }

  return (
    <Card className="h-fit">
      <CardHeader
        title="Suggested FAQs"
        description="Questions visitors asked in the last 30 days that no document answered."
        action={
          faqs.length > 0 ? (
            <Button size="sm" variant="secondary" onClick={copyAll}>
              <Copy className="h-3.5 w-3.5" />
              {copied ? "Copied" : "Copy as FAQ"}
            </Button>
          ) : undefined
        }
      />
      {faqs.length === 0 ? (
        <Empty
          icon={<MessageCircleQuestion className="h-6 w-6" />}
          title="No gaps found"
          description="Every recent question was answered from your documents."
        />
      ) : (
        <>
          <ol className="divide-y divide-line">
            {faqs.map((faq) => (
              <li key={faq.question} className="flex items-start gap-3 px-5 py-3">
                <MessageCircleQuestion className="mt-0.5 h-4 w-4 shrink-0 text-clay" />
                <div className="min-w-0 flex-1">
                  <p className="text-[13.5px]">{faq.question}</p>
                  <p className="mt-0.5 text-[11.5px] text-faint">
                    Asked {faq.timesAsked}× · last {formatRelative(faq.lastAskedAt)}
                  </p>
                </div>
              </li>
            ))}
          </ol>
          <p className="border-t border-line px-5 py-3 text-[12px] text-muted">
            Copy them, write the answers, save as a .txt or .docx and{" "}
            <Link href={`/bots/${botId}`} className="font-medium text-clay hover:underline">
              upload it
            </Link>
            . The bot will answer them from then on.
          </p>
        </>
      )}
    </Card>
  );
}
