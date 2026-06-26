"use client"

import { useEffect, useState, type ReactNode } from "react"
import { Loader2 } from "lucide-react"
import { toast } from "sonner"
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { Textarea } from "@/components/ui/textarea"
import { Switch } from "@/components/ui/switch"
import {
  ApiError,
  createBuild,
  updateBuild,
  type ApiBuild,
  type ApiPlugin,
} from "@/lib/api"

export function BuildDialog({
  trigger,
  build,
  plugins,
  onSaved,
}: {
  trigger: ReactNode
  build?: ApiBuild
  plugins: ApiPlugin[]
  onSaved?: (build: ApiBuild) => void
}) {
  const [open, setOpen] = useState(false)
  const [productId, setProductId] = useState("")
  const [hash, setHash] = useState("")
  const [version, setVersion] = useState("")
  const [active, setActive] = useState(true)
  const [reason, setReason] = useState("")
  const [saving, setSaving] = useState(false)

  useEffect(() => {
    if (open) {
      setProductId(build?.productId ?? plugins[0]?.id ?? "")
      setHash(build?.hash ?? "")
      setVersion(build?.version ?? "")
      setActive(build?.active ?? true)
      setReason(build?.reason ?? "")
      setSaving(false)
    }
  }, [open, build, plugins])

  async function handleSave() {
    if (!productId) {
      toast.error("Select a plugin first")
      return
    }
    if (!hash.trim()) {
      toast.error("SHA-256 hash is required")
      return
    }
    if (!version.trim()) {
      toast.error("Version is required")
      return
    }

    setSaving(true)
    try {
      const payload = {
        productId,
        hash: hash.trim().toLowerCase(),
        version: version.trim(),
        active,
        reason,
      }
      const saved = build ? await updateBuild(build.id, payload) : await createBuild(payload)
      onSaved?.(saved)
      toast.success(build ? "Build updated" : "Build registered")
      setOpen(false)
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : "Could not save build")
    } finally {
      setSaving(false)
    }
  }

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      {trigger}
      <DialogContent className="max-h-[90dvh] overflow-y-auto sm:max-w-lg">
        <DialogHeader>
          <DialogTitle>{build ? "Edit build" : "Add build"}</DialogTitle>
          <DialogDescription>
            Register an official plugin build and its verified hash.
          </DialogDescription>
        </DialogHeader>

        <div className="flex flex-col gap-4 py-2">
          <div className="flex flex-col gap-2">
            <Label>Plugin</Label>
            <Select value={productId} onValueChange={setProductId} disabled={!!build}>
              <SelectTrigger>
                <SelectValue placeholder="Select plugin" />
              </SelectTrigger>
              <SelectContent>
                {plugins.map((p) => (
                  <SelectItem key={p.id} value={p.id}>
                    {p.displayName}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>
          <div className="flex flex-col gap-2">
            <Label htmlFor="build-hash">JAR SHA-256 hash</Label>
            <Input
              id="build-hash"
              placeholder="a3f5c9e2b1d4..."
              value={hash}
              disabled={!!build}
              onChange={(e) => setHash(e.target.value)}
              className="font-mono text-xs"
            />
          </div>
          <div className="flex flex-col gap-2">
            <Label htmlFor="build-version">Version</Label>
            <Input
              id="build-version"
              placeholder="3.4.1"
              value={version}
              onChange={(e) => setVersion(e.target.value)}
            />
          </div>
          <div className="flex items-center justify-between gap-4 rounded-md border border-border p-3">
            <Label htmlFor="build-active" className="text-sm">
              Active
            </Label>
            <Switch id="build-active" checked={active} onCheckedChange={setActive} />
          </div>
          <div className="flex flex-col gap-2">
            <Label htmlFor="build-reason">Reason (optional)</Label>
            <Textarea
              id="build-reason"
              rows={2}
              placeholder="Release notes or rationale..."
              value={reason}
              onChange={(e) => setReason(e.target.value)}
            />
          </div>
        </div>

        <DialogFooter>
          <Button variant="outline" onClick={() => setOpen(false)} disabled={saving}>
            Cancel
          </Button>
          <Button onClick={handleSave} disabled={saving}>
            {saving ? <Loader2 className="size-4 animate-spin" /> : null}
            {build ? "Save changes" : "Register build"}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
