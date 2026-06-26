"use client"

import { useState } from "react"
import { useRouter } from "next/navigation"
import { ShieldCheck, AlertCircle, Loader2 } from "lucide-react"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { TurnstileWidget } from "@/components/dashboard/turnstile-widget"
import { login, ApiError } from "@/lib/api"

export default function LoginPage() {
  const router = useRouter()
  const [email, setEmail] = useState("")
  const [password, setPassword] = useState("")
  const [turnstileToken, setTurnstileToken] = useState<string | null>(null)
  const [error, setError] = useState("")
  const [loading, setLoading] = useState(false)

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    setError("")

    if (!email || !password) {
      setError("Please enter both email and password.")
      return
    }
    if (!turnstileToken) {
      setError("Please complete the security check.")
      return
    }

    setLoading(true)
    try {
      const result = await login(email, password, turnstileToken)
      if (result.step === "verify") {
        router.push(`/verify?email=${encodeURIComponent(email)}`)
        return
      }
      if (result.step === "passkey") {
        router.push(`/passkey?mode=login&email=${encodeURIComponent(email)}`)
        return
      }
      if (result.step === "register-passkey") {
        router.push(`/passkey?mode=register&email=${encodeURIComponent(email)}`)
        return
      }
      router.push("/overview")
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Something went wrong. Please try again.")
      setTurnstileToken(null) // Turnstile tokens are single-use — force a fresh widget render on retry.
    } finally {
      setLoading(false)
    }
  }

  return (
    <main className="flex min-h-dvh items-center justify-center bg-background px-4 py-12">
      <div className="w-full max-w-sm">
        <div className="mb-8 flex flex-col items-center text-center">
          <div className="mb-3 flex size-12 items-center justify-center rounded-xl bg-primary text-primary-foreground">
            <ShieldCheck className="size-6" />
          </div>
          <h1 className="text-xl font-semibold tracking-tight text-foreground">
            HubertStudios
          </h1>
          <p className="text-sm text-muted-foreground">License Manager</p>
        </div>

        <div className="mb-6">
          <h1 className="text-2xl font-semibold tracking-tight text-foreground">
            Sign in
          </h1>
        </div>

        <form onSubmit={handleSubmit} className="flex flex-col gap-4">
          {error ? (
            <div
              role="alert"
              className="flex items-start gap-2 rounded-md border border-destructive/30 bg-destructive/10 px-3 py-2 text-sm text-destructive"
            >
              <AlertCircle className="mt-0.5 size-4 shrink-0" />
              <span>{error}</span>
            </div>
          ) : null}

          <div className="flex flex-col gap-2">
            <Label htmlFor="email">Email</Label>
            <Input
              id="email"
              type="email"
              autoComplete="email"
              placeholder="admin@hubertstudios.dev"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
            />
          </div>

          <div className="flex flex-col gap-2">
            <Label htmlFor="password">Password</Label>
            <Input
              id="password"
              type="password"
              autoComplete="current-password"
              placeholder="••••••••••••"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
            />
          </div>

          <TurnstileWidget
            onVerify={(token) => setTurnstileToken(token)}
            onExpire={() => setTurnstileToken(null)}
          />

          <Button type="submit" className="w-full" disabled={loading}>
            {loading ? (
              <>
                <Loader2 className="size-4 animate-spin" />
                Signing in
              </>
            ) : (
              "Login"
            )}
          </Button>
        </form>
      </div>
    </main>
  )
}
