// Mock data + types for the HubertStudios License Manager.
// All data is static placeholder data — no backend logic.
//
// Model notes:
// - Plugins are FREE. Licenses exist purely for security / anti-piracy.
// - Licenses are GLOBAL per plugin (not per-user). Each license is a global
//   key tied to a specific plugin and carries a duration before it expires.

export type PluginMode = "legacy" | "modern"

export interface Plugin {
  id: string
  name: string
  displayName: string
  description: string
  image: string
  active: boolean
  mode: PluginMode
  requireHash: boolean
  licenses: number
  servers: number
}

// A global license key for a specific plugin with a fixed duration.
export interface License {
  id: string
  plugin: string
  key: string
  label: string
  status: "active" | "expired" | "revoked"
  duration: string
  expiry: string | null
  created: string
  servers: number
}

export interface Build {
  id: string
  plugin: string
  hash: string
  version: string
  active: boolean
  created: string
  reason?: string
}

export interface ServerBan {
  id: string
  plugin: string | null
  identifier: string
  reason: string
  until: string | null
  status: "active" | "expired"
}

// A server that currently has one of our plugins installed and validating.
export interface ActiveServer {
  id: string
  address: string
  plugin: string
  version: string
  players: number
  status: "online" | "offline"
  lastSeen: string
  country: string
}

export interface AuditEvent {
  id: string
  time: string
  admin: string
  action: string
  targetType: string
  targetId: string
  ip: string
  details: string
}

export const plugins: Plugin[] = [
  {
    id: "pl_1",
    name: "auth-guard",
    displayName: "AuthGuard",
    description: "Server-side authentication and session protection for networks.",
    image: "/plugin-shield-icon.png",
    active: true,
    mode: "modern",
    requireHash: true,
    licenses: 4,
    servers: 184,
  },
  {
    id: "pl_2",
    name: "anti-cheat",
    displayName: "SentinelAC",
    description: "Heuristic anti-cheat engine with packet inspection.",
    image: "/plugin-radar-icon.png",
    active: true,
    mode: "modern",
    requireHash: true,
    licenses: 2,
    servers: 97,
  },
  {
    id: "pl_3",
    name: "economy-core",
    displayName: "EconomyCore",
    description: "Multi-currency economy framework with vault support.",
    image: "/plugin-coin-icon.png",
    active: true,
    mode: "legacy",
    requireHash: false,
    licenses: 1,
    servers: 56,
  },
  {
    id: "pl_4",
    name: "world-edit-pro",
    displayName: "WorldEditPro",
    description: "Advanced world editing toolkit for builders.",
    image: "/plugin-cube-icon.png",
    active: false,
    mode: "legacy",
    requireHash: false,
    licenses: 1,
    servers: 12,
  },
  {
    id: "pl_5",
    name: "chat-filter",
    displayName: "ChatFilter",
    description: "Configurable chat moderation and spam protection.",
    image: "/plugin-chat-icon.png",
    active: true,
    mode: "modern",
    requireHash: true,
    licenses: 2,
    servers: 73,
  },
  {
    id: "pl_6",
    name: "backup-manager",
    displayName: "BackupManager",
    description: "Scheduled world and database backups.",
    image: "/plugin-archive-icon.png",
    active: true,
    mode: "modern",
    requireHash: false,
    licenses: 1,
    servers: 41,
  },
]

export const licenses: License[] = [
  {
    id: "lic_1",
    plugin: "AuthGuard",
    key: "AUTH-9F2K-7H3M-QX84-LP21",
    label: "Public Global Key",
    status: "active",
    duration: "1 year",
    expiry: "2026-12-01",
    created: "2025-12-01",
    servers: 142,
  },
  {
    id: "lic_2",
    plugin: "SentinelAC",
    key: "SENT-44XB-9M2P-KK10-ZZ09",
    label: "Lifetime Global Key",
    status: "active",
    duration: "Lifetime",
    expiry: null,
    created: "2025-03-22",
    servers: 97,
  },
  {
    id: "lic_3",
    plugin: "EconomyCore",
    key: "ECON-77QA-2BB1-MN55-RT33",
    label: "2024 Season Key",
    status: "expired",
    duration: "1 year",
    expiry: "2025-05-30",
    created: "2024-05-30",
    servers: 0,
  },
  {
    id: "lic_4",
    plugin: "ChatFilter",
    key: "CHAT-1290-XK22-PP01-WW77",
    label: "Public Global Key",
    status: "active",
    duration: "6 months",
    expiry: "2026-08-15",
    created: "2026-02-15",
    servers: 73,
  },
  {
    id: "lic_5",
    plugin: "AuthGuard",
    key: "AUTH-55TT-9091-MK20-DD12",
    label: "Beta Channel Key",
    status: "revoked",
    duration: "30 days",
    expiry: "2026-02-01",
    created: "2026-01-01",
    servers: 0,
  },
  {
    id: "lic_6",
    plugin: "BackupManager",
    key: "BACK-0192-LM33-PP44-QQ88",
    label: "Lifetime Global Key",
    status: "active",
    duration: "Lifetime",
    expiry: null,
    created: "2026-01-01",
    servers: 41,
  },
]

export const builds: Build[] = [
  {
    id: "bld_1",
    plugin: "AuthGuard",
    hash: "a3f5c9e2b1d47f88a0c6e2b4d9f1a3c5e7b9d0f2a4c6e8b0d2f4a6c8e0b2d4f6",
    version: "3.4.1",
    active: true,
    created: "2026-05-02",
  },
  {
    id: "bld_2",
    plugin: "SentinelAC",
    hash: "b4e6da03c2e58099b1d7f3c5e0a2b4d6f8c0e2a4b6d8f0c2e4a6b8d0f2c4e6a8",
    version: "2.9.0",
    active: true,
    created: "2026-04-18",
  },
  {
    id: "bld_3",
    plugin: "ChatFilter",
    hash: "c5f7eb14d3f6910ac2e8a4d6f1b3c5e7a9d1f3b5c7e9a1d3f5b7c9e1a3d5f7b9",
    version: "1.7.3",
    active: true,
    created: "2026-03-29",
  },
  {
    id: "bld_4",
    plugin: "EconomyCore",
    hash: "d6a8fc25e407a21bd3f9b5e7a2c4d6f8b0e2c4a6d8f0b2e4c6a8d0f2b4e6c8a0",
    version: "5.1.2",
    active: false,
    created: "2025-11-11",
    reason: "Superseded by 5.2.0",
  },
]

export const serverBans: ServerBan[] = [
  {
    id: "ban_1",
    plugin: null,
    identifier: "198.51.100.42",
    reason: "License key sharing across multiple networks.",
    until: null,
    status: "active",
  },
  {
    id: "ban_2",
    plugin: "AuthGuard",
    identifier: "cracked.example.net",
    reason: "Tampered JAR hash detected.",
    until: "2026-09-01",
    status: "active",
  },
  {
    id: "ban_3",
    plugin: "SentinelAC",
    identifier: "203.0.113.88",
    reason: "Repeated validation abuse.",
    until: "2026-01-15",
    status: "expired",
  },
  {
    id: "ban_4",
    plugin: null,
    identifier: "leak-host.example.org",
    reason: "Distribution of leaked builds.",
    until: null,
    status: "active",
  },
]

export const activeServers: ActiveServer[] = [
  {
    id: "srv_1",
    address: "play.novanetwork.gg",
    plugin: "AuthGuard",
    version: "3.4.1",
    players: 412,
    status: "online",
    lastSeen: "Just now",
    country: "United States",
  },
  {
    id: "srv_2",
    address: "mc.pixelrealms.io",
    plugin: "SentinelAC",
    version: "2.9.0",
    players: 188,
    status: "online",
    lastSeen: "2 min ago",
    country: "Germany",
  },
  {
    id: "srv_3",
    address: "hub.skyblockkingdoms.net",
    plugin: "ChatFilter",
    version: "1.7.3",
    players: 96,
    status: "online",
    lastSeen: "1 min ago",
    country: "United Kingdom",
  },
  {
    id: "srv_4",
    address: "survival.frostmc.gg",
    plugin: "AuthGuard",
    version: "3.4.0",
    players: 0,
    status: "offline",
    lastSeen: "6 hours ago",
    country: "Canada",
  },
  {
    id: "srv_5",
    address: "eco.crafthub.mc",
    plugin: "BackupManager",
    version: "4.2.0",
    players: 54,
    status: "online",
    lastSeen: "5 min ago",
    country: "France",
  },
  {
    id: "srv_6",
    address: "play.aurora-smp.net",
    plugin: "SentinelAC",
    version: "2.9.0",
    players: 233,
    status: "online",
    lastSeen: "Just now",
    country: "Netherlands",
  },
  {
    id: "srv_7",
    address: "mc.voidcraft.io",
    plugin: "AuthGuard",
    version: "3.4.1",
    players: 71,
    status: "online",
    lastSeen: "3 min ago",
    country: "Australia",
  },
  {
    id: "srv_8",
    address: "legacy.oldschoolmc.org",
    plugin: "EconomyCore",
    version: "5.1.2",
    players: 0,
    status: "offline",
    lastSeen: "2 days ago",
    country: "Brazil",
  },
]

export const auditEvents: AuditEvent[] = [
  {
    id: "evt_1",
    time: "2026-06-20 14:32:07",
    admin: "hubert@hubertstudios.dev",
    action: "license.create",
    targetType: "License",
    targetId: "lic_6",
    ip: "192.0.2.10",
    details: "Created global Lifetime key for BackupManager.",
  },
  {
    id: "evt_2",
    time: "2026-06-20 13:11:55",
    admin: "hubert@hubertstudios.dev",
    action: "server_ban.create",
    targetType: "ServerBan",
    targetId: "ban_4",
    ip: "192.0.2.10",
    details: "Global ban added for leak-host.example.org.",
  },
  {
    id: "evt_3",
    time: "2026-06-20 11:48:20",
    admin: "ops@hubertstudios.dev",
    action: "plugin.update",
    targetType: "Plugin",
    targetId: "pl_2",
    ip: "192.0.2.44",
    details: "Enabled hash validation for SentinelAC.",
  },
  {
    id: "evt_4",
    time: "2026-06-19 22:05:13",
    admin: "hubert@hubertstudios.dev",
    action: "license.revoke",
    targetType: "License",
    targetId: "lic_5",
    ip: "192.0.2.10",
    details: "Revoked Beta Channel key for AuthGuard.",
  },
  {
    id: "evt_5",
    time: "2026-06-19 18:39:41",
    admin: "ops@hubertstudios.dev",
    action: "build.create",
    targetType: "Build",
    targetId: "bld_1",
    ip: "192.0.2.44",
    details: "Registered official build AuthGuard 3.4.1.",
  },
  {
    id: "evt_6",
    time: "2026-06-19 09:14:02",
    admin: "ops@hubertstudios.dev",
    action: "server.flag",
    targetType: "Server",
    targetId: "srv_8",
    ip: "192.0.2.44",
    details: "Flagged legacy.oldschoolmc.org as offline > 48h.",
  },
]

export const adminEmail = "hubert@hubertstudios.dev"
