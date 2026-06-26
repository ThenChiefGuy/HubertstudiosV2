"use client"

import Link from "next/link"
import { usePathname } from "next/navigation"
import {
  LayoutDashboard,
  Boxes,
  KeyRound,
  Hammer,
  ShieldBan,
  Server,
  ScrollText,
  Settings,
  ShieldCheck,
} from "lucide-react"
import { cn } from "@/lib/utils"

const navItems = [
  { label: "Overview", href: "/overview", icon: LayoutDashboard },
  { label: "Plugins", href: "/plugins", icon: Boxes },
  { label: "Licenses", href: "/licenses", icon: KeyRound },
  { label: "Builds", href: "/builds", icon: Hammer },
  { label: "Server Bans", href: "/server-bans", icon: ShieldBan },
  { label: "Active Servers", href: "/active-servers", icon: Server },
  { label: "Audit Log", href: "/audit-log", icon: ScrollText },
  { label: "Settings", href: "/settings", icon: Settings },
]

export function Sidebar() {
  const pathname = usePathname()

  return (
    <aside className="hidden w-60 shrink-0 flex-col border-r border-sidebar-border bg-sidebar md:flex">
      <div className="flex h-16 items-center gap-2.5 border-b border-sidebar-border px-5">
        <div className="flex size-8 items-center justify-center rounded-md bg-primary text-primary-foreground">
          <ShieldCheck className="size-5" />
        </div>
        <div className="leading-tight">
          <p className="text-sm font-semibold text-sidebar-foreground">HubertStudios</p>
          <p className="text-xs text-muted-foreground">License Manager</p>
        </div>
      </div>

      <nav className="flex-1 overflow-y-auto p-3">
        <p className="px-2 pb-2 pt-1 text-xs font-medium uppercase tracking-wider text-muted-foreground">
          Management
        </p>
        <ul className="flex flex-col gap-0.5">
          {navItems.map((item) => {
            const active = pathname === item.href
            const Icon = item.icon
            return (
              <li key={item.href}>
                <Link
                  href={item.href}
                  className={cn(
                    "flex items-center gap-3 rounded-md px-2.5 py-2 text-sm font-medium transition-colors",
                    active
                      ? "bg-sidebar-accent text-sidebar-foreground"
                      : "text-muted-foreground hover:bg-sidebar-accent/60 hover:text-sidebar-foreground",
                  )}
                >
                  <Icon
                    className={cn(
                      "size-4 shrink-0",
                      active ? "text-primary" : "text-muted-foreground",
                    )}
                  />
                  {item.label}
                </Link>
              </li>
            )
          })}
        </ul>
      </nav>

      <div className="border-t border-sidebar-border p-4">
        <div className="rounded-md bg-sidebar-accent/50 p-3 text-xs text-muted-foreground">
          <p className="font-medium text-sidebar-foreground">Edge node: EU-West</p>
          <p className="mt-0.5">Validation latency 38ms</p>
        </div>
      </div>
    </aside>
  )
}
