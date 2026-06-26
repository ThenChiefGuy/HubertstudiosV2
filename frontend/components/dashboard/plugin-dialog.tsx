"use client"

import { useEffect, useState, type ChangeEvent, type ReactNode } from "react"
import { Loader2, Upload } from "lucide-react"
import { toast } from "sonner"
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { Textarea } from "@/components/ui/textarea"
import { Switch } from "@/components/ui/switch"
import {
  ApiError,
  createPlugin,
  deletePlugin,
  updatePlugin,
  type ApiPlugin,
} from "@/lib/api"

function ToggleRow({
  id,
  label,
  description,
  checked,
  onChange,
}: {
  id: string
  label: string
  description: string
  checked: boolean
  onChange: (v: boolean) => void
}) {
  return (
    <div className="flex items-center justify-between gap-4 rounded-md border border-border p-3">
      <div className="space-y-0.5">
        <Label htmlFor={id} className="text-sm">
          {label}
        </Label>
        <p className="text-xs text-muted-foreground">{description}</p>
      </div>
      <Switch id={id} checked={checked} onCheckedChange={onChange} />
    </div>
  )
}

async function fileToDataUrl(file: File) {
  return new Promise<string>((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(String(reader.result || ""))
    reader.onerror = () => reject(reader.error)
    reader.readAsDataURL(file)
  })
}

export function PluginDialog({
  trigger,
  plugin,
  onSaved,
  onDeleted,
}: {
  trigger: ReactNode
  plugin?: ApiPlugin
  onSaved?: (plugin: ApiPlugin) => void
  onDeleted?: (plugin: ApiPlugin) => void
}) {
  const [open, setOpen] = useState(false)
  const [name, setName] = useState("")
  const [displayName, setDisplayName] = useState("")
  const [description, setDescription] = useState("")
  const [image, setImage] = useState<string | null>(null)
  const [active, setActive] = useState(true)
  const [legacy, setLegacy] = useState(false)
  const [requireHash, setRequireHash] = useState(true)
  const [saving, setSaving] = useState(false)
  const [deleting, setDeleting] = useState(false)

  useEffect(() => {
    if (open) {
      setName(plugin?.name ?? "")
      setDisplayName(plugin?.displayName ?? "")
      setDescription(plugin?.description ?? "")
      setImage(plugin?.image ?? null)
      setActive(plugin?.active ?? true)
      setLegacy(plugin?.mode === "legacy")
      setRequireHash(plugin?.requireHash ?? true)
      setSaving(false)
      setDeleting(false)
    }
  }, [open, plugin])

  async function handleImageChange(event: ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0]
    if (!file) return
    try {
      setImage(await fileToDataUrl(file))
    } catch {
      toast.error("Could not read image file")
    }
  }

  async function handleSave() {
    if (!displayName.trim()) {
      toast.error("Display name is required")
      return
    }
    if (!plugin && !name.trim()) {
      toast.error("Plugin name is required")
      return
    }

    setSaving(true)
    try {
      const payload = {
        name: name.trim(),
        displayName: displayName.trim(),
        description,
        image,
        active,
        mode: legacy ? "legacy" as const : "modern" as const,
        requireHash,
      }
      const saved = plugin
        ? await updatePlugin(plugin.id, payload)
        : await createPlugin(payload)
      onSaved?.(saved)
      toast.success(plugin ? "Plugin updated" : "Plugin created")
      setOpen(false)
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : "Could not save plugin")
    } finally {
      setSaving(false)
    }
  }

  async function handleDelete() {
    if (!plugin) return
    setDeleting(true)
    try {
      await deletePlugin(plugin.id)
      onDeleted?.(plugin)
      toast.success(`Deleted ${plugin.displayName}`)
      setOpen(false)
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : "Could not delete plugin")
    } finally {
      setDeleting(false)
    }
  }

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      {trigger}
      <DialogContent className="max-h-[90dvh] overflow-y-auto sm:max-w-lg">
        <DialogHeader>
          <DialogTitle>{plugin ? "Edit plugin" : "Add new plugin"}</DialogTitle>
          <DialogDescription>
            Configure the plugin metadata and validation behavior.
          </DialogDescription>
        </DialogHeader>

        <div className="flex flex-col gap-4 py-2">
          <div className="flex flex-col gap-2">
            <Label htmlFor="plugin-name">Plugin name</Label>
            <Input
              id="plugin-name"
              placeholder="OrbitalStrike"
              value={name}
              disabled={!!plugin}
              onChange={(e) => setName(e.target.value)}
            />
            {plugin ? (
              <p className="text-xs text-muted-foreground">
                Internal product names cannot be changed after creation.
              </p>
            ) : null}
          </div>
          <div className="flex flex-col gap-2">
            <Label htmlFor="plugin-display">Display name</Label>
            <Input
              id="plugin-display"
              placeholder="Orbital Strike"
              value={displayName}
              onChange={(e) => setDisplayName(e.target.value)}
            />
          </div>
          <div className="flex flex-col gap-2">
            <Label htmlFor="plugin-desc">Description</Label>
            <Textarea
              id="plugin-desc"
              rows={3}
              placeholder="Short description of the plugin..."
              value={description}
              onChange={(e) => setDescription(e.target.value)}
            />
          </div>
          <div className="flex flex-col gap-2">
            <Label htmlFor="plugin-image">Plugin image</Label>
            <Input id="plugin-image" type="file" accept="image/*" onChange={handleImageChange} />
            {image ? (
              <Button type="button" variant="outline" onClick={() => setImage(null)}>
                <Upload className="size-4" />
                Remove selected image
              </Button>
            ) : null}
          </div>

          <div className="flex flex-col gap-2">
            <ToggleRow
              id="plugin-active"
              label="Active"
              description="Plugin is available for licensing."
              checked={active}
              onChange={setActive}
            />
            <ToggleRow
              id="plugin-legacy"
              label="Legacy mode"
              description="Use the legacy validation protocol."
              checked={legacy}
              onChange={setLegacy}
            />
            <ToggleRow
              id="plugin-hash"
              label="Require hash"
              description="Enforce JAR SHA-256 hash validation."
              checked={requireHash}
              onChange={setRequireHash}
            />
          </div>
        </div>

        <DialogFooter className="sm:justify-between">
          {plugin ? (
            <Button
              variant="ghost"
              onClick={handleDelete}
              disabled={saving || deleting}
              className="text-destructive hover:text-destructive"
            >
              {deleting ? <Loader2 className="size-4 animate-spin" /> : null}
              Delete plugin
            </Button>
          ) : (
            <span className="hidden sm:block" />
          )}
          <div className="flex flex-col gap-2 sm:flex-row">
            <Button variant="outline" onClick={() => setOpen(false)} disabled={saving || deleting}>
              Cancel
            </Button>
            <Button onClick={handleSave} disabled={saving || deleting}>
              {saving ? <Loader2 className="size-4 animate-spin" /> : null}
              {plugin ? "Save changes" : "Create plugin"}
            </Button>
          </div>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
