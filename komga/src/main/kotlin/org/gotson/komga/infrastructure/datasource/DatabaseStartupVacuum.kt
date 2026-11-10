package org.gotson.komga.infrastructure.datasource

import io.github.oshai.kotlinlogging.KotlinLogging
import org.gotson.komga.infrastructure.configuration.KomgaProperties
import org.springframework.beans.factory.InitializingBean
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization
import org.springframework.stereotype.Component
import java.nio.file.FileStore
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import javax.sql.DataSource
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.isDirectory
import kotlin.io.path.isExecutable
import kotlin.io.path.isRegularFile
import kotlin.io.path.isWritable
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.time.measureTime

private val logger = KotlinLogging.logger {}

/**
 * FORK-ONLY: rebuilds the main database with `VACUUM` at startup when that has not been
 * done for [intervalDays] days.
 *
 * Why: SQLite hands out pages in the order they are requested, so the small tables every
 * listing query reads (series, books, metadata, about 10% of the file) end up scattered
 * between the thumbnail blobs and media pages that make up the rest. On a copy-on-write
 * filesystem every checkpoint then relocates the pages it rewrites, which fragments the
 * file itself as well. Once the operating system has dropped the file from its page cache,
 * each page of those small tables costs one disk seek, and on spinning disks the first
 * dashboard load takes minutes of pure I/O wait. Measured on a 696 MB database on a btrfs
 * RAID 5 of hard disks: the queries of one dashboard and one library page took 45 s with
 * a cold cache before `VACUUM` and 1.5 s after it, because `VACUUM` stores each table
 * contiguously and rewrites the whole file in one go.
 *
 * Why at startup: `VACUUM` needs the write connection for its whole duration (seconds for
 * a database that was vacuumed recently, minutes the first time on a slow disk). This bean
 * runs while the application context is still being built, so nothing else is competing
 * for that connection, and the web server only starts accepting requests afterwards.
 *
 * The date of the last attempt is kept in a file next to the database, named after it
 * with the suffix [STAMP_SUFFIX]. Any failure is logged and startup carries on: `VACUUM`
 * is atomic, so a failed or interrupted run leaves the database as it was. A failed run
 * is only attempted again once the interval has passed, so that a database that cannot
 * be vacuumed does not delay every startup; an interrupted one (container stopped while
 * it runs) starts over at the next startup.
 *
 * Each run rewrites the whole file: on a filesystem with snapshots, the previous copy
 * stays allocated for as long as a snapshot references it.
 *
 * Set `komga-fork.database.vacuum-interval-days=0` to disable.
 */
@Component
@DependsOnDatabaseInitialization
class DatabaseStartupVacuum(
  // the primary datasource is the write pool of the main database, the one Flyway migrates
  private val dataSource: DataSource,
  private val komgaProperties: KomgaProperties,
  @param:Value("\${komga-fork.database.vacuum-interval-days:7}") private val intervalDays: Long,
) : InitializingBean {
  override fun afterPropertiesSet() {
    try {
      vacuumIfDue(Instant.now())
    } catch (e: Exception) {
      logger.warn(e) { "Database maintenance failed, the database was left as it was" }
    }
  }

  /**
   * @return true if the database was vacuumed
   */
  internal fun vacuumIfDue(now: Instant): Boolean {
    if (intervalDays <= 0) return false

    val file = komgaProperties.database.file
    // in-memory databases and SQLite URIs are not plain files: nothing to compact, and nowhere to keep the stamp
    if (file.contains(":memory:") || file.contains("mode=memory") || file.startsWith("file:")) return false

    // pragmas can be appended to the file name, the driver strips them as well
    val database = Path.of(file.substringBefore('?')).toAbsolutePath()
    if (!database.isRegularFile()) {
      logger.info { "Database maintenance skipped: $database is not a file" }
      return false
    }

    val stamp = database.resolveSibling(database.fileName.toString() + STAMP_SUFFIX)
    val lastRun = readStamp(stamp)
    if (!isVacuumDue(lastRun, now, Duration.ofDays(intervalDays))) {
      logger.debug { "Database maintenance not due, last run: $lastRun" }
      return false
    }

    val sizeBefore = database.fileSize()
    missingSpace(database, sizeBefore)?.let { (store, needed) ->
      logger.warn { "Database maintenance skipped: VACUUM needs about ${needed.toMegabytes()} MB of free space on $store, only ${store.usableSpace.toMegabytes()} MB available" }
      return false
    }

    logger.info { "Database maintenance: VACUUM of $database (${sizeBefore.toMegabytes()} MB, last attempt: ${lastRun ?: "never"}). This can take several minutes on a slow disk, the server starts afterwards: do not stop it meanwhile." }
    val done =
      try {
        val duration = measureTime { vacuum() }
        logger.info { "Database maintenance: VACUUM done in $duration, size ${sizeBefore.toMegabytes()} MB -> ${database.fileSize().toMegabytes()} MB" }
        true
      } catch (e: Exception) {
        logger.warn(e) { "Database maintenance: VACUUM failed, the database was left as it was. Next attempt in $intervalDays days, or at next startup if $stamp is deleted." }
        false
      }

    try {
      stamp.writeText(now.toString())
    } catch (e: Exception) {
      logger.warn(e) { "Could not write $stamp, the database will be vacuumed again at next startup" }
    }
    return done
  }

  /**
   * VACUUM writes the database three times: a copy in SQLite's temporary directory, then the write-ahead log
   * next to the database, then the database file itself, which a copy-on-write filesystem allocates anew.
   *
   * @return the first filesystem lacking space with the space it needs, or null if there is enough
   */
  private fun missingSpace(
    database: Path,
    size: Long,
  ): Pair<FileStore, Long>? {
    val needed = mutableMapOf<FileStore, Long>()
    needed.merge(Files.getFileStore(database), size * 2, Long::plus)
    sqliteTempDirectory()?.let { needed.merge(Files.getFileStore(it), size, Long::plus) }
    return needed.entries.firstOrNull { (store, bytes) -> store.usableSpace < bytes }?.toPair()
  }

  private fun vacuum() {
    dataSource.connection.use { connection ->
      connection.createStatement().use { statement ->
        // the temporary copy must not be held in memory, whatever the configured pragmas say: it is as big as the database
        val tempStore = statement.executeQuery("PRAGMA temp_store").use { if (it.next()) it.getInt(1) else 0 }
        statement.execute("PRAGMA temp_store = FILE")
        try {
          statement.execute("VACUUM")
        } finally {
          // the connection goes back to the pool: restore what it was configured with
          statement.execute("PRAGMA temp_store = $tempStore")
        }

        // in WAL mode the rebuilt database is still in the write-ahead log: fold it back and shrink the log
        statement.executeQuery("PRAGMA wal_checkpoint(TRUNCATE)").use {
          if (it.next() && it.getInt(1) != 0) logger.warn { "Database maintenance: the write-ahead log could not be truncated, it will be on a later checkpoint" }
        }
      }
    }
  }

  private fun readStamp(stamp: Path): Instant? =
    try {
      if (stamp.exists()) Instant.parse(stamp.readText().trim()) else null
    } catch (e: Exception) {
      logger.warn { "Ignoring unreadable $stamp: ${e.message}" }
      null
    }

  private fun Long.toMegabytes() = this / (1024 * 1024)

  companion object {
    const val STAMP_SUFFIX = ".last-vacuum"

    /**
     * A stamp in the future (clock moved backwards) is not trusted: it would otherwise disable the maintenance.
     */
    internal fun isVacuumDue(
      lastRun: Instant?,
      now: Instant,
      interval: Duration,
    ): Boolean = lastRun == null || lastRun.isAfter(now) || Duration.between(lastRun, now) >= interval

    /**
     * Where SQLite creates its temporary files on Unix: the first usable directory, in the order of its `unixTempFileDir()`.
     * Null when none of them exists (other platforms), in which case that location is not checked.
     */
    internal fun sqliteTempDirectory(): Path? =
      listOfNotNull(System.getenv("SQLITE_TMPDIR"), System.getenv("TMPDIR"), "/var/tmp", "/usr/tmp", "/tmp")
        .mapNotNull { runCatching { Path.of(it) }.getOrNull() }
        .firstOrNull { it.isDirectory() && it.isWritable() && it.isExecutable() }
  }
}
