"use client"

import { useEffect, useMemo, useState } from "react"
import { Loader2, Server, Users, Wifi, Search } from "lucide-react"
import { toast } from "sonner"
import { PageHeader } from "@/components/dashboard/page-header"
import { StatCard } from "@/components/dashboard/stat-card"
import { StatusBadge } from "@/components/dashboard/status-badge"
import {
  Card,
  CardContent,
  CardHeader,
  CardTitle,
} from "@/components/ui/card"
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
import {
  ApiError,
  fetchActiveServers,
  fetchPlugins,
  type ApiActiveServer,
  type ApiPlugin,
} from "@/lib/api"

export default function ActiveServersPage() {
  const [query, setQuery] = useState("")
  const [plugin, setPlugin] = useState("all")
  const [status, setStatus] = useState("all")
  const [activeServers, setActiveServers] = useState<ApiActiveServer[]>([])
  const [plugins, setPlugins] = useState<ApiPlugin[]>([])
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    let cancelled = false
    Promise.all([fetchActiveServers(), fetchPlugins()])
      .then(([serverData, pluginData]) => {
        if (cancelled) return
        setActiveServers(serverData)
        setPlugins(pluginData)
      })
      .catch((err) => toast.error(err instanceof ApiError ? err.message : "Could not load active servers"))
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [])

  const filtered = useMemo(() => {
    return activeServers.filter((s) => {
      const matchesQuery =
        s.address.toLowerCase().includes(query.toLowerCase()) ||
        s.country.toLowerCase().includes(query.toLowerCase())
      const matchesPlugin = plugin === "all" || s.plugin === plugin
      const matchesStatus = status === "all" || s.status === status
      return matchesQuery && matchesPlugin && matchesStatus
    })
  }, [activeServers, query, plugin, status])

  const onlineCount = activeServers.filter((s) => s.status === "online").length
  const totalPlayers = activeServers
    .filter((s) => s.status === "online")
    .reduce((sum, s) => sum + s.players, 0)

  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        title="Active Servers"
        description="Live servers that currently have a HubertStudios plugin installed and validating."
      />

      <div className="grid gap-4 sm:grid-cols-3">
        <StatCard
          label="Total servers"
          value={activeServers.length.toString()}
          icon={Server}
        />
        <StatCard
          label="Online now"
          value={onlineCount.toString()}
          icon={Wifi}
        />
        <StatCard
          label="Players online"
          value={totalPlayers.toLocaleString()}
          icon={Users}
        />
      </div>

      <Card>
        <CardHeader className="gap-4">
          <CardTitle className="text-base">Connected servers</CardTitle>
          <div className="flex flex-col gap-3 sm:flex-row sm:items-center">
            <div className="relative flex-1">
              <Search className="pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" />
              <Input
                value={query}
                onChange={(e) => setQuery(e.target.value)}
                placeholder="Search address or country"
                className="pl-9"
              />
            </div>
            <Select value={plugin} onValueChange={setPlugin}>
              <SelectTrigger className="sm:w-44">
                <SelectValue placeholder="Plugin" />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="all">All plugins</SelectItem>
                {plugins.map((p) => (
                  <SelectItem key={p.id} value={p.displayName}>
                    {p.displayName}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
            <Select value={status} onValueChange={setStatus}>
              <SelectTrigger className="sm:w-36">
                <SelectValue placeholder="Status" />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="all">All status</SelectItem>
                <SelectItem value="online">Online</SelectItem>
                <SelectItem value="offline">Offline</SelectItem>
              </SelectContent>
            </Select>
          </div>
        </CardHeader>
        <CardContent>
          {loading ? (
            <div className="flex min-h-48 items-center justify-center">
              <Loader2 className="size-6 animate-spin text-muted-foreground" />
            </div>
          ) : (
            <div className="overflow-x-auto">
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead>Server address</TableHead>
                    <TableHead>Plugin</TableHead>
                    <TableHead className="hidden sm:table-cell">Version</TableHead>
                    <TableHead className="hidden md:table-cell">Country</TableHead>
                    <TableHead>Players</TableHead>
                    <TableHead className="hidden lg:table-cell">Last seen</TableHead>
                    <TableHead>Status</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {filtered.map((s) => (
                    <TableRow key={s.id}>
                      <TableCell className="font-medium">{s.address}</TableCell>
                      <TableCell className="text-sm text-muted-foreground">
                        {s.plugin}
                      </TableCell>
                      <TableCell className="hidden font-mono text-sm text-muted-foreground sm:table-cell">
                        {s.version}
                      </TableCell>
                      <TableCell className="hidden text-sm text-muted-foreground md:table-cell">
                        {s.country}
                      </TableCell>
                      <TableCell className="text-sm">
                        {s.status === "online" ? s.players.toLocaleString() : "—"}
                      </TableCell>
                      <TableCell className="hidden whitespace-nowrap text-sm text-muted-foreground lg:table-cell">
                        {s.lastSeen}
                      </TableCell>
                      <TableCell>
                        <StatusBadge tone={s.status === "online" ? "success" : "muted"}>
                          {s.status === "online" ? "Online" : "Offline"}
                        </StatusBadge>
                      </TableCell>
                    </TableRow>
                  ))}
                  {filtered.length === 0 ? (
                    <TableRow>
                      <TableCell
                        colSpan={7}
                        className="py-10 text-center text-sm text-muted-foreground"
                      >
                        No servers match your filters.
                      </TableCell>
                    </TableRow>
                  ) : null}
                </TableBody>
              </Table>
            </div>
          )}
        </CardContent>
      </Card>
    </div>
  )
}
