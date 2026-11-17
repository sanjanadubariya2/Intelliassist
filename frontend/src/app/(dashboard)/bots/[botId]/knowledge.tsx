"use client";

import { useRef, useState } from "react";
import Link from "next/link";
import { FileText, HeartPulse, Trash2, Upload } from "lucide-react";
import { ApiError, api, isIndexing, type Document } from "@/lib/api";
import { useAsync, usePolling } from "@/lib/hooks";
import { DocumentStatusBadge } from "@/components/document-status";
import {
  Button,
  Card,
  CardHeader,
  ConfirmModal,
  Empty,
  ErrorNote,
  Loading,
  cx,
  formatBytes,
  formatRelative,
  notify,
} from "@/components/ui";

/** What the Python service can actually extract. Anything else is refused. */
const ACCEPTED = ".pdf,.docx,.txt,.md";

export function BotKnowledge({ botId }: { botId: string }) {
  const { data, loading, error, reload } = useAsync(
    () => api.knowledge.listDocuments(botId, 0, 100),
    [botId],
  );

  const fileInput = useRef<HTMLInputElement>(null);
  const [uploading, setUploading] = useState(false);
  const [uploadError, setUploadError] = useState<string | null>(null);
  const [dragging, setDragging] = useState(false);
  const [removing, setRemoving] = useState<Document | null>(null);

  const documents = data?.items ?? [];

  // Indexing runs on a worker, so the only way to see a document finish is to
  // ask again. Polling stops as soon as nothing is in flight. Every
  // non-terminal status counts: checking only PENDING stopped polling as soon
  // as a document moved on to EXTRACTING.
  const settling = documents.some((document) => isIndexing(document.status));
  usePolling(reload, 3000, settling);

  async function upload(files: FileList | File[] | null) {
    const list = files ? Array.from(files) : [];
    if (!list.length) return;
    setUploading(true);
    setUploadError(null);

    let uploaded = 0;
    try {
      // Sequential on purpose: the backend creates the knowledge base lazily on
      // first upload, and parallel first uploads would race for it.
      for (const file of list) {
        await api.knowledge.upload(botId, file);
        uploaded++;
      }
      notify.success(
        uploaded === 1 ? `${list[0].name} uploaded. Indexing…` : `${uploaded} files uploaded. Indexing…`,
      );
    } catch (caught) {
      setUploadError(
        (caught instanceof ApiError ? caught.message : "Could not upload. Check the file type and size.") +
          (uploaded > 0 ? ` (${uploaded} of ${list.length} uploaded before this.)` : ""),
      );
    } finally {
      // Reload either way: a partial batch still added documents.
      reload();
      setUploading(false);
      if (fileInput.current) fileInput.current.value = "";
    }
  }

  return (
    <Card
      className={cx(dragging && "ring-2 ring-clay/40")}
    >
      <div
        onDragOver={(event) => {
          event.preventDefault();
          setDragging(true);
        }}
        onDragLeave={() => setDragging(false)}
        onDrop={(event) => {
          event.preventDefault();
          setDragging(false);
          upload(event.dataTransfer.files);
        }}
      >
        <CardHeader
          title="Documents"
          description="What this bot is allowed to answer from. Drop files here, or upload."
          action={
            <div className="flex gap-2">
              <Link
                href={`/health?bot=${botId}`}
                className="inline-flex items-center gap-1.5 rounded-xl border border-line px-3 py-2 text-[13px] font-medium hover:border-clay/40"
              >
                <HeartPulse className="h-4 w-4 text-clay" />
                Health
              </Link>
              <input
                ref={fileInput}
                type="file"
                accept={ACCEPTED}
                multiple
                hidden
                onChange={(event) => upload(event.target.files)}
              />
              <Button loading={uploading} onClick={() => fileInput.current?.click()}>
                <Upload className="h-4 w-4" />
                Upload
              </Button>
            </div>
          }
        />

        {uploadError && (
          <div className="px-5 pt-4">
            <ErrorNote message={uploadError} />
          </div>
        )}

        {/* The whole card accepts drops; this is the visible target for it. */}
        <div className="px-5 pt-5">
          <button
            type="button"
            onClick={() => fileInput.current?.click()}
            className={cx(
              "flex w-full flex-col items-center justify-center gap-2 rounded-2xl border-2 border-dashed px-6 py-8 text-center transition-colors",
              dragging ? "border-clay bg-clay-soft/50" : "border-[#d9c6b3] bg-cream hover:border-clay/50",
            )}
          >
            <span className="grid h-12 w-12 place-items-center rounded-full bg-accent-soft text-accent">
              <Upload className="h-5 w-5" />
            </span>
            <span className="text-[16px] font-semibold">
              {uploading ? "Uploading…" : "Drop files here or browse"}
            </span>
            <span className="text-[13px] text-muted">PDF, DOCX, TXT or Markdown</span>
          </button>
        </div>

        {loading ? (
          <Loading />
        ) : error ? (
          <div className="p-5">
            <ErrorNote message={error} onRetry={reload} />
          </div>
        ) : documents.length === 0 ? (
          <Empty
            icon={<FileText className="h-6 w-6" />}
            title="No documents yet"
            description="Drop a PDF, Word document, Markdown or plain text file here. The bot answers only from what you give it."
            action={<Button onClick={() => fileInput.current?.click()}>Upload a document</Button>}
          />
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-line text-left text-[12px] text-muted">
                  <th className="px-5 py-3 font-medium">File</th>
                  <th className="px-5 py-3 font-medium">Status</th>
                  <th className="px-5 py-3 font-medium">Chunks</th>
                  <th className="px-5 py-3 font-medium">Uploaded</th>
                  <th className="px-5 py-3" />
                </tr>
              </thead>
              <tbody>
                {documents.map((document) => (
                  <tr key={document.id} className="border-b border-line last:border-0 hover:bg-cream/60">
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
                      <DocumentStatusBadge status={document.status} />
                      {/* The API returns why it failed; hiding it would leave the
                          user with an unexplained red badge. */}
                      {document.status === "FAILED" && document.errorMessage && (
                        <p className="mt-1 max-w-xs text-[12px] text-bad">{document.errorMessage}</p>
                      )}
                    </td>
                    <td className="px-5 py-3.5 text-muted">{document.chunkCount ?? "—"}</td>
                    <td className="px-5 py-3.5 text-muted">{formatRelative(document.createdAt)}</td>
                    <td className="px-5 py-3.5 text-right">
                      <button
                        onClick={() => setRemoving(document)}
                        aria-label={`Delete ${document.filename}`}
                        className="rounded-lg p-1.5 text-faint transition-colors hover:bg-bad-soft hover:text-bad"
                      >
                        <Trash2 className="h-4 w-4" />
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

      <ConfirmModal
        open={removing !== null}
        title="Delete document"
        confirmLabel="Delete"
        body={
          <>
            Remove <strong className="text-ink">{removing?.filename}</strong>? The bot stops
            using it straight away.
          </>
        }
        onClose={() => setRemoving(null)}
        onConfirm={async () => {
          if (!removing) return;
          try {
            await api.knowledge.removeDocument(removing.id);
            notify.success(`Deleted ${removing.filename}`);
            reload();
          } catch (caught) {
            notify.failure(caught, "Could not delete the document.");
          }
        }}
      />
    </Card>
  );
}
