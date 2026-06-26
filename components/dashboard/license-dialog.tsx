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
  createLicense,
  updateLicense,
  type ApiLicense,
  type ApiPlugin,
} from "@/lib/api"

export function LicenseDialog({
  trigger,
  license,
  plugins,
  onSaved,
}: {
  trigger: ReactNode
  license?: ApiLicense
  plugins: ApiPlugin[]
  onSaved?: (license: ApiLicense) => void
}) {
  const [open, setOpen] = useState(false)
  const [productId, setProductId] = useState("")
  const [label, setLabel] = useState("")
  const [key, setKey] = useState("")
  const [duration, setDuration] = useState("Lifetime")
  const [active, setActive] = useState(true)
  const [notes, setNotes] = useState("")
  const [saving, setSaving] = useState(false)

  useEffect(() => {
    if (open) {
      setProductId(license?.productId ?? plugins[0]?.id ?? "")
      setLabel(license?.label ?? "Global Key")
      setKey(license?.key ?? "")
      setDuration(license?.duration ?? "Lifetime")
      setActive(license ? license.status === "active" : true)
      setNotes(license?.notes ?? "")
      setSaving(false)
    }
  }, [open, license, plugins])

  async function handleSave() {
    if (!productId) {
      toast.error("Select a plugin first")
      return
    }
    if (!label.trim()) {
      toast.error("Label is required")
      return
    }

    setSaving(true)
    try {
      const payload = {
        productId,
        key: key.trim() || undefined,
        label: label.trim(),
        duration,
        active,
        notes,
      }
      const saved = license
        ? await updateLicense(license.id, payload)
        : await createLicense(payload)
      onSaved?.(saved)
      toast.success(license ? "License updated" : "License created")
      setOpen(false)
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : "Could not save license")
    } finally {
      setSaving(false)
    }
  }

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      {trigger}
      <DialogContent className="max-h-[90dvh] overflow-y-auto sm:max-w-lg">
        <DialogHeader>
          <DialogTitle>
            {license ? "Edit global license" : "Add global license"}
          </DialogTitle>
          <DialogDescription>
            Plugins are free — a global key secures a specific plugin for a fixed
            duration. The same key is shared across every server running it.
          </DialogDescription>
        </DialogHeader>

        <div className="grid grid-cols-1 gap-4 py-2 sm:grid-cols-2">
          <div className="flex flex-col gap-2">
            <Label>Plugin</Label>
            <Select value={productId} onValueChange={setProductId} disabled={!!license}>
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
            <Label htmlFor="lic-label">Label</Label>
            <Input
              id="lic-label"
              placeholder="Public Global Key"
              value={label}
              onChange={(e) => setLabel(e.target.value)}
            />
          </div>
          <div className="flex flex-col gap-2 sm:col-span-2">
            <Label htmlFor="lic-key">Global license key</Label>
            <Input
              id="lic-key"
              placeholder="Leave empty to generate automatically"
              value={key}
              disabled={!!license}
              onChange={(e) => setKey(e.target.value)}
              className="font-mono"
            />
            {license ? (
              <p className="text-xs text-muted-foreground">
                Existing license keys are immutable; create a new license to change the key.
              </p>
            ) : null}
          </div>
          <div className="flex flex-col gap-2 sm:col-span-2">
            <Label>Duration</Label>
            <Select value={duration} onValueChange={setDuration}>
              <SelectTrigger>
                <SelectValue placeholder="Select duration" />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="30 days">30 days</SelectItem>
                <SelectItem value="3 months">3 months</SelectItem>
                <SelectItem value="6 months">6 months</SelectItem>
                <SelectItem value="1 year">1 year</SelectItem>
                <SelectItem value="Lifetime">Lifetime</SelectItem>
              </SelectContent>
            </Select>
            <p className="text-xs text-muted-foreground">
              Expiry is calculated automatically from the duration. Choose
              Lifetime for a key that never expires.
            </p>
          </div>
          <div className="flex items-center justify-between gap-4 rounded-md border border-border p-3 sm:col-span-2">
            <Label htmlFor="lic-active" className="text-sm">
              Active
            </Label>
            <Switch id="lic-active" checked={active} onCheckedChange={setActive} />
          </div>
          <div className="flex flex-col gap-2 sm:col-span-2">
            <Label htmlFor="lic-reason">Notes (optional)</Label>
            <Textarea
              id="lic-reason"
              rows={2}
              placeholder="Notes about this license..."
              value={notes}
              onChange={(e) => setNotes(e.target.value)}
            />
          </div>
        </div>

        <DialogFooter>
          <Button variant="outline" onClick={() => setOpen(false)} disabled={saving}>
            Cancel
          </Button>
          <Button onClick={handleSave} disabled={saving}>
            {saving ? <Loader2 className="size-4 animate-spin" /> : null}
            {license ? "Save changes" : "Create license"}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
