# Social links — API documentation

The social media links (Facebook, Instagram, YouTube, WhatsApp…) are stored in the
**database**. You change a URL from the dashboard; no code change, no redeploy.

Last updated: 2026-08-26.

---

## 1. What changed (2026-08-26)

Before, `GET /api/v1/settings/social` returned **only active rows**. If an admin
switched a link off in the dashboard, the row vanished from the list and could
never be switched back on.

Now the endpoint takes an `includeInactive` query parameter, the same way
`/api/v1/nav-menu` already did:

| Request | Returns | Used by |
| --- | --- | --- |
| `GET /api/v1/settings/social` | active rows only | the **website** |
| `GET /api/v1/settings/social?includeInactive=true` | every row, active or not | the **dashboard** |

The default is `false`, so the website behaves exactly as before. **No database
migration was needed.**

### The three files touched

| File | Change |
| --- | --- |
| `khi_app/repository/site/SocialLinkRepository.java` | Added `findAllByOrderByDisplayOrderAsc()` next to the existing `findAllByActiveTrueOrderByDisplayOrderAsc()`. |
| `khi_app/service/site/SiteContentService.java` | `getSocialLinks()` → `getSocialLinks(boolean includeInactive)`, which picks one of the two repository methods. |
| `khi_app/api/site/PublicSiteController.java` | `@RequestParam(defaultValue = "false") boolean includeInactive` on the GET, passed through to the service. |

Note on the service: it is a **single method taking the boolean**, not a no-arg
method plus an overload. The controller is the only caller, so a no-arg version
would have been dead code — and this matches how `NavMenuService.list(boolean)`
is written. The HTTP behaviour is identical either way.

---

## 2. Where everything lives

| Thing | File |
| --- | --- |
| Table / entity | `khi_app/model/site/SocialLink.java` |
| Database queries | `khi_app/repository/site/SocialLinkRepository.java` |
| Logic | `khi_app/service/site/SiteContentService.java` (search "Social links") |
| Endpoints | `khi_app/api/site/PublicSiteController.java` (search "Global social settings") |
| Request / response shapes | `khi_app/dto/site/SiteContentDtos.java` |
| Who is allowed | `user/configs/SecurityConfig.java` |

## 3. The table: `social_links`

| Column | Type | Meaning |
| --- | --- | --- |
| `id` | number | Auto-generated. |
| `platform` | text (max 60), **unique** | `FACEBOOK`, `INSTAGRAM`, `YOUTUBE`, `WHATSAPP`… Saved UPPERCASE automatically. One row per platform — the constraint is `uk_social_platform`. |
| `url` | text, required | The link itself. This is the field you change when the account moves. |
| `label_ckb` | text (max 200) | Optional name in Sorani. Blank is saved as `null`. |
| `label_kmr` | text (max 200) | Optional name in Kurmanji. Blank is saved as `null`. |
| `active` | true/false | `false` = hidden from the website, but still saved. Defaults to `true`. |
| `display_order` | number | Small number first (0, 1, 2…). Defaults to `0`. |

## 4. The endpoints

Base path: `/api/v1/settings/social`

| Method | Path | Success status | Who can call it |
| --- | --- | --- | --- |
| `GET` | `/api/v1/settings/social` | 200 | **Everyone** — no login |
| `POST` | `/api/v1/settings/social` | **201 Created** | Admin only |
| `PUT` | `/api/v1/settings/social/{id}` | 200 | Admin only |
| `DELETE` | `/api/v1/settings/social/{id}` | 200 | Admin only |

"Admin only" = a logged-in user with role `ADMIN` or `SUPER_ADMIN`, sending
`Authorization: Bearer <token>`. The rules are in `SecurityConfig.java` and match
on **method + path** — the new query parameter does not affect them, so
`?includeInactive=true` is public too. See the warning in §7.

### Request body (POST and PUT)

```json
{
  "platform": "FACEBOOK",
  "url": "https://facebook.com/KurdishHeritage",
  "labelCkb": null,
  "labelKmr": null,
  "displayOrder": 0,
  "active": true
}
```

- `platform` — **required**, max 60 characters. Trimmed and upper-cased on save.
- `url` — **required**, max 2000 characters. Trimmed on save.
- `labelCkb`, `labelKmr` — optional.
- `displayOrder` — optional; `null` becomes `0`.
- `active` — optional; `null` becomes `true`.

`PUT` replaces the **whole** row. Always send every field, not only the changed one.

### Response

Every answer is wrapped the same way:

```json
{
  "success": true,
  "message": "Social links fetched",
  "data": [
    {
      "id": 1,
      "platform": "FACEBOOK",
      "url": "https://facebook.com/KurdishHeritage",
      "labelCkb": null,
      "labelKmr": null,
      "displayOrder": 0,
      "active": true
    }
  ]
}
```

Rows come back sorted by `displayOrder` ascending.

### Errors

| Situation | Status | Body |
| --- | --- | --- |
| Missing or invalid `platform` / `url` | 400 | validation error listing the fields |
| Second row with the same `platform` | **409 Conflict** | `"A record with this data already exists."` |
| `PUT` / `DELETE` on an unknown `id` | 404 | `"Social link not found: {id}"` |
| No token, or a non-admin token | 401 / 403 | standard error envelope |

## 5. Try it

```bash
API=https://your-backend-url        # your Railway URL

# 1. Read what the website sees (no login needed)
curl "$API/api/v1/settings/social"

# 2. Read what the dashboard sees — hidden rows included
curl "$API/api/v1/settings/social?includeInactive=true"

# 3. Log in and copy the token from the answer
curl -X POST "$API/api/auth/login" \
  -H "Content-Type: application/json" \
  -d '{"username":"YOUR_ADMIN","password":"YOUR_PASSWORD"}'

TOKEN=paste_the_token_here

# 4. Add a link  → 201 Created
curl -X POST "$API/api/v1/settings/social" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"platform":"FACEBOOK","url":"https://facebook.com/KurdishHeritage","displayOrder":0,"active":true}'

# 5. Change a link (id = 1) — send every field
curl -X PUT "$API/api/v1/settings/social/1" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"platform":"FACEBOOK","url":"https://facebook.com/NEW_PAGE","displayOrder":0,"active":true}'

# 6. Hide a link instead of deleting it
curl -X PUT "$API/api/v1/settings/social/1" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"platform":"FACEBOOK","url":"https://facebook.com/NEW_PAGE","displayOrder":0,"active":false}'

# 7. Delete a link
curl -X DELETE "$API/api/v1/settings/social/1" -H "Authorization: Bearer $TOKEN"
```

Add one row per platform the same way: `INSTAGRAM`, `YOUTUBE`, `WHATSAPP`.

## 6. Dashboard checklist

1. Load the list with `?includeInactive=true` so hidden rows are visible.
2. Show `active` as a toggle. Switching it off is a `PUT` with `"active": false`
   and every other field unchanged — the row stays in the dashboard list and can
   be switched back on.
3. `platform` is the unique key. When creating, expect a **409** if the platform
   already has a row; the right message is "this platform already has a link,
   edit it instead."
4. Sort by `displayOrder`; the API already returns them in that order.

## 7. Rules to remember

1. **One row per platform.** `platform` is unique — a second `FACEBOOK` row is rejected with 409.
2. **Platform names are fixed words**, saved in UPPERCASE. The website today shows
   only `FACEBOOK`, `INSTAGRAM`, `YOUTUBE`, `WHATSAPP`. A new platform such as
   `TIKTOK` will be stored and returned, but it will not appear on the site until
   the website side adds an icon for it.
3. **`active: false` is the safe way to hide a link** — better than deleting it.
4. **`GET` is public, including `?includeInactive=true`.** An inactive row is
   hidden from the website, *not* secret — anyone who knows the parameter can read
   it. Never put a private link (a personal WhatsApp, an admin URL) in this table,
   even switched off.

## 8. The website's cache

The website caches the answer under the tag `social`. After a change, either wait
for the cache window to pass, or purge it immediately:

```bash
curl -X POST "https://your-website-url/api/revalidate" \
  -H "x-revalidation-secret: $REVALIDATION_SECRET" \
  -H "Content-Type: application/json" \
  -d '{"tags":["social"]}'
```

## 9. Still open (website side, not backend)

The **contact page** already reads these links from the database. The **footer**
still uses links written in the code (`src/lib/mock/contact.ts`). Until the footer
is switched over, changing a URL in the dashboard updates the contact page but not
the footer icons. Small job, website side.
