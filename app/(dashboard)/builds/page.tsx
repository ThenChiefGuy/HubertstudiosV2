"use client"

import { useEffect, useState } from "react"
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
import { BuildDialog } from "@/components/dashboard/build-dialog"
import {
  ApiError,
  deleteBuild,
  fetchBuilds,
  fetchPlugins,
  type ApiBuild,
  type ApiPlugin,
} from "@/lib/api"

export default function BuildsPage() {
  const [builds, setBuilds] = useState<ApiBuild[]>([])
  const [plugins, setPlugins] = useState<ApiPlugin[]>([])
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    let cancelled = false
    Promise.all([fetchBuilds(), fetchPlugins()])
      .then(([buildData, pluginData]) => {
        if (cancelled) return
        setBuilds(buildData)
        setPlugins(pluginData)
      })
      .catch((err) => toast.error(err instanceof ApiError ? err.message : "Could not load builds"))
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [])

  function upsertBuild(build: ApiBuild) {
    setBuilds((prev) => {
      const exists = prev.some((b) => b.id === build.id)
      return exists ? prev.map((b) => (b.id === build.id ? build : b)) : [build, ...prev]
    })
  }

  async function handleDelete(build: ApiBuild) {
    try {
      await deleteBuild(build.id)
      setBuilds((prev) => prev.filter((b) => b.id !== build.id))
      toast.success(`Deleted build ${build.plugin} ${build.version}`)
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : "Could not delete build")
    }
  }

  return (
    <>
      <PageHeader
        title="Builds"
        description="Track official plugin builds and their verified SHA-256 hashes."
        action={
          <BuildDialog
            plugins={plugins}
            onSaved={upsertBuild}
            trigger={
              <DialogTrigger render={<Button />}>
                <Plus className="size-4" />
                Add build
              </DialogTrigger>
            }
          />
        }
      />

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
                    <TableHead>Version</TableHead>
                    <TableHead>SHA-256 Hash</TableHead>
                    <TableHead>Status</TableHead>
                    <TableHead className="hidden md:table-cell">Created</TableHead>
                    <TableHead className="text-right">Actions</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {builds.map((b) => (
                    <TableRow key={b.id}>
                      <TableCell className="font-medium">{b.plugin}</TableCell>
                      <TableCell className="font-mono text-sm">{b.version}</TableCell>
                      <TableCell className="max-w-[220px] truncate font-mono text-xs text-muted-foreground">
                        {b.hash}
                      </TableCell>
                      <TableCell>
                        <StatusBadge tone={b.active ? "success" : "muted"}>
                          {b.active ? "Active" : "Inactive"}
                        </StatusBadge>
                      </TableCell>
                      <TableCell className="hidden whitespace-nowrap text-sm text-muted-foreground md:table-cell">
                        {b.created}
                      </TableCell>
                      <TableCell className="text-right">
                        <div className="flex items-center justify-end gap-1">
                          <BuildDialog
                            build={b}
                            plugins={plugins}
                            onSaved={upsertBuild}
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
                            onClick={() => handleDelete(b)}
                          >
                            Delete
                          </Button>
                        </div>
                      </TableCell>
                    </TableRow>
                  ))}
                  {builds.length === 0 ? (
                    <TableRow>
                      <TableCell
                        colSpan={6}
                        className="py-10 text-center text-sm text-muted-foreground"
                      >
                        No builds registered.
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
