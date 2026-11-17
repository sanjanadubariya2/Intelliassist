"use client";

/**
 * The small set of pieces every screen is built from.
 *
 * Deliberately not a component library: a handful of typed wrappers keeps the
 * spacing and colour decisions in one file, which is the actual reason design
 * drifts. Anything used once lives with the screen that uses it.
 */

import { useEffect, useState, useSyncExternalStore } from "react";
import { AlertCircle, CheckCircle2, Loader2, X } from "lucide-react";

export function cx(...values: Array<string | false | null | undefined>) {
  return values.filter(Boolean).join(" ");
}

// ------------------------------------------------------------------- brand --

/**
 * The mark: Inti's speech bubble with two eyes cut out, so it reads on any
 * background in any colour. The full character lives in components/inti.tsx.
 */
export function LogoMark({ className }: { className?: string }) {
  return (
    <svg viewBox="0 0 32 32" aria-hidden className={cx("h-7 w-7", className)}>
      <path
        fillRule="evenodd"
        fill="currentColor"
        d="M8 3h16a7 7 0 0 1 7 7v8a7 7 0 0 1-7 7h-6l-5 5v-5H8a7 7 0 0 1-7-7v-8a7 7 0 0 1 7-7z M8.8 14a2.2 2.2 0 1 0 4.4 0a2.2 2.2 0 1 0 -4.4 0z M18.8 14a2.2 2.2 0 1 0 4.4 0a2.2 2.2 0 1 0 -4.4 0z"
      />
    </svg>
  );
}

export function Logo({ className }: { className?: string }) {
  return (
    <span className={cx("flex items-center gap-2", className)}>
      <LogoMark className="text-accent" />
      <span className="font-display text-[19px] font-semibold tracking-tight text-ink">
        IntelliAssist
      </span>
    </span>
  );
}

// ------------------------------------------------------------- page header --

/** The title block at the top of every screen. Serif, like the marketing site. */
export function PageHeader({
  title,
  description,
  eyebrow,
  action,
}: {
  title: React.ReactNode;
  description?: React.ReactNode;
  eyebrow?: React.ReactNode;
  action?: React.ReactNode;
}) {
  return (
    <div className="mb-6 flex flex-wrap items-end justify-between gap-4">
      <div className="min-w-0">
        {eyebrow && (
          <p className="mb-2 flex items-center gap-1.5 text-[12px] font-semibold uppercase tracking-[0.06em] text-muted">
            {eyebrow}
          </p>
        )}
        <h1 className="font-display text-[30px] font-semibold leading-tight tracking-tight text-ink sm:text-[34px]">
          {title}
        </h1>
        {description && <p className="mt-1.5 max-w-2xl text-[15px] text-muted">{description}</p>}
      </div>
      {action}
    </div>
  );
}

// ------------------------------------------------------------------- card --

export function Card({
  className,
  children,
}: {
  className?: string;
  children: React.ReactNode;
}) {
  return (
    <div className={cx("rounded-2xl border border-line bg-surface shadow-soft", className)}>
      {children}
    </div>
  );
}

export function CardHeader({
  title,
  action,
  description,
}: {
  title: React.ReactNode;
  description?: React.ReactNode;
  action?: React.ReactNode;
}) {
  return (
    <div className="flex flex-wrap items-start justify-between gap-4 border-b border-line px-5 py-4">
      <div className="min-w-0">
        <h2 className="text-[15px] font-semibold text-ink">{title}</h2>
        {description && <p className="mt-0.5 text-[13px] text-muted">{description}</p>}
      </div>
      {action}
    </div>
  );
}

// ----------------------------------------------------------------- button --

type ButtonProps = React.ButtonHTMLAttributes<HTMLButtonElement> & {
  variant?: "primary" | "secondary" | "ghost" | "danger" | "clay";
  size?: "sm" | "md";
  loading?: boolean;
};

const VARIANTS: Record<NonNullable<ButtonProps["variant"]>, string> = {
  primary: "bg-accent text-white shadow-sm hover:bg-accent-strong",
  clay: "bg-clay text-white shadow-sm hover:bg-clay-strong",
  secondary: "border border-line bg-surface text-ink hover:border-clay/40 hover:bg-cream",
  ghost: "text-muted hover:bg-cream hover:text-ink",
  danger: "border border-bad/30 bg-bad-soft text-bad hover:bg-bad hover:text-white",
};

export function Button({
  variant = "primary",
  size = "md",
  loading = false,
  disabled,
  className,
  children,
  ...props
}: ButtonProps) {
  return (
    <button
      type="button"
      {...props}
      // A loading button must not fire twice; disabling it is the guard.
      disabled={disabled || loading}
      className={cx(
        "inline-flex items-center justify-center gap-2 rounded-xl font-semibold transition-colors",
        "disabled:cursor-not-allowed disabled:opacity-50",
        // 44px is the smallest comfortable touch target; `sm` is for dense
        // rows (tables, toolbars) where a full-height button would crowd.
        size === "sm" ? "min-h-9 px-3 text-[13px]" : "min-h-11 px-4 text-sm",
        VARIANTS[variant],
        className,
      )}
    >
      {loading && <Loader2 className="h-3.5 w-3.5 animate-spin" />}
      {children}
    </button>
  );
}

/** Classes for a Link that should look like the primary button. */
export const buttonLink =
  "inline-flex min-h-11 items-center justify-center gap-2 rounded-xl bg-accent px-4 text-sm font-semibold text-white shadow-sm transition-colors hover:bg-accent-strong";

// ------------------------------------------------------------------ input --

const FIELD =
  "w-full min-h-11 rounded-xl border border-[#e2d4c5] bg-surface px-3.5 py-2 text-sm text-ink " +
  "placeholder:text-faint focus:border-clay/60 focus:outline-none focus:ring-2 focus:ring-clay/15";

export function Field({
  label,
  hint,
  error,
  children,
}: {
  label: string;
  hint?: string;
  error?: string;
  children: React.ReactNode;
}) {
  return (
    <label className="block">
      <span className="mb-1.5 block text-[13px] font-medium text-ink">{label}</span>
      {children}
      {error ? (
        <span className="mt-1 block text-[12px] text-bad">{error}</span>
      ) : hint ? (
        <span className="mt-1 block text-[12px] text-muted">{hint}</span>
      ) : null}
    </label>
  );
}

/**
 * The field classes, minus `w-full` when the caller sets its own width.
 * Both classes in one attribute leave the winner to stylesheet order, and
 * `w-full` won: a "w-auto" date-range picker stretched across its card.
 */
function field(className?: string) {
  const ownWidth = /(^|\s)w-/.test(className ?? "");
  return cx(ownWidth ? FIELD.replace("w-full ", "") : FIELD, className);
}

export function Input(props: React.InputHTMLAttributes<HTMLInputElement>) {
  return <input {...props} className={field(props.className)} />;
}

export function Textarea(props: React.TextareaHTMLAttributes<HTMLTextAreaElement>) {
  return <textarea {...props} className={cx(field(props.className), "resize-y")} />;
}

export function Select(props: React.SelectHTMLAttributes<HTMLSelectElement>) {
  return <select {...props} className={cx(field(props.className), "cursor-pointer")} />;
}

// ------------------------------------------------------------------ tabs --

/** Underlined tabs. Buttons, not links: the selection is local state. */
export function Tabs<T extends string>({
  tabs,
  value,
  onChange,
}: {
  tabs: ReadonlyArray<{ id: T; label: React.ReactNode }>;
  value: T;
  onChange: (id: T) => void;
}) {
  return (
    <div className="mb-5 flex gap-1 overflow-x-auto overflow-y-hidden border-b border-line" role="tablist">
      {tabs.map((tab) => (
        <button
          key={tab.id}
          type="button"
          role="tab"
          aria-selected={value === tab.id}
          onClick={() => onChange(tab.id)}
          className={cx(
            "-mb-px flex min-h-11 items-center gap-1.5 whitespace-nowrap border-b-2 px-4 text-[14px] transition-colors",
            value === tab.id
              ? "border-clay font-semibold text-clay-strong"
              : "border-transparent font-medium text-[#5f5249] hover:text-ink",
          )}
        >
          {tab.label}
        </button>
      ))}
    </div>
  );
}

// ------------------------------------------------------------------ badge --

export type Tone = "ok" | "warn" | "bad" | "neutral" | "accent" | "clay";

const TONES: Record<Tone, string> = {
  ok: "bg-ok-soft text-ok",
  warn: "bg-warn-soft text-warn",
  bad: "bg-bad-soft text-bad",
  accent: "bg-accent-soft text-accent",
  clay: "bg-clay-soft text-clay",
  neutral: "bg-cream text-muted ring-1 ring-inset ring-line",
};

export function Badge({ tone = "neutral", children }: { tone?: Tone; children: React.ReactNode }) {
  return (
    <span
      className={cx(
        "inline-flex items-center gap-1 whitespace-nowrap rounded-full px-2.5 py-0.5 text-[12px] font-semibold",
        TONES[tone],
      )}
    >
      {children}
    </span>
  );
}

/**
 * A headline number with its label.
 *
 * Shared because the overview and analytics screens must agree on how a metric
 * looks; two near-identical local copies is how the two drift apart.
 */
export function StatCard({
  label,
  value,
  hint,
  icon,
}: {
  label: string;
  value: string;
  hint?: string;
  icon: React.ReactNode;
}) {
  return (
    <Card className="p-5">
      <div className="flex items-center justify-between">
        <span className="text-[13px] text-muted">{label}</span>
        <span className="grid h-8 w-8 place-items-center rounded-xl bg-clay-soft text-clay">
          {icon}
        </span>
      </div>
      <p className="mt-2 font-display text-[32px] font-semibold leading-tight tracking-tight">
        {value}
      </p>
      {/* `muted`, not `faint`: faint fails contrast on white at this size. */}
      {hint && <p className="mt-0.5 text-[13px] text-muted">{hint}</p>}
    </Card>
  );
}

/**
 * How a conversation turned out.
 *
 * Separate from status: a conversation can still be open and already known to
 * be escalated. Escalated is red because it is the one that needs a person.
 */
export function ResolutionBadge({
  resolution,
}: {
  resolution: "UNRESOLVED" | "RESOLVED" | "ESCALATED";
}) {
  const map = {
    RESOLVED: { tone: "ok" as Tone, label: "Resolved" },
    ESCALATED: { tone: "bad" as Tone, label: "Escalated" },
    UNRESOLVED: { tone: "neutral" as Tone, label: "Open" },
  };
  const { tone, label } = map[resolution];
  return <Badge tone={tone}>{label}</Badge>;
}

/** A person's initials in a round chip. */
export function Avatar({ name, className }: { name: string; className?: string }) {
  return (
    <span
      className={cx(
        "grid h-8 w-8 shrink-0 place-items-center rounded-full bg-clay-soft text-[12px] font-semibold text-clay",
        className,
      )}
      aria-hidden
    >
      {initials(name)}
    </span>
  );
}

export function initials(name: string) {
  return name
    .split(/\s+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((part) => part[0]?.toUpperCase() ?? "")
    .join("");
}

// ------------------------------------------------------------------ state --

export function Spinner({ className }: { className?: string }) {
  return <Loader2 className={cx("h-4 w-4 animate-spin text-muted", className)} />;
}

export function Loading({ label = "Loading" }: { label?: string }) {
  return (
    <div className="flex items-center justify-center gap-2 py-14 text-sm text-muted">
      <Spinner />
      {label}…
    </div>
  );
}

/**
 * The empty state.
 *
 * Always offers the next action where there is one: a screen that only says
 * "no data" leaves the user to guess how to change that.
 */
export function Empty({
  icon,
  title,
  description,
  action,
}: {
  icon?: React.ReactNode;
  title: string;
  description?: string;
  action?: React.ReactNode;
}) {
  return (
    <div className="flex flex-col items-center justify-center px-6 py-14 text-center">
      {icon && (
        <div className="mb-3 grid h-12 w-12 place-items-center rounded-2xl bg-sage-soft text-sage">
          {icon}
        </div>
      )}
      <p className="text-sm font-medium text-ink">{title}</p>
      {description && <p className="mt-1 max-w-sm text-[13px] text-muted">{description}</p>}
      {action && <div className="mt-4">{action}</div>}
    </div>
  );
}

/** A failed request. Shows the API's own message, which never leaks internals. */
export function ErrorNote({ message, onRetry }: { message: string; onRetry?: () => void }) {
  return (
    <div className="flex flex-wrap items-center justify-between gap-3 rounded-xl border border-bad/20 bg-bad-soft px-4 py-3">
      <p className="text-[13px] text-bad">{message}</p>
      {onRetry && (
        <Button size="sm" variant="secondary" onClick={onRetry}>
          Try again
        </Button>
      )}
    </div>
  );
}

// ------------------------------------------------------------------ modal --

export function Modal({
  open,
  onClose,
  title,
  children,
  wide,
}: {
  open: boolean;
  onClose: () => void;
  title: string;
  children: React.ReactNode;
  wide?: boolean;
}) {
  // Escape closes, as every dialog on the web has taught people to expect.
  useEffect(() => {
    if (!open) return;
    const onKey = (event: KeyboardEvent) => {
      if (event.key === "Escape") onClose();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [open, onClose]);

  if (!open) return null;

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-ink/30 p-4 backdrop-blur-[2px]"
      // Clicking the backdrop closes; clicking the panel must not, hence the
      // target check rather than a handler on the panel.
      onClick={(event) => {
        if (event.target === event.currentTarget) onClose();
      }}
    >
      <div
        role="dialog"
        aria-modal="true"
        aria-label={title}
        className={cx(
          "animate-rise w-full rounded-2xl border border-line bg-surface shadow-lift",
          wide ? "max-w-xl" : "max-w-md",
        )}
      >
        <div className="flex items-center justify-between border-b border-line px-5 py-4">
          <h2 className="text-[15px] font-semibold">{title}</h2>
          <button
            type="button"
            onClick={onClose}
            aria-label="Close"
            className="rounded-lg p-1 text-faint hover:bg-cream hover:text-ink"
          >
            <X className="h-4 w-4" />
          </button>
        </div>
        <div className="px-5 py-4">{children}</div>
      </div>
    </div>
  );
}

/**
 * A confirmation dialog, in place of `window.confirm`.
 *
 * The browser's own dialog blocks the page, cannot be styled, and reads as an
 * error on most systems; this one says what will happen and lets the danger
 * button carry the weight.
 */
export function ConfirmModal({
  open,
  title,
  body,
  confirmLabel,
  onConfirm,
  onClose,
}: {
  open: boolean;
  title: string;
  body: React.ReactNode;
  confirmLabel: string;
  onConfirm: () => Promise<void> | void;
  onClose: () => void;
}) {
  const [busy, setBusy] = useState(false);

  return (
    <Modal open={open} onClose={onClose} title={title}>
      <div className="text-[13px] text-muted">{body}</div>
      <div className="mt-5 flex justify-end gap-2">
        <Button variant="secondary" onClick={onClose}>
          Cancel
        </Button>
        <Button
          variant="danger"
          loading={busy}
          onClick={async () => {
            setBusy(true);
            try {
              await onConfirm();
              onClose();
            } finally {
              setBusy(false);
            }
          }}
        >
          {confirmLabel}
        </Button>
      </div>
    </Modal>
  );
}

// ------------------------------------------------------------------ toast --

/**
 * Transient feedback for actions that do not have a form to show it in.
 *
 * A delete button that fails silently is the worst outcome: the row stays, and
 * nobody knows why. A tiny module-level store means any handler can report,
 * without threading a context through every screen.
 */
type ToastItem = { id: number; tone: "ok" | "bad"; message: string };

let toasts: ToastItem[] = [];
let nextId = 1;
const listeners = new Set<() => void>();

function emit() {
  for (const listener of listeners) listener();
}

function push(tone: ToastItem["tone"], message: string) {
  const id = nextId++;
  toasts = [...toasts, { id, tone, message }];
  emit();
  setTimeout(() => {
    toasts = toasts.filter((toast) => toast.id !== id);
    emit();
  }, 4200);
}

export const notify = {
  success: (message: string) => push("ok", message),
  error: (message: string) => push("bad", message),
  /** Reports a caught error with the API's own message where there is one. */
  failure: (caught: unknown, fallback: string) =>
    push("bad", caught instanceof Error && caught.message ? caught.message : fallback),
};

const EMPTY: ToastItem[] = [];

export function Toaster() {
  const items = useSyncExternalStore(
    (listener) => {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
    () => toasts,
    () => EMPTY,
  );

  return (
    <div
      className="pointer-events-none fixed bottom-4 right-4 z-[60] flex w-[min(360px,calc(100vw-2rem))] flex-col gap-2"
      aria-live="polite"
    >
      {items.map((toast) => (
        <div
          key={toast.id}
          className={cx(
            "animate-rise pointer-events-auto flex items-start gap-2.5 rounded-xl border bg-surface px-4 py-3 text-[13px] shadow-lift",
            toast.tone === "ok" ? "border-ok/25" : "border-bad/25",
          )}
        >
          {toast.tone === "ok" ? (
            <CheckCircle2 className="mt-0.5 h-4 w-4 shrink-0 text-ok" />
          ) : (
            <AlertCircle className="mt-0.5 h-4 w-4 shrink-0 text-bad" />
          )}
          <span className="text-ink">{toast.message}</span>
        </div>
      ))}
    </div>
  );
}

// ----------------------------------------------------------------- format --

export function formatDate(value: string | null | undefined) {
  if (!value) return "—";
  return new Date(value).toLocaleDateString(undefined, {
    day: "numeric",
    month: "short",
    year: "numeric",
  });
}

export function formatTime(value: string | null | undefined) {
  if (!value) return "—";
  return new Date(value).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });
}

/** "3 minutes ago". Falls back to a date once it stops being useful. */
export function formatRelative(value: string | null | undefined) {
  if (!value) return "—";

  const seconds = Math.floor((Date.now() - new Date(value).getTime()) / 1000);
  if (seconds < 60) return "just now";

  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) return `${minutes}m ago`;

  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours}h ago`;

  const days = Math.floor(hours / 24);
  if (days < 7) return `${days}d ago`;

  return formatDate(value);
}

/** "2h 15m" from minutes. */
export function formatDuration(minutes: number) {
  if (minutes < 60) return `${minutes}m`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours}h ${minutes % 60}m`;
  const days = Math.floor(hours / 24);
  return `${days}d ${hours % 24}h`;
}

export function formatBytes(bytes: number) {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(0)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

export function formatNumber(value: number) {
  return value.toLocaleString();
}

export function formatPercent(value: number | null | undefined, digits = 0) {
  return value == null ? "—" : `${(value * 100).toFixed(digits)}%`;
}
