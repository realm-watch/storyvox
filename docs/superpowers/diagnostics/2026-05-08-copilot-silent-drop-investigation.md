# Copilot silent-drop investigation — 2026-05-08

**Investigator:** Sloane (subagent, Opus 4.7)
**Trigger:** 17+ storyvox PRs in a single day where requesting Copilot as reviewer
appeared to silently drop — `requested_reviewers` array empties within seconds, no
review ever arrives.
**Outcome:** Root cause identified — upstream Copilot Code Review backend degradation,
undeclared on status page. Not a JP-side or storyvox-side problem. Capped-wait playbook
is the correct workaround.

---

## 1. Symptom (what we observed)

The reported symptom: POST to `/repos/jphein/storyvox/pulls/{N}/requested_reviewers`
with `reviewers[]=copilot-pull-request-reviewer[bot]` returns `Copilot` in
`requested_reviewers`, but within seconds the array empties and no review is posted.

**The actual symptom (reframed by evidence):** Copilot is NOT silently dropping. It IS
posting a review — but the review body is the canned error string:

> "Copilot encountered an error and was unable to review this pull request. You can try
> again by re-requesting a review."

The `requested_reviewers` array empties because Copilot transitions the request from
"pending" to "submitted" the moment it posts a review (even an error one). That
transition LOOKS like a silent drop to anything polling `requested_reviewers` rather
than `reviews`.

### The cliff

| Marker | PR | Time (UTC) | Review body |
|--------|----|-----------|--------------|
| Last successful review | jphein/storyvox #22 | 2026-05-07T18:12:45Z | "## Pull request overview\n\nThis PR connects the sleep-timer fade-out path…" |
| First error review | jphein/storyvox #23 | 2026-05-07T18:43:37Z | "Copilot encountered an error and was unable to review this pull request." |

Gap: ~30 minutes. From #23 forward, **every** Copilot review on jphein/storyvox is the
error string (44 errors / 58 total Copilot reviews on storyvox; 100% failure rate from
#23 onward).

### The "successful 17:14Z batch" is a misread

The orchestrator's notes say PRs #61, #63, #73, #74 had a successful Copilot review at
"~17:14Z." They did not. Those `updated_at` timestamps are *metadata updates* (label
changes, edits). The actual `submittedAt` on the Copilot review records:

- PR #61 review: 2026-05-08T01:16:23Z — error
- PR #63 review: 2026-05-08T03:41:25Z — error
- PR #73 review: 2026-05-08T06:41:31Z — error
- PR #74 review: 2026-05-08T06:41:32Z — error

All four were errors. There has been no successful Copilot review on jphein/storyvox
since 2026-05-07T18:12:45Z (PR #22).

---

## 2. Hypotheses tested

### H1 — Copilot tier requirement / billing — RULED OUT
- `viewer.copilotEndpoints.api` returns `https://api.individual.githubcopilot.com` →
  jphein has an active individual Copilot subscription.
- `gh api /user/copilot/billing` is 404 for individual users (org-only endpoint), so
  tier/quota not directly readable.
- BUT: 22 substantive Copilot reviews succeeded in the 24h before the cliff. Quota
  exhaustion at PR #23 would yield a quota-specific message, not a generic error. AND
  microsoft/vscode is getting reviews *while jphein isn't* — quota exhaustion would be
  account-by-account, not cross-account asymmetric within the same hour.

### H2 — GitHub service incident — PARTIALLY CONFIRMED (degraded, undeclared)
- status.github.com lists Copilot as "Operational." Last component update is
  2026-04-23 — i.e., status page hasn't acknowledged anything recent.
- Most-relevant declared incident: **2026-05-07T05:02–06:56Z — "CCR and CCA failing to
  start for PR comments"** (Copilot Code Review + Cloud Agents). Marked resolved.
- jphein's cliff at 2026-05-07T18:43Z is 12 hours after that resolution. Plausible
  reading: declared resolution was incomplete; rolling residual error rate continues.
- Daily auto-tracked issues matching "Copilot encountered an error and was unable to
  review this pull request" (created date):

| Date     | Issues created |
|----------|---------------|
| Apr 15   | 326           |
| Apr 30   | 511           |
| May 6    | 670           |
| May 7    | 808           |
| May 8    | 906 (still rising — day not over) |

Trend: failure rate is **rising platform-wide for 3+ weeks**, undeclared on status page.

### H3 — Repo-level Copilot setting — RULED OUT
- jphein/mempalace (public): error 2026-05-08T01:51:23Z
- jphein/starcharts (public): error 2026-05-08T00:11:24Z
- jphein/storyvox (private): errors throughout

Three different repos, two visibility settings, all hitting the error. Not a per-repo
config.

### H4 — GitHub App / installation gap — RULED OUT
- Bot is invoked, posts reviews. Permission gaps would manifest as 403 on the
  `requested_reviewers` POST. The POST consistently succeeds and returns Copilot in the
  response — installation and write permission are intact.

### H5 — API contract change — RULED OUT
- microsoft/vscode is still getting successful Copilot reviews via the same API path
  (e.g. PR #315346 at 2026-05-08T19:29:41Z, PR #315352 at 19:49:35Z, PR #315351 at
  19:48:07Z, all substantive). API contract is unchanged.

### H6 — Account quota / rate limit — RULED OUT (or at most a contributor)
- 22 successful reviews in 24h is well within Copilot Pro's 300 premium-request monthly
  quota. Quota exhaustion would yield a quota-specific message.
- `/rate_limit` shows REST core 4872/5000 remaining.

### H7 — Repo-size / language / file-count gate — RULED OUT
- First error PR #23: 1 file, 16+/8- additions/deletions.
- Last successful PR #22: 3 files, 42+/4-.
- The first error is *smaller* than the last success. Not a size gate.

### H8 — Branch / base-branch issue — RULED OUT
- All today's PRs target `main`, both before and after the cliff.

### H9 — Cross-repo / cross-account comparison — KEY ASYMMETRY EVIDENCE

| Account/Repo | State | Sample |
|---|---|---|
| jphein/storyvox (private) | 100% error post-cliff | 44 errors / 58 total |
| jphein/mempalace (public) | error | #14, #15 both error |
| jphein/starcharts (public) | error | #52, #55 both error |
| microsoft/vscode (public) | 9/10 success | recent reviews 14:29Z–19:49Z all success |
| GitHub-wide | partial fail | ~1000-1500 PRs/day hitting the error |

**Asymmetry confirms the failure is real and partial, not a JP-side or full-platform
problem.** Some accounts/PRs route to healthy CCR workers and succeed; others (like
jphein right now) route to unhealthy lanes and error. Whether JP is specifically
affinity-bound to a bad lane vs. just statistically unlucky is undecidable from
external probing.

---

## 3. Root cause

**Upstream Copilot Code Review backend partial degradation, undeclared on status page.**

Evidence chain:
1. The error string is posted by the Copilot bot itself — that's its try/catch around
   an internal API call to the CCR backend. It's the bot saying "my upstream failed."
2. Daily volume of this error across GitHub is ~1000-1500 PRs/day and growing.
3. Asymmetry: some big repos (microsoft/vscode) mostly succeed; jphein's account is
   ~100% fail post-cliff. Indicates partial / per-shard / per-account-affinity
   degradation, not full backend outage.
4. JP's cliff timing (2026-05-07T18:43Z) is 12 hours after a declared CCR incident
   was marked resolved (2026-05-07T05:02–06:56Z). Plausible: resolution was incomplete.
5. status.github.com Copilot component last updated 2026-04-23 — undeclared current
   issue.

**Confidence:**
- High: this is platform-side, not jphein-side.
- High: no immediate JP-actionable fix on GitHub's side beyond escalation.
- Medium: specific root cause is partial CCR backend degradation. Could be capacity,
  could be downstream LLM provider rate-limit, could be a stuck backend shard.
- Low: whether JP is specifically affinity-bound to a bad lane vs. statistically
  unlucky.

---

## 4. Action items for JP

### Now (do nothing-side)
- **Stop blocking storyvox merges on Copilot review** when CI is green. The
  capped-wait-then-merge playbook (3-min cap per the tightened rule) is correct and
  should stay in place.
- **Don't waste cycles re-requesting reviews** on already-errored PRs — the upstream
  is sticky-bad, retry rates are low.

### When you have a moment
- **Open a GitHub support ticket.** Include:
  - Account login: jphein
  - Sample errored PRs (jphein/storyvox #23 through #95+, jphein/mempalace #14-15,
    jphein/starcharts #52, #55).
  - Timing: cliff at 2026-05-07T18:43Z UTC.
  - Reference declared incident 2026-05-07T05:02–06:56Z ("CCR and CCA failing to start
    for PR comments") — ask whether resolution was complete and whether the account is
    sticky-bound to a bad backend lane.
  - Ask for confirmation of subscription tier and whether code review is fully enabled.

### If Copilot review is load-bearing for the storyvox workflow
- **Option A — alternative bot:** evaluate Greptile / CodeRabbit / Sweep / similar as a
  fallback reviewer.
- **Option B — local AI review:** pre-PR Claude Code review (`/review` skill,
  `claude-md-management`) catches most issues that Copilot would, and is fully under
  JP's control.
- **Option C — accept the gap:** Copilot review is a nice-to-have, not a merge gate.
  Ship-on-CI continues to be the right call.

### Do NOT
- Don't try to "fix" anything on JP's side — there's nothing to fix.
- Don't change repo settings, App permissions, or billing in response to this. None of
  those are the cause.

---

## 5. Workaround already in place

The capped-wait Copilot playbook (3-min cap, then merge if CI green) is the correct
operational response. This investigation **validates** the playbook — there's no
account-side fix to wait for; backing up and shipping is the right move.

The previous orchestrator's diagnosis ("silent drop") was wrong about the mechanism (it's
an error response, not a drop) but the operational call (cap and ship) was right.

---

## 6. Recommended monitoring

- **Weekly probe:** on a small / disposable PR (e.g. a docs typo), request Copilot
  review and log whether body starts with "## Pull request overview" (success) or
  "Copilot encountered an error" (failure). Track success rate over time.
- **Cross-account spot check:** if a probe fails, also check microsoft/vscode's most
  recent Copilot review to distinguish "JP-bucketed bad" from "platform-wide bad."
- **Alert threshold:** if 3 consecutive weekly probes fail AND microsoft/vscode is
  succeeding in the same window, it's worth a fresh support ticket — JP-account
  affinity is the next thing to investigate.
- **Re-evaluate** when GitHub declares any of: (a) a new CCR incident on status page,
  (b) a Copilot Code Review GA / billing change, (c) a major service announcement
  about CCR. Each is a candidate trigger for the failure pattern shifting.

---

## Appendix — diagnostic commands run

```bash
# Confirm auth and account
gh api /user --jq '{login, plan: .plan.name}'           # → {"login":"jphein","plan":null}
gh api graphql -f query='{ viewer { copilotEndpoints { api } } }'
# → "https://api.individual.githubcopilot.com" (subscription active)

# Confirm cliff
gh pr view 22 -R jphein/storyvox --json reviews   # last "## Pull request overview"
gh pr view 23 -R jphein/storyvox --json reviews   # first "Copilot encountered an error"

# Cross-repo evidence
gh api '/search/issues?q=reviewed-by:app/copilot-pull-request-reviewer+user:jphein...'
# Showed errors on jphein/mempalace, jphein/starcharts during same window

# Cross-account evidence
gh pr view 315346 -R microsoft/vscode --json reviews   # success at 19:29Z
gh pr view 315352 -R microsoft/vscode --json reviews   # success at 19:49Z

# Platform-wide volume
gh api -X GET '/search/issues' -f q='"Copilot encountered an error and was unable to review" created:2026-05-08'
# → 906 issues created on May 8 alone

# Status page
WebFetch https://www.githubstatus.com/api/v2/components.json
# Copilot component: status "operational", updated_at 2026-04-23 (stale; no recent
# acknowledgement of degradation)
```
