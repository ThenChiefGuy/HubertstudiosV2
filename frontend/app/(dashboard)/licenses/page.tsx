"use client"

import { useEffect, useMemo, useState } from "react"
import { Loader2, Plus, Search } from "lucide-react"
import { toast } from "sonner"
import { Button } from "@/components/ui/button"
import { Card, CardContent } from "@/components/ui/card"
import { Input } from "@/components/ui/input"
import { DialogTrigger } from "@/components/ui/dialog"
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
import { LicenseDialog } from "@/components/dashboard/license-dialog"
import {
  ApiError,
  deleteLicense,
  fetchLicenses,
  fetchPlugins,
  type ApiLicense,
  type ApiPlugin,
} from "@/lib/api"

const statusTone = {
  active: "success",
  expired: "warning",
  revoked: "danger",
} as const

export default function LicensesPage() {
  const [licenses, setLicenses] = useState<ApiLicense[]>([])
  const [plugins, setPlugins] = useState<ApiPlugin[]>([])
  const [query, setQuery] = useState("")
  const [plugin, setPlugin] = useState("all")
  const [status, setStatus] = useState("all")
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    let cancelled = false
    Promise.all([fetchLicenses(), fetchPlugins()])
      .then(([licenseData, pluginData]) => {
        if (cancelled) return
        setLicenses(licenseData)
        setPlugins(pluginData)
      })
      .catch((err) => toast.error(err instanceof ApiError ? err.message : "Could not load licenses"))
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [])

  function upsertLicense(license: ApiLicense) {
    setLicenses((prev) => {
      const exists = prev.some((l) => l.id === license.id)
      return exists ? prev.map((l) => (l.id === license.id ? license : l)) : [license, ...prev]
    })
  }

  async function handleDelete(license: ApiLicense) {
    try {
      await deleteLicense(license.id)
      setLicenses((prev) => prev.filter((l) => l.id !== license.id))
      toast.success(`Deleted ${license.label}`)
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : "Could not delete license")
    }
  }

  const filtered = useMemo(() => {
    return licenses.filter((l) => {
      const matchQuery =
        l.key.toLowerCase().includes(query.toLowerCase()) ||
        l.label.toLowerCase().includes(query.toLowerCase())
      const matchPlugin = plugin === "all" || l.productId === plugin
      const matchStatus = status === "all" || l.status === status
      return matchQuery && matchPlugin && matchStatus
    })
  }, [licenses, query, plugin, status])

  return (
    <>
      <PageHeader
        title="Global Licenses"
        description="Plugins are free — these global keys exist purely for security. Issue a global key per plugin with a fixed duration."
        action={
          <LicenseDialog
            plugins={plugins}
            onSaved={upsertLicense}
            trigger={
              <DialogTrigger render={<Button />}>
                <Plus className="size-4" />
                Add license
              </DialogTrigger>
            }
          />
        }
      />

      <Card>
        <CardContent className="flex flex-col gap-3 p-4 sm:flex-row sm:items-center">
          <div className="relative flex-1">
            <Search className="absolute left-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" />
            <Input
              placeholder="Search by key or label..."
              value={query}
              onChange={(e) => setQuery(e.target.value)}
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
                <SelectItem key={p.id} value={p.id}>
                  {p.displayName}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
          <Select value={status} onValueChange={setStatus}>
            <SelectTrigger className="sm:w-40">
              <SelectValue placeholder="Status" />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="all">All statuses</SelectItem>
              <SelectItem value="active">Active</SelectItem>
              <SelectItem value="expired">Expired</SelectItem>
              <SelectItem value="revoked">Revoked</SelectItem>
            </SelectContent>
          </Select>
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
                    <TableHead>Plugin</TableHead>
                    <TableHead>License Key</TableHead>
                    <TableHead>Label</TableHead>
                    <TableHead>Duration</TableHead>
                    <TableHead>Status</TableHead>
                    <TableHead>Expiry</TableHead>
                    <TableHead className="hidden lg:table-cell">Servers</TableHead>
                    <TableHead className="text-right">Actions</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {filtered.map((l) => (
                    <TableRow key={l.id}>
                      <TableCell className="font-medium">{l.plugin}</TableCell>
                      <TableCell className="font-mono text-xs text-muted-foreground">
                        {l.key}
                      </TableCell>
                      <TableCell className="whitespace-nowrap">{l.label}</TableCell>
                      <TableCell className="whitespace-nowrap text-sm text-muted-foreground">
                        {l.duration}
                      </TableCell>
                      <TableCell>
                        <StatusBadge tone={statusTone[l.status]}>
                          {l.status.charAt(0).toUpperCase() + l.status.slice(1)}
                        </StatusBadge>
                      </TableCell>
                      <TableCell className="whitespace-nowrap text-sm text-muted-foreground">
                        {l.expiry ?? "Never"}
                      </TableCell>
                      <TableCell className="hidden whitespace-nowrap text-sm text-muted-foreground lg:table-cell">
                        {l.servers.toLocaleString()}
                      </TableCell>
                      <TableCell className="text-right">
                        <div className="flex items-center justify-end gap-1">
                          <LicenseDialog
                            license={l}
                            plugins={plugins}
                            onSaved={upsertLicense}
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
                            onClick={() => handleDelete(l)}
                          >
                            Delete
                          </Button>
                        </div>
                      </TableCell>
                    </TableRow>
                  ))}
                  {filtered.length === 0 ? (
                    <TableRow>
                      <TableCell colSpan={8} className="py-10 text-center text-sm text-muted-foreground">
                        No licenses match your filters.
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
