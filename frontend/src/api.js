import { reactive } from 'vue'

/**
 * Tiny API client. The server is the source of truth: the console never hides a
 * button to enforce a rule — every action is posted and the server's verdict shown.
 */
const TOKEN_KEY = 'craft.token'
const ROLE_KEY = 'craft.role'

export const session = reactive({
  token: localStorage.getItem(TOKEN_KEY) || '',
  role: localStorage.getItem(ROLE_KEY) || '',
  user: JSON.parse(localStorage.getItem('craft.user') || 'null'),
  error: ''
})

function saveSession(login) {
  session.token = login.token
  session.role = login.role
  session.user = { id: login.userId, username: login.username, displayName: login.displayName }
  localStorage.setItem(TOKEN_KEY, login.token)
  localStorage.setItem(ROLE_KEY, login.role)
  localStorage.setItem('craft.user', JSON.stringify(session.user))
}

export function logout() {
  session.token = ''
  session.role = ''
  session.user = null
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(ROLE_KEY)
  localStorage.removeItem('craft.user')
}

async function request(method, path, body, headers = {}) {
  const opts = {
    method,
    headers: { 'Content-Type': 'application/json', ...headers }
  }
  if (session.token) opts.headers['X-Auth-Token'] = session.token
  if (body !== undefined) opts.body = JSON.stringify(body)

  const resp = await fetch(path, opts)
  let data = null
  const text = await resp.text()
  if (text) {
    try { data = JSON.parse(text) } catch { data = { raw: text } }
  }
  if (!resp.ok) {
    const err = new Error(data?.message || `${resp.status} ${resp.statusText}`)
    err.code = data?.error
    err.status = resp.status
    err.data = data
    throw err
  }
  return data
}

export const api = {
  async login(username, password) {
    const login = await request('POST', '/api/auth/login', { username, password })
    saveSession(login)
    return login
  },

  // ---- player ----
  catalog: () => request('GET', '/api/player/recipes'),
  preview: (id) => request('GET', `/api/player/recipes/${id}/preview`),
  inventory: () => request('GET', '/api/player/inventory'),
  myCrafts: () => request('GET', '/api/player/crafts'),
  craftDetail: (no) => request('GET', `/api/player/crafts/${no}`),
  myLedger: () => request('GET', '/api/player/ledger?limit=200'),

  preoccupy: (recipeId, key) =>
    request('POST', '/api/player/crafts/preoccupy', { recipeId }, { 'Idempotency-Key': key }),
  commit: (orderNo, key) =>
    request('POST', '/api/player/crafts/commit', { orderNo }, { 'Idempotency-Key': key }),
  cancel: (orderNo, reason) =>
    request('POST', '/api/player/crafts/cancel', { orderNo, reason }),

  // ---- player batch plans ----
  myPlans: () => request('GET', '/api/player/plans'),
  planDetail: (no) => request('GET', `/api/player/plans/${no}`),
  createPlan: (recipeId, count, key) =>
    request('POST', '/api/player/plans', { recipeId, count }, { 'Idempotency-Key': key }),
  cancelPlan: (planNo, reason) =>
    request('POST', '/api/player/plans/cancel', { planNo, reason }),

  // ---- operator batch plans / reconciliation / repair ----
  opPlans: (view = 'all') => request('GET', `/api/operator/plans?view=${view}`),
  opPlanDetail: (no) => request('GET', `/api/operator/plans/${no}`),
  opReconcile: (no) => request('GET', `/api/operator/plans/${no}/reconcile`),
  opRepairs: (planNo) =>
    request('GET', '/api/operator/repairs' + (planNo ? `?planNo=${encodeURIComponent(planNo)}` : '')),
  opRepair: (body, key) =>
    request('POST', '/api/operator/plans/repair', body, { 'Idempotency-Key': key }),

  // ---- operator ----
  opRecipes: () => request('GET', '/api/operator/recipes'),
  createRecipe: (code, name) => request('POST', '/api/operator/recipes', { code, name }),
  newVersion: (recipeId) => request('POST', '/api/operator/recipes/new-version', { recipeId }),
  saveDraft: (versionId, spec) =>
    request('POST', '/api/operator/recipes/draft', { versionId, spec }),
  publish: (recipeId, versionId) =>
    request('POST', '/api/operator/recipes/publish', { recipeId, versionId }),
  closeRecipe: (recipeId, reason) =>
    request('POST', '/api/operator/recipes/close', { recipeId, reason }),
  opCrafts: () => request('GET', '/api/operator/crafts?limit=200'),
  opLedger: (refNo) => request('GET', '/api/operator/ledger' + (refNo ? `?refNo=${encodeURIComponent(refNo)}` : '')),
  revoke: (orderNo) => request('POST', '/api/operator/revokes', { orderNo }),
  revokes: (result) => request('GET', '/api/operator/revokes' + (result ? `?result=${result}` : '')),
  exceptions: () => request('GET', '/api/operator/exceptions'),
  grant: (playerId, itemCode, qty) =>
    request('POST', '/api/operator/inventory/grant', { playerId, itemCode, qty })
}

/** Client-generated idempotency key (crypto.randomUUID, fallback included). */
export function idemKey() {
  if (window.crypto?.randomUUID) return window.crypto.randomUUID()
  return 'k-' + Date.now() + '-' + Math.random().toString(36).slice(2, 10)
}
