package org.gotson.komga.infrastructure.unicode

import org.springframework.context.annotation.Configuration

/**
 * FORK-ONLY: makes the shared [Collators] safe to use from several threads.
 *
 * Both collators are registered as SQLite collations on every database connection, so
 * each connection compares strings with the same instance, from whichever thread runs
 * its query. An ICU collator keeps a scratch buffer per instance and only guards it with
 * a lock once it is frozen: unfrozen, two connections sorting at the same time corrupt
 * each other's buffer, which shows up as an `ArrayIndexOutOfBoundsException` inside the
 * query (HTTP 500 on a series list sorted by title), or as a wrong order.
 *
 * That needs two connections working at once, which is rare with the default read pool
 * of a single connection (only a write sorting while a read sorts), and frequent as soon
 * as `komga.database.pool-size` or `max-pool-size` is raised.
 *
 * This runs while the application context is being built, before any request or task
 * thread can run a query. Freezing is idempotent, and the collators are never
 * reconfigured after their creation, which is the only thing a frozen collator refuses.
 */
@Configuration
class FrozenCollatorsConfiguration {
  init {
    Collators.collator1.freeze()
    Collators.collator3.freeze()
  }
}
