# Book genres — backend guide (from enum to editor-managed rows)

The genre chips on the writings page (شیعر، ڕۆمان، مێژوو، هونەر…) and the genre
list on every book used to come from a **fixed Java enum** (`BookGenre`, 22
values). Nobody could add a genre, rename one, or retire one without a code
change and a redeploy — and the names shown to readers lived in the website's
translation files, not in the CMS at all.

Genres are now rows in the database: the dashboard does full CRUD on them,
books link to them, and the enum survives only as a request-compatibility shim.

Written: 2026-09-03. **Implemented: 2026-09-03** — backend live; website switch
(§10) pending.

---

## 1. The shape of the change (as implemented)

1. **A new `book_genres` table** with CRUD endpoints at `/api/v1/book-genres` —
   the social-links pattern plus one extra column (`slug`).
2. **Books link to genre rows** through the `book_genre_links (book_id,
   genre_id)` join table. The entity's old enum `@ElementCollection` is gone.
3. **A one-time migration** (`BookGenreSeeder`, an idempotent
   `ApplicationRunner`) seeds the 22 genres with their bilingual names (§6) and
   converts every book's enum values into link rows at first boot after deploy.

The public book responses keep their `bookGenres` string array (now the linked
rows' slugs — byte-identical for the seeded genres), so the website keeps
working unchanged on deploy day (§5).

## 2. Where everything lives

| Thing | File |
| --- | --- |
| Table / entity | `khi_app/model/publishment/writing/BookGenre.java` |
| Database queries | `khi_app/repository/publishment/writing/BookGenreRepository.java` |
| CRUD + genre resolution | `khi_app/service/publishment/writing/BookGenreService.java` |
| Endpoints | `khi_app/api/publishment/writing/BookGenreController.java` |
| Migration / seed | `khi_app/service/publishment/writing/BookGenreSeeder.java` |
| Request / response shapes | `khi_app/dto/publishment/writing/WritingDtos.java` — `BookGenreRequest` / `BookGenreResponse` / `GenreInfo` |
| Book side | `Writing.getGenres()` (`@ManyToMany`), wired in `WritingService` |
| Who is allowed | `user/configs/SecurityConfig.java` — same rules as social links |
| Legacy enum (request shim) | `khi_app/enums/publishment/BookGenre.java` — delete once the dashboard sends `genreIds` |
| Tests | `BookGenreServiceTests.java` (plus the existing enum tests) |

Tables are created by Hibernate (`ddl-auto: update`) on first boot; the seeder
runs right after.

## 3. The table: `book_genres`

| Column | Type | Meaning |
| --- | --- | --- |
| `id` | number | Auto-generated. |
| `slug` | text (max 60), **unique**, required | Stable machine key, UPPERCASE (`POETRY`, `NOVEL`…). Used in website URLs (`/writings?genre=POETRY`) and kept equal to the old enum codes for the seeded rows. **Never changes after creation** — renaming a genre means changing its names, not its slug. |
| `name_ckb` | text (max 200) | Name in Sorani — what the chip shows. |
| `name_kmr` | text (max 200) | Name in Kurmanji. |
| `display_order` | number | Small number first (0, 1, 2…). The chips row follows this order. Defaults to 0. |
| `active` | true/false | `false` = chip hidden on the website; books keep the link. Defaults to `true`. |

Validation: at least one of `name_ckb` / `name_kmr` must be non-blank; `slug`
required, trimmed, upper-cased, letters/digits/underscore only. Blank names are
saved as `null`.

Join table: `book_genre_links (book_id, genre_id)` — plain many-to-many. The
service detaches link rows itself on genre delete, and Hibernate removes them
when a book is deleted.

## 4. The endpoints

Base path: `/api/v1/book-genres`

| Method | Path | Success status | Who can call it |
| --- | --- | --- | --- |
| `GET` | `/api/v1/book-genres` | 200 | **Everyone** — no login |
| `GET` | `/api/v1/book-genres?includeInactive=true` | 200 | Everyone (used by the dashboard) |
| `POST` | `/api/v1/book-genres` | 201 Created | Admin only |
| `PUT` | `/api/v1/book-genres/{id}` | 200 | Admin only |
| `DELETE` | `/api/v1/book-genres/{id}` | 200 | Admin only |

`includeInactive` works exactly like `/api/v1/settings/social` and
`/api/v1/nav-menu`: default `false`, rows sorted by `display_order` ascending.

"Admin only" = a logged-in user with role `ADMIN` or `SUPER_ADMIN`, sending
`Authorization: Bearer <token>`.

### Request body (POST and PUT)

```json
{
  "slug": "POETRY",
  "nameCkb": "شیعر",
  "nameKmr": "Şîir",
  "displayOrder": 0,
  "active": true
}
```

**`PUT` replaces the whole row.** Always send every field. A `PUT` that tries
to change `slug` on a row that has book links is rejected (400) — the slug is
the key the website filters by.

### Response

The standard envelope. `bookCount` is how many books link to the row — the
dashboard uses it to warn before delete:

```json
{
  "success": true,
  "message": "Book genres fetched",
  "data": [
    { "id": 1, "slug": "POETRY", "nameCkb": "شیعر", "nameKmr": "Şîir",
      "displayOrder": 0, "active": true, "bookCount": 42 }
  ]
}
```

### Errors

| Situation | Status | Body |
| --- | --- | --- |
| Both names blank, or missing/invalid `slug` | 400 | message naming the field |
| Second row with the same `slug` | 409 Conflict | `"A record with this data already exists."` |
| Changing `slug` on a genre that books use | 400 | `"Slug cannot change while books use this genre"` |
| `PUT` / `DELETE` on an unknown id | 404 | `"Book genre not found: {id}"` |
| No token, or a non-admin token, on write | 401 / 403 | standard error envelope |

**`DELETE` detaches the genre from every book** (the service removes the join
rows first) and the books survive untouched. Hiding a chip without touching the
books is `active: false` — always the recommendation; the dashboard says so too.

## 5. The change to books

- **Entity**: `Writing.genres` is a `@ManyToMany` to `BookGenre` rows via
  `book_genre_links` (EAGER + `@BatchSize(25)`, like the other collections).
- **Write side**: book create/update accepts `"genreIds": [1, 5, 12]`
  (preferred). The old `"bookGenres": ["POETRY"]` keeps working for one release
  — each string is normalised the way the enum always normalised
  (`POLITICAL → POLITICS`, `ACADEMIC → EDUCATIONAL`, `ESSAY → OTHER`, unknown →
  `OTHER`) and resolved against `slug`. When both are sent, `genreIds` wins.
  Create still requires at least one genre; on update, null/empty leaves the
  current genres unchanged.
- **Read side — the website does not break**: every book response keeps
  `bookGenres` as a string array, now built from the linked rows' **slugs**,
  and additionally gains the full objects:

```json
{
  "bookGenres": ["POETRY", "HISTORY"],
  "genres": [
    { "id": 1, "slug": "POETRY", "nameCkb": "شیعر", "nameKmr": "Şîir" },
    { "id": 5, "slug": "HISTORY", "nameCkb": "مێژوو", "nameKmr": "Dîrok" }
  ]
}
```

  Both arrays are ordered by the genres' `display_order`.

## 6. Deploy order — why nothing breaks

The website today **silently drops any `bookGenres` value it does not
recognise** (its normalizer filters against the 22 known codes). So:

1. **Deploy the backend.** The seeder creates and fills the tables on boot;
   slugs equal the old enum codes, so the website sees identical `bookGenres`
   arrays — zero visible change.
2. Editors may rename genres immediately in the dashboard — but the renamed
   words appear **on the website only after step 3**, because the site still
   draws its own translated labels.
3. **The website then switches** to reading `/api/v1/book-genres` for the chips
   row and the card labels, and to the `genres` objects on books. From that
   moment new genres, renames, ordering and `active` all take effect on the
   site. (Website work — tell that side when the endpoint is live.)

A genre created before step 3 is not an error: books carrying it keep
rendering; only its chip and label wait for step 3.

## 7. Migration + seed — what the seeder does

`BookGenreSeeder` runs at every boot, in a transaction, and is idempotent:

1. If `book_genres` is empty, insert the 22 rows below (`display_order` = the
   `#` column — the order readers see today).
2. If `book_genre_links` is empty and the legacy `writing_book_genres` table
   exists, insert one link per book per enum value. Legacy alias codes are
   mapped (`POLITICAL → POLITICS`, `ACADEMIC → EDUCATIONAL`, `ESSAY → OTHER`,
   `RELIGIOUS → RELIGION`); a truly unknown code gets its own new genre row
   (named after its slug, for editors to fix) — no book's genre is dropped
   silently. Alias collapse is deduplicated.
3. The legacy `writing_book_genres` table is **left untouched** as a rollback
   snapshot. Nothing reads or writes it anymore; drop it manually once the
   website's switch (§6 step 3) is confirmed.

| # | slug | nameCkb | nameKmr |
| --- | --- | --- | --- |
| 0 | POETRY | شیعر | Şîir |
| 1 | NOVEL | ڕۆمان | Roman |
| 2 | SHORT_STORY | چیرۆکی کورت | Çîroka kurt |
| 3 | DRAMA | شانۆ | Şano |
| 4 | HISTORY | مێژوو | Dîrok |
| 5 | BIOGRAPHY | ژیاننامە | Jiyanname |
| 6 | PHILOSOPHY | فەلسەفە | Felsefe |
| 7 | RELIGION | ئایین | Ol |
| 8 | FOLKLORE | زارگوتن | Zargotina gelêrî |
| 9 | POLITICS | سیاسەت | Siyaset |
| 10 | SOCIOLOGY | کۆمەڵناسی | Komelezanî |
| 11 | ECONOMICS | ئابووری | Aborî |
| 12 | LAW | یاسا | Qanûn |
| 13 | LINGUISTICS | زمانناسی | Zimanzanî |
| 14 | ARTS | هونەر | Huner |
| 15 | CULTURAL | کولتووری | Kultûrî |
| 16 | SCIENCE | زانست | Zanist |
| 17 | MEDICINE | پزیشکی | Pizîşkî |
| 18 | EDUCATIONAL | پەروەردەیی | Perwerdeyî |
| 19 | CHILDREN | منداڵان | Zarokan |
| 20 | TRAVEL | گەشتوگوزار | Ger û gerr |
| 21 | OTHER | یتر | Yên din |

## 8. Try it

```bash
API=https://your-backend-url        # your Railway URL

# 1. Read the genres (no login needed)
curl "$API/api/v1/book-genres"

# 2. Log in and copy the token
curl -X POST "$API/api/auth/login" \
  -H "Content-Type: application/json" \
  -d '{"username":"YOUR_ADMIN","password":"YOUR_PASSWORD"}'

TOKEN=paste_the_token_here

# 3. Add a genre  → 201 Created
curl -X POST "$API/api/v1/book-genres" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"slug":"MEMOIR","nameCkb":"یادەوەری","nameKmr":"Bîranîn","displayOrder":22,"active":true}'

# 4. Rename a genre (id = 1) — names change, slug stays
curl -X PUT "$API/api/v1/book-genres/1" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"slug":"POETRY","nameCkb":"شیعر و هۆنراوە","nameKmr":"Şîir","displayOrder":0,"active":true}'

# 5. Hide a chip without touching books
#    (same PUT with "active": false)

# 6. Delete a genre — detaches it from every book
curl -X DELETE "$API/api/v1/book-genres/22" -H "Authorization: Bearer $TOKEN"

# 7. Attach genres to a book by id (book update)
#    {"genreIds":[1,5,12], ...the rest of the book fields...}
```

## 9. Rules to remember

1. **`slug` is forever.** It is the key in website URLs and in the compatible
   `bookGenres` array. Rename = change `nameCkb`/`nameKmr` only.
2. **Order decides the chips row.** Lowest `display_order` first.
3. **`active: false` hides the chip, keeps the books.** The safe way to retire
   a genre.
4. **`DELETE` detaches from all books** — reserve it for mistakes, not tidying.
5. **`GET` is public, including `?includeInactive=true`** — an inactive genre
   is hidden, not secret.
6. `PUT` replaces the whole row — send every field.

## 10. The website's cache

Writings (and their genres, once the website reads them) are cached under the
tag `writings`. After a change, purge it:

```bash
curl -X POST "https://your-website-url/api/revalidate" \
  -H "x-revalidation-secret: $REVALIDATION_SECRET" \
  -H "Content-Type: application/json" \
  -d '{"tags":["writings"]}'
```

## 11. Website side — pending

The website still draws its 22 built-in genres with its own translated labels
(`BOOK_GENRES` in `src/lib/writing/genres.ts` + `messages/*.json`), and its
parser drops unknown codes — that tolerance is exactly what makes the deploy
order in §6 safe. Now that the endpoint is implemented, once it is deployed the
website will switch the chips row, the card labels and the genre filter to the
dynamic list. Tell the website side when the endpoint is deployed.
