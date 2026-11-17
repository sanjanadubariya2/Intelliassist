"use client";

import { useState } from "react";
import Link from "next/link";
import { BookOpen, FileText, HeartPulse } from "lucide-react";
import { api, isIndexing, type Bot, type Document } from "@/lib/api";
import { DocumentStatusBadge } from "@/components/document-status";
import { useAsync, usePolling } from "@/lib/hooks";
import {
  Badge,
  Card,
  CardHeader,
  Empty,
  ErrorNote,
  Loading,
  PageHeader,
  Select,
  formatBytes,
  formatRelative,
  cx,
} from "@/components/ui";

/**
 * The tabs from the design.
 *
 * Only Documents has a backend. The AI service ingests PDF, DOCX, TXT and MD
 * files and nothing else, so FAQs and website crawling would need work in that
 * service — which is frozen. They are shown, disabled, with the reason stated,
 * rather than either quietly dropped from the design or faked with a form that
 * posts nowhere.
 */
const TABS = [
  { id: "documents", label: "Documents", available: true },
  { id: "faqs", label: "FAQs", available: false },
  { id: "websites", label: "Websites", available: false },
] as const;

type TabId = (typeof TABS)[number]["id"];

interface Library {
  bots: Bot[];
  documents: Array<Document & { botId: string; botName: string }>;
}

async function loadLibrary(): Promise<Library> {
  const bots = (await api.bots.list()).items;
  const pages = await Promise.all(bots.map((bot) => api.knowledge.listDocuments(bot.id, 0, 100)));

  const documents = pages.flatMap((page, index) =>
    page.items.map((document) => ({
      ...document,
      botId: bots[index].id,
      botName: bots[index].name,
    })),
  );

  documents.sort(
    (a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime(),
  );

  return { bots, documents };
}

export default function KnowledgePage() {
  const [tab, setTab] = useState<TabId>("documents");
  const [botFilter, setBotFilter] = useState<string | "all">("all");
  const { data, loading, error, reload } = useAsync(loadLibrary);

  const settling = (data?.documents ?? []).some((document) => isIndexing(document.status));
  usePolling(reload, 3000, settling);

  const documents =
    botFilter === "all"
      ? (data?.documents ?? [])
      : (data?.documents ?? []).filter((document) => document.botId === botFilter);

  return (
    <>
      <PageHeader
        title="Knowledge Base"
        description="Everything your bots are allowed to answer from."
        action={
          <Link
            href="/health"
            className="inline-flex items-center gap-2 rounded-xl border border-line bg-surface px-4 py-2 text-sm font-medium hover:border-clay/40"
          >
            <HeartPulse className="h-4 w-4 text-clay" />
            Check health
          </Link>
        }
      />

      <div className="mb-5 flex gap-1 overflow-x-auto border-b border-line">
        {TABS.map((entry) => (
          <button
            key={entry.id}
            onClick={() => setTab(entry.id)}
            className={cx(
              "-mb-px flex items-center gap-1.5 whitespace-nowrap border-b-2 px-3.5 py-2.5 text-sm transition-colors",
              tab === entry.id
                ? "border-clay font-medium text-ink"
                : "border-transparent text-muted hover:text-ink",
            )}
          >
            {entry.label}
            {!entry.available && <Badge tone="neutral">Not built</Badge>}
          </button>
        ))}
      </div>

      {tab !== "documents" && <Unsupported kind={tab} />}

      {tab === "documents" && (
      <Card>
        <CardHeader
          title="Documents"
          description="Upload from a bot's own Knowledge tab."
          action={
            data && data.bots.length > 1 ? (
              <Select
                value={botFilter}
                onChange={(event) => setBotFilter(event.target.value)}
                className="w-auto"
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

        {loading ? (
          <Loading />
        ) : error ? (
          <div className="p-5">
            <ErrorNote message={error} onRetry={reload} />
          </div>
        ) : documents.length === 0 ? (
          <Empty
            icon={<BookOpen className="h-7 w-7" />}
            title="No documents yet"
            description="Open a bot and upload a PDF, Word file, Markdown or plain text to get started."
          />
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-line text-left text-[12px] text-muted">
                  <th className="px-5 py-3 font-medium">File</th>
                  <th className="px-5 py-3 font-medium">Bot</th>
                  <th className="px-5 py-3 font-medium">Status</th>
                  <th className="px-5 py-3 font-medium">Chunks</th>
                  <th className="px-5 py-3 font-medium">Uploaded</th>
                </tr>
              </thead>
              <tbody>
                {documents.map((document) => (
                  <tr key={document.id} className="border-b border-line last:border-0">
                    <td className="px-5 py-3.5">
                      <div className="flex items-center gap-2.5">
                        <FileText className="h-4 w-4 shrink-0 text-faint" />
                        <div className="min-w-0">
                          <p className="truncate font-medium">{document.filename}</p>
                          <p className="text-[12px] text-muted">
                            {formatBytes(document.sizeBytes)}
                            {document.pageCount ? ` · ${document.pageCount} pages` : ""}
                          </p>
                        </div>
                      </div>
                    </td>
                    <td className="px-5 py-3.5">
                      <Link
                        href={`/bots/${document.botId}`}
                        className="text-muted hover:text-clay"
                      >
                        {document.botName}
                      </Link>
                    </td>
                    <td className="px-5 py-3.5">
                      <DocumentStatusBadge status={document.status} />
                      {document.status === "FAILED" && document.errorMessage && (
                        <p className="mt-1 max-w-xs text-[12px] text-bad">
                          {document.errorMessage}
                        </p>
                      )}
                    </td>
                    <td className="px-5 py-3.5 text-muted">{document.chunkCount ?? "—"}</td>
                    <td className="px-5 py-3.5 text-muted">
                      {formatRelative(document.createdAt)}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>
      )}
    </>
  );
}

/**
 * The tabs the design asks for that have no backend.
 *
 * Shown with the reason rather than hidden or, worse, wired to an upload form
 * that would accept a URL and silently drop it. The AI service ingests files;
 * crawling a site and storing question/answer pairs are each a real feature in
 * that service, which is frozen.
 */
function Unsupported({ kind }: { kind: "faqs" | "websites" }) {
  const copy = {
    faqs: {
      title: "FAQs are not built",
      body: "A question-and-answer pair is not a document, so it needs its own ingestion path in the AI service: no extraction or chunking, but its own embedding and retrieval handling. That service is frozen, so this tab has nothing behind it.",
    },
    websites: {
      title: "Website crawling is not built",
      body: "Crawling needs a fetcher, a robots.txt policy, a recrawl schedule and a way to handle pages that change or disappear. None of that exists, and a form that accepted a URL and did nothing would be worse than this message.",
    },
  }[kind];

  return (
    <Card>
      <div className="flex items-start gap-3 p-5">
        <BookOpen className="mt-0.5 h-5 w-5 shrink-0 text-faint" />
        <div>
          <p className="text-sm font-medium">{copy.title}</p>
          <p className="mt-1 max-w-2xl text-[13px] leading-relaxed text-muted">{copy.body}</p>
          <p className="mt-2 text-[13px] text-muted">
            Upload PDF, DOCX, TXT or Markdown on the Documents tab instead.
          </p>
        </div>
      </div>
    </Card>
  );
}
