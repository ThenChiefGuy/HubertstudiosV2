"use client"

import { useEffect, useMemo, useState } from "react"
import { Loader2, Search } from "lucide-react"
import { toast } from "sonner"
import { Card, CardContent } from "@/components/ui/card"
import { Input } from "@/components/ui/input"
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select"
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
import { ApiError, fetchAuditLog, type ApiAuditEvent } from "@/lib/api"

export default function AuditLogPage() {
  const [query, setQuery] = useState("")
  const [action, setAction] = useState("all")
  const [events, setEvents] = useState<ApiAuditEvent[]>([])
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    let cancelled = false
    fetchAuditLog()
      .then((data) => {
        if (!cancelled) setEvents(data)
      })
      .catch((err) => toast.error(err instanceof ApiError ? err.message : "Could not load audit log"))
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [])

  const actions = useMemo(
    () => Array.from(new Set(events.map((e) => e.action))),
    [events],
  )

  const rows = useMemo(() => {
    return events.filter((e) => {
      const matchQuery =
        e.details.toLowerCase().includes(query.toLowerCase()) ||
        e.admin.toLowerCase().includes(query.toLowerCase()) ||
        e.targetId.toLowerCase().includes(query.toLowerCase())
      const matchAction = action === "all" || e.action === action
      return matchQuery && matchAction
    })
  }, [events, query, action])

  return (
    <>
      <PageHeader
        title="Audit Log"
        description="Immutable record of administrative actions."
      />

      <Card>
        <CardContent className="flex flex-col gap-3 p-4 sm:flex-row sm:items-center">
          <div className="relative flex-1">
            <Search className="absolute left-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" />
            <Input
              placeholder="Search admin, target, or details..."
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              className="pl-9"
            />
          </div>
          <Select value={action} onValueChange={setAction}>
            <SelectTrigger className="sm:w-52">
              <SelectValue placeholder="Action" />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="all">All actions</SelectItem>
              {actions.map((a) => (
                <SelectItem key={a} value={a}>
                  {a}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
          <Input type="date" className="sm:w-44" aria-label="Filter by date" />
        </CardContent>
      </Card>

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
                    <TableHead>Time</TableHead>
                    <TableHead>Admin</TableHead>
                    <TableHead>Action</TableHead>
                    <TableHead className="hidden md:table-cell">Target</TableHead>
                    <TableHead className="hidden lg:table-cell">IP</TableHead>
                    <TableHead className="hidden xl:table-cell">Details</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {rows.map((e) => (
                    <TableRow key={e.id}>
                      <TableCell className="whitespace-nowrap font-mono text-xs text-muted-foreground">
                        {e.time}
                      </TableCell>
                      <TableCell className="whitespace-nowrap text-sm">{e.admin}</TableCell>
                      <TableCell>
                        <StatusBadge tone="info">{e.action}</StatusBadge>
                      </TableCell>
                      <TableCell className="hidden md:table-cell">
                        <span className="text-sm">{e.targetType}</span>
                        <span className="ml-1 font-mono text-xs text-muted-foreground">
                          {e.targetId}
                        </span>
                      </TableCell>
                      <TableCell className="hidden whitespace-nowrap font-mono text-xs text-muted-foreground lg:table-cell">
                        {e.ip}
                      </TableCell>
                      <TableCell className="hidden max-w-[260px] truncate text-sm text-muted-foreground xl:table-cell">
                        {e.details}
                      </TableCell>
                    </TableRow>
                  ))}
                  {rows.length === 0 ? (
                    <TableRow>
                      <TableCell colSpan={6} className="py-10 text-center text-sm text-muted-foreground">
                        No events match your filters.
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
