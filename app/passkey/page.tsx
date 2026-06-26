"use client"

import { Suspense, useState } from "react"
import Link from "next/link"
import { useRouter, useSearchParams } from "next/navigation"
import { Fingerprint, AlertCircle, Loader2 } from "lucide-react"
import { Button } from "@/components/ui/button"
import {
  fetchPasskeyLoginOptions,
  fetchPasskeyRegisterOptions,
  verifyPasskeyLogin,
  verifyPasskeyRegister,
  ApiError,
} from "@/lib/api"
import { createPasskey, getPasskey } from "@/lib/passkey"

function PasskeyForm() {
  const router = useRouter()
  const searchParams = useSearchParams()
  const mode = searchParams.get("mode") === "register" ? "register" : "login"
  const email = searchParams.get("email") || "your admin account"

  const [error, setError] = useState("")
  const [loading, setLoading] = useState(false)

  const isRegister = mode === "register"

  async function handlePasskey() {
    setError("")
    setLoading(true)
    try {
      if (isRegister) {
        const options = await fetchPasskeyRegisterOptions()
        const credential = await createPasskey(options)
        await verifyPasskeyRegister(credential)
      } else {
        const options = await fetchPasskeyLoginOptions()
        const credential = await getPasskey(options)
        await verifyPasskeyLogin(credential)
      }
      router.push("/overview")
    } catch (err: any) {
      setError(err instanceof ApiError ? err.message : err?.message || "Passkey verification failed. Please retry.")
    } finally {
      setLoading(false)
    }
  }

  return (
    <main className="flex min-h-dvh items-center justify-center bg-background px-4 py-12">
      <div className="w-full max-w-md">
        <div className="mb-6 flex flex-col items-center text-center">
          <div className="mb-4 flex size-14 items-center justify-center rounded-2xl bg-primary/15 text-primary">
            <Fingerprint className="size-7" />
          </div>
          <h1 className="text-2xl font-semibold tracking-tight text-foreground">
            {isRegister ? "Create your passkey" : "Continue with passkey"}
          </h1>
          <p className="mt-2 max-w-sm text-pretty text-sm leading-relaxed text-muted-foreground">
            {isRegister
              ? "This is the first secure second step for your dashboard. Your browser will save a passkey for this website."
              : "Use your Apple, Android, Windows, security key, or password-manager passkey to finish signing in."}
          </p>
          <p className="mt-2 text-sm font-medium text-foreground">{email}</p>
        </div>

        {error ? (
          <div
            role="alert"
            className="mb-4 flex items-start gap-2 rounded-md border border-destructive/30 bg-destructive/10 px-3 py-2 text-sm text-destructive"
          >
            <AlertCircle className="mt-0.5 size-4 shrink-0" />
            <span>{error}</span>
          </div>
        ) : null}

        <div className="flex flex-col gap-3">
          <Button type="button" className="w-full" disabled={loading} onClick={handlePasskey}>
            {loading ? (
              <>
                <Loader2 className="size-4 animate-spin" />
                {isRegister ? "Creating passkey" : "Checking passkey"}
              </>
            ) : isRegister ? (
              "Create passkey"
            ) : (
              "Use passkey"
            )}
          </Button>

          <div className="text-center text-sm text-muted-foreground">
            <Link href="/login" className="font-medium text-foreground underline-offset-4 transition-colors hover:underline">
              Back to login
            </Link>
          </div>
        </div>
      </div>
    </main>
  )
}

export default function PasskeyPage() {
  return (
    <Suspense fallback={null}>
      <PasskeyForm />
    </Suspense>
  )
}
