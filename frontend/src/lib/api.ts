/**
 * The dashboard's only door to the backend.
 *
 * Every type here mirrors a Java record in `com.intelliassist.backend.dto`. If
 * a field is not in that package it is not in this file — a UI built on fields
 * the API does not return looks finished and works for nobody.
 *
 * The browser talks to Spring Boot and nothing else. The Python AI service sits
 * on a private network with no route from here, which is why there is no
 * `AI_URL` to configure.
 */

const BASE_URL = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";

// ------------------------------------------------------------------ types --

export type MemberRole = "OWNER" | "ADMIN" | "MEMBER";
export type BotStatus = "ACTIVE" | "INACTIVE";
export type ConversationStatus = "ACTIVE" | "CLOSED";
/** Whether the visitor got what they came for. Independent of status. */
export type ConversationResolution = "UNRESOLVED" | "RESOLVED" | "ESCALATED";
export type WidgetPosition = "BOTTOM_RIGHT" | "BOTTOM_LEFT" | "TOP_RIGHT" | "TOP_LEFT";
export type LauncherIcon = "CHAT" | "MESSAGE" | "HELP" | "SPARKLE";
/**
 * Mirrors the backend's DocumentStatus exactly. The three middle stages are
 * reported while the AI service works through a file; the UI must handle all
 * of them, because an unknown status used to crash the knowledge screens.
 */
export type DocumentStatus =
  | "PENDING"
  | "EXTRACTING"
  | "CHUNKING"
  | "EMBEDDING"
  | "INDEXED"
  | "FAILED";

/** Still moving: worth polling. */
export function isIndexing(status: DocumentStatus) {
  return status !== "INDEXED" && status !== "FAILED";
}
export type MessageRole = "USER" | "ASSISTANT";

export interface AuthResponse {
  token: string;
  expiresAt: string;
  role: MemberRole;
}

/** Mirrors OAuthController.ProviderSummary. */
export type SsoProviderId = "google" | "github" | "linkedin";

export interface SsoProvider {
  id: SsoProviderId;
  name: string;
  /** False when the deployment has no client id and secret for it. */
  enabled: boolean;
}

/**
 * Where a provider button sends the browser. A full navigation, not a fetch:
 * the backend answers with a redirect to the provider's own sign-in page.
 */
export function ssoStartUrl(provider: SsoProviderId, returnTo?: string) {
  // Tells the backend where to come back to: this dashboard, on whatever port
  // it happens to be running. The backend accepts it only if it is an allowed
  // origin, and otherwise falls back to OAUTH_FRONTEND_URL.
  const query = returnTo ? `?return_to=${encodeURIComponent(returnTo)}` : "";
  return `${BASE_URL}/api/v1/auth/oauth/${provider}/start${query}`;
}

/** What each `sso_error` code from the backend means, in words. */
export const SSO_ERRORS: Record<string, string> = {
  access_denied: "Sign-in was cancelled.",
  provider_not_configured: "That sign-in option isn't set up on this server yet.",
  unknown_provider: "That sign-in option doesn't exist.",
  invalid_state: "The sign-in link expired or was opened in another browser. Please try again.",
  no_verified_email: "The provider didn't share a verified email address, so we can't sign you in with it.",
  account_exists:
    "An account with this email already exists. Sign in with your email and password instead.",
  no_organization: "Your account isn't part of any workspace.",
  provider_error: "We couldn't reach the sign-in provider. Please try again.",
};

export interface UserSummary {
  id: string;
  name: string;
  email: string;
  createdAt: string;
}

export interface OrganizationSummary {
  id: string;
  name: string;
  slug: string;
  createdAt: string;
}

export interface CurrentUser {
  user: UserSummary;
  organization: OrganizationSummary;
  role: MemberRole;
}

export interface Bot {
  id: string;
  organizationId: string;
  name: string;
  description: string | null;
  status: BotStatus;
  defaultLanguage: string;
  systemPrompt: string | null;
  primaryColor: string;
  widgetPosition: WidgetPosition;
  welcomeMessage: string;
  fallbackMessage: string;
  launcherIcon: LauncherIcon;
  createdAt: string;
  updatedAt: string;
}

export interface KnowledgeBase {
  id: string;
  botId: string;
  name: string;
  documentCount: number;
  createdAt: string;
}

export interface Document {
  id: string;
  knowledgeBaseId: string;
  filename: string;
  contentType: string;
  sizeBytes: number;
  status: DocumentStatus;
  queryable: boolean;
  errorMessage: string | null;
  chunkCount: number | null;
  pageCount: number | null;
  createdAt: string;
  updatedAt: string;
}

export interface Conversation {
  id: string;
  botId: string;
  botName: string;
  visitorId: string | null;
  title: string | null;
  status: ConversationStatus;
  resolution: ConversationResolution;
  messageCount: number;
  startedAt: string;
  lastMessageAt: string | null;
  /** The teammate handling a handed-off conversation. Null: the shared queue. */
  assigneeId: string | null;
  assigneeName: string | null;
  /** First escalation. Never cleared, so resolved handoffs stay countable. */
  escalatedAt: string | null;
  resolvedAt: string | null;
}

export type InboxView = "OPEN" | "MINE" | "UNASSIGNED" | "CLOSED";

export interface InboxSummary {
  open: number;
  mine: number;
  unassigned: number;
  resolvedLast30Days: number;
  /** Null when nothing was resolved in the window. */
  medianResolutionMinutes: number | null;
}

export interface QuestionCluster {
  question: string;
  count: number;
  unansweredCount: number;
  lastAskedAt: string;
}

export interface ResolutionDay {
  date: string;
  started: number;
  resolved: number;
  escalated: number;
  open: number;
}

export interface ConversationInsights {
  from: string;
  to: string;
  totalQuestions: number;
  answeredQuestions: number;
  unansweredQuestions: number;
  answerRate: number | null;
  totalConversations: number;
  resolvedConversations: number;
  escalatedConversations: number;
  openConversations: number;
  /** resolved / (resolved + escalated). A proxy: there is no visitor rating. */
  satisfactionRate: number | null;
  topQuestions: QuestionCluster[];
  unansweredTopics: QuestionCluster[];
  trend: ResolutionDay[];
  truncated: boolean;
}

export type HealthIssueType =
  | "EMPTY_KNOWLEDGE_BASE"
  | "FAILED"
  | "STUCK"
  | "NO_CONTENT"
  | "CONFLICTING_VERSIONS"
  | "DUPLICATE"
  | "OUTDATED";

export interface HealthIssue {
  type: HealthIssueType;
  severity: "HIGH" | "MEDIUM" | "LOW";
  title: string;
  detail: string;
  recommendation: string;
  documentIds: string[];
  filenames: string[];
}

export interface KnowledgeHealth {
  score: number;
  documentCount: number;
  indexedCount: number;
  totalChunks: number;
  issues: HealthIssue[];
  suggestedFaqs: Array<{ question: string; timesAsked: number; lastAskedAt: string }>;
  checkedAt: string;
}

export interface Citation {
  marker: number;
  documentId: string;
  filename: string;
  pageNumber: number | null;
  snippet: string;
  score: number;
}

export interface Message {
  id: string;
  conversationId: string;
  role: MessageRole;
  content: string;
  citations: Citation[];
  grounded: boolean | null;
  model: string | null;
  latencyMs: number | null;
  createdAt: string;
}

export interface UsageDay {
  date: string;
  conversations: number;
  /** Counts both sides of an exchange, so it is always twice `answers`. */
  messages: number;
  answers: number;
  groundedAnswers: number;
  ungroundedAnswers: number;
  tokens: number;
}

export interface BotUsage {
  from: string;
  to: string;
  days: UsageDay[];
  totalConversations: number;
  totalMessages: number;
  totalAnswers: number;
  groundedAnswers: number;
  ungroundedAnswers: number;
  totalTokens: number;
  /** Null when the bot answered nothing in the range. Not zero. */
  averageResponseMs: number | null;
}


/**
 * A stored RAG evaluation run.
 *
 * Metric values are `number | null`, and the distinction matters: null means
 * the dataset stated no expectation for that metric, which is not the same as
 * scoring zero. Render null as "n/a".
 */
export interface RagEvaluation {
  id: string;
  botId: string;
  dataset: string;
  model: string;
  startedAt: string;
  finishedAt: string;
  totalQuestions: number;
  successful: number;
  failed: number;
  unmetExpectations: number;
  metrics: Partial<Record<EvaluationMetric, number | null>>;
  latency: Partial<Record<"mean_ms" | "p50_ms" | "p95_ms" | "max_ms", number>>;
  cases: EvaluationCase[];
}

export type EvaluationMetric =
  | "hit_at_k"
  | "mrr"
  | "source_recall"
  | "source_precision"
  | "answer_token_f1"
  | "groundedness"
  | "answer_rate"
  | "abstention_accuracy";

export interface EvaluationCase {
  id: string;
  question: string;
  ok: boolean;
  error?: string | null;
  answer?: string;
  abstained?: boolean;
  expected_sources?: string[];
  cited_sources?: string[];
  hit_at_k?: number | null;
  latency_ms?: number;
}

export interface Member {
  userId: string;
  name: string;
  email: string;
  role: MemberRole;
  joinedAt: string;
}

export interface ConversationNote {
  id: string;
  authorId: string | null;
  authorName: string;
  body: string;
  createdAt: string;
}

export interface ApiKey {
  id: string;
  botId: string;
  name: string;
  keyPrefix: string;
  active: boolean;
  lastUsedAt: string | null;
  revokedAt: string | null;
  createdAt: string;
}

export interface CreatedApiKey {
  key: ApiKey;
  /** Returned exactly once. Only a hash is stored, so it cannot be shown again. */
  rawKey: string;
  warning: string;
}

export interface AllowedDomain {
  id: string;
  botId: string;
  domain: string;
  createdAt: string;
}

export interface Page<T> {
  items: T[];
  page: number;
  size: number;
  totalItems: number;
  totalPages: number;
  hasNext: boolean;
}

// ------------------------------------------------------------------ token --

/**
 * The JWT lives in localStorage.
 *
 * An httpOnly cookie would resist XSS better, but it needs the API to set
 * cookies cross-origin with credentials, and the backend deliberately does not
 * enable that. This is the honest trade for a token-based API; the mitigation
 * that matters is not rendering untrusted HTML, which nothing here does.
 */
const TOKEN_KEY = "intelliassist.token";

export const tokenStore = {
  get(): string | null {
    if (typeof window === "undefined") return null;
    try {
      return localStorage.getItem(TOKEN_KEY);
    } catch {
      return null;
    }
  },
  set(token: string) {
    try {
      localStorage.setItem(TOKEN_KEY, token);
    } catch {
      /* private mode: the session simply will not survive a reload */
    }
  },
  clear() {
    try {
      localStorage.removeItem(TOKEN_KEY);
    } catch {
      /* nothing to do */
    }
  },
};

// ------------------------------------------------------------------ error --

export class ApiError extends Error {
  constructor(
    readonly status: number,
    message: string,
    readonly fieldErrors?: Record<string, string>,
  ) {
    super(message);
    this.name = "ApiError";
  }

  /** The session is gone or was never valid. */
  get isUnauthorized() {
    return this.status === 401;
  }

  /** The request never reached the server at all. */
  get isUnreachable() {
    return this.status === 0;
  }
}

/**
 * Turns a failed response into an ApiError.
 *
 * The backend returns a ProblemDetail-shaped body and deliberately omits stack
 * traces and internal detail, so whatever `detail` says is safe to show.
 */
async function toError(response: Response): Promise<ApiError> {
  let message = `Request failed (${response.status})`;
  let fieldErrors: Record<string, string> | undefined;

  try {
    const body = await response.json();
    message = body.detail ?? body.message ?? body.title ?? message;
    if (body.errors && typeof body.errors === "object") {
      fieldErrors = body.errors;
    }
  } catch {
    // A non-JSON body (a proxy error page, say). The status alone is the
    // most honest thing we can report.
  }

  return new ApiError(response.status, message, fieldErrors);
}

// ------------------------------------------------------------------ fetch --

interface RequestOptions {
  method?: string;
  body?: unknown;
  /** Multipart uploads set their own content type via the boundary. */
  formData?: FormData;
  signal?: AbortSignal;
}

/**
 * Explains a request that never got a response.
 *
 * fetch rejects without saying why: connection refused, DNS failure and a CORS
 * rejection all look identical. The old message always blamed a stopped
 * backend, which was wrong in the commonest local case: the dashboard on port
 * 3001 (Next.js moves there when 3000 is busy), refused by the API's CORS
 * policy while the API was perfectly healthy.
 *
 * So ask once more in `no-cors` mode, which CORS cannot block. If that
 * resolves, the server is up and the browser refused to share its answer with
 * this page: an origin problem, named as such, with the origin to allow.
 */
async function unreachable(): Promise<ApiError> {
  let serverUp = false;
  try {
    await fetch(`${BASE_URL}/api/v1/health`, { mode: "no-cors", cache: "no-store" });
    serverUp = true;
  } catch {
    serverUp = false;
  }

  if (serverUp && typeof window !== "undefined") {
    return new ApiError(
      0,
      `The API at ${BASE_URL} is running but refused requests from ${window.location.origin}. ` +
        `Add ${window.location.origin} to CORS_ALLOWED_ORIGINS in .env and restart the backend ` +
        "(docker compose --profile app up -d backend).",
    );
  }
  return new ApiError(
    0,
    `Cannot reach the API at ${BASE_URL}. Is the backend running? ` +
      "Start it with: docker compose --profile app up -d",
  );
}

async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const token = tokenStore.get();
  const headers: Record<string, string> = {};

  if (token) headers.Authorization = `Bearer ${token}`;
  if (options.body !== undefined) headers["Content-Type"] = "application/json";

  let response: Response;
  try {
    response = await fetch(`${BASE_URL}/api/v1${path}`, {
      method: options.method ?? (options.body || options.formData ? "POST" : "GET"),
      headers,
      body: options.formData ?? (options.body !== undefined ? JSON.stringify(options.body) : undefined),
      signal: options.signal,
    });
  } catch (cause) {
    if (cause instanceof DOMException && cause.name === "AbortError") throw cause;
    throw await unreachable();
  }

  if (!response.ok) {
    const error = await toError(response);
    // A token that was valid at sign-in has expired (they last 12 hours).
    // Every screen would otherwise show a bare 401 with no way forward, so the
    // session is ended and the user sent to sign in again. Not for the auth
    // calls themselves, whose 401 means "wrong password".
    if (error.isUnauthorized && token && !path.startsWith("/auth/") && typeof window !== "undefined") {
      tokenStore.clear();
      window.location.assign("/login?expired=1");
    }
    throw error;
  }

  // 204, and any other empty success.
  if (response.status === 204 || response.headers.get("Content-Length") === "0") {
    return undefined as T;
  }
  return (await response.json()) as T;
}

// -------------------------------------------------------------------- api --

export const api = {
  auth: {
    register: (body: {
      name: string;
      email: string;
      password: string;
      organizationName: string;
    }) => request<AuthResponse>("/auth/register", { body }),

    login: (body: { email: string; password: string }) =>
      request<AuthResponse>("/auth/login", { body }),

    me: () => request<CurrentUser>("/auth/me"),

    /** Which single sign-on providers this deployment has configured. */
    providers: () => request<{ providers: SsoProvider[] }>("/auth/oauth/providers"),
  },

  bots: {
    list: (page = 0, size = 50) =>
      request<Page<Bot>>(`/bots?page=${page}&size=${size}`),
    get: (botId: string) => request<Bot>(`/bots/${botId}`),

    create: (body: {
      name: string;
      description?: string;
      defaultLanguage?: string;
      systemPrompt?: string;
    }) => request<Bot>("/bots", { body }),

    update: (
      botId: string,
      body: {
        name?: string;
        description?: string;
        status?: BotStatus;
        defaultLanguage?: string;
        systemPrompt?: string;
      },
    ) => request<Bot>(`/bots/${botId}`, { method: "PUT", body }),

    remove: (botId: string) => request<void>(`/bots/${botId}`, { method: "DELETE" }),

    /**
     * The Customization screen's save.
     *
     * PATCH, and every field optional: the screen saves one section at a time,
     * and an omitted field must be left alone rather than blanked.
     */
    updateAppearance: (
      botId: string,
      body: {
        primaryColor?: string;
        widgetPosition?: WidgetPosition;
        welcomeMessage?: string;
        fallbackMessage?: string;
        launcherIcon?: LauncherIcon;
      },
    ) => request<Bot>(`/bots/${botId}/appearance`, { method: "PATCH", body }),
  },

  knowledge: {
    /** A bot has exactly one knowledge base, so this returns it rather than a list. */
    getBase: (botId: string) => request<KnowledgeBase>(`/bots/${botId}/knowledge`),

    createBase: (botId: string, body: { name: string }) =>
      request<KnowledgeBase>(`/bots/${botId}/knowledge`, { body }),

    listDocuments: (botId: string, page = 0, size = 50) =>
      request<Page<Document>>(`/bots/${botId}/documents?page=${page}&size=${size}`),

    /**
     * Accepted with 202: indexing happens on a worker, so the document arrives
     * PENDING and the caller polls until it is INDEXED or FAILED.
     */
    upload: (botId: string, file: File) => {
      const formData = new FormData();
      formData.append("file", file);
      return request<Document>(`/bots/${botId}/documents`, { formData });
    },

    getDocument: (documentId: string) => request<Document>(`/documents/${documentId}`),

    removeDocument: (documentId: string) =>
      request<void>(`/documents/${documentId}`, { method: "DELETE" }),
  },

  conversations: {
    list: (botId: string, page = 0, size = 20) =>
      request<Page<Conversation>>(`/bots/${botId}/conversations?page=${page}&size=${size}`),

    get: (conversationId: string) => request<Conversation>(`/conversations/${conversationId}`),

    messages: (conversationId: string) =>
      request<Message[]>(`/conversations/${conversationId}/messages`),

    start: (botId: string, body: { visitorId?: string } = {}) =>
      request<Conversation>(`/bots/${botId}/conversations`, { body }),

    send: (conversationId: string, content: string) =>
      request<{ userMessage: Message; assistantMessage: Message }>(
        `/conversations/${conversationId}/messages`,
        { body: { content } },
      ),

    close: (conversationId: string) =>
      request<Conversation>(`/conversations/${conversationId}/close`, { method: "POST" }),

    /** Records how it turned out. Does not close the conversation. */
    resolve: (conversationId: string, resolution: ConversationResolution) =>
      request<Conversation>(`/conversations/${conversationId}/resolution`, {
        method: "PATCH",
        body: { resolution },
      }),

    notes: (conversationId: string) =>
      request<ConversationNote[]>(`/conversations/${conversationId}/notes`),

    addNote: (conversationId: string, body: string) =>
      request<ConversationNote>(`/conversations/${conversationId}/notes`, { body: { body } }),
  },

  handoff: {
    list: (view: InboxView, page = 0, size = 50) =>
      request<Page<Conversation>>(`/handoff/conversations?view=${view}&page=${page}&size=${size}`),

    summary: () => request<InboxSummary>("/handoff/summary"),

    /** Null returns it to the shared queue. Assigning an open conversation escalates it. */
    assign: (conversationId: string, assigneeId: string | null) =>
      request<Conversation>(`/conversations/${conversationId}/assignee`, {
        method: "PATCH",
        body: { assigneeId },
      }),
  },

  insights: {
    forBot: (botId: string, from?: string, to?: string) => {
      const query = new URLSearchParams();
      if (from) query.set("from", from);
      if (to) query.set("to", to);
      const suffix = query.toString() ? `?${query}` : "";
      return request<ConversationInsights>(`/bots/${botId}/insights${suffix}`);
    },

    health: (botId: string) => request<KnowledgeHealth>(`/bots/${botId}/knowledge/health`),
  },

  members: {
    /** Always the caller's own organization; there is no id to pass. */
    list: () => request<Member[]>("/members"),
  },

  evaluation: {
    /**
     * The latest run, or null when the bot has never been evaluated.
     *
     * The API answers 204 in that case, which `request` returns as undefined.
     */
    latest: (botId: string) =>
      request<RagEvaluation | undefined>(`/bots/${botId}/analytics/rag-evaluation`).then(
        (result) => result ?? null,
      ),
  },

  usage: {
    forBot: (botId: string, from?: string, to?: string) => {
      const query = new URLSearchParams();
      if (from) query.set("from", from);
      if (to) query.set("to", to);
      const suffix = query.toString() ? `?${query}` : "";
      return request<BotUsage>(`/bots/${botId}/usage${suffix}`);
    },
  },

  credentials: {
    listKeys: (botId: string) => request<ApiKey[]>(`/bots/${botId}/api-keys`),

    createKey: (botId: string, body: { name: string }) =>
      request<CreatedApiKey>(`/bots/${botId}/api-keys`, { body }),

    revokeKey: (keyId: string) => request<void>(`/api-keys/${keyId}`, { method: "DELETE" }),

    listDomains: (botId: string) => request<AllowedDomain[]>(`/bots/${botId}/domains`),

    addDomain: (botId: string, body: { domain: string }) =>
      request<AllowedDomain>(`/bots/${botId}/domains`, { body }),

    removeDomain: (domainId: string) =>
      request<void>(`/domains/${domainId}`, { method: "DELETE" }),
  },
};

/**
 * Streams an answer in the dashboard's own test chat.
 *
 * `EventSource` is not usable: it cannot POST and cannot set an Authorization
 * header, so the SSE frames are parsed out of the fetch body by hand. The frame
 * format matches `StreamingChatService.send`.
 */
export async function streamMessage(
  conversationId: string,
  content: string,
  handlers: {
    onToken: (text: string) => void;
    onCitations: (citations: Citation[]) => void;
    onError: (message: string) => void;
    onDone: () => void;
  },
  signal?: AbortSignal,
): Promise<void> {
  let response: Response;
  try {
    response = await fetch(`${BASE_URL}/api/v1/conversations/${conversationId}/messages/stream`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Authorization: `Bearer ${tokenStore.get() ?? ""}`,
      },
      body: JSON.stringify({ content }),
      signal,
    });
  } catch (cause) {
    // Without this the chat showed the browser's bare "Failed to fetch".
    if (cause instanceof DOMException && cause.name === "AbortError") throw cause;
    throw await unreachable();
  }

  if (!response.ok) throw await toError(response);
  if (!response.body) throw new ApiError(500, "The server sent no response body");

  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";

  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;

    buffer += decoder.decode(value, { stream: true });

    // Frames are separated by a blank line. A partial frame stays in the
    // buffer until the rest of it arrives.
    const frames = buffer.split("\n\n");
    buffer = frames.pop() ?? "";

    for (const frame of frames) {
      let name: string | null = null;
      let data: unknown = null;

      for (const line of frame.split("\n")) {
        if (line.startsWith("event: ")) name = line.slice(7).trim();
        else if (line.startsWith("data: ")) {
          try {
            data = JSON.parse(line.slice(6));
          } catch {
            data = null;
          }
        }
      }

      if (!name || data === null) continue;
      const payload = data as Record<string, unknown>;

      if (name === "token") handlers.onToken((payload.text as string) ?? "");
      else if (name === "citations")
        handlers.onCitations((payload.citations as Citation[]) ?? []);
      else if (name === "error")
        handlers.onError((payload.message as string) ?? "The assistant could not answer.");
      else if (name === "done") handlers.onDone();
    }
  }
}
