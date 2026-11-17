"use client";

import { useState } from "react";
import Link from "next/link";
import { BookOpen, FileText, HeartPulse, Upload } from "lucide-react";
import { api, isIndexing, type Bot, type Document } from "@/lib/api";
import { DocumentStatusBadge } from "@/components/document-status";
import { useAsync, usePolling } from "@/lib/hooks";
import {
  Badge,
  buttonLink,
  Card,
  CardHeader,
  Empty,
  ErrorNote,
  Loading,
  PageHeader,
  Select,
  formatBytes,
  formatNumber,
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
        description={
          data
            ? `Everything your bots are allowed to answer from. ${formatNumber(data.documents.length)} document${
                data.documents.length === 1 ? "" : "s"
              }, ${formatNumber(data.documents.reduce((total, d) => total + (d.chunkCount ?? 0), 0))} passages.`
            : "Everything your bots are allowed to answer from."
        }
        action={
          <div className="flex flex-wrap gap-2.5">
            <Link
              href="/health"
              className="inline-flex min-h-11 items-center gap-2 rounded-xl border border-[#e2d4c5] bg-surface px-4 text-sm font-semibold hover:bg-cream"
            >
              <HeartPulse className="h-4 w-4 text-clay" />
              Check health
            </Link>
            {data && data.bots.length > 0 && (
              <Link
                // Uploads belong to a bot; with a filter picked, go straight to it.
                href={`/bots/${botFilter !== "all" ? botFilter : data.bots[0].id}?tab=knowledge`}
                className={buttonLink}
              >
                <Upload className="h-4 w-4" />
                Upload documents
              </Link>
            )}
          </div>
        }
      />

      <div className="mb-5 flex gap-1 overflow-x-auto border-b border-line">
        {TABS.map((entry) => (
          <button
            key={entry.id}
            onClick={() => setTab(entry.id)}
            className={cx(
              "-mb-px flex min-h-11 items-center gap-1.5 whitespace-nowrap border-b-2 px-4 text-[14px] transition-colors",
              tab === entry.id
                ? "border-clay font-semibold text-clay-strong"
                : "border-transparent font-medium text-[#5f5249] hover:text-ink",
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
            <table className="w-full text-[14px]">
              <thead>
                <tr className="border-b border-line text-left text-[12px] font-semibold text-muted">
                  <th className="px-5 py-3">Source</th>
                  <th className="px-5 py-3">Type</th>
                  <th className="px-5 py-3">Bot</th>
                  <th className="px-5 py-3">Passages</th>
                  <th className="px-5 py-3">Uploaded</th>
                  <th className="px-5 py-3">Status</th>
                </tr>
              </thead>
              <tbody>
                {documents.map((document) => (
                  <tr key={document.id} className="border-b border-[#f3ece4] last:border-0 hover:bg-cream/60">
                    <td className="px-5 py-3.5">
                      <div className="flex items-center gap-2.5">
                        <span className="grid h-8 w-8 shrink-0 place-items-center rounded-lg bg-clay-soft text-clay">
                          <FileText className="h-4 w-4" />
                        </span>
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
                      <Badge>{fileType(document.filename)}</Badge>
                    </td>
                    <td className="whitespace-nowrap px-5 py-3.5">
                      <Link
                        href={`/bots/${document.botId}?tab=knowledge`}
                        className="text-muted hover:text-clay"
                      >
                        {document.botName}
                      </Link>
                    </td>
                    <td className="px-5 py-3.5">{document.chunkCount ?? "—"}</td>
                    <td className="whitespace-nowrap px-5 py-3.5 text-muted">
                      {formatRelative(document.createdAt)}
                    </td>
                    <td className="px-5 py-3.5">
                      <DocumentStatusBadge status={document.status} />
                      {document.status === "FAILED" && document.errorMessage && (
                        <p className="mt-1 max-w-xs text-[12px] text-bad">
                          {document.errorMessage}
                        </p>
                      )}
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

/** "PDF", "DOCX", "MD" from the file name. */
function fileType(filename: string) {
  const dot = filename.lastIndexOf(".");
  return dot > 0 ? filename.slice(dot + 1).toUpperCase() : "FILE";
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
