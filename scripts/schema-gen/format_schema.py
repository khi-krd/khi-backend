#!/usr/bin/env python3
"""Reflow Hibernate's one-line-per-statement DDL into a readable schema file.

Whitespace only: every statement is preserved verbatim apart from indentation
and line breaks. Called by scripts/render-schema.sh.
"""
import re, sys

raw_path, out_path = sys.argv[1], sys.argv[2]
lines = [l.rstrip(';') for l in open(raw_path).read().splitlines() if l.strip()]


def split_top(s):
    """Split on commas that are not nested inside parentheses."""
    parts, depth, cur = [], 0, ''
    for ch in s:
        if ch == '(':
            depth += 1
        elif ch == ')':
            depth -= 1
        if ch == ',' and depth == 0:
            parts.append(cur.strip())
            cur = ''
        else:
            cur += ch
    if cur.strip():
        parts.append(cur.strip())
    return parts


seqs    = [l for l in lines if l.startswith('create sequence')]
tables  = [l for l in lines if l.startswith('create table')]
indexes = [l for l in lines if l.startswith('create index')]
alters  = [l for l in lines if l.startswith('alter table')]

TAIL = ('primary key', 'constraint', 'unique (', 'foreign key', 'check ')


def fmt_table(l):
    name = re.match(r'create table (\S+) \(', l).group(1)
    body = l[l.index('(') + 1:l.rindex(')')]
    parts = split_top(body)
    cols = [p for p in parts if not p.lower().startswith(TAIL)]
    tail = [p for p in parts if p.lower().startswith(TAIL)]
    w = min(max((len(p.split()[0]) for p in cols), default=0), 34)
    rows = []
    for p in cols:
        tok = p.split()[0]
        rows.append(f'    {tok.ljust(w)} {p[len(tok):].strip()}')
    rows += [f'    {t}' for t in tail]
    return f'create table {name} (\n' + ',\n'.join(rows) + '\n);'


def rule(title):
    return ('\n\n-- ---------------------------------------------------------------------\n'
            f'-- {title}\n'
            '-- ---------------------------------------------------------------------\n')


header = f"""-- =====================================================================
-- KHI Backend — full PostgreSQL schema
--
-- {len(tables)} tables · {sum(len([p for p in split_top(t[t.index('(')+1:t.rindex(')')]) if not p.lower().startswith(TAIL)]) for t in tables)} columns · {len(indexes)} indexes · {len(seqs)} sequence · {len(alters)} foreign keys
--
-- GENERATED FILE. This is not the source of truth and is not executed by
-- the application. The JPA entity classes under
-- src/main/java/ak/dev/khi_backend/ are the source of truth; this file is
-- the DDL Hibernate derives from them under the PostgreSQL dialect, with
-- Spring Boot's naming strategies (PhysicalNamingStrategySnakeCaseImpl
-- and SpringImplicitNamingStrategy).
--
-- At runtime the application does NOT run this script. application.yaml
-- sets spring.jpa.hibernate.ddl-auto: update, so Hibernate reconciles the
-- live database at startup. `update` only ever ADDS — it creates missing
-- tables, columns and indexes, but never drops a column, never changes an
-- existing column's type or nullability, and never adds a missing
-- ON DELETE clause or check constraint to a constraint that already
-- exists. A long-lived database can therefore differ from this file.
-- Use this to provision a FRESH database, or to diff against an existing
-- one; see MIGRATIONS.md next to this file.
--
-- To regenerate:  ./scripts/render-schema.sh
-- =====================================================================

{rule('Sequences').lstrip()}"""

out = [header]
out.append('\n'.join(s + ';' for s in seqs))
out.append(rule('Tables'))
out.append('\n\n'.join(fmt_table(t) for t in tables))
out.append(rule('Indexes'))
out.append('\n'.join(i + ';' for i in indexes))
out.append(rule('Foreign keys and table constraints'))
out.append('\n'.join(a + ';' for a in alters))
out.append('')

open(out_path, 'w').write('\n'.join(out))
