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
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-line text-left text-[12px] text-muted">
                  <th className="px-5 py-3 font-medium">Name</th>
                  <th className="px-5 py-3 font-medium">Email</th>
                  <th className="px-5 py-3 font-medium">Role</th>
                  <th className="px-5 py-3 font-medium">Joined</th>
                </tr>
              </thead>
              <tbody>
                {data.map((member) => (
                  <tr key={member.userId} className="border-b border-line last:border-0">
                    <td className="px-5 py-3.5">
                      <div className="flex items-center gap-2.5">
                        <span className="grid h-8 w-8 shrink-0 place-items-center rounded-full bg-accent-soft text-[12px] font-semibold text-accent">
                          {member.name
                            .split(/\s+/)
                            .slice(0, 2)
                            .map((part) => part[0]?.toUpperCase() ?? "")
                            .join("")}
                        </span>
                        <span className="font-medium">{member.name}</span>
                        {member.userId === user?.user.id && (
                          <span className="text-[12px] text-faint">(you)</span>
                        )}
                      </div>
                    </td>
                    <td className="px-5 py-3.5 text-muted">{member.email}</td>
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
    </>
  );
}
