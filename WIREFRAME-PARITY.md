# iEvent — Wireframe Parity Checklist (v3 wireframes → app)

Round 7 / bundle `ieventpush8`. Every page of `ieventwireframesv3` was audited feature-by-feature
and all closeable gaps were implemented. Items marked **Phase 2** are the agreed exclusions
(online payment gateways, SMS verification, real AR/KU translations) or need infrastructure
we deliberately postponed. Everything else is **live in this bundle**.

Legend: ✅ implemented · 🔜 Phase 2 (with reason)

---

## Public site

### 1. index.html — Home
- ✅ Hero with live "N events across Iraq this month" count, search
- ✅ Category tiles → `/browse?category=…`, city cards → `/browse?city=…`
- ✅ This-weekend / trending rails with intent-carrying "See all" links (`?when=weekend`, `?sort=popular`)
- ✅ Host promo CTA + footer host links → `/host`
- 🔜 City-scoped "This weekend in **Baghdad**" heading (kept nationwide until city-detection exists)

### 2. browse.html — Directory
- ✅ Sort: Soonest / Price low→high / Most popular
- ✅ Price filter: All / Free / Paid (desktop sidebar + mobile slide-over)
- ✅ Date pills: Any / Today / Tomorrow / This weekend / Next 7 days / This month
- ✅ Numbered pagination with prev/next + ellipsis, current page highlighted
- ✅ Results count line ("Showing X of Y")
- ✅ Active-filter chips, each removable individually, plus "Clear all"
- ✅ Empty state per wireframe

### 3. event.html — Event detail
- ✅ Short summary under the title, tag chips (link to browse), lineup/schedule section
- ✅ "Get directions" (Google Maps link built from venue address)
- ✅ Refund policy line (No refunds / Up to 48 h / Up to 7 days)
- ✅ Ended & cancelled banners — ticket rail replaced by "sales closed" card
- ✅ Inline follow-organizer button (real POST, returns you to the event page)
- ✅ Page-view counter (feeds host dashboard/console "views" stats)
- ✅ Share control, related events, direct-pay checkout entry (existing, verified)

### 4. organizer.html — Organizer profile
- ✅ Upcoming / Past / About tabs
- ✅ Past events with "N attended" counts and Ended badges
- ✅ About: bio + contact block (email, phone, website, Instagram — shown only when set)
- ✅ Organizer logo (uploadable in Settings → Branding) or initials avatar; verified badge, counts
- ✅ "Message" (mailto) button when a contact email exists

### 5. auth.html — Sign in / Register
- ✅ Remember me (30-day persistent login)
- ✅ Forgot password → email link → reset page (60-min token, used-once, full email round-trip)
- ✅ Terms checkbox enforced server-side on registration
- ✅ Google sign-in button (when GOOGLE_CLIENT_ID configured)
- 🔜 Apple sign-in, phone OTP ("Coming soon" per exclusions)

---

## Purchase & user area

### 6. checkout.html
- ✅ Per-ticket holder rows: name **and** optional email (ticket emailed per holder where set)
- ✅ "Copy from buyer" on holder rows; rows follow quantity steppers
- ✅ "Keep me updated" checkbox (updates your notification preference)
- ✅ Sticky summary: per-type lines, promo discount, booking fee 1,500 IQD/paid ticket, total
- ✅ Direct-transfer block (card copy, reference, receipt upload), free orders skip payment
- 🔜 Hold-timer countdown (no server-side seat hold — a fake timer would mislead)

### 7. confirmation.html
- ✅ Confirmed / Pending / Closed (rejected-cancelled-refunded) hero variants with status pill
- ✅ Pending state: 3-step organizer-review timeline + transfer reference recap
- ✅ Ticket stub cards with QR (confirmed only), per-ticket status links
- ✅ Download PDF, add-to-calendar (.ics), More-events CTA

### 8. tickets.html — My tickets
- ✅ Upcoming / Past tabs with counts and per-tab empty states
- ✅ Status badges incl. new **Refunded** (badge only, QR removed)
- ✅ Per-ticket rows with holder, QR/PDF links when confirmed
- 🔜 "Rate event" (ratings backlog)

### 9. favorites.html
- ✅ Events tab (liked events) + **Organizers tab** (followed organizers, unfollow in place)
- 🔜 Per-organizer alert toggles (needs per-org notification prefs)

### 10. profile.html
- ✅ City select (10 Iraqi cities) + interests chip-picker (10 categories) — feed future recommendations
- ✅ Password change, notification toggles (existing, verified)
- 🔜 Avatar photo upload; real AR/KU language switch (visual only)

---

## Host console

### 11. dashboard.html
- ✅ Six stat cards: tickets, revenue, pending, live events + **page views** + **followers**
- ✅ Delta chips ("+12% vs prev. 30 days") on tickets & revenue
- ✅ Sales chart range pills **7d / 30d / 90d**
- ✅ First-steps to-do checklist (create event / payments / branding / team) — hides when complete
- ✅ Pending-orders alert banner, recent orders, upcoming events
- 🔜 Sales-by-channel card (needs order-source attribution)

### 12. dash-events.html
- ✅ Search + status chips (All/Live/Draft/Ended/Cancelled) with counts
- ✅ Rows: cover thumb, sold/quantity progress, revenue, status badge
- ✅ Actions menu: Edit / Open console / **Duplicate** / Publish / Unpublish
- ✅ Empty states (no events vs. no matches)

### 13. dash-create.html — Create wizard
- ✅ New fields in the wizard: summary (160-char counter), tags, lineup, **visibility Public/Unlisted**, refund policy
- ✅ Cover theme picker + image upload, ticket types, review step reflects new fields
- 🔜 Per-ticket sales windows & min/max per order, FAQs, gateway choice (gateway = Phase 2)

### 14. Event edit
- ✅ All new fields editable; ticket type management with statuses & sold counts
- ✅ Danger zone: **Cancel event** (buyers emailed), **Postpone** (new date, buyers emailed), Duplicate
- 🔜 "Paused" per-ticket status (needs enum migration — queued for next round)

### 15. dash-event.html — Event console
- ✅ Stats: sold, revenue, **page views + conversion %**, likes, checked-in
- ✅ Sales-by-ticket-type breakdown, recent orders for this event
- ✅ Share block: public URL copy + embed snippet; quick links to attendees/check-in
- ✅ Publish/unpublish/duplicate/postpone/cancel controls, cancelled banner

### 16. host-onboarding.html
- ✅ Four-step funnel with value-prop cards, audience chips, handle preview
- ✅ Lands on dashboard with the first-steps checklist active

### 17. dash-attendees.html
- ✅ Search (name/email/order #), ticket-type & status filters
- ✅ Stats row: total, checked-in + %, remaining, void
- ✅ **CSV export** honoring current filters
- ✅ Per-row: resend ticket email, check-in/undo; **bulk check-in**
- ✅ Insights panel (progress ring, tickets by type)

### 18. dash-orders.html
- ✅ Tabs incl. **Refunded**, search, date-range filter, pagination
- ✅ Stat cards (gross, orders, awaiting, refunds)
- ✅ Expandable order detail (buyer, items, transfer ref, receipt link, actions)
- ✅ **Refund** (voids tickets, restores inventory, emails buyer) + resend confirmation
- ✅ CSV export of the current view

### 19. dash-checkin.html
- ✅ Camera QR scanner, manual entry, door list + search, undo, progress bar
- ✅ Door-list tabs: All / Not arrived / Checked in
- 🔜 Gates, offline mode, scan-rate widgets (wireframe mockups without backend meaning yet)

### 20. dash-payouts.html — Earnings
- ✅ Summary cards: gross / booking fees / your revenue (+ refunded note)
- ✅ Per-event earnings table, "how fees work" example, direct-transfer payout explainer
- 🔜 Withdrawals, payout methods & history (gateway territory)

### 21. dash-marketing.html
- ✅ Promo codes (existing, verified: percent/fixed, per-event, max uses, toggle)
- ✅ **Email campaigns**: audience = This event's attendees / Past attendees / Followers, history with recipient counts
- ✅ **Tracking links**: per-channel short links `/l/{code}` with click counting, copy, delete
- ✅ Social: prebuilt WhatsApp/Telegram/Facebook/X share links per event; embed widget (existing)
- 🔜 Open/click rates, auto-generated social images

### 22. dash-settings.html
- ✅ Tabs: Organization / Team / **Branding** / Payments / **Notifications** with deep links
- ✅ Branding: logo upload, brand color, contact email/phone/website/Instagram (drives public organizer page)
- ✅ Notifications: email-me-on-pending-orders toggle
- ✅ Team (invite/remove/roles) and direct-payments settings (existing, verified)
- 🔜 API keys & integrations tab

---

## Test coverage added this round
- Playwright walkthrough: **15 new tests (ab–ap)** — browse filters/pagination, event parity, organizer tabs,
  auth extras + full password-reset round-trip (Mailpit), tickets/favorites/profile, dashboard stats & pills,
  events filters + duplicate, edit-new-fields → public page, duplicate→publish→postpone→cancel lifecycle,
  attendees filters/CSV/resend, orders refund round-trip (Mailpit), marketing tracking-link + FOLLOWERS
  campaign (Mailpit), settings branding/notifications.
- SmokeTest: **7 new tests** (forgot/reset pages, terms enforcement, /l/ redirect, browse sort+price,
  enriched orders view, attendees CSV) — now 25.

## Upgrade notes
- New DB migration `V5__wireframe_parity.sql` applies automatically on `docker compose up -d --build app`.
- Existing demo databases are enriched automatically at boot (summaries/tags/lineups and @zainevents
  contact details are filled in only where empty).
- No new environment variables required.
