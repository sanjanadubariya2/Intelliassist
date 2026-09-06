"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import {
  BarChart3,
  BookOpen,
  Bot,
  ChevronDown,
  CreditCard,
  Gift,
  Globe,
  HelpCircle,
  LayoutDashboard,
  LogOut,
  Menu,
  MessagesSquare,
  Palette,
  Plug,
  Search,
  Settings,
  Bell,
  Code2,
  Users,
  X,
} from "lucide-react";
import { useAuth } from "@/lib/auth";
import { Loading, cx } from "@/components/ui";

/**
 * The sidebar, grouped the way the work actually divides.
 *
 * Flat lists of twelve items are hard to scan; the headings cost one line each
 * and make the thing readable at a glance.
 */
const NAV = [
  {
    heading: null,
    items: [{ href: "/", label: "Overview", icon: LayoutDashboard }],
  },
  {
    heading: "Assistants",
    items: [
      { href: "/bots", label: "Bots", icon: Bot },
      { href: "/knowledge", label: "Knowledge Base", icon: BookOpen },
      { href: "/conversations", label: "Conversations", icon: MessagesSquare },
      { href: "/analytics", label: "Analytics", icon: BarChart3 },
    ],
  },
  {
    heading: "Deployment",
    items: [
      { href: "/customization", label: "Customization", icon: Palette },
      { href: "/embed", label: "API & Embed", icon: Code2 },
      { href: "/domains", label: "Domains", icon: Globe },
      { href: "/integrations", label: "Integrations", icon: Plug },
    ],
  },
  {
    heading: "Organization",
    items: [
      { href: "/members", label: "Members", icon: Users },
      { href: "/billing", label: "Billing", icon: CreditCard },
      { href: "/settings", label: "Settings", icon: Settings },
    ],
  },
] as const;

export default function DashboardLayout({ children }: { children: React.ReactNode }) {
  const { user, loading, signOut } = useAuth();
  const router = useRouter();
  const pathname = usePathname();
  const [menuOpen, setMenuOpen] = useState(false);

  useEffect(() => {
    if (!loading && !user) router.replace("/login");
  }, [loading, user, router]);

  // Navigating on a phone should close the drawer it was opened from.
  useEffect(() => setMenuOpen(false), [pathname]);

  if (loading || !user) return <Loading label="Checking your session" />;

  return (
    <div className="flex min-h-dvh">
      {menuOpen && (
        <div
          className="fixed inset-0 z-30 bg-ink/40 lg:hidden"
          onClick={() => setMenuOpen(false)}
          aria-hidden
        />
      )}

      <aside
        className={cx(
          "fixed inset-y-0 left-0 z-40 flex w-64 flex-col bg-rail text-rail-text",
          "transition-transform lg:translate-x-0",
          menuOpen ? "translate-x-0" : "-translate-x-full",
        )}
      >
        <div className="flex items-center gap-2.5 px-5 py-5">
          <span className="grid h-8 w-8 place-items-center rounded-lg bg-accent text-white">
            <Bot className="h-4.5 w-4.5" />
          </span>
          <span className="font-semibold tracking-tight text-rail-text">IntelliAssist</span>
          <button
            className="ml-auto text-rail-muted lg:hidden"
            onClick={() => setMenuOpen(false)}
            aria-label="Close menu"
          >
            <X className="h-5 w-5" />
          </button>
        </div>

        {/* Organization context. A single organization today, so it reads as a
            label rather than pretending to be a switcher that switches nothing. */}
        <div className="mx-3 mb-3 flex items-center gap-2 rounded-lg border border-rail-line px-3 py-2">
          <span className="min-w-0 flex-1 truncate text-[13px] font-medium">
            {user.organization.name}
          </span>
          <ChevronDown className="h-3.5 w-3.5 shrink-0 text-rail-muted" />
        </div>

        <nav className="flex-1 overflow-y-auto px-3 pb-3">
          {NAV.map((group, index) => (
            <div key={group.heading ?? index} className={group.heading ? "mt-5" : ""}>
              {group.heading && (
                <p className="mb-1 px-3 text-[10.5px] font-semibold uppercase tracking-wider text-rail-muted/70">
                  {group.heading}
                </p>
              )}
              <div className="space-y-0.5">
                {group.items.map(({ href, label, icon: Icon }) => {
                  // "/" would otherwise match every route.
                  const active = href === "/" ? pathname === "/" : pathname.startsWith(href);
                  return (
                    <Link
                      key={href}
                      href={href}
                      aria-current={active ? "page" : undefined}
                      className={cx(
                        "flex items-center gap-2.5 rounded-lg px-3 py-2 text-sm transition-colors",
                        active
                          ? "bg-accent font-medium text-white"
                          : "text-rail-muted hover:bg-rail-hover hover:text-rail-text",
                      )}
                    >
                      <Icon className="h-4 w-4 shrink-0" />
                      {label}
                    </Link>
                  );
                })}
              </div>
            </div>
          ))}
        </nav>

        <div className="border-t border-rail-line p-3">
          <div className="flex items-center gap-2.5 px-2 py-1.5">
            <span className="grid h-8 w-8 shrink-0 place-items-center rounded-full bg-accent/25 text-[12px] font-semibold text-rail-text">
              {initials(user.user.name)}
            </span>
            <span className="min-w-0 flex-1">
              <span className="block truncate text-[13px] font-medium text-rail-text">
                {user.user.name}
              </span>
              <span className="block truncate text-[11.5px] capitalize text-rail-muted">
                {user.role.toLowerCase()}
              </span>
            </span>
          </div>
          <button
            onClick={signOut}
            className="mt-1 flex w-full items-center gap-2.5 rounded-lg px-3 py-2 text-sm text-rail-muted transition-colors hover:bg-rail-hover hover:text-rail-text"
          >
            <LogOut className="h-4 w-4" />
            Sign out
          </button>
        </div>
      </aside>

      <div className="flex min-w-0 flex-1 flex-col lg:pl-64">
        <Topbar onOpenMenu={() => setMenuOpen(true)} />
        <main className="min-w-0 flex-1 px-4 py-6 sm:px-6 lg:px-8">{children}</main>
      </div>
    </div>
  );
}

/**
 * The top bar.
 *
 * Search is a real client-side filter over bots and conversations rather than a
 * decorative box — an input that looks searchable and does nothing is worse
 * than no input at all.
 */
function Topbar({ onOpenMenu }: { onOpenMenu: () => void }) {
  const router = useRouter();
  const [query, setQuery] = useState("");

  return (
    <header className="sticky top-0 z-20 flex items-center gap-3 border-b border-line bg-surface/90 px-4 py-3 backdrop-blur sm:px-6 lg:px-8">
      <button onClick={onOpenMenu} aria-label="Open menu" className="lg:hidden">
        <Menu className="h-5 w-5 text-muted" />
      </button>

      <form
        className="relative max-w-md flex-1"
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
          className="w-full rounded-lg border border-line bg-canvas py-2 pl-9 pr-3 text-sm placeholder:text-faint focus:border-accent focus:bg-surface focus:outline-none focus:ring-2 focus:ring-accent/20"
        />
      </form>

      <div className="ml-auto flex items-center gap-1">
        <IconLink href="/billing" label="Plan" icon={<Gift className="h-4.5 w-4.5" />} />
        <IconLink
          href="/conversations?resolution=ESCALATED"
          label="Escalations"
          icon={<Bell className="h-4.5 w-4.5" />}
          dot
        />
        <IconLink
          href="/settings"
          label="Help"
          icon={<HelpCircle className="h-4.5 w-4.5" />}
        />
      </div>
    </header>
  );
}

function IconLink({
  href,
  label,
  icon,
  dot,
}: {
  href: string;
  label: string;
  icon: React.ReactNode;
  dot?: boolean;
}) {
  return (
    <Link
      href={href}
      title={label}
      aria-label={label}
      className="relative grid h-9 w-9 place-items-center rounded-lg text-muted transition-colors hover:bg-canvas hover:text-ink"
    >
      {icon}
      {dot && (
        <span className="absolute right-2 top-2 h-1.5 w-1.5 rounded-full bg-bad" aria-hidden />
      )}
    </Link>
  );
}

function initials(name: string) {
  return name
    .split(/\s+/)
    .slice(0, 2)
    .map((part) => part[0]?.toUpperCase() ?? "")
    .join("");
}
