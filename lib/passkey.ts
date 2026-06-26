export function base64UrlToBytes(value: string): Uint8Array {
  const padding = value.length % 4 ? 4 - (value.length % 4) : 0
  const base64 = value.replace(/-/g, "+").replace(/_/g, "/") + "=".repeat(padding)
  const binary = atob(base64)
  const bytes = new Uint8Array(binary.length)
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i)
  return bytes
}

export function bytesToBase64Url(value: ArrayBuffer | Uint8Array | null): string | null {
  if (!value) return null
  const bytes = value instanceof Uint8Array ? value : new Uint8Array(value)
  let binary = ""
  for (let i = 0; i < bytes.length; i++) binary += String.fromCharCode(bytes[i])
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "")
}

export function decodePublicKeyCreationOptions(publicKey: any): PublicKeyCredentialCreationOptions {
  return {
    ...publicKey,
    challenge: base64UrlToBytes(publicKey.challenge),
    user: {
      ...publicKey.user,
      id: base64UrlToBytes(publicKey.user.id),
    },
    excludeCredentials: publicKey.excludeCredentials?.map((credential: any) => ({
      ...credential,
      id: base64UrlToBytes(credential.id),
    })),
  }
}

export function decodePublicKeyRequestOptions(publicKey: any): PublicKeyCredentialRequestOptions {
  return {
    ...publicKey,
    challenge: base64UrlToBytes(publicKey.challenge),
    allowCredentials: publicKey.allowCredentials?.map((credential: any) => ({
      ...credential,
      id: base64UrlToBytes(credential.id),
    })),
  }
}

export function serializeRegistrationCredential(credential: PublicKeyCredential) {
  const response = credential.response as AuthenticatorAttestationResponse
  return {
    id: credential.id,
    rawId: bytesToBase64Url(credential.rawId),
    type: credential.type,
    response: {
      clientDataJSON: bytesToBase64Url(response.clientDataJSON),
      attestationObject: bytesToBase64Url(response.attestationObject),
    },
  }
}

export function serializeLoginCredential(credential: PublicKeyCredential) {
  const response = credential.response as AuthenticatorAssertionResponse
  return {
    id: credential.id,
    rawId: bytesToBase64Url(credential.rawId),
    type: credential.type,
    response: {
      clientDataJSON: bytesToBase64Url(response.clientDataJSON),
      authenticatorData: bytesToBase64Url(response.authenticatorData),
      signature: bytesToBase64Url(response.signature),
      userHandle: bytesToBase64Url(response.userHandle),
    },
  }
}

export async function createPasskey(publicKey: any) {
  if (!window.PublicKeyCredential || !navigator.credentials?.create) {
    throw new Error("This browser does not support passkeys.")
  }
  const decoded = decodePublicKeyCreationOptions(publicKey)
  const credential = await navigator.credentials.create({ publicKey: decoded })
  if (!credential) throw new Error("Passkey creation was cancelled.")
  return serializeRegistrationCredential(credential as PublicKeyCredential)
}

export async function getPasskey(publicKey: any) {
  if (!window.PublicKeyCredential || !navigator.credentials?.get) {
    throw new Error("This browser does not support passkeys.")
  }
  const decoded = decodePublicKeyRequestOptions(publicKey)
  const credential = await navigator.credentials.get({ publicKey: decoded })
  if (!credential) throw new Error("Passkey verification was cancelled.")
  return serializeLoginCredential(credential as PublicKeyCredential)
}
