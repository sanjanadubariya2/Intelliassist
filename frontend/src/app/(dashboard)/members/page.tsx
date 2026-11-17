"use client";

import { Users } from "lucide-react";
import { api } from "@/lib/api";
import { useAsync } from "@/lib/hooks";
import { useAuth } from "@/lib/auth";
import {
  Badge,
  Card,
  CardHeader,
  Empty,
  ErrorNote,
  Loading,
  PageHeader,
  formatDate,
} from "@/components/ui";

/** Owner reads as the strongest, so it gets the accent. */
const ROLE_TONE = { OWNER: "accent", ADMIN: "ok", MEMBER: "neutral" } as const;

export default function MembersPage() {
  const { user } = useAuth();
  const { data, loading, error, reload } = useAsync(() => api.members.list());

  return (
    <>
      <PageHeader
        title="Members"
        description={`People with access to ${user?.organization.name ?? "this workspace"}. Anyone here can be assigned handoff conversations.`}
      />

      <Card>
        <CardHeader
          title="Team"
          description="Read-only for now — inviting needs an invitation flow and email delivery."
        />

        {loading ? (
          <Loading />
        ) : error ? (
          <div className="p-5">
            <ErrorNote message={error} onRetry={reload} />
          </div>
        ) : !data?.length ? (
          <Empty icon={<Users className="h-7 w-7" />} title="No members" />
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-[14px]">
              <thead>
                <tr className="border-b border-line text-left text-[12px] font-semibold text-muted">
                  <th className="px-5 py-3">Member</th>
                  <th className="px-5 py-3">Role</th>
                  <th className="px-5 py-3">Joined</th>
                </tr>
              </thead>
              <tbody>
                {data.map((member) => (
                  <tr key={member.userId} className="border-b border-[#f3ece4] last:border-0">
                    <td className="px-5 py-3.5">
                      <div className="flex items-center gap-3">
                        <span className="grid h-[38px] w-[38px] shrink-0 place-items-center rounded-full bg-accent-soft text-[13px] font-semibold text-accent">
                          {member.name
                            .split(/\s+/)
                            .slice(0, 2)
                            .map((part) => part[0]?.toUpperCase() ?? "")
                            .join("")}
                        </span>
                        <span className="min-w-0">
                          <span className="block font-semibold">
                            {member.name}
                            {member.userId === user?.user.id && (
                              <span className="ml-1.5 text-[12px] font-normal text-muted">(you)</span>
                            )}
                          </span>
                          <span className="block truncate text-[13px] text-muted">{member.email}</span>
                        </span>
                      </div>
                    </td>
                    <td className="px-5 py-3.5">
                      <Badge tone={ROLE_TONE[member.role]}>
                        {member.role[0] + member.role.slice(1).toLowerCase()}
                      </Badge>
                    </td>
                    <td className="px-5 py-3.5 text-muted">{formatDate(member.joinedAt)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>

      <Card className="mt-5 p-6">
        <h2 className="mb-4 font-display text-[20px] font-semibold">What each role can do</h2>
        <div className="grid grid-cols-[repeat(auto-fit,minmax(220px,1fr))] gap-5 text-[14px] leading-normal">
          <div>
            <p className="font-semibold">Owner</p>
            <p className="mt-1 text-muted">Full control, including deleting the workspace.</p>
          </div>
          <div>
            <p className="font-semibold">Admin</p>
            <p className="mt-1 text-muted">Manages bots, documents, widget keys and allowed domains.</p>
          </div>
          <div>
            <p className="font-semibold">Member</p>
            <p className="mt-1 text-muted">Reads conversations, uses the bots and can be assigned handoffs.</p>
          </div>
        </div>
      </Card>
    </>
  );
}
