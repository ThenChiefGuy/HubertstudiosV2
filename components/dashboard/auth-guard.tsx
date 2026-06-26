"use client"

import { createContext, useContext, useEffect, useState, type ReactNode } from "react"
import { useRouter } from "next/navigation"
import { Loader2 } from "lucide-react"
import { getSession } from "@/lib/api"

interface AdminSession {
  email: string
}

const AdminSessionContext = createContext<AdminSession | null>(null)

export function useAdminSession() {
  return useContext(AdminSessionContext)
}

/**
 * Wraps the dashboard. Checks GET /api/auth/session on mount; if there's no
 * valid session, redirects to /login. While the check is in flight, shows a
 * loading state instead of flashing the dashboard's contents.
 */
export function AuthGuard({ children }: { children: ReactNode }) {
  const router = useRouter()
  const [session, setSession] = useState<AdminSession | null>(null)
  const [checked, setChecked] = useState(false)

  useEffect(() => {
    let cancelled = false
    getSession().then((data) => {
      if (cancelled) return
      if (!data) {
        router.replace("/login")
        return
      }
      setSession({ email: data.email })
      setChecked(true)
    })
    return () => {
      cancelled = true
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  if (!checked || !session) {
    return (
      <div className="flex h-dvh items-center justify-center bg-background">
        <Loader2 className="size-6 animate-spin text-muted-foreground" />
      </div>
    )
  }

  return (
    <AdminSessionContext.Provider value={session}>{children}</AdminSessionContext.Provider>
  )
}
