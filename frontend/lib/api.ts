// lib/api.ts
//
// Thin fetch wrapper around the HubertStudios License Manager Worker.
//
// Auth model this client follows:
// - The Worker sets an HttpOnly session cookie on successful /api/auth/verify.
//   We never read or store that cookie ourselves — `credentials: "include"` on
//   every request lets the browser attach it automatically.
// - The Worker also returns a `csrfToken` from GET /api/auth/session. We keep
//   that in memory (module-level variable) and attach it as the X-CSRF-Token
//   header on every mutating request (POST/PUT/DELETE), per the Worker's CSRF
//   middleware. It is NOT a secret — it just has to match what the session
//   cookie's value was bound to server-side — so memory storage is fine and
//   simpler than re-reading a cookie.
//
// Configure the Worker's base URL via NEXT_PUBLIC_API_BASE_URL (see .env.example).
// If unset, defaults to same-origin "/" which works when the dashboard and
// Worker are served from the same domain/path.

const API_BASE_URL = (process.env.NEXT_PUBLIC_API_BASE_URL || "").replace(/\/$/, "")

let csrfToken: string | null = null

export class ApiError extends Error {
  status: number
  constructor(message: string, status: number) {
    super(message)
    this.name = "ApiError"
    this.status = status
  }
}

function url(path: string) {
  return `${API_BASE_URL}${path}`
}

async function request<T>(
  path: string,
  options: { method?: string; body?: unknown } = {},
): Promise<T> {
  const method = options.method || "GET"
  const headers: Record<string, string> = {}
  let body: string | undefined

  if (options.body !== undefined) {
    headers["Content-Type"] = "application/json"
    body = JSON.stringify(options.body)
  }

  if (method !== "GET" && csrfToken) {
    headers["X-CSRF-Token"] = csrfToken
  }

  const res = await fetch(url(path), {
    method,
    headers,
    body,
    credentials: "include",
  })

  let data: any = null
  try {
    data = await res.json()
  } catch {
    // No/invalid JSON body — fall through, data stays null.
  }

  if (!res.ok || data?.ok === false) {
    const message = data?.error || `Request failed (${res.status})`
    throw new ApiError(message, res.status)
  }

  return data as T
}

// ---------------- Auth ----------------

export async function login(email: string, password: string, turnstileToken: string) {
  return request<{ ok: true; step: "verify" }>("/api/auth/login", {
    method: "POST",
    body: { email, password, turnstileToken },
  })
}

export async function verifyCode(code: string) {
  const result = await request<{ ok: true }>("/api/auth/verify", {
    method: "POST",
    body: { code },
  })
  // Fetch the session immediately after verifying so we have the CSRF token
  // for subsequent admin requests in this tab.
  await getSession()
  return result
}

export async function logout() {
  const result = await request<{ ok: true }>("/api/auth/logout", { method: "POST" })
  csrfToken = null
  return result
}

export async function getSession() {
  try {
    const data = await request<{ ok: true; email: string; csrfToken: string }>("/api/auth/session")
    csrfToken = data.csrfToken
    return data
  } catch {
    csrfToken = null
    return null
  }
}

// ---------------- Types matching the Worker's API shapes ----------------

export interface ApiPlugin {
  id: string
  name: string
  displayName: string
  description: string
  image: string | null
  active: boolean
  mode: "legacy" | "modern"
  requireHash: boolean
  licenses: number
  servers: number
}

export interface ApiLicense {
  id: string
  plugin: string
  productId: string
  key: string
  label: string
  status: "active" | "expired" | "revoked"
  duration: string
  expiry: string | null
  created: string
  servers: number
  notes: string
}

export interface ApiBuild {
  id: string
  plugin: string
  productId: string
  hash: string
  version: string
  active: boolean
  created: string
  reason?: string
}

export interface ApiBan {
  id: string
  plugin: string | null
  productId: string | null
  identifier: string
  reason: string
  until: string | null
  status: "active" | "expired"
}

export interface ApiActiveServer {
  id: string
  address: string
  plugin: string
  version: string
  players: number
  status: "online" | "offline"
  lastSeen: string
  country: string
}

export interface ApiAuditEvent {
  id: string
  time: string
  admin: string
  action: string
  targetType: string
  targetId: string
  ip: string
  details: string
}

export interface ApiOverviewStats {
  totalPlugins: number
  activePlugins: number
  totalLicenses: number
  activeLicenses: number
  onlineServers: number
  totalServersSeen: number
}

export interface ApiSettings {
  globalRequireHash: boolean
  emailProvider: string
  emailFrom: string | null
}

// ---------------- Overview ----------------

export async function fetchOverview() {
  const data = await request<{ ok: true; stats: ApiOverviewStats }>("/api/admin/overview")
  return data.stats
}

// ---------------- Plugins ----------------

export async function fetchPlugins() {
  const data = await request<{ ok: true; products: ApiPlugin[] }>("/api/admin/products")
  return data.products
}

export interface PluginInput {
  name: string
  displayName: string
  description: string
  image?: string | null
  active: boolean
  mode: "legacy" | "modern"
  requireHash: boolean
}

export async function createPlugin(input: PluginInput) {
  const data = await request<{ ok: true; product: ApiPlugin }>("/api/admin/products", {
    method: "POST",
    body: input,
  })
  return data.product
}

export async function updatePlugin(id: string, input: Partial<PluginInput>) {
  const data = await request<{ ok: true; product: ApiPlugin }>(`/api/admin/products/${id}`, {
    method: "PUT",
    body: input,
  })
  return data.product
}

export async function deletePlugin(id: string) {
  return request<{ ok: true }>(`/api/admin/products/${id}`, { method: "DELETE" })
}

// ---------------- Licenses ----------------

export async function fetchLicenses() {
  const data = await request<{ ok: true; licenses: ApiLicense[] }>("/api/admin/licenses")
  return data.licenses
}

export interface LicenseInput {
  productId: string
  key?: string
  label: string
  duration: string
  active: boolean
  notes?: string
}

export async function createLicense(input: LicenseInput) {
  const data = await request<{ ok: true; license: ApiLicense }>("/api/admin/licenses", {
    method: "POST",
    body: input,
  })
  return data.license
}

export async function updateLicense(id: string, input: Partial<LicenseInput>) {
  const data = await request<{ ok: true; license: ApiLicense }>(`/api/admin/licenses/${id}`, {
    method: "PUT",
    body: input,
  })
  return data.license
}

export async function deleteLicense(id: string) {
  return request<{ ok: true }>(`/api/admin/licenses/${id}`, { method: "DELETE" })
}

// ---------------- Builds ----------------

export async function fetchBuilds() {
  const data = await request<{ ok: true; builds: ApiBuild[] }>("/api/admin/builds")
  return data.builds
}

export interface BuildInput {
  productId: string
  hash: string
  version: string
  active: boolean
  reason?: string
}

export async function createBuild(input: BuildInput) {
  const data = await request<{ ok: true; build: ApiBuild }>("/api/admin/builds", {
    method: "POST",
    body: input,
  })
  return data.build
}

export async function updateBuild(id: string, input: Partial<BuildInput>) {
  const data = await request<{ ok: true; build: ApiBuild }>(`/api/admin/builds/${id}`, {
    method: "PUT",
    body: input,
  })
  return data.build
}

export async function deleteBuild(id: string) {
  return request<{ ok: true }>(`/api/admin/builds/${id}`, { method: "DELETE" })
}

// ---------------- Server bans ----------------

export async function fetchBans() {
  const data = await request<{ ok: true; bans: ApiBan[] }>("/api/admin/bans")
  return data.bans
}

export interface BanInput {
  productId?: string | null
  identifier: string
  reason: string
  permanent: boolean
  duration?: string
  until?: string
  active?: boolean
}

export async function createBan(input: BanInput) {
  const data = await request<{ ok: true; ban: ApiBan }>("/api/admin/bans", {
    method: "POST",
    body: input,
  })
  return data.ban
}

export async function updateBan(id: string, input: Partial<BanInput>) {
  const data = await request<{ ok: true; ban: ApiBan }>(`/api/admin/bans/${id}`, {
    method: "PUT",
    body: input,
  })
  return data.ban
}

export async function deleteBan(id: string) {
  return request<{ ok: true }>(`/api/admin/bans/${id}`, { method: "DELETE" })
}

// ---------------- Active servers (read-only) ----------------

export async function fetchActiveServers() {
  const data = await request<{ ok: true; servers: ApiActiveServer[] }>("/api/admin/active-servers")
  return data.servers
}

// ---------------- Audit log (read-only) ----------------

export async function fetchAuditLog() {
  const data = await request<{ ok: true; events: ApiAuditEvent[] }>("/api/admin/audit-log")
  return data.events
}

// ---------------- Settings ----------------

export async function fetchSettings() {
  const data = await request<{ ok: true; settings: ApiSettings }>("/api/admin/settings")
  return data.settings
}

export async function updateSettings(input: Pick<ApiSettings, "globalRequireHash">) {
  const data = await request<{ ok: true; settings: ApiSettings }>("/api/admin/settings", {
    method: "PUT",
    body: input,
  })
  return data.settings
}

