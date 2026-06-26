"use client"

import { useEffect, useState } from "react"
import Image from "next/image"
import { Loader2, Plus, Fingerprint } from "lucide-react"
import { toast } from "sonner"
import { Button } from "@/components/ui/button"
import { Card } from "@/components/ui/card"
import { DialogTrigger } from "@/components/ui/dialog"
import { PageHeader } from "@/components/dashboard/page-header"
import { StatusBadge } from "@/components/dashboard/status-badge"
import { PluginDialog } from "@/components/dashboard/plugin-dialog"
import { ApiError, fetchPlugins, type ApiPlugin } from "@/lib/api"

export default function PluginsPage() {
  const [plugins, setPlugins] = useState<ApiPlugin[]>([])
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    let cancelled = false
    fetchPlugins()
      .then((data) => {
        if (!cancelled) setPlugins(data)
      })
      .catch((err) => toast.error(err instanceof ApiError ? err.message : "Could not load plugins"))
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [])

  function upsertPlugin(plugin: ApiPlugin) {
    setPlugins((prev) => {
      const exists = prev.some((p) => p.id === plugin.id)
      return exists ? prev.map((p) => (p.id === plugin.id ? plugin : p)) : [plugin, ...prev]
    })
  }

  function removePlugin(plugin: ApiPlugin) {
    setPlugins((prev) => prev.filter((p) => p.id !== plugin.id))
  }

  return (
    <>
      <PageHeader
        title="Plugins"
        description="Manage your Minecraft plugins and their validation settings."
        action={
          <PluginDialog
            onSaved={upsertPlugin}
            trigger={
              <DialogTrigger render={<Button />}>
                <Plus className="size-4" />
                Add new plugin
              </DialogTrigger>
            }
          />
        }
      />

      {loading ? (
        <div className="flex min-h-48 items-center justify-center">
          <Loader2 className="size-6 animate-spin text-muted-foreground" />
        </div>
      ) : (
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
          {plugins.map((plugin) => (
            <Card key={plugin.id} className="flex flex-col gap-4 p-5">
              <div className="flex items-start gap-3">
                <Image
                  src={plugin.image || "/placeholder.svg"}
                  alt={`${plugin.displayName} icon`}
                  width={48}
                  height={48}
                  unoptimized={!!plugin.image?.startsWith("data:")}
                  className="size-12 rounded-lg border border-border object-cover"
                />
                <div className="min-w-0 flex-1">
                  <h3 className="truncate font-semibold text-foreground">
                    {plugin.displayName}
                  </h3>
                  <p className="truncate font-mono text-xs text-muted-foreground">
                    {plugin.name}
                  </p>
                </div>
              </div>

              <p className="line-clamp-2 text-sm text-muted-foreground">
                {plugin.description}
              </p>

              <div className="flex flex-wrap gap-2">
                <StatusBadge tone={plugin.active ? "success" : "muted"}>
                  {plugin.active ? "Active" : "Disabled"}
                </StatusBadge>
                <StatusBadge tone={plugin.mode === "modern" ? "info" : "warning"}>
                  {plugin.mode === "modern" ? "Modern" : "Legacy"}
                </StatusBadge>
                <StatusBadge tone="success">Free</StatusBadge>
                {plugin.requireHash ? (
                  <StatusBadge tone="info">
                    <Fingerprint className="mr-1 size-3" />
                    Hash
                  </StatusBadge>
                ) : null}
              </div>

              <div className="mt-auto flex items-center justify-between border-t border-border pt-3">
                <span className="text-xs text-muted-foreground">
                  {plugin.licenses} {plugin.licenses === 1 ? "license" : "licenses"} ·{" "}
                  {plugin.servers.toLocaleString()} servers
                </span>
                <PluginDialog
                  plugin={plugin}
                  onSaved={upsertPlugin}
                  onDeleted={removePlugin}
                  trigger={
                    <DialogTrigger render={<Button variant="outline" size="sm" />}>
                      Manage
                    </DialogTrigger>
                  }
                />
              </div>
            </Card>
          ))}
          {plugins.length === 0 ? (
            <Card className="p-6 text-sm text-muted-foreground">No plugins configured yet.</Card>
          ) : null}
        </div>
      )}
    </>
  )
}
