# Nav Menu — Backend & Dashboard Guide

Move the hamburger menu (its links, its bilingual text, and the background photo
behind each link) out of hardcoded frontend config and into the CMS, so an editor
can change it from the dashboard without a deploy.

| | |
| --- | --- |
| Backend | `/Users/khi/Desktop/khi_backend` — Spring Boot 4, Java 21, PostgreSQL |
| Dashboard | `/Users/khi/Desktop/khi-web-frontend` — Vue 3 + Vite, admin at `src/components/AdminDashboard/` |
| API base | `https://blissful-spontaneity-production.up.railway.app` |
| New path | `/api/v1/nav-menu` |

---

## 1 · The logic

The menu has **10 top-level items** (news, projects, sound, video, gallery,
writings, services, about, contact, donate). Each item has:

- a **label** and a **description** in two languages (CKB Sorani, KMR Kurmanji)
- a **link** (`/news`, `/audio`, …)
- a **background photo** — shown full-screen behind the menu when you hover that item
- **0–8 secondary links** (the small list under "گەڕان" in the right panel)

So: **two tables** — one for the items, one for their secondary links.

```
nav_menu_items                    nav_menu_links
──────────────                    ──────────────
id                                id
item_key      (unique) ────────┐  item_id  (FK) ──┘
label_ckb / label_kmr          └─<  label_ckb / label_kmr
description_ckb / _kmr            href
href                              display_order
image_url                         active
display_order
active
```

### Rules

1. **Secondary links are saved with their parent item.** The item's JSON body carries
   the whole `links[]` array and the server replaces the set. One form, one save
   button — no separate CRUD for links.
2. **Ordering is the server's job.** Items come back sorted by `display_order`, links
   likewise. If a link has no `display_order`, use its position in the array.
3. **`active = false` hides a row** from the website but keeps it in the dashboard —
   for items *and* for secondary links. The public `GET` drops both; the dashboard
   asks for `?includeInactive=true` and sees everything (§3.3).
4. **CKB is required, KMR is optional.** Blank optional strings are saved as `null`.
5. **`item_key` must not change after it is created.** For six sections (news,
   projects, sound, video, gallery, writings) the website uses this key to build the
   secondary links automatically from CMS categories/tags/topics — renaming the key
   silently breaks that link. For services/about/contact/donate the links in the
   table are the only source.
6. **Images are not stored in this table** — only the URL string. Upload goes through
   the existing shared media endpoint (§4).

---

## 2 · Tables

> No migration file is needed. This backend has no Flyway/Liquibase —
> `ddl-auto: update` creates the tables from the entity classes at boot. This SQL
> just documents the resulting shape.

```sql
CREATE TABLE nav_menu_items (
    id              BIGSERIAL     PRIMARY KEY,
    item_key        VARCHAR(60)   NOT NULL UNIQUE,
    label_ckb       VARCHAR(200)  NOT NULL,
    label_kmr       VARCHAR(200),
    description_ckb TEXT,
    description_kmr TEXT,
    href            VARCHAR(300)  NOT NULL,
    image_url       TEXT,
    display_order   INTEGER       NOT NULL DEFAULT 0,
    active          BOOLEAN       NOT NULL DEFAULT TRUE
);

CREATE TABLE nav_menu_links (
    id            BIGSERIAL     PRIMARY KEY,
    item_id       BIGINT        NOT NULL REFERENCES nav_menu_items (id) ON DELETE CASCADE,
    label_ckb     VARCHAR(200)  NOT NULL,
    label_kmr     VARCHAR(200),
    href          VARCHAR(300)  NOT NULL,
    display_order INTEGER       NOT NULL DEFAULT 0,
    active        BOOLEAN       NOT NULL DEFAULT TRUE
);
```

---

## 3 · Endpoints

Five endpoints. No pagination — it is ten rows.

| Method | Path | Auth | Purpose | Success |
| --- | --- | --- | --- | --- |
| `GET` | `/api/v1/nav-menu` | public | whole menu, active only, with links | `200` |
| `GET` | `/api/v1/nav-menu/{id}` | public | one item (for the edit form) | `200` |
| `POST` | `/api/v1/nav-menu` | ADMIN | create | `201` |
| `PUT` | `/api/v1/nav-menu/{id}` | ADMIN | update (replaces its links) | `200` |
| `DELETE` | `/api/v1/nav-menu/{id}` | ADMIN | delete (links cascade) | `200` |

Writes need `Authorization: Bearer <token>` on an `ADMIN` or `SUPER_ADMIN` account —
`EMPLOYEE` and `GUEST` get `403`. Reads need nothing.

`GET` takes one optional param: `?includeInactive=true` — the dashboard list uses it,
the website does not.

### 3.1 Request fields (POST / PUT)

Both verbs take the same body. Strings are trimmed; a blank optional string is
stored as `null`.

| Field | Type | Required | Notes |
| --- | --- | --- | --- |
| `itemKey` | string ≤ 60 | **yes** | lower-cased on save (`"News"` → `"news"`); unique, case-insensitive |
| `labelCkb` | string ≤ 200 | **yes** | |
| `labelKmr` | string ≤ 200 | no | |
| `descriptionCkb` | text | no | |
| `descriptionKmr` | text | no | |
| `href` | string ≤ 300 | **yes** | site-relative, e.g. `/news` |
| `imageUrl` | text | no | absolute URL from `/api/v1/media/upload` (§4) |
| `displayOrder` | integer | no | omitted → `0` |
| `active` | boolean | no | omitted → `true` |
| `links` | array | no | see below — omitted ≠ `[]` |

Each entry of `links`:

| Field | Type | Required | Notes |
| --- | --- | --- | --- |
| `labelCkb` | string ≤ 200 | **yes** | |
| `labelKmr` | string ≤ 200 | no | |
| `href` | string ≤ 300 | **yes** | |
| `displayOrder` | integer | no | omitted → its 1-based position in the array |
| `active` | boolean | no | omitted → `true` |

Link ids are never sent by the client — the server drops the old rows and inserts
the array as given, so every save mints fresh link ids.

Extra fields are ignored rather than rejected: you can take a response object and
`PUT` it straight back, `id`s and all. A posted `id` is not honoured — the item id
comes from the URL and link ids are always regenerated. Building an explicit DTO
(§6.6) is still the house rule, since that is what does the trimming and the
blank → `null` conversion.

| `links` value | Effect |
| --- | --- |
| omitted / `null` | existing links left untouched |
| `[]` | all links removed |
| `[ … ]` | the whole set is replaced by this array |

### 3.2 POST `/api/v1/nav-menu`

```json
{
  "itemKey": "news",
  "labelCkb": "هەواڵ",
  "labelKmr": "Nûçe",
  "descriptionCkb": "هەواڵە لێکۆڵینەوەییەکان لەسەر کەلتوور و مێژوو.",
  "descriptionKmr": "Nûçeyên lêkolînê li ser çand û dîrok.",
  "href": "/news",
  "imageUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/abc-news.jpg",
  "displayOrder": 1,
  "active": true,
  "links": [
    { "labelCkb": "کەلتوور", "labelKmr": "Çand",  "href": "/news?category=culture" },
    { "labelCkb": "مێژوو",   "labelKmr": "Dîrok", "href": "/news?category=history" }
  ]
}
```

`201 Created` — the saved row, ids and defaults filled in:

```json
{
  "success": true,
  "message": "Nav menu item created",
  "data": {
    "id": 1,
    "itemKey": "news",
    "labelCkb": "هەواڵ",
    "labelKmr": "Nûçe",
    "descriptionCkb": "هەواڵە لێکۆڵینەوەییەکان لەسەر کەلتوور و مێژوو.",
    "descriptionKmr": "Nûçeyên lêkolînê li ser çand û dîrok.",
    "href": "/news",
    "imageUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/abc-news.jpg",
    "displayOrder": 1,
    "active": true,
    "links": [
      { "id": 1, "labelCkb": "کەلتوور", "labelKmr": "Çand",  "href": "/news?category=culture", "displayOrder": 1, "active": true },
      { "id": 2, "labelCkb": "مێژوو",   "labelKmr": "Dîrok", "href": "/news?category=history", "displayOrder": 2, "active": true }
    ]
  }
}
```

**Null fields are omitted, not returned as `null`.** An item saved with only the
three required fields comes back without `labelKmr`, `descriptionCkb`,
`descriptionKmr` or `imageUrl` at all:

```json
{
  "success": true,
  "message": "Nav menu item created",
  "data": {
    "id": 1,
    "itemKey": "donate",
    "labelCkb": "بەخشین",
    "href": "/donate",
    "displayOrder": 0,
    "active": true,
    "links": []
  }
}
```

So the editor must fill its form defensively — `form.labelKmr = item.labelKmr ?? ''`
for every optional field — or `v-model` binds `undefined` and the input goes
uncontrolled. `displayOrder`, `active` and `links` are always present.

### 3.3 GET `/api/v1/nav-menu`

Items sorted by `display_order` then `id`, links likewise. Same object shape as
above, wrapped in an array:

```json
{
  "success": true,
  "message": "Nav menu fetched",
  "data": [
    {
      "id": 1,
      "itemKey": "news",
      "labelCkb": "هەواڵ",
      "labelKmr": "Nûçe",
      "descriptionCkb": "هەواڵە لێکۆڵینەوەییەکان لەسەر کەلتوور و مێژوو.",
      "descriptionKmr": "Nûçeyên lêkolînê li ser çand û dîrok.",
      "href": "/news",
      "imageUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/abc-news.jpg",
      "displayOrder": 1,
      "active": true,
      "links": [
        { "id": 1, "labelCkb": "کەلتوور", "labelKmr": "Çand",  "href": "/news?category=culture", "displayOrder": 1, "active": true },
        { "id": 2, "labelCkb": "مێژوو",   "labelKmr": "Dîrok", "href": "/news?category=history", "displayOrder": 2, "active": true }
      ]
    }
  ]
}
```

What `includeInactive` changes — it filters **links** as well as items:

| Call | Items returned | Links returned |
| --- | --- | --- |
| `GET /api/v1/nav-menu` | `active: true` only | `active: true` only |
| `GET /api/v1/nav-menu?includeInactive=true` | all | all |
| `GET /api/v1/nav-menu/{id}` | the one asked for | all — it feeds the edit form |

An empty menu is `"data": []`, never `null`.

### 3.4 PUT `/api/v1/nav-menu/{id}`

Full replace, not a patch: **every field you omit is reset to its default**
(`displayOrder` → `0`, `active` → `true`, optional strings → `null`). Send the whole
object back, which is what the dashboard's `submit()` in §6.6 does. The single
exception is `links`, per the table in §3.1.

```json
{
  "itemKey": "news",
  "labelCkb": "هەواڵ",
  "labelKmr": "Nûçe",
  "href": "/news",
  "imageUrl": "https://s3-khiwebsite.s3.us-east-1.amazonaws.com/khi-web-folders/images/abc-news.jpg",
  "displayOrder": 1,
  "active": true,
  "links": [
    { "labelCkb": "ژینگە", "labelKmr": "Jîngeh", "href": "/news?category=nature", "displayOrder": 1, "active": true }
  ]
}
```

`200 OK`, `"message": "Nav menu item updated"`, `data` shaped exactly like §3.2 —
the two old links are gone and the new one comes back with a fresh `id`.

`itemKey` may be sent unchanged (it is not a conflict against itself), but changing
it breaks the website's automatic secondary links — see rule 5.

### 3.5 DELETE `/api/v1/nav-menu/{id}`

Links cascade. There is no `data` key on the response — the envelope omits nulls:

```json
{
  "success": true,
  "message": "Nav menu item deleted"
}
```

### 3.6 Errors

Failures do **not** use the `success/message/data` envelope. They use the standard
error envelope from `GlobalExceptionHandler`:

| Status | `code` | When |
| --- | --- | --- |
| `400` | `VALIDATION_ERROR` | a required field is missing or a length cap is exceeded |
| `403` | — | not logged in, or role below `ADMIN`, on a write |
| `404` | `NOT_FOUND` | no item with that id (GET one, PUT, DELETE) |
| `409` | `CONFLICT` | `itemKey` already belongs to another item |

`400` — `fieldErrors` names the offending path, nested links included:

```json
{
  "timestamp": "2026-08-15T19:43:04.487091Z",
  "status": 400,
  "path": "/api/v1/nav-menu",
  "method": "POST",
  "traceId": "509f8af1-08d1-43ed-8588-abdf50f931c2",
  "code": "VALIDATION_ERROR",
  "message": "One or more fields failed validation.",
  "messageEn": "One or more fields failed validation.",
  "messageKu": "هەڵەی پشکنینەوە لە کێبڕکێی یان زیاتر.",
  "fieldErrors": [
    { "field": "links[0].href", "message": "must not be blank", "messageEn": "must not be blank", "messageKu": "must not be blank" },
    { "field": "itemKey",       "message": "must not be blank", "messageEn": "must not be blank", "messageKu": "must not be blank" }
  ]
}
```

`409` — `details.itemKey` echoes the key that clashed, already normalized:

```json
{
  "timestamp": "2026-08-15T19:43:54.758681Z",
  "status": 409,
  "path": "/api/v1/nav-menu",
  "method": "POST",
  "traceId": "2b567e61-acf5-4936-8e2a-e6f4e6efd66c",
  "code": "CONFLICT",
  "message": "ئەم کلیلە (itemKey) پێشتر بەکارهاتووە.",
  "messageEn": "Conflict",
  "messageKu": "کێشەی تێکچوون هەیە",
  "details": { "itemKey": "news" }
}
```

`404` — `details.id` echoes the id:

```json
{
  "timestamp": "2026-08-15T19:43:04.472539Z",
  "status": 404,
  "path": "/api/v1/nav-menu/9999",
  "method": "GET",
  "traceId": "370012e4-ec65-42ff-915f-3ffa48f21280",
  "code": "NOT_FOUND",
  "message": "Resource not found",
  "messageEn": "Resource not found",
  "messageKu": "سەرچاوە نەدۆزرایەوە",
  "details": { "id": 9999 }
}
```

Two things the dashboard has to handle:

- **`403` has an empty body.** `e?.response?.data?.message` is `undefined`, so the
  toast must fall back to its own text (the §6.6 snippet already does).
- **Send `Accept-Language: ckb`** (or `kmr`) to get the Kurdish `message`. Without
  it you get the generic English fallback — `"Conflict"`, `"Resource not found"` —
  because the request defaults to the English bundle, whose file is currently
  misnamed (see §5). `messageKu` is always populated regardless.

### 3.7 curl

```bash
BASE=https://blissful-spontaneity-production.up.railway.app
TOKEN=<admin jwt>

# public read
curl "$BASE/api/v1/nav-menu"

# dashboard read
curl "$BASE/api/v1/nav-menu?includeInactive=true" -H "Authorization: Bearer $TOKEN"

# create
curl -X POST "$BASE/api/v1/nav-menu" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -H "Accept-Language: ckb" \
  -d '{"itemKey":"news","labelCkb":"هەواڵ","href":"/news",
       "links":[{"labelCkb":"کەلتوور","href":"/news?category=culture"}]}'

# update, delete
curl -X PUT    "$BASE/api/v1/nav-menu/1" -H "Authorization: Bearer $TOKEN" \
     -H "Content-Type: application/json" -d '{"itemKey":"news","labelCkb":"هەواڵ","href":"/news"}'
curl -X DELETE "$BASE/api/v1/nav-menu/1" -H "Authorization: Bearer $TOKEN"
```

---

## 4 · Images

**No new upload code.** Reuse the existing endpoint and store the URL it returns:

```http
POST /api/v1/media/upload
Content-Type: multipart/form-data
Authorization: Bearer <token>

file=<binary>     (required)
type=image        (optional, defaults to "image")
```

```json
{ "success": true, "data": { "fileUrl": "https://s3-khiwebsite.s3.../abc-photo.jpg", "fileName": "photo.jpg" } }
```

Take `data.fileUrl` → send it as `imageUrl`. The URL is absolute and public.

Recommend **2000px+ wide** images to editors — this photo fills the entire screen
behind the menu, and anything smaller looks soft on a large monitor.

---

## 5 · Backend guide

> **Built.** All six files exist under `ak.dev.khi_backend.khi_app`, plus the
> `SecurityConfig` rules below, two i18n keys, and
> `src/test/java/.../khi_app/api/site/NavMenuIntegrationTests.java` — eight MockMvc
> tests over H2 covering create/list/update/delete, link replacement, the
> inactive filter, the duplicate key and the role rules. The sections below are
> what shipped.

Files under `ak.dev.khi_backend.khi_app` (same layout as the existing `site`
module — `TeamMember` is the closest example to copy):

```
model/site/NavMenuItem.java
model/site/NavMenuLink.java
repository/site/NavMenuItemRepository.java
dto/site/NavMenuDtos.java
service/site/NavMenuService.java
api/site/NavMenuController.java
```

Three details the sketches below do not show, all in the shipped code:

- `@ToString.Exclude @EqualsAndHashCode.Exclude` on `NavMenuItem.links` and
  `NavMenuLink.item` — plain `@Data` on both sides recurses forever and touches a
  lazy proxy.
- `findById` is overridden with `@EntityGraph(attributePaths = "links")` too, not
  just the two list queries.
- Not-found throws `NotFoundException("navMenu.not_found", Map.of("id", id))` —
  the convention in the newer `WritingService`/`ServiceService`, rather than the
  older `EntityNotFoundException` in `SiteContentService`. Both map to `404`.

The two message keys live in `messages_ckb.properties` and
`messages_kmr.properties`: `navMenu.not_found`, `navMenu.itemKey.duplicate`.
They are in the English bundle as well, but that file is named
`" messages_en.properties"` — with a leading space — so it does not match the
`classpath:i18n/messages` basename and has never loaded. Pre-existing, affects
every endpoint, not just this one; renaming it is the fix.

### Entities

```java
@Entity
@Table(name = "nav_menu_items",
       uniqueConstraints = @UniqueConstraint(name = "uk_nav_item_key", columnNames = "item_key"))
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class NavMenuItem {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "item_key", nullable = false, length = 60) private String itemKey;

    @Column(name = "label_ckb", nullable = false, length = 200) private String labelCkb;
    @Column(name = "label_kmr", length = 200)                   private String labelKmr;

    @Column(name = "description_ckb", columnDefinition = "TEXT") private String descriptionCkb;
    @Column(name = "description_kmr", columnDefinition = "TEXT") private String descriptionKmr;

    @Column(nullable = false, length = 300)               private String href;
    @Column(name = "image_url", columnDefinition = "TEXT") private String imageUrl;

    @Column(name = "display_order") @Builder.Default private Integer displayOrder = 0;
    @Builder.Default private boolean active = true;

    @OneToMany(mappedBy = "item", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("displayOrder ASC, id ASC")
    @Builder.Default
    private List<NavMenuLink> links = new ArrayList<>();
}
```

```java
@Entity
@Table(name = "nav_menu_links")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class NavMenuLink {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "item_id", nullable = false)
    private NavMenuItem item;

    @Column(name = "label_ckb", nullable = false, length = 200) private String labelCkb;
    @Column(name = "label_kmr", length = 200)                   private String labelKmr;
    @Column(nullable = false, length = 300)                     private String href;

    @Column(name = "display_order") @Builder.Default private Integer displayOrder = 0;
    @Builder.Default private boolean active = true;
}
```

### Repository

```java
public interface NavMenuItemRepository extends JpaRepository<NavMenuItem, Long> {

    @EntityGraph(attributePaths = "links")
    List<NavMenuItem> findAllByActiveTrueOrderByDisplayOrderAscIdAsc();

    @EntityGraph(attributePaths = "links")
    List<NavMenuItem> findAllByOrderByDisplayOrderAscIdAsc();

    boolean existsByItemKeyIgnoreCase(String itemKey);
    boolean existsByItemKeyIgnoreCaseAndIdNot(String itemKey, Long id);
}
```

`@EntityGraph` loads the links in the same query — without it you get a
`LazyInitializationException`, because `open-in-view` is off.

### Service

One private `apply(entity, request)` shared by create and update, exactly like
`SiteContentService` does for team members:

```java
@Slf4j @Service @RequiredArgsConstructor
public class NavMenuService {

    private final NavMenuItemRepository repository;

    @Transactional(readOnly = true)
    public List<NavMenuItemResponse> list(boolean includeInactive) {
        var items = includeInactive
                ? repository.findAllByOrderByDisplayOrderAscIdAsc()
                : repository.findAllByActiveTrueOrderByDisplayOrderAscIdAsc();
        return items.stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public NavMenuItemResponse get(Long id) {
        return toResponse(repository.findById(id).orElseThrow(() -> notFound(id)));
    }

    @Transactional
    public NavMenuItemResponse create(NavMenuItemRequest r) {
        if (repository.existsByItemKeyIgnoreCase(r.getItemKey().trim()))
            throw new ConflictException("navMenu.itemKey.duplicate", Map.of("itemKey", r.getItemKey()));
        var item = new NavMenuItem();
        apply(item, r);
        return toResponse(repository.save(item));
    }

    @Transactional
    public NavMenuItemResponse update(Long id, NavMenuItemRequest r) {
        var item = repository.findById(id).orElseThrow(() -> notFound(id));
        if (repository.existsByItemKeyIgnoreCaseAndIdNot(r.getItemKey().trim(), id))
            throw new ConflictException("navMenu.itemKey.duplicate", Map.of("itemKey", r.getItemKey()));
        apply(item, r);
        return toResponse(repository.save(item));
    }

    @Transactional
    public void delete(Long id) {
        if (!repository.existsById(id)) throw notFound(id);
        repository.deleteById(id);
    }

    private void apply(NavMenuItem item, NavMenuItemRequest r) {
        item.setItemKey(r.getItemKey().trim().toLowerCase());
        item.setLabelCkb(r.getLabelCkb().trim());
        item.setLabelKmr(trimToNull(r.getLabelKmr()));
        item.setDescriptionCkb(trimToNull(r.getDescriptionCkb()));
        item.setDescriptionKmr(trimToNull(r.getDescriptionKmr()));
        item.setHref(r.getHref().trim());
        item.setImageUrl(trimToNull(r.getImageUrl()));
        item.setDisplayOrder(r.getDisplayOrder() == null ? 0 : r.getDisplayOrder());
        item.setActive(r.getActive() == null || r.getActive());

        if (r.getLinks() == null) return;          // null = leave links alone

        item.getLinks().clear();                    // orphanRemoval deletes the old rows
        for (int i = 0; i < r.getLinks().size(); i++) {
            var lr = r.getLinks().get(i);
            var link = new NavMenuLink();
            link.setItem(item);
            link.setLabelCkb(lr.getLabelCkb().trim());
            link.setLabelKmr(trimToNull(lr.getLabelKmr()));
            link.setHref(lr.getHref().trim());
            link.setDisplayOrder(lr.getDisplayOrder() == null ? i + 1 : lr.getDisplayOrder());
            link.setActive(lr.getActive() == null || lr.getActive());
            item.getLinks().add(link);
        }
    }
}
```

### Controller

```java
@RestController
@RequestMapping("/api/v1/nav-menu")
@RequiredArgsConstructor
@Tag(name = "Nav Menu", description = "Website hamburger menu items and background images")
public class NavMenuController {

    private final NavMenuService service;

    @GetMapping
    public ApiResponse<List<NavMenuItemResponse>> list(
            @RequestParam(defaultValue = "false") boolean includeInactive) {
        return ApiResponse.success(service.list(includeInactive), "Nav menu fetched");
    }

    @GetMapping("/{id}")
    public ApiResponse<NavMenuItemResponse> get(@PathVariable Long id) {
        return ApiResponse.success(service.get(id), "Nav menu item fetched");
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<NavMenuItemResponse> create(@Valid @RequestBody NavMenuItemRequest request) {
        return ApiResponse.success(service.create(request), "Nav menu item created");
    }

    @PutMapping("/{id}")
    public ApiResponse<NavMenuItemResponse> update(@PathVariable Long id,
                                                   @Valid @RequestBody NavMenuItemRequest request) {
        return ApiResponse.success(service.update(id, request), "Nav menu item updated");
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ApiResponse.success(null, "Nav menu item deleted");
    }
}
```

### ⚠️ Security — do not skip this

`SecurityConfig` makes every `GET /api/v1/**` public automatically, but an
unlisted **write** path falls through to "any logged-in user" — including `GUEST`.

Shipped as one extra line in each of the three existing admin-only blocks (the ones
already holding `/api/v1/settings/social/**`), which sit above the
`GET /api/v1/**` catch-all:

```java
.requestMatchers(HttpMethod.POST,
        "/api/v1/featured/**",
        // … the paths that were already listed …
        "/api/v1/nav-menu/**"          // ← added
).hasAnyRole("ADMIN", "SUPER_ADMIN")
// same one-line addition in the PUT and DELETE blocks
```

`/api/v1/nav-menu/**` also matches the bare `/api/v1/nav-menu`, so `POST` to the
collection is covered. Verified by `writesAreAdminOnlyWhileReadsArePublic` —
`EMPLOYEE` and `GUEST` get `403` on POST/PUT/DELETE, anonymous `GET` gets `200`.

---

## 6 · Dashboard guide

Vue 3 + Vite, plain JavaScript, no UI kit and no form library — screens are
hand-written and the house workflow is to copy the closest existing screen.
**Copy `pages/contact/ContactEditor.vue`** — it is a single record with bilingual
fields and an image upload, which is exactly this shape.

### 6.1 Files

```
src/components/AdminDashboard/pages/menu/MenuList.vue     ← copy pages/services/ServiceList.vue
src/components/AdminDashboard/pages/menu/MenuEditor.vue   ← copy pages/contact/ContactEditor.vue
```

### 6.2 Routes

In `src/router.js`, **above** the generic `:resource` catch-all routes (they are
marked "MUST be last" — routes added after them never match):

```js
{ path: 'menu',          name: 'AdminMenuList',   component: () => import('@/components/AdminDashboard/pages/menu/MenuList.vue') },
{ path: 'menu/new',      name: 'AdminMenuCreate', component: () => import('@/components/AdminDashboard/pages/menu/MenuEditor.vue') },
{ path: 'menu/:id/edit', name: 'AdminMenuEdit',   component: () => import('@/components/AdminDashboard/pages/menu/MenuEditor.vue'), props: true },
```

Auth needs nothing — the parent `/admin` route already carries
`meta: { requiresAuth: true, roles: [...] }`.

### 6.3 Sidebar

In `src/components/Sidebar.vue`, three edits — all three are needed, the third is
the one people forget (without it the item never highlights):

```html
<!-- 1. link in <nav>, in the ڕێکخستن group next to About/Contact -->
<RouterLink class="nav-item" :to="{ name: 'AdminMenuList' }"
            :class="{ 'nav-item--active': isActive('menu') }" title="مێنیوی ماڵپەڕ">
  <span class="nav-item__ico" v-html="SVGs.menu"></span>
  <Transition name="label"><span v-if="!slim" class="nav-item__label">مێنیوی ماڵپەڕ</span></Transition>
</RouterLink>
```

```js
// 2. an icon in the SVGs object
menu: '<svg …></svg>',

// 3. an entry in routeMap inside isActive()
menu: { names: ['AdminMenuList','AdminMenuCreate','AdminMenuEdit'], prefix: '/admin/menu' },
```

### 6.4 API calls

Import the shared axios instance. There is no per-resource service layer, and
`baseURL` is the bare host, so write the full path every time. The token is added
automatically by the interceptor in `src/api.js` — never set headers yourself.

```js
import api from '@/api.js'

// list — dashboard wants inactive rows too, and a fresh read
const { data } = await api.get('/api/v1/nav-menu', {
  params: { includeInactive: true },
  _skipCache: true,
})
const items = data?.data ?? data          // always unwrap defensively

const one = await api.get(`/api/v1/nav-menu/${route.params.id}`)
await api.post('/api/v1/nav-menu', dto)
await api.put(`/api/v1/nav-menu/${route.params.id}`, dto)
await api.delete(`/api/v1/nav-menu/${route.params.id}`)
```

### 6.5 Image upload

Upload first, then store the returned URL — so the item payload stays plain JSON:

```js
const imageUploading = ref(false)

async function uploadImage(event) {
  const file = event.target.files?.[0]
  if (!file) return
  imageUploading.value = true
  try {
    const fd = new FormData()
    fd.append('file', file)
    fd.append('type', 'image')
    const { data } = await api.post('/api/v1/media/upload', fd, {
      headers: { 'Content-Type': 'multipart/form-data' },
    })
    const payload = data?.data ?? data
    form.imageUrl = payload?.fileUrl ?? payload?.url ?? ''
    showToast('success', 'وێنە بارکرا ✓')
  } catch (e) {
    showToast('error', e?.response?.data?.message || 'بارکردن سەرکەوتوو نەبوو')
  } finally {
    imageUploading.value = false
    event.target.value = ''        // so the same file can be picked again
  }
}
```

> ⚠️ Do **not** copy the endpoint from `ContactEditor.vue` or `AdminAboutEditor.vue` —
> they call `/api/v1/contact/upload` and `/api/v1/about/upload`, which **do not exist**
> on the backend. The correct one is `/api/v1/media/upload`.

Markup — preview, a paste-a-URL input, and an upload button; the file input is
hidden inside a `<label>`, per house style:

```html
<div class="hero-field">
  <div class="hero-preview" :class="{ 'hero-preview--empty': !form.imageUrl }">
    <img v-if="form.imageUrl" :src="form.imageUrl" class="hero-preview__img" loading="lazy" />
    <div v-else class="hero-preview__placeholder">
      <span>وێنە هەڵنەبژێردراوە</span>
      <span class="hero-preview__hint">پێشنیاری پێوانە: 2000 × 1400px</span>
    </div>
    <button v-if="form.imageUrl" type="button" class="hero-preview__remove" @click="form.imageUrl = ''">✕</button>
  </div>

  <div class="hero-input-row">
    <input v-model="form.imageUrl" class="inp" dir="ltr" placeholder="https://… یان فایل باربکە" />
    <label class="upload-btn" :class="{ 'upload-btn--loading': imageUploading }">
      {{ imageUploading ? 'بارکردن…' : 'بارکردن' }}
      <input type="file" hidden accept="image/*" @change="uploadImage" />
    </label>
  </div>
</div>
```

### 6.6 The form

```js
const form = reactive({
  itemKey: '',
  labelCkb: '', labelKmr: '',
  descriptionCkb: '', descriptionKmr: '',
  href: '',
  imageUrl: '',
  displayOrder: 0,
  active: true,
  links: [],
})

const addLink    = ()  => form.links.push({ labelCkb: '', labelKmr: '', href: '', active: true })
const removeLink = (i) => form.links.splice(i, 1)
const moveLink   = (i, dir) => {
  const j = i + dir
  if (j < 0 || j >= form.links.length) return
  const [row] = form.links.splice(i, 1)
  form.links.splice(j, 0, row)
}
```

Save — build an explicit DTO, never post `form` directly:

```js
const submit = async () => {
  if (!validate()) { window.scrollTo({ top: 0, behavior: 'smooth' }); return }
  saving.value = true
  const dto = {
    itemKey: form.itemKey.trim(),
    labelCkb: form.labelCkb.trim(),
    labelKmr: form.labelKmr.trim() || null,
    descriptionCkb: form.descriptionCkb.trim() || null,
    descriptionKmr: form.descriptionKmr.trim() || null,
    href: form.href.trim(),
    imageUrl: form.imageUrl.trim() || null,
    displayOrder: Number(form.displayOrder) || 0,
    active: !!form.active,
    links: form.links
      .filter(l => l.labelCkb.trim() && l.href.trim())
      .map((l, i) => ({
        labelCkb: l.labelCkb.trim(),
        labelKmr: l.labelKmr.trim() || null,
        href: l.href.trim(),
        displayOrder: i + 1,
        active: l.active !== false,
      })),
  }
  try {
    if (isEdit.value) await api.put(`/api/v1/nav-menu/${route.params.id}`, dto)
    else              await api.post('/api/v1/nav-menu', dto)
    showToast('success', isEdit.value ? 'مێنیو نوێکرایەوە ✓' : 'مێنیو دروستکرا ✓')
    setTimeout(() => router.push('/admin/menu'), 1200)
  } catch (e) {
    showToast('error', e?.response?.data?.message || 'هەڵەیەک ڕوویدا')
  } finally {
    saving.value = false
  }
}
```

Validation is a hand-written `validate()` returning an errors object (no Vuelidate,
even though it is in `package.json`). Minimum: `itemKey`, `labelCkb` and `href`
required, `href` starts with `/`, every link has a label and an href.

### 6.7 Hints to show the editor

- **`itemKey`** — lock it for existing items. Changing it disconnects that section
  from its automatic links on the website.
- **Secondary links** — for هەواڵ، پڕۆژە، دەنگ، دەنگ و ڕەنگ، وێنە، نووسین the website
  builds this list automatically from CMS categories/tags/topics; what you type here
  only shows if that automatic list is empty. For خزمەتگوزاری and ئێمە, what you type
  here is the only source.
- The website shows at most **8** secondary links per section.

---

## 7 · Website side (for reference)

The Next.js site adds `src/lib/api/nav-menu.ts` calling `GET /api/v1/nav-menu`, and
keeps the current `NAV_ITEMS` array in `src/config/site.ts` as the offline fallback
(API failures return `null` there rather than throwing). Two small notes:

- The site picks the language with `locale === "ckb" ? labelCkb : labelKmr` — the
  site's `ku` locale is the backend's `KMR`.
- `publications-dropdown.tsx` reads `NAV_ITEMS` synchronously at module load, so it
  needs converting to a server-fed component before the static config can go away.
