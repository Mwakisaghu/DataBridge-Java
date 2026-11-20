#!/usr/bin/env node
// DataBridge end-to-end run on the sample data, through the real server and its HTTP API.
//
//   node scripts/e2e-sample-run.mjs --base http://127.0.0.1:8086 --admin-password '<your admin password>'
//
// What it does, as the people who would do it (each a real account with its own role):
//   Run 1 (mock, the error set):  clean -> submit -> 72 findings exactly as the answer key says
//     (21 data errors, unbalanced trial balance, 2 control-account variances, 48 template findings),
//     cleaning removes exactly the 5 auto-fixable errors, a consultant explains the 81,000.00
//     variance, a reviewer is refused, Finance approves; register and reconciliation pack saved.
//   Run 2 (cutover, the clean set): submit -> 48 template findings waived by maker-checker ->
//     reconciliation ties -> import packages (refused for the Transactional workbook, which has no
//     configuration tables) -> imports recorded -> readiness checklist passes -> sign-off requested by
//     the consultant, refused to the consultant, approved by Finance -> certificate, run closed.
// Every check prints PASS or FAIL; the exit code is the number of failures. Files go to e2e-output/.
// Needs Node 18+ and nothing else. Works with sign-in on (default) or off (DATABRIDGE_AUTH=none).

import { readFileSync, writeFileSync, mkdirSync, existsSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { randomBytes } from 'node:crypto'

// ------------------------------------------------------------------ options
const args = process.argv.slice(2)
const opt = (name, env, dflt) => {
  const i = args.indexOf(`--${name}`)
  return i >= 0 ? args[i + 1] : process.env[env] ?? dflt
}
const BASE = opt('base', 'DATABRIDGE_E2E_BASE', 'http://127.0.0.1:8085').replace(/\/$/, '')
const ADMIN_USER = opt('admin-user', 'DATABRIDGE_E2E_ADMIN', 'admin')
const ADMIN_PASSWORD = opt('admin-password', 'DATABRIDGE_E2E_ADMIN_PASSWORD', '')
const HERE = dirname(fileURLToPath(import.meta.url))
const SAMPLES = resolve(opt('samples', 'DATABRIDGE_E2E_SAMPLES', join(HERE, '..', 'samples', 'databridge-sample-data')))
const STAMP = new Date().toISOString().replace(/[-:T]/g, '').slice(4, 14) // MMDDhhmmss
const OUT = resolve(opt('out', 'DATABRIDGE_E2E_OUT', join(process.cwd(), 'e2e-output', STAMP)))

// ------------------------------------------------------------------ reporting
let failures = 0
let passes = 0
const green = (s) => `\x1b[32m${s}\x1b[0m`
const red = (s) => `\x1b[31m${s}\x1b[0m`
const bold = (s) => `\x1b[1m${s}\x1b[0m`
function check(label, ok, detail = '') {
  if (ok) passes++
  else failures++
  console.log(`  ${ok ? green('PASS') : red('FAIL')}  ${label}${detail ? `  (${detail})` : ''}`)
  return ok
}
const expectEq = (label, actual, expected) => check(label, actual === expected, `expected ${expected}, got ${actual}`)
const step = (s) => console.log(`\n${bold(s)}`)

// ------------------------------------------------------------------ HTTP as a person
class Person {
  constructor(name, mode) {
    this.name = name
    this.mode = mode
    this.cookie = null
  }
  headers(extra = {}) {
    const h = { 'X-DataBridge-Client': 'e2e', ...extra }
    if (this.mode === 'none') h['X-DataBridge-User'] = this.name
    else if (this.cookie) h.Cookie = this.cookie
    return h
  }
  async call(method, path, body, { raw = false, binary = false, type } = {}) {
    const init = { method, headers: this.headers() }
    if (body !== undefined && body !== null) {
      if (binary) {
        init.body = body
        init.headers['Content-Type'] = 'application/octet-stream'
      } else {
        init.body = JSON.stringify(body)
        init.headers['Content-Type'] = 'application/json'
      }
    }
    const r = await fetch(BASE + path, init)
    const setCookie = r.headers.getSetCookie ? r.headers.getSetCookie() : [r.headers.get('set-cookie')].filter(Boolean)
    for (const c of setCookie) if (c.startsWith('DB_SESSION=')) this.cookie = c.split(';')[0]
    if (raw) return { status: r.status, headers: r.headers, bytes: Buffer.from(await r.arrayBuffer()) }
    const text = await r.text()
    let json = null
    try {
      json = text ? JSON.parse(text) : null
    } catch {
      json = { raw: text }
    }
    return { status: r.status, body: json, type }
  }
  async ok(method, path, body, opts) {
    const r = await this.call(method, path, body, opts)
    if (r.status >= 400) throw new Error(`${this.name}: ${method} ${path} -> ${r.status} ${JSON.stringify(r.body)}`)
    return opts?.raw ? r : r.body
  }
}

async function signIn(person, password) {
  const r = await person.call('POST', '/api/auth/login', { username: person.name, password })
  if (r.status !== 200) throw new Error(`Sign-in failed for ${person.name}: ${r.status} ${JSON.stringify(r.body)}`)
  return r.body.user
}

/** Creates a user as admin, signs in with the temporary password and sets an own one. */
async function newPerson(admin, mode, name, roles) {
  const p = new Person(name, mode)
  if (mode === 'none') return p
  const issued = await admin.ok('POST', '/api/users', { username: name, displayName: name, roles })
  await signIn(p, issued.temporaryPassword)
  const own = `E2e-${randomBytes(6).toString('hex')}-1`
  await p.ok('POST', '/api/auth/password', { currentPassword: issued.temporaryPassword, newPassword: own })
  return p
}

// ------------------------------------------------------------------ sample data
const FILES = [
  ['Static', 'STATIC'],
  ['HR', 'HR'],
  ['Transactional', 'TRANSACTIONAL'],
]
const samplePath = (set, name) => join(SAMPLES, set, `Sample ${name} Data Templates.xlsx`)
const save = (file, bytes) => {
  writeFileSync(join(OUT, file), bytes)
  return join(OUT, file)
}

/** Submits workbooks (name -> bytes) as one submission; returns the check. */
async function submit(consultant, runId, workbooks) {
  const sub = await consultant.ok('POST', `/api/runs/${runId}/submissions`)
  for (const [name, kind, bytes] of workbooks) {
    await consultant.ok('POST', `/api/submissions/${sub.id}/files?kind=${kind}&fileName=${encodeURIComponent(name)}`, bytes, { binary: true })
  }
  const r = await consultant.call('POST', `/api/submissions/${sub.id}/process`)
  if (r.status !== 201) throw new Error(`Processing failed: ${r.status} ${JSON.stringify(r.body)}`)
  return r.body.check
}

async function allFindings(person, checkId) {
  const out = []
  for (let offset = 0; ; offset += 500) {
    const page = await person.ok('GET', `/api/checks/${checkId}/findings?offset=${offset}&limit=500`)
    out.push(...page.items)
    if (out.length >= page.total || page.items.length === 0) return out
  }
}

const key = (sheet, row, type) => `${sheet}|${row}|${type}`

// ------------------------------------------------------------------ the run
async function main() {
  console.log(bold('DataBridge end-to-end run on the sample data'))
  console.log(`  server   ${BASE}\n  samples  ${SAMPLES}\n  output   ${OUT}`)
  if (!existsSync(samplePath('clean', 'Static'))) throw new Error(`Sample data not found in ${SAMPLES} (use --samples)`)
  mkdirSync(OUT, { recursive: true })
  const answerKey = JSON.parse(readFileSync(join(SAMPLES, 'seeded-errors.json'), 'utf8'))

  step('0. Server and people')
  const health = await fetch(`${BASE}/health`).then((r) => r.json()).catch(() => null)
  if (!check('Server answers /health', health?.status === 'UP' || health?.database === 'UP' || !!health, JSON.stringify(health))) return
  const probe = await new Person('probe', 'local').call('GET', '/api/auth/me')
  const mode = probe.status === 200 && probe.body?.authMode === 'none' ? 'none' : 'local'
  console.log(`  sign-in mode: ${mode}`)
  const admin = new Person(ADMIN_USER, mode)
  if (mode === 'local') {
    if (!ADMIN_PASSWORD) throw new Error('Give the admin password: --admin-password <password> (or DATABRIDGE_E2E_ADMIN_PASSWORD)')
    const me = await signIn(admin, ADMIN_PASSWORD)
    if (me.mustChangePassword) throw new Error('The admin still has a temporary password: sign in once in the UI and change it first')
  }
  const suffix = STAMP.slice(-6)
  const cora = await newPerson(admin, mode, `cora.${suffix}`, ['CONSULTANT'])
  const rita = await newPerson(admin, mode, `rita.${suffix}`, ['REVIEWER'])
  const fiona = await newPerson(admin, mode, `fiona.${suffix}`, ['FINANCE'])
  check('Consultant, reviewer and Finance users created and signed in', true, `${cora.name}, ${rita.name}, ${fiona.name}`)
  if (mode === 'local') {
    const viewerTry = await cora.call('GET', '/api/users')
    expectEq('A consultant cannot see the user list', viewerTry.status, 403)
  }

  const client = await cora.ok('POST', '/api/clients', { code: `E2E${suffix}`, name: `E2E Sample SACCO ${suffix}` })
  const project = await cora.ok('POST', `/api/clients/${client.id}/projects`, { code: 'BC', name: 'Business Central go-live' })
  check('Client and project created', !!project.id, `${client.code} / ${project.code}`)

  // ================================================================ RUN 1: the error set
  step('1. Mock run 1: the workbooks with planted errors')
  const run1 = await cora.ok('POST', `/api/projects/${project.id}/runs`, { type: 'MOCK', cutoffDate: '2026-10-31' })
  const raw = FILES.map(([n, k]) => [`Sample ${n} Data Templates.xlsx`, k, readFileSync(samplePath('with-errors', n))])
  const check1 = await submit(cora, run1.id, raw)
  const f1 = await allFindings(cora, check1.id)
  expectEq('Findings on the error set (48 template + 24 planted)', f1.length, 72)
  const found = new Set(f1.map((f) => key(f.sheet, f.rowNo, f.ruleType)))
  const missing = answerKey.filter((e) => !found.has(key(e.sheet, e.row, e.rule)))
  check('Every planted error in the answer key was found', missing.length === 0,
    missing.length ? `missing: ${missing.map((e) => key(e.sheet, e.row, e.rule)).join(', ')}` : `${answerKey.length} of ${answerKey.length}`)
  const hrOnly = f1.filter((f) => !answerKey.some((e) => key(e.sheet, e.row, e.rule) === key(f.sheet, f.rowNo, f.ruleType)))
  check('Everything else is the HR template\'s phone columns', hrOnly.every((f) => f.sheet === 'Employee Upload'), `${hrOnly.length} findings`)
  check('Finding values are masked (no raw client data)', f1.every((f) => f.value == null || !String(f.value).includes('0712345678')))
  const reg = await cora.ok('GET', `/api/checks/${check1.id}/register.xlsx`, null, { raw: true })
  check('Exception register downloaded', reg.bytes.length > 1000, save('run1-exception-register.xlsx', reg.bytes))

  step('2. Reconciliation of run 1')
  const lines = await cora.ok('GET', `/api/checks/${check1.id}/reconciliation`)
  const variances = Object.fromEntries(lines.filter((l) => l.line.status === 'VARIANCE').map((l) => [l.line.type === 'TB' ? 'TB' : l.line.glAccount, Number(l.line.variance)]))
  expectEq('Reconciliation lines (trial balance + 9 control accounts)', lines.length, 10)
  expectEq('Trial balance out by 1,000.00', variances.TB, 1000)
  expectEq('GL 102110 BOSA loans 81,000.00 below the subledger', variances['102110'], 81000)
  expectEq('GL 201210 FOSA savings 50,000.00 below the subledger', variances['201210'], 50000)
  const bosa = lines.find((l) => l.line.glAccount === '102110')
  await cora.ok('POST', `/api/findings/${bosa.finding.id}/waiver`, { reason: 'Loan BSL000999 written off after cutoff; journal JV-0142 to follow' })
  if (mode === 'local') {
    // With sign-in off everyone holds every role, so this refusal can only be shown with sign-in on.
    const byReviewer = await rita.call('POST', `/api/findings/${bosa.finding.id}/waiver/decision`, { approve: true })
    expectEq('A reviewer cannot approve a variance explanation', byReviewer.status, 403)
  }
  const byFinance = await fiona.call('POST', `/api/findings/${bosa.finding.id}/waiver/decision`, { approve: true, note: 'Agreed with the write-off schedule' })
  expectEq('Finance approves the explanation', byFinance.status, 200)
  const pack = await fiona.ok('GET', `/api/checks/${check1.id}/reconciliation.xlsx`, null, { raw: true })
  check('Reconciliation pack downloaded', pack.bytes.length > 1000, save('run1-reconciliation-pack.xlsx', pack.bytes))

  step('3. Cleaning run 1 and submitting the cleaned files')
  const cleaned = []
  let cells = 0
  let rowsEmptied = 0
  for (const [name, kind, bytes] of raw) {
    const r = await cora.ok('POST', `/api/runs/${run1.id}/clean?kind=${kind}&fileName=${encodeURIComponent(name)}`, bytes, { raw: true, binary: true })
    const jobId = r.headers.get('x-databridge-cleaning-job')
    const job = await cora.ok('GET', `/api/cleaning-jobs/${jobId}`)
    cells += job.job.cells
    rowsEmptied += job.job.rowsEmptied
    const leak = JSON.stringify(job).includes('0712345678')
    check(`${kind}: cleaned, change log holds no values`, !leak, `${job.job.cells} cells, ${job.job.rowsEmptied} duplicate rows`)
    save(`run1-cleaned-${kind.toLowerCase()}.xlsx`, r.bytes)
    cleaned.push([name.replace('.xlsx', ' (cleaned).xlsx'), kind, r.bytes])
  }
  expectEq('Exact duplicate rows emptied', rowsEmptied, 1)
  const check1b = await submit(cora, run1.id, cleaned)
  const f1b = await allFindings(cora, check1b.id)
  const after = new Set(f1b.map((f) => key(f.sheet, f.rowNo, f.ruleType)))
  const fixed = [...found].filter((k) => !after.has(k))
  const fixable = answerKey.filter((e) => e.auto_fixable === 'Yes').map((e) => key(e.sheet, e.row, e.rule))
  check('Cleaning fixed exactly the auto-fixable planted errors', fixed.length === fixable.length && fixable.every((k) => fixed.includes(k)),
    `fixed ${fixed.length}: ${fixed.join(', ')}`)
  check('Cleaning created no new finding', [...after].every((k) => found.has(k)))
  const carried = f1b.find((f) => f.ruleType === 'CONTROL' && f.sheet === 'TRIAL BALANCE' && f.rowNo === 2)
  expectEq('The approved variance explanation carried forward to the new check', carried?.status, 'WAIVED')
  const closed1 = await cora.call('POST', `/api/runs/${run1.id}/close`)
  check('Run 1 closed (its files purged)', closed1.status === 200, String(closed1.status))

  // ================================================================ RUN 2: the clean set
  step('4. Cutover run: the clean workbooks')
  const run2 = await cora.ok('POST', `/api/projects/${project.id}/runs`, { type: 'CUTOVER', cutoffDate: '2026-10-31' })
  const clean = FILES.map(([n, k]) => [`Sample ${n} Data Templates.xlsx`, k, readFileSync(samplePath('clean', n))])
  const check2 = await submit(cora, run2.id, clean)
  const f2 = await allFindings(cora, check2.id)
  expectEq('Findings on the clean set: only the HR template\'s phone columns', f2.length, 48)
  check('All of them on Employee Upload', f2.every((f) => f.sheet === 'Employee Upload'))
  const lines2 = await cora.ok('GET', `/api/checks/${check2.id}/reconciliation`)
  expectEq('Trial balance and all 9 control accounts tie', lines2.filter((l) => l.line.status === 'TIES').length, 10)

  step('5. Waivers for the template findings (maker-checker)')
  let selfRefused = 0
  for (const f of f2) {
    await cora.ok('POST', `/api/findings/${f.id}/waiver`, { reason: 'Template types phone numbers as Integer; Business Central field is Text30' })
  }
  const self = await cora.call('POST', `/api/findings/${f2[0].id}/waiver/decision`, { approve: true })
  if (self.status === 403 || self.status === 400) selfRefused++
  check('The requester cannot approve their own waiver', selfRefused === 1, String(self.status))
  for (const f of f2) await rita.ok('POST', `/api/findings/${f.id}/waiver/decision`, { approve: true, note: 'Template fix logged' })
  const summaryA = await cora.ok('GET', `/api/runs/${run2.id}/summary`)
  expectEq('48 Critical findings waived by the reviewer', summaryA.findings.waivedCritical, 48)

  step('6. Import packages and tracking')
  for (const [name, kind, bytes] of clean) {
    const r = await cora.call('POST', `/api/runs/${run2.id}/package?kind=${kind}&fileName=${encodeURIComponent(name)}`, bytes, { raw: true, binary: true })
    if (kind === 'TRANSACTIONAL') {
      expectEq('Transactional workbook: no configuration tables (opening balances go through journals)', r.status, 400)
    } else {
      check(`${kind} import package built from the validated file`, r.status === 200, save(`run2-package-${kind.toLowerCase()}.xlsx`, r.bytes))
    }
  }
  const wrongFile = await cora.call('POST', `/api/runs/${run2.id}/package?kind=STATIC&fileName=x.xlsx`, raw[0][2], { raw: true, binary: true })
  expectEq('A file other than the validated one is refused', wrongFile.status, 400)
  const imports = await cora.ok('GET', `/api/runs/${run2.id}/imports`)
  for (const r of imports) await cora.ok('PUT', `/api/runs/${run2.id}/imports/${encodeURIComponent(r.sheet)}`, { importedRows: r.expectedRows })
  check('Every packaged table recorded as imported', true, `${imports.length} tables`)

  step('7. Readiness, sign-off and certificate')
  const summary = await cora.ok('GET', `/api/runs/${run2.id}/summary`)
  for (const i of summary.checklist) check(`Readiness: ${i.label}`, i.passed, i.detail)
  await cora.ok('POST', `/api/runs/${run2.id}/signoff`, { note: 'Cutover data complete' })
  const consultantDecides = await cora.call('POST', `/api/runs/${run2.id}/signoff/decision`, { approve: true })
  check('The consultant cannot approve the sign-off', consultantDecides.status === 403 || consultantDecides.status === 400, String(consultantDecides.status))
  const approved = await fiona.ok('POST', `/api/runs/${run2.id}/signoff/decision`, { approve: true, note: 'Balances agreed with the GL' })
  check('Finance approves: certificate issued',
    new RegExp(`^DB-${client.code}-BC-R${run2.runNo}-\\d{8}$`).test(approved.certificateNo || ''), approved.certificateNo)
  const final = await fiona.ok('GET', `/api/runs/${run2.id}/summary`)
  expectEq('Run closed by the sign-off', final.run.state, 'CLOSED')
  const so = await fiona.ok('GET', `/api/runs/${run2.id}/signoff-pack.xlsx`, null, { raw: true })
  check('Sign-off pack downloaded', so.bytes.length > 1000, save('run2-signoff-pack.xlsx', so.bytes))
  const audit = await admin.ok('GET', '/api/audit.xlsx?limit=1000', null, { raw: true })
  check('Audit trail exported', audit.bytes.length > 1000, save('audit-trail.xlsx', audit.bytes))

  console.log(`\n  Certificate page: ${BASE.replace('8085', '5173').replace('8086', '5173')}/reports/certificate?run=${run2.id}`)
  console.log(`  (or ${BASE}/reports/certificate?run=${run2.id} when the server hosts the UI)`)
}

main()
  .catch((e) => {
    failures++
    console.log(`\n${red('STOPPED')}: ${e.message}`)
  })
  .finally(() => {
    console.log(`\n${bold(`${passes} passed, ${failures} failed`)}`)
    process.exit(failures)
  })
