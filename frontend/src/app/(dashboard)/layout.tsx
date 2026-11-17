"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import {
  BarChart3,
  Bell,
  BookOpen,
  Bot,
  CreditCard,
  Globe,
  HeartPulse,
  Home,
  Inbox,
  Lightbulb,
  LogOut,
  Menu,
  MessageSquareText,
  MessagesSquare,
  Plug,
  Search,
  Settings,
  SlidersHorizontal,
  Sparkles,
  Users,
  X,
} from "lucide-react";
import { useAuth } from "@/lib/auth";
import { api } from "@/lib/api";
import { useAsync, usePolling } from "@/lib/hooks";
import { Avatar, Loading, Logo, Toaster, cx } from "@/components/ui";

type NavItem = {
  href: string;
  label: string;
  icon: React.ComponentType<{ className?: string }>;
  badge?: "inbox" | "new";
};

/**
 * The sidebar, grouped the way the work actually divides: build the assistant,
 * look after the people it talks to, then run the workspace.
 */
const NAV: Array<{ heading: string | null; items: NavItem[] }> = [
  {
    heading: null,
    items: [
      { href: "/", label: "Overview", icon: Home },
      { href: "/ask", label: "Ask Intelli", icon: Sparkles, badge: "new" },
    ],
  },
  {
    heading: "Build",
    items: [
      { href: "/bots", label: "Bots", icon: Bot },
      { href: "/playground", label: "Chat Playground", icon: MessageSquareText },
      { href: "/knowledge", label: "Knowledge Base", icon: BookOpen },
      { href: "/health", label: "KB Health", icon: HeartPulse },
      { href: "/studio", label: "Widget Studio", icon: SlidersHorizontal },
    ],
  },
  {
    heading: "Engage",
    items: [
      { href: "/conversations", label: "Conversations", icon: MessagesSquare },
      { href: "/inbox", label: "Handoff Inbox", icon: Inbox, badge: "inbox" },
      { href: "/insights", label: "Insights", icon: Lightbulb },
      { href: "/analytics", label: "Analytics", icon: BarChart3 },
    ],
  },
  {
    heading: "Workspace",
    items: [
      { href: "/domains", label: "Domains", icon: Globe },
      { href: "/integrations", label: "Integrations", icon: Plug },
      { href: "/members", label: "Members", icon: Users },
      { href: "/billing", label: "Billing", icon: CreditCard },
      { href: "/settings", label: "Settings", icon: Settings },
    ],
  },
];

function isActive(pathname: string, href: string) {
  // "/" would otherwise match every route; "/knowledge" must not light up for
  // a sibling that merely shares the prefix.
  if (href === "/") return pathname === "/";
  return pathname === href || pathname.startsWith(`${href}/`);
}

export default function DashboardLayout({ children }: { children: React.ReactNode }) {
  const { user, loading, signOut } = useAuth();
  const router = useRouter();
  const pathname = usePathname();
  const [menuOpen, setMenuOpen] = useState(false);

  useEffect(() => {
    // Signed-out visitors land on the product page, which links to sign in.
    if (!loading && !user) router.replace("/welcome");
  }, [loading, user, router]);

  // Navigating on a phone should close the drawer it was opened from.
  useEffect(() => setMenuOpen(false), [pathname]);

  // The inbox count drives the sidebar badge and the bell. Refreshed on
  // navigation and once a minute, which is plenty for a queue measured in
  // minutes, and skipped entirely until there is a session.
  const inbox = useAsync(
    () => (user ? api.handoff.summary() : Promise.resolve(null)),
    [user?.user.id, pathname],
  );
  usePolling(inbox.reload, 60_000, Boolean(user));
  const waiting = inbox.data?.open ?? 0;

  if (loading || !user) return <Loading label="Checking your session" />;

  return (
    <div className="flex min-h-dvh">
      {menuOpen && (
        <div
          className="fixed inset-0 z-30 bg-ink/30 lg:hidden"
          onClick={() => setMenuOpen(false)}
          aria-hidden
        />
      )}

      <aside
        className={cx(
          "fixed inset-y-0 left-0 z-40 flex w-64 flex-col border-r border-line bg-surface",
          "transition-transform lg:translate-x-0",
          menuOpen ? "translate-x-0" : "-translate-x-full",
        )}
      >
        <div className="flex items-center gap-2 px-5 pb-4 pt-5">
          <Link href="/" aria-label="IntelliAssist home">
            <Logo />
          </Link>
          <button
            className="ml-auto text-muted lg:hidden"
            onClick={() => setMenuOpen(false)}
            aria-label="Close menu"
          >
            <X className="h-5 w-5" />
          </button>
        </div>

        {/* Organization context. A single organization today, so it reads as a
            label rather than pretending to be a switcher that switches nothing. */}
        <div className="mx-4 mb-2 rounded-xl bg-cream px-3 py-2 ring-1 ring-line">
          <p className="text-[10.5px] font-medium uppercase tracking-wider text-faint">
            Workspace
          </p>
          <p className="truncate text-[13px] font-medium">{user.organization.name}</p>
        </div>

        <nav className="flex-1 overflow-y-auto px-3 pb-3" aria-label="Main">
          {NAV.map((group, index) => (
            <div key={group.heading ?? index} className={group.heading ? "mt-5" : "mt-2"}>
              {group.heading && (
                <p className="mb-1 px-3 text-[10.5px] font-semibold uppercase tracking-wider text-faint">
                  {group.heading}
                </p>
              )}
              <div className="space-y-0.5">
                {group.items.map(({ href, label, icon: Icon, badge }) => {
                  const active = isActive(pathname, href);
                  return (
                    <Link
                      key={href}
                      href={href}
                      aria-current={active ? "page" : undefined}
                      className={cx(
                        "flex items-center gap-2.5 rounded-xl px-3 py-2 text-[13.5px] transition-colors",
                        active
                          ? "bg-clay-soft font-medium text-ink"
                          : "text-muted hover:bg-cream hover:text-ink",
                      )}
                    >
                      <Icon className={cx("h-4 w-4 shrink-0", active && "text-clay")} />
                      <span className="flex-1">{label}</span>
                      {badge === "new" && <Sparkles className="h-3.5 w-3.5 text-clay" />}
                      {badge === "inbox" && waiting > 0 && (
                        <span className="rounded-full bg-clay px-1.5 text-[11px] font-semibold leading-[18px] text-white">
                          {waiting > 99 ? "99+" : waiting}
                        </span>
                      )}
                    </Link>
                  );
                })}
              </div>
            </div>
          ))}
        </nav>

        <div className="border-t border-line p-3">
          <div className="flex items-center gap-2.5 px-2 py-1.5">
            <Avatar name={user.user.name} />
            <span className="min-w-0 flex-1">
              <span className="block truncate text-[13px] font-medium">{user.user.name}</span>
              <span className="block truncate text-[11.5px] capitalize text-muted">
                {user.role.toLowerCase()}
              </span>
            </span>
          </div>
          <button
            onClick={signOut}
            className="mt-1 flex w-full items-center gap-2.5 rounded-xl px-3 py-2 text-sm text-muted transition-colors hover:bg-cream hover:text-ink"
          >
            <LogOut className="h-4 w-4" />
            Sign out
          </button>
        </div>
      </aside>

      <div className="flex min-w-0 flex-1 flex-col lg:pl-64">
        <Topbar onOpenMenu={() => setMenuOpen(true)} waiting={waiting} />
        <main className="mx-auto w-full min-w-0 max-w-[1400px] flex-1 px-4 py-6 sm:px-6 lg:px-8">
          {children}
        </main>
      </div>

      <Toaster />
    </div>
  );
}

/**
 * The top bar.
 *
 * Search is a real client-side filter over bots and conversations rather than a
 * decorative box: an input that looks searchable and does nothing is worse
 * than no input at all.
 */
function Topbar({ onOpenMenu, waiting }: { onOpenMenu: () => void; waiting: number }) {
  const router = useRouter();
  const [query, setQuery] = useState("");

  return (
    <header className="sticky top-0 z-20 flex items-center gap-3 border-b border-line bg-canvas/85 px-4 py-3 backdrop-blur sm:px-6 lg:px-8">
      <button onClick={onOpenMenu} aria-label="Open menu" className="lg:hidden">
        <Menu className="h-5 w-5 text-muted" />
      </button>

      <form
        className="relative max-w-md flex-1"
        role="search"
        onSubmit={(event) => {
          event.preventDefault();
          const term = query.trim();
          if (term) router.push(`/search?q=${encodeURIComponent(term)}`);
        }}
      >
        <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-faint" />
        <input
          value={query}
          onChange={(event) => setQuery(event.target.value)}
          placeholder="Search bots and conversations…"
          aria-label="Search"
          className="w-full rounded-xl border border-line bg-surface py-2 pl-9 pr-3 text-sm placeholder:text-faint focus:border-clay/60 focus:outline-none focus:ring-2 focus:ring-clay/15"
        />
      </form>

      <div className="ml-auto flex items-center gap-1.5">
        <Link
          href="/ask"
          className="hidden items-center gap-1.5 rounded-xl border border-line bg-surface px-3 py-2 text-[13px] font-medium text-ink transition-colors hover:border-clay/40 sm:inline-flex"
        >
          <Sparkles className="h-4 w-4 text-clay" />
          Ask Intelli
        </Link>
        <Link
          href="/inbox"
          title={waiting ? `${waiting} conversation${waiting === 1 ? "" : "s"} waiting` : "Handoff inbox"}
          aria-label={waiting ? `Handoff inbox, ${waiting} waiting` : "Handoff inbox"}
          className="relative grid h-9 w-9 place-items-center rounded-xl text-muted transition-colors hover:bg-surface hover:text-ink"
        >
          <Bell className="h-4.5 w-4.5" />
          {/* Only lit when something is actually waiting. A dot that is always
              on is a dot nobody looks at. */}
          {waiting > 0 && (
            <span className="absolute right-2 top-2 h-2 w-2 rounded-full bg-clay ring-2 ring-canvas" aria-hidden />
          )}
        </Link>
      </div>
    </header>
  );
}
