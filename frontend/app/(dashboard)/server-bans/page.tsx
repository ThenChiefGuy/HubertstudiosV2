"use client"

import { useEffect, useMemo, useState } from "react"
import { Loader2, Plus } from "lucide-react"
import { toast } from "sonner"
import { Button } from "@/components/ui/button"
import { Card, CardContent } from "@/components/ui/card"
import { DialogTrigger } from "@/components/ui/dialog"
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table"
import { PageHeader } from "@/components/dashboard/page-header"
import { StatusBadge } from "@/components/dashboard/status-badge"
import { BanDialog } from "@/components/dashboard/ban-dialog"
import { cn } from "@/lib/utils"
import {
  ApiError,
  deleteBan,
  fetchBans,
  fetchPlugins,
  type ApiBan,
  type ApiPlugin,
} from "@/lib/api"

const filters = [
  { key: "all", label: "All" },
  { key: "global", label: "Global bans" },
  { key: "plugin", label: "Plugin-specific" },
  { key: "active", label: "Active" },
  { key: "expired", label: "Expired" },
] as const

type FilterKey = (typeof filters)[number]["key"]

export default function ServerBansPage() {
  const [filter, setFilter] = useState<FilterKey>("all")
  const [bans, setBans] = useState<ApiBan[]>([])
  const [plugins, setPlugins] = useState<ApiPlugin[]>([])
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    let cancelled = false
    Promise.all([fetchBans(), fetchPlugins()])
      .then(([banData, pluginData]) => {
        if (cancelled) return
        setBans(banData)
        setPlugins(pluginData)
      })
      .catch((err) => toast.error(err instanceof ApiError ? err.message : "Could not load bans"))
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [])

  function upsertBan(ban: ApiBan) {
    setBans((prev) => {
      const exists = prev.some((b) => b.id === ban.id)
      return exists ? prev.map((b) => (b.id === ban.id ? ban : b)) : [ban, ...prev]
    })
  }

  async function handleUnban(ban: ApiBan) {
    try {
      await deleteBan(ban.id)
      setBans((prev) => prev.filter((b) => b.id !== ban.id))
      toast.success(`Unbanned ${ban.identifier}`)
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : "Could not remove ban")
    }
  }

  const rows = useMemo(() => {
    return bans.filter((b) => {
      switch (filter) {
        case "global":
          return b.productId === null
        case "plugin":
          return b.productId !== null
        case "active":
          return b.status === "active"
        case "expired":
          return b.status === "expired"
        default:
          return true
      }
    })
  }, [filter, bans])

  return (
    <>
      <PageHeader
        title="Server Bans"
        description="Block abusive servers globally or per plugin."
        action={
          <BanDialog
            plugins={plugins}
            onSaved={upsertBan}
            trigger={
              <DialogTrigger render={<Button />}>
                <Plus className="size-4" />
                Add server ban
              </DialogTrigger>
            }
          />
        }
      />

      <div className="flex flex-wrap gap-2">
        {filters.map((f) => (
          <button
            key={f.key}
            onClick={() => setFilter(f.key)}
            className={cn(
              "rounded-full border px-3 py-1.5 text-sm font-medium transition-colors",
              filter === f.key
                ? "border-primary bg-primary/10 text-primary"
                : "border-border bg-card text-muted-foreground hover:text-foreground",
            )}
          >
            {f.label}
          </button>
        ))}
      </div>

      <Card>
        <CardContent className="p-0">
          {loading ? (
            <div className="flex min-h-48 items-center justify-center">
              <Loader2 className="size-6 animate-spin text-muted-foreground" />
            </div>
          ) : (
            <div className="overflow-x-auto">
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead>Plugin</TableHead>
                    <TableHead>Identifier</TableHead>
                    <TableHead className="hidden md:table-cell">Reason</TableHead>
                    <TableHead>Until</TableHead>
                    <TableHead>Status</TableHead>
                    <TableHead className="text-right">Actions</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {rows.map((b) => (
                    <TableRow key={b.id}>
                      <TableCell>
                        {b.plugin ? (
                          <span className="font-medium">{b.plugin}</span>
                        ) : (
                          <StatusBadge tone="warning">Global</StatusBadge>
                        )}
                      </TableCell>
                      <TableCell className="font-mono text-xs text-muted-foreground">
                        {b.identifier}
                      </TableCell>
                      <TableCell className="hidden max-w-[260px] truncate text-sm text-muted-foreground md:table-cell">
                        {b.reason}
                      </TableCell>
                      <TableCell className="whitespace-nowrap text-sm text-muted-foreground">
                        {b.until ?? "Permanent"}
                      </TableCell>
                      <TableCell>
                        <StatusBadge tone={b.status === "active" ? "danger" : "muted"}>
                          {b.status === "active" ? "Active" : "Expired"}
                        </StatusBadge>
                      </TableCell>
                      <TableCell className="text-right">
                        <div className="flex items-center justify-end gap-1">
                          <BanDialog
                            ban={b}
                            plugins={plugins}
                            onSaved={upsertBan}
                            trigger={
                              <DialogTrigger render={<Button variant="ghost" size="sm" />}>
                                Edit
                              </DialogTrigger>
                            }
                          />
                          <Button
                            variant="ghost"
                            size="sm"
                            className="text-destructive hover:text-destructive"
                            onClick={() => handleUnban(b)}
                          >
                            Unban
                          </Button>
                        </div>
                      </TableCell>
                    </TableRow>
                  ))}
                  {rows.length === 0 ? (
                    <TableRow>
                      <TableCell colSpan={6} className="py-10 text-center text-sm text-muted-foreground">
                        No bans match this filter.
                      </TableCell>
                    </TableRow>
                  ) : null}
                </TableBody>
              </Table>
            </div>
          )}
        </CardContent>
      </Card>
    </>
  )
}
