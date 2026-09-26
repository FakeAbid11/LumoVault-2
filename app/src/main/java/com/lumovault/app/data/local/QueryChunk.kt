package com.lumovault.app.data.local

/**
 * How many ids a single `IN (…)` may bind.
 *
 * Room expands a collection parameter into one placeholder per element, so the ceiling is SQLite's own —
 * 999 variables per statement on the SQLite bundled with API 29, the oldest device this app claims to
 * run on. Every read or write below a repository that takes a list of ids therefore has to chunk, and the
 * number has to live in one place, because a query that forgets is not wrong until a library grows: a
 * timeline window starts at 300 and only ever gets bigger, so the first screen that breaks is the fourth
 * page of scrolls in a 1,200-photo library.
 *
 * 400, not 999: the rest of a statement has its own binds, and `SELECT … WHERE x IN (?, ?) AND y = ?`
 * counts the `y` too.
 *
 * This is *not* about a DAO method that takes `List<Entity>` for `@Upsert`/`@Insert` — Room compiles those
 * to one statement executed in a loop, so the parameter count is a single row's, however many rows are passed.
 */
internal const val MAX_IDS_PER_QUERY = 400
