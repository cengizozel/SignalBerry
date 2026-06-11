# SignalBerry Redesign v2 — Data Integrity & Feature Completion

Revision 2 — incorporates the 4-lens adversarial critique (26 blockers/risks addressed).
Target: BlackBerry Q10 (API 18 / Dalvik / SQLite 3.7.11 / 720×720 / small heap).
Hard facts pinned by live testing (2026-06-11):
- **F1**: signal-cli does NOT echo its own /v2/send onto its own /v1/receive (2 API
  self-sends while connected → 0 bridge rows). Echo-based capture of app sends is
  impossible; the app must report sends to the bridge itself.
- **F2**: `/v1/accounts` empty until container restart after relink (accounts load at startup).
- **F3**: bb-q10 emulator `/sdcard` is read-only; `adb exec-out` unsupported on API 18.

## 1. Root causes (unchanged from v1 — 26 verified findings)

1. No canonical message identity (fuzzy ±2s rebinding, text matching, dual image rows).
2. Four unsynchronized writers (3 WebSockets + 3s poll, check-then-insert races).
3. Bridge loses metadata, dedups nothing, receipts hit "latest row".
4. Read-status wrong both directions; never sends read receipts.
5. Media fragile (content:// death, literal-"null" rows, full-res decode, video-as-image).

## 2. Bridge v2

### 2.1 Storage — two tables, version-segregated, zero-loss rollback
- **`messages` (v1) stays byte-identical in schema and ingestion semantics.** The
  real Q10 runs the old app against this bridge; v1 endpoints (`/messages`, `/read`,
  `/unread`, `/health` ok-field) are frozen — golden contract test pins the v1
  response shape (text always a string, attId/mime only when present, no new fields).
- New table (same DB file):
```sql
CREATE TABLE messages_v2 (
  id INTEGER PRIMARY KEY,
  peer TEXT NOT NULL,              -- canonical key (§2.4)
  dir TEXT NOT NULL,               -- 'in'|'out'
  kind TEXT NOT NULL DEFAULT 'text', -- text|image|video|audio|file
  body TEXT NOT NULL DEFAULT '',   -- caption for attachment rows; NEVER the string "null"
  server_ts INTEGER NOT NULL,      -- Signal envelope/sent ts only
  status INTEGER NOT NULL DEFAULT 1, -- 1 sent/2 delivered/3 read (out); 2 (in)
  att_id TEXT NOT NULL DEFAULT '', -- '' for text rows (plain-column UNIQUE legality)
  mime TEXT NOT NULL DEFAULT '',
  quote_ts INTEGER NOT NULL DEFAULT 0, quote_author TEXT NOT NULL DEFAULT '',
  quote_text TEXT NOT NULL DEFAULT '',
  reactions TEXT NOT NULL DEFAULT '{}',  -- {"me"|"peer:<key>": emoji}
  edited_ts INTEGER NOT NULL DEFAULT 0,  -- latest edit envelope ts
  edit_count INTEGER NOT NULL DEFAULT 0,
  deleted INTEGER NOT NULL DEFAULT 0,
  mod_seq INTEGER NOT NULL,        -- §2.2
  UNIQUE(peer, dir, server_ts, att_id)
);
CREATE INDEX idx_v2_seq ON messages_v2(mod_seq);
CREATE TABLE meta (k TEXT PRIMARY KEY, v INTEGER);  -- meta['seq'] global counter
```
- **Dual ingestion**: every envelope writes v1 table with today's exact semantics
  AND v2 table with new semantics, both sides of one transaction (one global
  write lock — v1/v2 can never diverge on a crash). Plus /v2/sent dual-writes v1
  (§2.5). Rollback to the old binary = v1 table fully current. v2 endpoints read
  only v2; v1 endpoints read only v1.
- All v2 writes: `INSERT OR IGNORE` then targeted `UPDATE` in one transaction
  (idempotent; no UPSERT syntax dependency anywhere, same pattern the app uses).

### 2.2 Change feed — the cursor that can deliver mutations
- Global `mod_seq` from `meta.seq`, bumped on EVERY v2 insert AND update
  (reactions, receipts, edits, deletes, re-keys, marker updates).
- **Atomicity invariant**: seq read+bump+row-write happen inside ONE SQLite write
  transaction, serialized by the bridge's single global write lock (`_db_lock`
  already exists) — a lower-seq row can never commit after a higher-seq row was
  served, so the cursor never skips.
- `GET /v2/changes?since_seq=N&limit=M` → changed rows ordered by mod_seq, plus
  `{"max_seq": K}`. **Client cursor invariant**: advance only to the last
  RETURNED row's mod_seq and loop while the page is full; `max_seq` is
  informational (progress display) only — never adopted as the cursor.
- `GET /v2/messages?peer=&before_ts=&before_id=&limit=` for scrollback paging —
  keyset cursor on the (server_ts, id) pair so ts-groups never split across pages.

### 2.3 Envelope handling (v2 semantics)
- **dataMessage**: text+attachments = caption merged into first attachment row
  (`body`=caption); N attachments = N rows sharing server_ts (distinct att_id).
  kind from mime prefix; non-media mime → kind='file'. Never store "null".
- **syncMessage.sentMessage**: same shaping, dir='out', ts=sent.timestamp. (Covers
  phone-originated sends; app-originated sends arrive via §2.5 — F1.)
- **syncMessage.readMessages**: stored as read-marker events in the change feed
  (kind='marker' row, deleted=1 trick avoided — instead a dedicated
  `markers(peer, last_read_ts, mod_seq)` table also served by /v2/changes) so the
  app clears badges when the phone reads a chat.
- **receiptMessage**: for each ts in `timestamps[]`: update ALL rows in the
  (peer=sender, dir='out', server_ts∈{ts, or edited_ts=ts}) group —
  `status=MAX(status,new)`, tombstones excluded. No "latest row" fallback.
  **Orphan receipts**: a receipt matching no row is stored in
  `orphan_receipts(peer, ts, status, seen_at)` and applied (then deleted) when a
  later `/v2/sent` or sync row lands at that identity — closes the
  receipt-before-/v2/sent race without reintroducing guessing. Orphans expire
  after 30 days.
- **dataMessage.reaction / sync reaction**: dir = (targetAuthor==self) ? 'out':'in'
  (targetAuthor: legacy number-or-uuid identifier — for the ==self test our own
  number is always known; pinned to 0.14.3 semantics); match targetSentTimestamp
  against `server_ts OR edited_ts` (official clients react to the latest
  revision); apply to ALL rows in the matched ts-group; isRemove deletes the
  author's key. Stored while app closed; surfaces via mod_seq.
- **editMessage** (both peer + sync): match `targetSentTimestamp` against
  `server_ts OR edited_ts` (chained edits), update body across the ts-group,
  edited_ts = envelope ts for peer edits / sentMessage.timestamp for sync edits,
  edit_count+=1. Original server_ts remains the identity.
- **remoteDelete**: match against server_ts OR edited_ts; deleted=1 across the
  ts-group (+mod_seq bump). Phone-side data never touched.
- **typingMessage**: not stored (ephemeral; app gets it via its own WS, §3.1).
- **groupInfo envelopes**: skipped entirely in v2 ingest AND backfill (v1 stored
  group traffic keyed by sender — that garbage must not migrate; groups are the
  next milestone with their own keying).

### 2.4 Peer keys — bridge-side map, ongoing re-key
- `peer_map(uuid TEXT PRIMARY KEY, number_digits TEXT)` learned from every envelope
  carrying both fields, plus GET /v1/contacts at startup and every 6h.
- Canonical key = number digits when known, else lowercase uuid. When a mapping is
  learned later, a re-key transaction merges uuid-keyed rows into the number key
  (INSERT OR IGNORE + max(status)/reactions-merge for collisions, then delete uuid
  rows), merges the `markers` row (max last_read_ts) in the same transaction, and
  bumps mod_seq on every moved row so apps refetch. Idempotent, reruns on every
  new mapping — not one-time. /v2/changes rows carry both `peer` and, when known,
  `peer_uuid` so the app learns mappings from the feed itself.

### 2.5 New endpoints for the app
- `POST /v2/sent` — the app reports each confirmed send
  `{peer, kind, body, server_ts, att_id, mime, quote_*}`; idempotent upsert that
  also (a) applies+clears any matching `orphan_receipts`, (b) dual-writes the v1
  table with old-echo semantics (out,1) — so the old app on the real Q10 sees
  messages sent from the new app, which the echo-less v1 bridge never delivered.
  Required because of F1; the only way the bridge has app-sent history.
  **Durability contract (app side)**: out rows carry a `reported` flag; the
  report queue is DB-backed, not in-memory — Repo init re-enqueues every
  unreported confirmed row. Survives force-stop and process death.
- `POST /v2/read-receipts {peer, timestamps:[...]}` — bridge fans out one
  signal-api `POST /v1/receipts` per ts ({receipt_type:"read", recipient,
  timestamp} — SINGULAR, per 0.99 swagger) server-side with retry; one radio
  round-trip for the Q10 instead of N. Skipped entirely for the self-thread.
  Fan-out queue is persisted in SQLite (drained on restart); the bridge also
  bumps its own `markers` row for the peer so post-reinstall badge state is
  correct. Best-effort beyond that (a receipt lost to a crashed fan-out is
  re-sent next time the chat opens, since the app's watermark hasn't advanced
  past unacked rows — ack = 200 from signal-api, not enqueue).
- `GET /health` → `{ok:true, schema:2, ws_connected, account_present,
  last_envelope_age_s}`. Container healthcheck stays "Flask answers" (no
  restart flapping — restarts can't heal json-rpc loss windows); the new fields
  are for monitoring/alerting and the app's version handshake.

### 2.6 v1→v2 migration (offline, before serving)
Backfill v2 from v1 rows with sanitization: skip `text='null'` rows; skip rows
from group envelopes where identifiable; coalesce caption-split pairs (text row +
att rows at same (peer,dir,ts) → att row gets body=text, text row dropped); kind
derived from mime (att rows with empty mime → kind='file'); statuses imported
as-is (marked legacy — receipt history is unrecoverable); ts kept (legacy
wall-clock ts acknowledged — the app-side reconcile §3.6 handles collisions).
**Copy mechanics**: `sqlite3 src.db ".backup copy.db"` (WAL-safe — never a raw
file copy of a live WAL database), migrate the copy, stop bridge, swap, start.
Old DB backed up (done: bridge-pre-v2-20260611.db, two locations).
**Image pinning**: docker-compose pins `bbernhard/signal-cli-rest-api:0.99`
(replacing `:latest`) and the bridge image is tagged per release. All protocol
facts (incl. F1) are version-dependent; any signal-api upgrade re-runs the M1
checklist first.

## 3. App data layer v2

### 3.1 Single writer, background service preserved
- MessageService keeps today's lifecycle (sticky, runs in background — API 18 has
  no Doze; notifications continue to work; NotificationChannel/startForeground
  guards kept). It owns the ONLY WebSocket. Fix: close previous WS on
  onStartCommand re-entry; no reconnect after onDestroy.
- `Repo` singleton = only DB writer. `Repo.ingest(envelope)` / `ingestChanges(rows)`
  / `confirmSend(...)` are synchronized; all writes are INSERT-OR-IGNORE + UPDATE
  in transactions (SQLite 3.7.11-safe).
- Activities never open sockets, never poll timers. They register
  `Repo.Listener` (main-thread delivery): granular events
  (itemInserted/itemChanged/threadChanged) keyed by (peer, dir, serverTs, attId)
  → adapters use notifyItemInserted/Changed — no full rebuild, no forced
  scroll-to-bottom unless already at bottom. With granular notify, all row
  click/long-press listeners must use getAdapterPosition() at event time (the
  current bind-time-position captures were only "safe" because everything went
  through notifyDataSetChanged). The conversation-list search filter mutates the
  bound adapter's data (notifyDataSetChanged) instead of forking a new adapter
  per keystroke. Ephemeral events (typing, transient send-failure) flow through
  the same listener but skip the DB.
- Catch-up: `GET /v2/changes?since_seq=<last_seq>` on service start, WS reconnect,
  and app foreground. last_seq stored once globally in prefs.

### 3.2 Identity & send pipeline
- Identity = (peerKey, dir, serverTs, attId'') mirroring bridge §2.1, enforced by
  the same plain-column UNIQUE index (created in migration after dedupe, §3.6).
- DB v6 columns added: kind, deleted, quote_ts, edited_ts, client_nonce; plus
  reactions for fresh-v5 installs (the onCreate-missing-column bug).
- **Send**: insert PENDING row with `server_ts = -nonce` where
  `nonce = (localMillis << 8) | counter` (monotonic per-process counter — double
  -tap in the same ms cannot collide; INSERT OR IGNORE must never silently drop
  a second send). Display rules: pendings sort at thread bottom by |server_ts|,
  produce no date header, age = now − millis(nonce). → POST /v2/send → response
  ts T (parse as string-or-number) → in ONE transaction: if a row exists at
  (peer,'out',T,*) [phone-echo or bridge row won a race]: merge local fields
  (local file path, quote) into it and delete the pending row; else update
  pending: server_ts=T, status=SENT, reported=0. The DB-backed report queue
  (§2.5) then POSTs bridge /v2/sent and sets reported=1 on 200.
- **No fuzzy ts correction, no text matching, ever.** Receipts never modify serverTs.
- **Send timeouts**: text sends keep the 8s read timeout; attachment sends use
  120s (signal-cli must decode+dispatch the whole upload before responding —
  with 8s, timeout-but-actually-sent becomes the COMMON path).
- **Sent attachments**: app sends ONE attachment per message (current UX, kept).
  The app never learns its own att_id (F1: no self-echo) — its row keeps
  att_id='' + local file path. If a phone-echo row (rare: only for phone-originated
  sends) arrives at (peer,'out',T,attId≠''), the merge rule above absorbs the
  att_id=='' row into it, keeping the local path as the cache entry for attId.
- **PENDING recovery**: on Repo init, rows with server_ts<0 older than 10 min →
  status=ST_FAILED (new), rendered with tap-to-retry (retry = fresh nonce).
  **Honest residual**: /v2/send has no idempotency token, so
  timeout-but-actually-sent → FAILED + manual retry can duplicate at the peer.
  Mitigated by the 120s media timeout; for text (8s against a LAN bridge) the
  window is negligible. The phone-echo absorption rule (exact-field match within
  60s) only helps phone-originated sends and must never absorb across differing
  attachment ids.

### 3.3 Peer keys
- `PeerKeys.resolve()` everywhere + persisted uuid→number map updated from
  envelopes and /v1/contacts. Re-key merge (same semantics as bridge §2.4) runs
  whenever a new mapping is learned — idempotent, not one-time.

### 3.4 Read status
- Inbound: exact per-timestamp receipt application (sender-gated, max(), tombstone-
  excluded). `upgradeAllOutStatus` and the ±2s path are deleted.
- Outbound read receipts: when Chat is foregrounded and rows are newly visible →
  enqueue bridge `/v2/read-receipts` (cap: 25 newest unread; never for self-thread;
  Settings toggle **default OFF** — the user's Signal account has read receipts
  disabled, and this client must not leak read state the account-level setting
  suppresses; the toggle exists for if they ever re-enable it on the phone).
- `syncMessage.readMessages` ingestion kept — phone reads clear app badges (served
  while-closed via the bridge markers feed §2.3).
- Unread watermark = **max(server_ts of rows actually rendered)** — wall-clock
  read_ts is gone (clock-skew family of bugs closed). Stamped per-message while
  chat resumed, finalized onPause. Per-peer phone-read markers (from the bridge
  markers feed) are persisted and applied AT INSERT TIME to late-arriving rows
  with server_ts below the marker — a delayed row can never get stuck unread.
- Ticks: four small **drawables** (pending/sent/delivered dim/read accent) — no
  unicode glyphs (U+25F7 is tofu on 4.3), no emoji dependency.

### 3.5 Attachments & media
- `AttachmentStore`: disk cache `getFilesDir()/att/<attId>`; byte-bounded budgets
  (images 48MB LRU, video 64MB LRU, active playback pinned). Sent media copied in
  before POST. Byte-bounded `LruCache` (maxMemory/8) for bitmaps; shared 2-thread
  executor replaces thread-per-request loaders (chat images + avatars).
- Decode: inSampleSize to view size; hard cap 2048px either dimension (GL texture
  limit on old GPUs); viewer uses the same cap.
- **Upload streaming** (kills the 6-8× heap blowup): HttpURLConnection
  `setChunkedStreamingMode(0)`; write JSON prefix → stream file through
  `android.util.Base64OutputStream` with **Base64.NO_WRAP** (the default flag
  line-wraps with raw \n — illegal inside a JSON string; deterministic 400) →
  suffix. Practical cap enforced client-side: images 8MB, video 50MB with a
  clear toast.
- **Playback**: androidx `FileProvider` added (manifest <provider>); VideoView/
  MediaPlayer via `content://` (mediaserver cannot read file:// app-private paths
  on 4.3; file:// also throws FileUriExposedException ≥24). On MediaPlayer error →
  inline error state + "open externally" via content:// grant. Codec reality:
  Signal video is H.264 Main/High; API 18 guarantees only Baseline — graceful
  failure is a first-class state, emulator results don't predict Q10 hardware.
- Video bubbles: thumbnail via MediaMetadataRetriever(setDataSource(fd)), strictly
  off-main-thread, release() in finally, result persisted to disk cache (never
  per-bind); duration + play overlay; **tap-to-download** gated by Content-Length
  (no auto-download).
- Save-to-gallery: WRITE_EXTERNAL_STORAGE maxSdkVersion=28 added; 3-branch
  (≤28 file+MediaScanner / 29+ MediaStore IS_PENDING). On bb-q10 the emulator
  /sdcard is RO (F3) → defensive error handling, verified manually on API 36 AVD.
- Image rows render placeholders for loading/failed + tap-to-retry;
  `setImageBitmap(null)` paths removed.

### 3.6 App DB migration (v5→v6) + first-catch-up reconcile
Order matters; staged with a persisted migration-state flag (steps 1–6 are local
and transactional; step 7 is network-dependent and separately tracked):
1. Add columns (kind, deleted, quote_ts, edited_ts, client_nonce, reported;
   reactions if absent — the fresh-v5-install bug).
2. **Normalize NULLs**: `att_id=''`, `text=''`, `quote_*=''` where NULL — SQLite
   treats NULLs as pairwise distinct in UNIQUE indexes, so without this the
   index enforces nothing for legacy rows (the majority).
3. Purge literal-"null" text and quote_text; re-kind rows by mime (video-as-image
   fix); legacy positive-ts status=0 pendings → ST_FAILED (they can never
   confirm; §3.2 recovery only handles negative-ts rows).
4. uuid→digits re-key merge (collision rules as bridge §2.4); reruns
   post-migration as mappings are learned (§3.3). Runs BEFORE dedupe so
   cross-key twins become same-key and pass 5 can pair them.
5. **Dedupe pass**, two sub-passes (idempotent; re-run after any later re-key):
   a. Exact: group (peer_key,dir,server_ts,att_id): survivor takes max(status)
      (ST_DELETED tombstone wins outright), per-field coalesce of
      quote_*/caption/local_uri/reactions (first non-empty), then delete losers.
   b. **Image twins** (differ in BOTH ts and att_id — never share a group key):
      pair rows where (same peer_key, dir='out', kind='image', one has
      local_uri≠'' ∧ att_id='', other has att_id≠'' ∧ local_uri='',
      |Δts|≤120s), nearest-Δts pairing, **each row consumed at most once**;
      merge into the att_id row (Signal ts wins), local_uri kept as
      AttachmentStore hint.
6. CREATE UNIQUE INDEX (legal now: deduped data, no NULLs in key columns).
7. Reset bridge cursor last_seq=0; **first-catch-up reconcile** over the full
   /v2/changes drain. Matching order per bridge row, **each local row consumed
   at most once, exact-first**:
   i.  exact identity (peer,dir,server_ts,att_id) — already-correct rows
       consume themselves (this is what makes restart-from-0 idempotent);
   ii. attachment leg: (peer, dir, att_id) with att_id≠'' (text rows are
       never matched by this leg);
   iii. text leg: (peer, dir, exact body text, nearest |Δts|≤30s among
       unconsumed candidates — window sized for legacy wall-clock skew incl.
       the Q10's fast clock; exact-text guard keeps it safe) →
   adopt bridge Signal ts. **On adopt-collision** (a legacy row already occupies
   the target identity — the old ±2s path stole timestamps): merge-into-occupant
   (max status, union reactions, keep local_uri), delete the adopting row.
   **Completion semantics**: snapshot S=max_seq at reconcile start; the one-shot
   flag clears ONLY after the drain verifiably reaches S; any interruption
   restarts the reconcile from seq 0. Only after the flag clears does strict
   identity apply.
8. Legacy chat_hist_* prefs migration path kept (it feeds the same upsert).

**Steady-state ingestChanges field rules** (after the one-shot flag clears):
INSERT OR IGNORE; on existing rows status=MAX(local,bridge) — a stale bridge
status can never downgrade ticks the app's own WS already upgraded; reactions/
body/edited_ts/deleted adopt the bridge value only when the bridge row's
edited_ts/mod_seq is newer; local-only fields (local_uri, client_nonce,
reported) are never touched by feed ingestion.

**`reported` flag birth values**: v6 migration sets reported=1 on ALL
pre-existing out rows; rows born from ingestChanges/ingest(envelope) are born
reported=1; ONLY rows confirmed via the app's own /v2/send start at reported=0.
(Otherwise Repo init would mass-re-report the entire legacy history to
/v2/sent, polluting both bridge tables with receipt-unmatchable rows.)

### 3.7 Version handshake
On connect: GET bridge /health → require schema≥2 (clear error screen otherwise);
GET /v1/about → store server version; feature-gate by version, not the
capabilities list (0.99 omits edits from capabilities yet supports them).
Handshake result cached in prefs (schema_ok + checked_at); MessageService
START_STICKY restarts use the cache and re-verify at most daily or on any v2
endpoint failure — no per-restart blocking call.

### 3.8 App rollback drill (honest)
MessageDatabase has no onDowngrade; a v5 APK opening a v6 DB throws, and Android
refuses in-place APK downgrades anyway. Rollback = uninstall → reinstall v5 APK
→ history rebuilt from the bridge's still-current v1 /messages (and the dual-
written /v2/sent rows, §2.5, so new-app sends survive in the rebuilt view).
Accepted loss: local-only state (reactions/edits/quotes on legacy rows, read
watermarks). The v6 migration deletes nothing from the bridge, so rollback is
never data-destructive server-side.

## 4. Feature sweep (post-core)

Remote-delete send (age-gated per Signal's window, surface API error body);
incoming text styles (SpannableString), mentions, link-preview rendering;
search registered number → start chat; identity-trust prompt on send failure
(PUT /v1/identities/{n}/trust/{peer}); manual contact refresh; typing indicators
**explicitly preserved** through the service WS → Repo ephemeral channel.
Exceptions: groups (next milestone), stickers (stretch, static only), polls
(post-groups), calls (impossible over REST), registration/devices/profiles
(server-side concerns).

## 5. Verification

- **M1 protocol pinning (before core code)**: live-assert on the real stack via
  Note to Self from the phone (capture tap already running): (a) sync-echo
  envelope inventory — text/image/reaction/edit/read shapes incl. attachment id
  fields, + whether an accompanying dataMessage appears for self-sends (dedup
  needed), (b) /v2/send response ts type (string/number) — the exact-equality
  assert vs echo is unexecutable (F1: app sends have no echo; phone sends don't
  pass /v2/send): receipts carrying the send-result ts is signal-cli's
  documented contract, asserted in the mock suite instead, (c) receiptMessage/
  typingMessage absence in self-thread (expected → mock-only paths, documented),
  (d) editMessage envelope nesting (peer + sync variants) to pin the mock's
  factories.
- **Mock stack**: mock signal-api (WS /v1/receive + REST: /v2/send returning ts,
  /v1/attachments/{id} bytes, /v1/receipts, /v1/reactions, /v1/contacts,
  /v1/accounts, /v1/about) so the REAL bridge v2 runs against it in pytest —
  full-stack scenario tests without the production account. The app on the
  emulator points at host (10.0.2.2) mock for peer scenarios.
- **Bridge pytest**: every envelope type, receipt ordering (delivery-after-read),
  reaction add/remove/replace, chained edits, re-key merges, v1 contract
  golden-JSON, migration fixtures (seeded v1 DBs with dupes/caption pairs/
  wall-clock ts/mangled statuses → row-by-row asserts).
- **App migration test**: seeded v5 DB (exact dupes, CROSS-TS duplicate chains
  from the old fuzzy-receipt path, fuzz-rewritten ts-thief rows, image twins
  with differing ts, positive-ts status=0 pendings, tombstones, null rows,
  uuid/digit splits) pushed via adb run-as, then upgrade build installed →
  assert thread correctness via uiautomator/screenshots.
- **Navigation gauntlet** (adb-scripted on bb-q10): per action (send text/image/
  video, receive, reply, react±remove, edit, remote-delete, receipts): act →
  leave → re-enter → force-stop → relaunch → assert. Real-account runs via Note
  to Self for sync paths; mock for peer paths.
- **Deploy order**: bridge v2 first (Q10 keeps working via frozen v1 API);
  new app only after handshake passes. Rollback: old binary + intact v1 table.
