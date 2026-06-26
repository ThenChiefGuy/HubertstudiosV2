"use client"

import { Suspense, useRef, useState } from "react"
import Link from "next/link"
import { useRouter, useSearchParams } from "next/navigation"
import { MailCheck, AlertCircle, Loader2 } from "lucide-react"
import { Button } from "@/components/ui/button"
import { verifyCode, ApiError } from "@/lib/api"

const LENGTH = 6

function VerifyForm() {
  const router = useRouter()
  const searchParams = useSearchParams()
  const email = searchParams.get("email") || "your email address"

  const [digits, setDigits] = useState<string[]>(Array(LENGTH).fill(""))
  const [error, setError] = useState("")
  const [loading, setLoading] = useState(false)
  const inputs = useRef<Array<HTMLInputElement | null>>([])

  const code = digits.join("")

  function setDigit(index: number, value: string) {
    const clean = value.replace(/\D/g, "")
    setError("")
    if (clean.length > 1) {
      // Handle paste of full code.
      const chars = clean.slice(0, LENGTH).split("")
      const next = Array(LENGTH).fill("")
      chars.forEach((c, i) => (next[i] = c))
      setDigits(next)
      inputs.current[Math.min(chars.length, LENGTH - 1)]?.focus()
      return
    }
    setDigits((prev) => {
      const next = [...prev]
      next[index] = clean
      return next
    })
    if (clean && index < LENGTH - 1) {
      inputs.current[index + 1]?.focus()
    }
  }

  function handleKeyDown(index: number, e: React.KeyboardEvent<HTMLInputElement>) {
    if (e.key === "Backspace" && !digits[index] && index > 0) {
      inputs.current[index - 1]?.focus()
    }
  }

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    setError("")
    if (code.length < LENGTH) {
      setError("Enter the 6-digit verification code.")
      return
    }
    setLoading(true)
    try {
      await verifyCode(code)
      router.push("/overview")
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Something went wrong. Please try again.")
      setDigits(Array(LENGTH).fill(""))
      inputs.current[0]?.focus()
    } finally {
      setLoading(false)
    }
  }

  return (
    <main className="flex min-h-dvh items-center justify-center bg-background px-4 py-12">
      <div className="w-full max-w-md">
        <div className="mb-6 flex flex-col items-center text-center">
          <div className="mb-4 flex size-14 items-center justify-center rounded-2xl bg-primary/15 text-primary">
            <MailCheck className="size-7" />
          </div>
          <h1 className="text-2xl font-semibold tracking-tight text-foreground">
            Check your email
          </h1>
          <p className="mt-2 max-w-xs text-pretty text-sm leading-relaxed text-muted-foreground">
            We sent a 6-digit verification code to
          </p>
          <p className="mt-0.5 text-sm font-medium text-foreground">{email}</p>
        </div>

        <form onSubmit={handleSubmit} className="flex flex-col gap-5">
          {error ? (
            <div
              role="alert"
              className="flex items-start gap-2 rounded-md border border-destructive/30 bg-destructive/10 px-3 py-2 text-sm text-destructive"
            >
              <AlertCircle className="mt-0.5 size-4 shrink-0" />
              <span>{error}</span>
            </div>
          ) : null}

          <div
            className="flex items-center justify-center gap-2 sm:gap-3"
            role="group"
            aria-label="Verification code"
          >
            {digits.map((digit, i) => (
              <input
                key={i}
                ref={(el) => {
                  inputs.current[i] = el
                }}
                inputMode="numeric"
                autoComplete={i === 0 ? "one-time-code" : "off"}
                maxLength={LENGTH}
                value={digit}
                onChange={(e) => setDigit(i, e.target.value)}
                onKeyDown={(e) => handleKeyDown(i, e)}
                aria-label={`Digit ${i + 1}`}
                className="size-12 rounded-lg border border-input bg-card text-center text-xl font-semibold text-foreground outline-none transition-colors focus:border-primary focus:ring-2 focus:ring-primary/30 sm:size-14"
              />
            ))}
          </div>

          <Button type="submit" className="w-full" disabled={loading}>
            {loading ? (
              <>
                <Loader2 className="size-4 animate-spin" />
                Verifying
              </>
            ) : (
              "Verify and continue"
            )}
          </Button>

          <div className="text-center text-sm text-muted-foreground">
            <Link
              href="/login"
              className="font-medium text-foreground underline-offset-4 transition-colors hover:underline"
            >
              Back to login
            </Link>
          </div>
        </form>
      </div>
    </main>
  )
}

export default function VerifyPage() {
  return (
    <Suspense fallback={null}>
      <VerifyForm />
    </Suspense>
  )
}
