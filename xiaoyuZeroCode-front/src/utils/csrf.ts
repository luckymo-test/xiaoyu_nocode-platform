const CSRF_COOKIE = 'XSRF-TOKEN'
export const CSRF_HEADER = 'X-XSRF-TOKEN'

let bootstrapPromise: Promise<void> | null = null
let csrfSupported: boolean | undefined

export function getCsrfToken(): string | null {
  if (typeof document === 'undefined') return null

  const prefix = `${CSRF_COOKIE}=`
  const cookie = document.cookie
    .split(';')
    .map((item) => item.trim())
    .find((item) => item.startsWith(prefix))

  if (!cookie) return null
  try {
    return decodeURIComponent(cookie.slice(prefix.length))
  } catch {
    return cookie.slice(prefix.length)
  }
}

export async function ensureCsrfToken(apiBaseUrl: string): Promise<string | null> {
  const existing = getCsrfToken()
  if (existing || csrfSupported === false) return existing

  if (!bootstrapPromise) {
    bootstrapPromise = fetch(`${apiBaseUrl}/v1/security/csrf`, {
      credentials: 'include',
      cache: 'no-store',
    })
      .then(async (response) => {
        if (!response.ok) return
        const body = (await response.json()) as {
          data?: { enabled?: boolean }
        }
        csrfSupported = body.data?.enabled === true
      })
      .catch(() => {
        csrfSupported = undefined
      })
      .finally(() => {
        bootstrapPromise = null
      })
  }

  await bootstrapPromise
  return getCsrfToken()
}
