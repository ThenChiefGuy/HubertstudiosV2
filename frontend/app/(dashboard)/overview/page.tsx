"use client"

import { useEffect, useState } from "react"
import Link from "next/link"
import { Boxes, KeyRound, ShieldBan, Server, Plus, Loader2 } from "lucide-react"
import { toast } from "sonner"
import {
  Card,
  CardContent,
  CardHeader,
  CardTitle,
} from "@/components/ui/card"
import { Button } from "@/components/ui/button"
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table"
import { PageHeader } from "@/components/dashboard/page-header"
import { StatCard } from "@/components/dashboard/stat-card"
import { StatusBadge } from "@/components/dashboard/status-badge"
import {
  ApiError,
  fetchAuditLog,
  fetchBans,
  fetchOverview,
  type ApiAuditEvent,
  type ApiOverviewStats,
} from "@/lib/api"

export default function OverviewPage() {
  const [stats, setStats] = useState<ApiOverviewStats | null>(null)
  const [events, setEvents] = useState<ApiAuditEvent[]>([])
  const [blockedServers, setBlockedServers] = useState(0)
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    let cancelled = false
    Promise.all([fetchOverview(), fetchAuditLog(), fetchBans()])
      .then(([statsData, eventData, banData]) => {
        if (cancelled) return
        setStats(statsData)
        setEvents(eventData.slice(0, 5))
        setBlockedServers(banData.filter((b) => b.status === "active").length)
      })
      .catch((err) => toast.error(err instanceof ApiError ? err.message : "Could not load overview"))
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [])

  return (
    <>
      <PageHeader
        title="Overview"
        description="Live snapshot of your licensing infrastructure."
      />

      {loading ? (
        <div className="flex min-h-48 items-center justify-center">
          <Loader2 className="size-6 animate-spin text-muted-foreground" />
        </div>
      ) : (
        <>
          <div className="grid grid-cols-2 gap-4 lg:grid-cols-4">
            <StatCard
              label="Active Plugins"
              value={(stats?.activePlugins ?? 0).toString()}
              hint={`of ${stats?.totalPlugins ?? 0} total`}
              icon={Boxes}
            />
            <StatCard
              label="Global Licenses"
              value={(stats?.activeLicenses ?? 0).toString()}
              hint={`${stats?.totalLicenses ?? 0} total keys`}
              icon={KeyRound}
            />
            <StatCard
              label="Active Servers"
              value={(stats?.onlineServers ?? 0).toString()}
              hint={`${stats?.totalServersSeen ?? 0} total seen`}
              icon={Server}
            />
            <StatCard
              label="Blocked Servers"
              value={blockedServers.toString()}
              hint="active bans"
              icon={ShieldBan}
            />
          </div>

          <Card>
            <CardHeader>
              <CardTitle className="text-base">Quick actions</CardTitle>
            </CardHeader>
            <CardContent className="flex flex-wrap gap-3">
              <Button nativeButton={false} render={<Link href="/plugins" />}>
                <Plus className="size-4" />
                Add Plugin
              </Button>
              <Button
                variant="outline"
                nativeButton={false}
                render={<Link href="/licenses" />}
              >
                <Plus className="size-4" />
                Add License
              </Button>
              <Button
                variant="outline"
                nativeButton={false}
                render={<Link href="/server-bans" />}
              >
                <Plus className="size-4" />
                Add Server Ban
              </Button>
            </CardContent>
          </Card>

          <Card>
            <CardHeader>
              <CardTitle className="text-base">Recent audit events</CardTitle>
            </CardHeader>
            <CardContent className="p-0">
              <div className="overflow-x-auto">
                <Table>
                  <TableHeader>
                    <TableRow>
                      <TableHead>Time</TableHead>
                      <TableHead>Admin</TableHead>
                      <TableHead>Action</TableHead>
                      <TableHead className="hidden md:table-cell">Details</TableHead>
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {events.map((e) => (
                      <TableRow key={e.id}>
                        <TableCell className="whitespace-nowrap font-mono text-xs text-muted-foreground">
                          {e.time}
                        </TableCell>
                        <TableCell className="whitespace-nowrap text-sm">{e.admin}</TableCell>
                        <TableCell>
                          <StatusBadge tone="info">{e.action}</StatusBadge>
                        </TableCell>
                        <TableCell className="hidden text-sm text-muted-foreground md:table-cell">
                          {e.details}
                        </TableCell>
                      </TableRow>
                    ))}
                    {events.length === 0 ? (
                      <TableRow>
                        <TableCell colSpan={4} className="py-10 text-center text-sm text-muted-foreground">
                          No audit events yet.
                        </TableCell>
                      </TableRow>
                    ) : null}
                  </TableBody>
                </Table>
              </div>
            </CardContent>
          </Card>
        </>
      )}
    </>
  )
}
