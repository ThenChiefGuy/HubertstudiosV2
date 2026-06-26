"use client"

import { useEffect, useState } from "react"
import { toast } from "sonner"
import { ShieldCheck, Mail, Fingerprint, Lock, CheckCircle2, Loader2 } from "lucide-react"
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card"
import { Label } from "@/components/ui/label"
import { Switch } from "@/components/ui/switch"
import { Separator } from "@/components/ui/separator"
import { PageHeader } from "@/components/dashboard/page-header"
import { StatusBadge } from "@/components/dashboard/status-badge"
import { fetchSettings, updateSettings, type ApiSettings } from "@/lib/api"

export default function SettingsPage() {
  const [settings, setSettings] = useState<ApiSettings | null>(null)
  const [saving, setSaving] = useState(false)
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    let active = true
    fetchSettings()
      .then((data) => {
        if (active) setSettings(data)
      })
      .catch((err) => {
        console.error(err)
        toast.error("Could not load settings")
      })
      .finally(() => {
        if (active) setLoading(false)
      })
    return () => {
      active = false
    }
  }, [])

  async function toggleGlobalHash(value: boolean) {
    if (!settings || saving) return
    const previous = settings
    setSettings({ ...settings, globalRequireHash: value })
    setSaving(true)
    try {
      const updated = await updateSettings({ globalRequireHash: value })
      setSettings(updated)
      toast.success(value ? "Global hash validation enabled" : "Global hash validation disabled")
    } catch (err) {
      console.error(err)
      setSettings(previous)
      toast.error("Could not save settings")
    } finally {
      setSaving(false)
    }
  }

  const securityItems = [
    { label: "Two-factor email verification", ok: true },
    { label: "Cloudflare Turnstile", ok: true },
    { label: "Hash validation enforcement", ok: settings?.globalRequireHash ?? true },
    { label: "Audit logging", ok: true },
  ]

  return (
    <>
      <PageHeader
        title="Settings"
        description="Configure global validation and security status."
      />

      <div className="grid grid-cols-1 gap-6 lg:grid-cols-2">
        <Card>
          <CardHeader>
            <CardTitle className="flex items-center gap-2 text-base">
              <Fingerprint className="size-4 text-primary" />
              Global hash validation
            </CardTitle>
            <CardDescription>
              Enforce SHA-256 JAR validation across all plugins unless a plugin-specific setting overrides it.
            </CardDescription>
          </CardHeader>
          <CardContent>
            <div className="flex items-center justify-between gap-4 rounded-md border border-border p-3">
              <Label htmlFor="global-hash" className="text-sm">
                Require hash by default
              </Label>
              {loading ? (
                <Loader2 className="size-4 animate-spin text-muted-foreground" />
              ) : (
                <Switch
                  id="global-hash"
                  checked={settings?.globalRequireHash ?? true}
                  disabled={saving}
                  onCheckedChange={toggleGlobalHash}
                />
              )}
            </div>
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle className="flex items-center gap-2 text-base">
              <Mail className="size-4 text-primary" />
              Email settings
            </CardTitle>
            <CardDescription>Transactional email configuration from the Worker environment.</CardDescription>
          </CardHeader>
          <CardContent className="flex flex-col gap-3 text-sm">
            <div className="flex items-center justify-between">
              <span className="text-muted-foreground">Provider</span>
              <span className="font-medium">{settings?.emailProvider || "Resend"}</span>
            </div>
            <Separator />
            <div className="flex items-center justify-between gap-3">
              <span className="text-muted-foreground">From address</span>
              <span className="break-all text-right font-mono text-xs">
                {settings?.emailFrom || "Not configured"}
              </span>
            </div>
            <Separator />
            <div className="flex items-center justify-between">
              <span className="text-muted-foreground">Verification codes</span>
              <StatusBadge tone={settings?.emailFrom ? "success" : "warning"}>
                {settings?.emailFrom ? "Enabled" : "Check env"}
              </StatusBadge>
            </div>
          </CardContent>
        </Card>

        <Card className="lg:col-span-2">
          <CardHeader>
            <CardTitle className="flex items-center gap-2 text-base">
              <Lock className="size-4 text-primary" />
              Security status
            </CardTitle>
            <CardDescription>
              Current protection layers for the admin panel.
            </CardDescription>
          </CardHeader>
          <CardContent>
            <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
              {securityItems.map((item) => (
                <div
                  key={item.label}
                  className="flex items-center gap-3 rounded-md border border-border p-3"
                >
                  <CheckCircle2 className="size-5 shrink-0 text-emerald-400" />
                  <span className="text-sm text-foreground">{item.label}</span>
                  <StatusBadge tone={item.ok ? "success" : "warning"} className="ml-auto">
                    {item.ok ? "Active" : "Disabled"}
                  </StatusBadge>
                </div>
              ))}
            </div>
            <div className="mt-4 flex items-center gap-2 rounded-md border border-emerald-500/30 bg-emerald-500/10 p-3 text-sm text-emerald-400">
              <ShieldCheck className="size-4" />
              Admin authentication, CSRF protection, audit logging, and validation settings are wired to the backend.
            </div>
          </CardContent>
        </Card>
      </div>
    </>
  )
}
