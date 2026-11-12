# GMF Tool Control: RFID Handheld Developer Handover

Nov 10, 2026 · @Dandeats

## Purpose and summary

The handheld is an Android app for GMF tool-crib staff that finds a missing tool and runs drawer checks by reading UHF RFID tags. It talks only to the RFID **Site Gateway** handheld API; its results reach the GMF tracking app through the Site Gateway's signed webhook, never by calling GMF directly.

This document is for the developer building that app. It explains the whole tool-control system around it (GMF's Tools Management System, the GMF tracking app, the exit gate), the API the app uses, the identifiers and privacy rules it must respect, and how to test it end to end.

What the app must do, in one line each:

- **Find a tool**: search by tool code or EPC, then walk toward it with a warmer/colder signal (RSSI homing).
- **Drawer check**: open the drawer count GMF started (or start one), read the drawer, submit, and show the result (for example 86/87 with the missing tool named).
- **Inventory / stock opname**: count an area against its expected list.
- **Work without the final device**: the reader sits behind an interface with a simulator, because the handheld model is not chosen yet.

## System overview

GMF's TMS decides who may borrow a tool, the RFID Site Gateway enforces it at the crib exit and runs the handheld, and the GMF tracking app links the two and keeps each tool's history.

&#91;embedded content: tool control architecture · 6 parts\]

The handheld never calls the GMF app and never writes GMF data; GMF never talks to the handheld. Everything the handheld produces reaches GMF as a Site Gateway webhook event.

| Part | Owner | Role | Talks to |
| --- | --- | --- | --- |
| GMF TMS | GMF AeroAsia | Official loan record (who borrowed which tool, under which work order). No API given yet. | GMF tracking app, later |
| GMF tracking app (gmf-tracking.taggiot.com) | Taggiot GMF team, repo `taggiot-dashboard` | Tool registry with EPC and drawer, loans and hand-overs, per-tool timeline (BLE zones + gate passes), drawer check, missing-tool search, gate board | Site Gateway: API key calls out, signed webhook in |
| RFID Site Gateway (demo: rfid.taggiot.com) | Taggiot RFID team, repo `taggiot-rfid` | Tag master, pass/alarm verdict per gate, handheld API, counts, webhook outbox | Gate, handheld, GMF |
| Gate P1 + Edge Agent | Taggiot RFID team | UHF reader at the exit, S1/S2 beams for direction, Raspberry Pi agent | Site Gateway (MQTT over mTLS), signal tower |
| Handheld app | You | Finder, drawer check, counts | Site Gateway handheld API only |
| PATLITE LR5 tower | Taggiot RFID team | Red / yellow / green + buzzer at the exit | Edge Agent only (exclusive connection) |

## Handheld screens and use cases

The app needs seven screens; drawer check is the one GMF depends on most, and binding tool tags is deliberately NOT a handheld job in the GMF deployment.

| Screen | Who (operator role) | Site Gateway calls | What it does |
| --- | --- | --- | --- |
| Pair device (once) | Site admin | `POST /handheld/pair` | Admin registers the handheld and gets an 8-character pairing code (15 min, single use); the app exchanges it for a device key and keeps that key in the Android Keystore. |
| Sign in | Any operator | `POST /handheld/login` with header `X-Handheld-Key`, then `/handheld/me` | Nomor Operator (3 to 10 digits) + 6-digit PIN. Token `tgh_…` lives 12 h; one session per device. Refuse plain HTTP. |
| Home | Any | `POST /handheld/me` | Operator name and role, device name, token expiry; buttons shown by role. |
| Find tool | viewer and up | `/handheld/finder/epc`, `/finder/lookup` (and `/finder/products`, `/finder/sku` where tools carry a SKU) | Search a tool, then walk toward it: reader filtered to the target EPC, RSSI as a warmer/colder bar and beep. Scanned EPCs resolve in batches of up to 200. |
| Drawer check | operator reads; supervisor reviews and closes | `/handheld/counts/list`, `/counts/get`, `/counts/reads`, `/counts/review`, `/counts/differences`, `/counts/close` | Pick the open check for a drawer (GMF usually created it), read the drawer with `area` = drawer code, see the running tally, then a supervisor reviews: missing, extra, unknown. |
| Inventory count | operator and up | `/handheld/counts/create` (`kind: inventory`), `/counts/reads`, `/counts/close` | Free tally of any area (free-text area label), no expected list. |
| Change PIN | Any | `POST /handheld/pin` | Old and new PIN; trivial PINs refused. |

Rules that shape the screens:

- **No tool-tag binding on the handheld.** In GMF, a tool's EPC is bound in the GMF app (Add tool, Bind, Replace), which registers it at the Site Gateway with the tool code as `item_ref`. Binding from the handheld (`/handheld/items/bind`) would create a tag GMF does not know. Hide bind and replace for the GMF site, or show them read-only.
- **The handheld can still help binding** as a plain reader: show the EPC it just read in large text with a copy button. On the PC, GMF's Add tool form takes typed EPCs or a USB desk reader in keyboard mode.
- **Offline:** queue read batches with their `batch_id` and send them when Wi-Fi returns; set order does not matter. On `401 unauthenticated` (token expired), sign in again and resend with the same ids.
- **Review is a supervisor step.** The review (`/counts/review`) is what produces the drawer-check result GMF shows (the `count_review` event).

## Site Gateway handheld API

Every handheld call is `POST https://<site gateway>/api/v1/handheld/...` with a JSON body (`{}` when empty); the spec of record is `api/site/openapi.yaml` in the `taggiot-rfid` repo, and the design notes are `docs/16-handheld-api.md` there.

**Auth and headers**

- Pair once: `POST /handheld/pair {"pairing_code":"AB12CD34"}` → `{"device_key":"…"}` (shown once; store in the Android Keystore).
- Sign in: `POST /handheld/login` with header `X-Handheld-Key: <device_key>` and body `{"operator_no":"0042","pin":"482915"}` → `{"token":"tgh_…","expires_at":"…","operator":{no, name, role}}`.
- Every other call: `Authorization: Bearer tgh_…`. The token works only under `/api/v1/handheld/`; it is refused everywhere else.
- `Idempotency-Key` (8 to 128 characters) is required on bind and replace; read uploads use `batch_id` instead.
- HTTPS only; the app must refuse to send a PIN over plain HTTP.

**Routes the GMF build uses**

| Route | Body (main fields) | Answer | Role |
| --- | --- | --- | --- |
| `/handheld/me` | `{}` | operator, handheld, `expires_at` | any |
| `/handheld/logout` | `{}` | 200 | any |
| `/handheld/pin` | old and new PIN | 200 | any |
| `/handheld/finder/epc` | `epc` | `epc`, `known`, `sku`, `name`, `serial`, `item_state`, `tag_status` | viewer+ |
| `/handheld/finder/lookup` | `epcs[]` (1 to 200) | `items[]` of the same shape | viewer+ |
| `/handheld/finder/products`, `/finder/sku` | `q` / `sku`, `cursor`, `limit` | products / a product's EPCs, paged | viewer+ |
| `/handheld/counts/list` | `kind?`, `status?`, `cursor?`, `limit?` (≤ 200) | sessions, `next_cursor` | operator+ |
| `/handheld/counts/get` | `id` | session with `area`, `tally` (`distinct_epcs`, `by_sku`, `by_area`) | operator+ |
| `/handheld/counts/create` | `kind` (`inventory` or `opname`), `area?`, `expected_source?`, `lines?` | session (201) | operator+; opname needs supervisor |
| `/handheld/counts/reads` | `id`, `batch_id` (UUID), `area?`, `reads[]` (≤ 1000, each `epc`, `seen_at?`) | `accepted`, `duplicates`, `replayed`, `tally` | operator+ |
| `/handheld/counts/review` | `id` | session + differences `by_kind` | supervisor+ |
| `/handheld/counts/differences` | `id`, `kind?`, `resolution?`, paging | `missing`, `extra`, `unknown`, `short`, `over`, `area_conflict` rows | supervisor+ |
| `/handheld/counts/resolve` | `id`, `difference_id`, `resolution` (`accepted`, `recount`, `open`), `comment` | the difference | supervisor+ |
| `/handheld/counts/reopen` | `id` | session back to `open` | supervisor+ |
| `/handheld/counts/close`, `/counts/cancel` | `id`, `comment` (3 to 500) | session | creator or supervisor+ |

Not for the GMF site: `/handheld/items/bind` and `/handheld/items/replace` (tool tags are bound in the GMF app).

**Count states:** `open` (reads accepted) → `review` (differences computed, counting stops) → `closed`; `review` → `open` by reopen; `open` or `review` → `cancelled`. A read for a count that is not `open` answers `409 count_state`, except a replayed `batch_id`, which returns its first answer with `replayed: true`.

**Errors** use one envelope: `{"error":{"code","message","request_id","details":[{path, code, message}]}}`.

| Code | Status | When |
| --- | --- | --- |
| `validation_failed` | 400 | bad body; `details[].path` is a JSON pointer |
| `unauthenticated` | 401 | no or expired token: sign in again, resend with the same ids |
| `invalid_credentials` | 401 | wrong PIN or unknown operator (same answer for both) |
| `forbidden` | 403 | role too low, or unknown/disabled handheld |
| `account_disabled` | 423 | operator disabled (only with the right PIN) |
| `rate_limited` | 429 | five wrong PINs lock 15 min; honour `Retry-After` |
| `pairing_invalid` | 400 | pairing code wrong, expired or used |
| `count_state` | 409 | action not allowed in the session's state |
| `not_found` | 404 | unknown count or difference id |

**Limits:** 1,000 EPCs and 256 KiB per read batch; 200 EPCs per lookup; up to 50 handhelds per site; target p95 500 ms for finder and under 1 s per read batch. Device `seen_at` is validated but not trusted: the server uses its own time.

## Identifiers and rules

A tool is known by its GMF tool code everywhere; its UHF tag is an EPC registered at the Site Gateway with that code as `item_ref`, and the tag's status tells the gate whether the tool may leave.

| Identifier | Format | Where it comes from | Notes |
| --- | --- | --- | --- |
| EPC | Upper-case hex in whole 16-bit words: `^([0-9A-F]{4}){1,31}$` (4 to 124 characters; 24 and 32 are the common lengths) | Read by the reader | Trim and upper-case what the SDK returns before any call; reject anything else. |
| Tool code (`item_ref`) | GMF entity code, e.g. `GMF-TL-0042` | GMF tracking app (Add tool) | The gate never re-assigns an EPC to another code; a registered tool's code cannot be renamed in GMF. |
| Drawer code | Short upper-case code, e.g. `D3` (GMF keeps ≤ 16 characters; the Site Gateway `area` allows 1 to 60) | GMF tool record (`drawer`) | Use it unchanged as the count `area` so the result maps back to the drawer. |
| Count number | `SO-yyyymmdd-nnn` (opname), `IC-yyyymmdd-nnn` (inventory) | Site Gateway | Local day in `Asia/Jakarta`; show it on screen so staff can match it with GMF. |
| Operator | Nomor Operator, 3 to 10 digits (leading zeros kept), 6-digit PIN | Site admin | Separate from GMF web users. |

**Tag status** (`tag_status` in finder answers):

- `active`: registered, in the crib, NOT allowed out; leaving raises the gate alarm.
- `released`: on loan (GMF checked it out), allowed out once.
- `blocked`: an old label (replaced or unbound in GMF); leaving raises `blocked_tag`.
- `known: false`: the Site Gateway has never seen this EPC.

**Privacy and safety rules the app must keep**

- EPCs are staff-only data in GMF. The handheld is a staff device, so it may show EPCs, but never put EPCs, PINs, tokens or device keys in logs, crash reports or analytics.
- Some BLE tags belong to people in GMF; RFID tool tags do not. Show only tool and product data, never personal data.
- Store the device key only in the Android Keystore; keep the token in memory (or encrypted storage) and drop it on logout.
- Never change tag status from the handheld in the GMF deployment: release and return belong to GMF loans and the gate.

## How handheld results reach GMF

The handheld's work reaches GMF only through the Site Gateway: GMF starts a drawer check with its API key, the handheld counts it, and the review sends GMF a signed `count_review` event.

&#91;embedded content: drawer check sequence · 7 calls\]

Step 4 repeats as often as needed (several handhelds may read one check; the result is a set union). Steps 5 and 7 need a supervisor role.

**What GMF does with each Site Gateway event**

| Event | Comes from | What GMF shows |
| --- | --- | --- |
| `count_review` | Handheld review (step 5) | Drawer check result: found / expected (e.g. 86/87), missing tools by name, extra and unknown counts. 86/87 = expected minus missing, over expected. |
| `pass` | Exit gate | Gate pass on each tool's timeline; an alarm (tool out without a loan) becomes a GMF alert. |
| `tag_status` | Gate return, GMF's own calls, Site UI | Status changes on the timeline; a gate return closes an open loan. |

**GMF screens that relate to the handheld** (staff only, at gmf-tracking.taggiot.com, Tools page):

- **Drawer check**: pick a drawer, see its expected tools, press Start (GMF sends step 1), then wait for the result. The page shows the count number to match on the handheld.
- **Missing tool**: last gate pass (time, direction), last BLE zone if the tool also carries a BLE tag, the open loan and borrower, and the hint "search with the handheld".
- **Gates**: each gate's presence, reader health, last pass and any alarm it holds.

If a check is started on the handheld instead of in GMF, GMF still receives its `count_review`, but it can only map it to a drawer when `area` equals the GMF drawer code.

## TMS context

The handheld does not touch loans: GMF's TMS (later) or the GMF tracking app (now) decides who may take a tool, and GMF tells the gate by releasing or returning the tool's tag.

|  | Built-in (now, default) | TMS (later) |
| --- | --- | --- |
| Where a loan is made | GMF Tools page: pick or scan tools, borrower, work order, optional due time; one hand-over for many tools | GMF's TMS; GMF shows its reference on each tool (read-only) |
| Who tells the gate | GMF app: `POST /tags/release` on check-out, `/tags/return` on check-in | Same GMF app calls, triggered by the TMS loan |
| Return | Manual check-in in GMF, or the tool walking back in through a gate with direction beams | Same |
| Override | n/a | Admin only, with a reason, audited |
| Status | Live, switched off until the end-to-end test | Shows "Not configured" until GMF provides the TMS API |

What this means for the handheld:

- A tool on loan has tag status `released`; a tool in the crib is `active`. Show that status in the finder, so staff know whether a "missing" tool is simply on loan.
- A drawer check counts what is physically in the drawer. A tool on loan shows as missing in the raw result; GMF puts the loan next to it, so staff can tell "on loan" from "lost".
- The handheld never releases or returns a tag.

Still needed from GMF AeroAsia for TMS mode: the TMS API (URL, auth, loan objects: issue, return, work order, borrower), and whether GMF should write gate returns back into the TMS.

## Hardware and reader SDK

The handheld model is not chosen yet, so build every screen against a small reader interface with a simulator behind it, and add the vendor driver once the device is bought.

| Candidate | Form | Status |
| --- | --- | --- |
| Chainway C72-class | Android handheld with a built-in UHF reader | Owner to pick; vendor facts not verified |
| Zebra RFD40 + phone | UHF sled paired with an Android phone | Owner to pick; vendor facts not verified |

Verify with the chosen vendor before coding the driver: the SDK returns the EPC as hex, it reports RSSI per read, transmit power can be set, and whether it can write tags (only needed if encoding is added later).

Suggested reader interface (a proposal, not an existing contract):

- `start(filter: EPC? , power: dBm)` and `stop()`; one active session at a time.
- A stream of `TagRead(epc, rssi, antenna, readAt)`; normalise `epc` (trim, upper-case, check the EPC pattern) inside the driver.
- `SimulatorReader`: replays a scripted list of EPCs with an RSSI ramp, so finder homing and drawer counts work on any phone or emulator.

Behaviour that makes the two jobs work:

- **Finder homing**: filter on the target EPC, smooth RSSI over about half a second, show a 0 to 100 bar and a beep that speeds up as RSSI rises; lower the power for the last metre to pinpoint the drawer.
- **Drawer count**: full power, de-duplicate EPCs locally, upload a batch every few seconds or every 500 EPCs (the limit is 1,000), each with a new `batch_id`, and keep unsent batches across app restarts.
- **Language**: Kotlin with the Android Keystore is a natural fit; the stack is your choice.

## Environments and access

Develop against the RFID demo stack and the GMF demo stack; never test against GMF live. Every account, PIN, pairing code and key comes from the owner, never from a document or chat.

| What | Where | Who provides it |
| --- | --- | --- |
| Site Gateway (handheld API) | `https://rfid.taggiot.com/api/v1/handheld/` (demo; tenant `acme`, site `dev`) | Running; the Taggiot RFID team maintains it |
| Operator accounts | One `operator` and one `supervisor` (Nomor Operator + 6-digit PIN) | Owner, through the Site Gateway admin API (`/operators/create`); the Site UI screens for this are not built yet |
| Device pairing code | 8 characters, valid 15 minutes, single use | Owner (`/handhelds/create`, `/handhelds/pairing-code`) |
| GMF demo app (to see drawer-check results) | `https://gmf-demo.taggiot.com`, Tools page | Owner creates a staff login |
| GMF live app | `https://gmf-tracking.taggiot.com` | Not for testing |
| Lab gate | `lab-01` (Zebra FX9600), one antenna, no direction beams | Taggiot RFID team; only needed for gate tests, not for the handheld |
| Code and specs | Repo `taggiot-rfid`: `api/site/openapi.yaml`, `docs/16-handheld-api.md`, `docs/17-gmf-tool-control.md`; the Android app is planned under `apps/handheld-android/` | Owner grants repo access |

Before the first drawer-check test, GMF's link to the RFID demo must be switched on (owner steps already listed for the GMF team), and the test tools must exist in GMF with their EPCs and drawer codes.

## Testing and acceptance

The app is done when a drawer check started in GMF can be counted on the handheld and its 86/87-style result appears in GMF, with every call safe to repeat.

**End-to-end scenario (GMF demo + RFID demo)**

1. In GMF, add three test tools with EPCs and drawer `D3`; GMF registers them at the gate.
2. In GMF, start a drawer check for `D3`; note the `SO-…` number.
3. On the handheld, sign in as the operator, open the check for `D3`, read only two of the three tags.
4. Turn Wi-Fi off, read again, turn it back on: the queued batch uploads once (`replayed` or no duplicate).
5. Sign in as the supervisor, review: one `missing`.
6. In GMF, the drawer check shows 2/3 with the missing tool's name.
7. Close the check on the handheld with a comment.
8. Finder: search the missing tool, walk toward it with the homing bar, scan it; status shows `active`.

**Acceptance checklist**

- [ ] Pairing stores the device key in the Android Keystore; reinstall forces a new pairing.
- [ ] Wrong PIN and unknown operator look the same; five failures show the lockout time from `Retry-After`.
- [ ] Token expiry mid-count: sign in again and resend without losing or doubling reads.
- [ ] Every read batch has its own UUID `batch_id`; a resend returns the first answer.
- [ ] EPCs are trimmed, upper-cased and checked against the pattern before any call.
- [ ] No EPC, PIN, token or device key in logs, crash reports or analytics.
- [ ] Plain HTTP is refused.
- [ ] Operator role cannot see review, close or bind actions; supervisor can review and close.
- [ ] Bind and replace are hidden for the GMF site.
- [ ] The app works fully with the simulator reader, before the device arrives.

The Site Gateway team's own API tests cover the server side (`docs/16` §12); report any answer that differs from `openapi.yaml` to them rather than working around it.

## Open questions and references

The biggest open point is that the finder cannot yet name a GMF tool from its EPC; settle it with the RFID team before building the Find tool screen.

| # | Question | Who decides |
| --- | --- | --- |
| 1 | Finder answers (`/finder/epc`, `/finder/lookup`) carry `sku`, `name` and `serial` from the item master, but not the tag's `item_ref`. GMF registers tools as tags with `item_ref` = tool code and no product, so the handheld cannot show the tool code or search by it. Add `item_ref` to the finder answers and a search by `item_ref`, or have GMF register tools under one product (e.g. SKU `TOOL`)? | RFID team with the owner |
| 2 | Which handheld to buy (Chainway C72-class or Zebra RFD40 + phone), and does its SDK give hex EPCs and RSSI? | Owner |
| 3 | May drawer checks be started on the handheld, or only in GMF? If on the handheld, `area` must equal the GMF drawer code. | Owner |
| 4 | Does the customer LAN at GMF have TLS for the Site Gateway? The app refuses plain HTTP. | Owner, RFID team |
| 5 | Site UI screens for operators, handhelds and pairing codes are not built; until then the owner uses the admin API. | RFID team |
| 6 | TMS API from GMF AeroAsia (loan source), and whether gate returns are written back to TMS. | GMF AeroAsia |
| 7 | Encode Tag (writing new labels from the handheld) is out of scope for this build. | Owner |

**References**

- `taggiot-rfid` repo: `api/site/openapi.yaml` (spec of record), `docs/16-handheld-api.md` (handheld design and as-built notes), `docs/17-gmf-tool-control.md` (GMF ⇄ Site Gateway contract), `docs/05-site-gateway.md` (anti-theft rules).
- `taggiot-dashboard` repo: `docs/RFID_INTEGRATION.md` (GMF's mirror of the contract), `docs/USER_GUIDE.md` (Tools chapter: Add tool, hand-overs, Drawer check, Gates).
- Demo stacks: Site Gateway `https://rfid.taggiot.com`, GMF `https://gmf-demo.taggiot.com`.
