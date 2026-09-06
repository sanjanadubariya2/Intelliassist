"use client";

import { useRef, useState } from "react";
import { FileText, Trash2, Upload } from "lucide-react";
import { ApiError, api, type Document, type DocumentStatus } from "@/lib/api";
import { useAsync, usePolling } from "@/lib/hooks";
import {
  Badge,
  Button,
  Card,
  CardHeader,
  Empty,
  ErrorNote,
  Loading,
  type Tone,
  formatBytes,
  formatRelative,
} from "@/components/ui";

/** What the Python service can actually extract. Anything else is refused. */
const ACCEPTED = ".pdf,.docx,.txt,.md";

const STATUS: Record<DocumentStatus, { label: string; tone: Tone }> = {
  PENDING: { label: "Queued", tone: "neutral" },
  PROCESSING: { label: "Indexing", tone: "warn" },
  INDEXED: { label: "Ready", tone: "ok" },
  FAILED: { label: "Failed", tone: "bad" },
};

export function BotKnowledge({ botId }: { botId: string }) {
  const { data, loading, error, reload } = useAsync(
    () => api.knowledge.listDocuments(botId),
    [botId],
  );

  const fileInput = useRef<HTMLInputElement>(null);
  const [uploading, setUploading] = useState(false);
  const [uploadError, setUploadError] = useState<string | null>(null);

  const documents = data?.items ?? [];

  // Indexing runs on a worker, so the only way to see a document finish is to
  // ask again. Polling stops as soon as nothing is in flight.
  const settling = documents.some(
    (document) => document.status === "PENDING" || document.status === "PROCESSING",
  );
  usePolling(reload, 3000, settling);

  async function upload(files: FileList | null) {
    if (!files?.length) return;
    setUploading(true);
    setUploadError(null);

    try {
      // Sequential on purpose: the backend creates the knowledge base lazily on
      // first upload, and parallel first uploads would race for it.
      for (const file of Array.from(files)) {
        await api.knowledge.upload(botId, file);
      }
      reload();
    } catch (caught) {
      setUploadError(
        caught instanceof ApiError
          ? caught.message
          : "Could not upload. Check the file type and size.",
      );
    } finally {
      setUploading(false);
      if (fileInput.current) fileInput.current.value = "";
    }
  }

  async function remove(document: Document) {
    await api.knowledge.removeDocument(document.id);
    reload();
  }

  return (
    <Card>
      <CardHeader
        title="Documents"
        description="What this bot is allowed to answer from."
        action={
          <>
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
          </>
        }
      />

      {uploadError && (
        <div className="px-5 pt-4">
          <ErrorNote message={uploadError} />
        </div>
      )}

      {loading ? (
        <Loading />
      ) : error ? (
        <div className="p-5">
          <ErrorNote message={error} onRetry={reload} />
        </div>
      ) : documents.length === 0 ? (
        <Empty
          icon={<FileText className="h-7 w-7" />}
          title="No documents yet"
          description="Upload a PDF, Word document, Markdown or plain text file. The bot answers only from what you give it."
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
                    <Badge tone={STATUS[document.status].tone}>
                      {STATUS[document.status].label}
                    </Badge>
                    {/* The API returns why it failed; hiding it would leave the
                        user with an unexplained red badge. */}
                    {document.status === "FAILED" && document.errorMessage && (
                      <p className="mt-1 max-w-xs text-[12px] text-bad">
                        {document.errorMessage}
                      </p>
                    )}
                  </td>
                  <td className="px-5 py-3.5 text-muted">{document.chunkCount ?? "—"}</td>
                  <td className="px-5 py-3.5 text-muted">{formatRelative(document.createdAt)}</td>
                  <td className="px-5 py-3.5 text-right">
                    <button
                      onClick={() => remove(document)}
                      aria-label={`Delete ${document.filename}`}
                      className="rounded-md p-1.5 text-faint transition-colors hover:bg-bad-soft hover:text-bad"
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
    </Card>
  );
}
