# Instant alerts on your phone (push, without Google)

AnProx checks your servers in the background every 15 minutes to 3 hours (More → Alerts).
That is the battery-friendly default, but it isn't instant. For alerts within seconds, let
Proxmox send them itself: Proxmox VE 8.1 and newer have a notification system that can post
to **ntfy** or **Gotify**, and both have Android apps that work without Google services
(questionnaire Q26).

This guide sets that up. AnProx isn't involved in the delivery: Proxmox → your push server →
the ntfy or Gotify app. Tapping a notification opens that app; open AnProx from there to act.

## Which one?

| | ntfy | Gotify |
|---|---|---|
| Server | ntfy.sh (public, free) or self-hosted | self-hosted only |
| Proxmox target | **Webhook** (Proxmox VE 8.3+) | **Gotify** (Proxmox VE 8.1+) |
| Android app | ntfy (F-Droid or Play), also a UnifiedPush distributor | Gotify (F-Droid or Play) |
| Without Google services | yes (F-Droid build keeps its own connection) | yes |

On Proxmox VE 8.1 or 8.2, use Gotify (the webhook target came with 8.3).

## Option A: ntfy

1. **Pick a topic.** On ntfy.sh anyone who knows the topic name can read it, so use a long
   random one, e.g. `pve-alerts-7f3k9q2x`. Self-hosting ntfy with access control is better for a
   homelab you care about.
2. **Phone:** install ntfy (F-Droid recommended without Google services), **Subscribe to topic**
   → your server (or ntfy.sh) and the topic.
3. **Proxmox:** Datacenter → Notifications → Notification Targets → **Add → Webhook**:
   - Endpoint name: `ntfy`
   - Method / URL: `POST` `https://ntfy.sh/pve-alerts-7f3k9q2x` (or your server)
   - Headers: `Title` = `{{ title }}` (optional); with access control on your ntfy server add
     `Authorization` = `Bearer <token>`, and keep the token under **Secrets**.
   - Body: `{{ message }}`
   - Instead of writing the topic into the URL you can store it as a secret named `topic` and
     use `https://ntfy.sh/{{ secrets.topic }}`, so it doesn't show in the configuration.
   - **Test**: the phone shows the test message within seconds.
4. **Matcher:** Datacenter → Notifications → Notification Matchers → **Add**: name `phone`,
   target `ntfy`, and match **severity** `error` and `warning` (or everything). Leave the
   default `default-matcher` (mail to root) as it is.

## Option B: Gotify

1. Run Gotify (for example the `gotify/server` container) and create an **application** in its web
   UI; copy the application token.
2. **Phone:** install the Gotify app, log in to your server.
3. **Proxmox:** Datacenter → Notifications → Notification Targets → **Add → Gotify**: name
   `gotify`, server URL, the application token → **Test**.
4. **Matcher** as in option A, with target `gotify`.

## What Proxmox sends

Backup jobs (success/failure, depending on each job's notification setting), replication
failures, fencing events of the HA manager, package updates available (daily, if enabled under
the node's updates settings) and some system events. Node and guest **status** changes (a node
going offline, a guest stopping) are not Proxmox notifications: AnProx's background check covers
those, so keep it on alongside push.

## Check it

- Datacenter → Notifications → your target → **Test** reaches the phone.
- Run a backup job whose notification setting sends on success too, and wait for its result.

## Later in AnProx

Receiving UnifiedPush directly in AnProx (so its own notifications open the right screen) is on
the roadmap after 1.0; ntfy can then act as the UnifiedPush distributor.
