package org.gotson.komga.infrastructure.datasource

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.assertj.core.api.Assertions.assertThat
import org.gotson.komga.infrastructure.configuration.KomgaProperties
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.sqlite.SQLiteDataSource
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.nio.file.Path
import java.sql.Connection
import java.sql.Statement
import java.time.Duration
import java.time.Instant
import javax.sql.DataSource
import kotlin.io.path.createFile
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.readText
import kotlin.io.path.writeText

class DatabaseStartupVacuumTest {
  @TempDir
  lateinit var tempDir: Path

  private lateinit var database: Path
  private lateinit var stamp: Path
  private lateinit var dataSource: HikariDataSource
  private val komgaProperties = KomgaProperties()

  private val now = Instant.parse("2026-11-10T10:00:00Z")

  @BeforeEach
  fun setup() {
    database = tempDir.resolve("database.sqlite")
    stamp = tempDir.resolve("database.sqlite" + DatabaseStartupVacuum.STAMP_SUFFIX)
    komgaProperties.database.file = database.toString()

    // same shape as the main write pool: a single WAL connection, here with the temp_store pragma set by the user
    dataSource =
      HikariDataSource(
        HikariConfig().apply {
          dataSource =
            SQLiteDataSource().apply {
              url = "jdbc:sqlite:$database?temp_store=MEMORY"
              setJournalMode("WAL")
            }
          maximumPoolSize = 1
        },
      )
  }

  @AfterEach
  fun teardown() {
    dataSource.close()
  }

  private fun <T> query(
    sql: String,
    extract: (java.sql.ResultSet) -> T,
  ): T =
    dataSource.connection.use { connection ->
      connection.createStatement().use { statement ->
        statement.executeQuery(sql).use {
          it.next()
          extract(it)
        }
      }
    }

  private fun execute(vararg sql: String) =
    dataSource.connection.use { connection ->
      connection.createStatement().use { statement -> sql.forEach { statement.execute(it) } }
    }

  private fun freelistCount() = query("PRAGMA freelist_count") { it.getInt(1) }

  /**
   * Leaves the database with unused pages, which only VACUUM gives back.
   */
  private fun fragmentDatabase() {
    execute(
      "CREATE TABLE IF NOT EXISTS BLOBS (ID integer PRIMARY KEY, DATA blob)",
      "CREATE TABLE IF NOT EXISTS KEPT (ID integer PRIMARY KEY, DATA varchar)",
      "INSERT INTO BLOBS (DATA) WITH RECURSIVE seq(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < 200) SELECT randomblob(8000) FROM seq",
      "INSERT INTO KEPT (DATA) WITH RECURSIVE seq(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < 200) SELECT 'row ' || n FROM seq",
      "DELETE FROM BLOBS",
      // so that the database file itself holds the unused pages, and not only the write-ahead log
      "PRAGMA wal_checkpoint(TRUNCATE)",
    )
    assertThat(freelistCount()).isGreaterThan(100)
  }

  private fun vacuum(intervalDays: Long = 7) = DatabaseStartupVacuum(dataSource, komgaProperties, intervalDays)

  /**
   * Wraps [target] so that every SQL string passed to a statement is added to [statements].
   */
  private fun recordingStatements(
    target: DataSource,
    statements: MutableList<String>,
  ): DataSource {
    fun <T : Any> record(
      type: Class<T>,
      delegate: T,
      wrap: (Any?) -> Any? = { it },
    ): T =
      type.cast(
        Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args ->
          if (type == Statement::class.java && method.name.startsWith("execute")) statements += args[0] as String
          try {
            wrap(method.invoke(delegate, *(args ?: emptyArray())))
          } catch (e: InvocationTargetException) {
            throw e.targetException
          }
        },
      )

    return record(DataSource::class.java, target) { connection ->
      if (connection is Connection) record(Connection::class.java, connection) { if (it is Statement) record(Statement::class.java, it) else it } else connection
    }
  }

  @Test
  fun `given database never vacuumed when starting then it is compacted and the run is recorded`() {
    fragmentDatabase()
    val sizeBefore = database.fileSize()

    val done = vacuum().vacuumIfDue(now)

    assertThat(done).isTrue
    assertThat(freelistCount()).isZero
    assertThat(database.fileSize()).isLessThan(sizeBefore)
    assertThat(query("SELECT count(*) FROM KEPT") { it.getInt(1) }).isEqualTo(200)
    assertThat(query("PRAGMA integrity_check") { it.getString(1) }).isEqualTo("ok")
    assertThat(Instant.parse(stamp.readText())).isEqualTo(now)
  }

  @Test
  fun `given database vacuumed when starting then the write-ahead log is emptied and the connection keeps its pragmas`() {
    fragmentDatabase()

    vacuum().vacuumIfDue(now)

    assertThat(tempDir.resolve("database.sqlite-wal").fileSize()).isZero
    assertThat(query("PRAGMA journal_mode") { it.getString(1) }).isEqualToIgnoringCase("wal")
    // 2 is MEMORY, as configured in the JDBC url
    assertThat(query("PRAGMA temp_store") { it.getInt(1) }).isEqualTo(2)
  }

  @Test
  fun `given database vacuumed recently when starting then it is left alone until the interval has passed`() {
    fragmentDatabase()
    vacuum().vacuumIfDue(now)
    fragmentDatabase()

    val tooEarly = vacuum().vacuumIfDue(now.plus(Duration.ofDays(6)))

    assertThat(tooEarly).isFalse
    assertThat(freelistCount()).isGreaterThan(100)
    assertThat(Instant.parse(stamp.readText())).isEqualTo(now)

    val later = now.plus(Duration.ofDays(7))
    val due = vacuum().vacuumIfDue(later)

    assertThat(due).isTrue
    assertThat(freelistCount()).isZero
    assertThat(Instant.parse(stamp.readText())).isEqualTo(later)
  }

  @Test
  fun `given unreadable stamp when starting then database is vacuumed`() {
    fragmentDatabase()
    stamp.writeText("not a date")

    val done = vacuum().vacuumIfDue(now)

    assertThat(done).isTrue
    assertThat(Instant.parse(stamp.readText())).isEqualTo(now)
  }

  @Test
  fun `given interval of zero when starting then nothing is done`() {
    fragmentDatabase()

    val done = vacuum(intervalDays = 0).vacuumIfDue(now)

    assertThat(done).isFalse
    assertThat(freelistCount()).isGreaterThan(100)
    assertThat(stamp.exists()).isFalse
  }

  @Test
  fun `given database that is not a plain file when starting then nothing is done`() {
    listOf(
      "file:database?mode=memory",
      ":memory:",
      "file:${tempDir.resolve("uri.sqlite")}",
      tempDir.resolve("missing.sqlite").toString(),
      tempDir.toString(),
    ).forEach { file ->
      komgaProperties.database.file = file

      assertThat(vacuum().vacuumIfDue(now)).`as`(file).isFalse
    }
    assertThat(tempDir.toFile().list()).noneMatch { it.endsWith(DatabaseStartupVacuum.STAMP_SUFFIX) }
  }

  @Test
  fun `given database path with pragmas appended when starting then the file itself is vacuumed`() {
    fragmentDatabase()
    komgaProperties.database.file = "$database?cache_size=-2000"

    val done = vacuum().vacuumIfDue(now)

    assertThat(done).isTrue
    assertThat(freelistCount()).isZero
    assertThat(stamp.exists()).isTrue
  }

  @Test
  fun `given temporary storage in memory when vacuuming then the temporary copy goes to a file and the setting is restored`() {
    fragmentDatabase()
    val statements = mutableListOf<String>()

    DatabaseStartupVacuum(recordingStatements(dataSource, statements), komgaProperties, 7).vacuumIfDue(now)

    // 2 is MEMORY, as configured in the JDBC url
    assertThat(statements).containsSubsequence("PRAGMA temp_store = FILE", "VACUUM", "PRAGMA temp_store = 2")
  }

  @Test
  fun `given vacuum that fails when starting then startup carries on and it is not attempted again before the interval has passed`() {
    // not a database: VACUUM fails on it
    val garbage = tempDir.resolve("garbage.sqlite").createFile().also { it.writeText("this is not a sqlite database, ".repeat(200)) }
    val garbageStamp = tempDir.resolve("garbage.sqlite" + DatabaseStartupVacuum.STAMP_SUFFIX)
    komgaProperties.database.file = garbage.toString()
    val statements = mutableListOf<String>()
    val failing = DatabaseStartupVacuum(recordingStatements(SQLiteDataSource().apply { url = "jdbc:sqlite:$garbage" }, statements), komgaProperties, 7)

    failing.afterPropertiesSet()

    assertThat(statements).isNotEmpty
    assertThat(garbageStamp.exists()).isTrue
    assertThat(garbage.readText()).startsWith("this is not a sqlite database")

    statements.clear()
    val retried = failing.vacuumIfDue(Instant.parse(garbageStamp.readText()).plus(Duration.ofDays(6)))

    assertThat(retried).isFalse
    assertThat(statements).isEmpty()
  }

  @Test
  fun `vacuum is due when never run, when the interval has passed, or when the stamp is in the future`() {
    val week = Duration.ofDays(7)

    assertThat(DatabaseStartupVacuum.isVacuumDue(null, now, week)).isTrue
    assertThat(DatabaseStartupVacuum.isVacuumDue(now.minus(Duration.ofDays(7)), now, week)).isTrue
    assertThat(DatabaseStartupVacuum.isVacuumDue(now.minus(Duration.ofDays(30)), now, week)).isTrue
    assertThat(DatabaseStartupVacuum.isVacuumDue(now.plusSeconds(60), now, week)).isTrue

    assertThat(DatabaseStartupVacuum.isVacuumDue(now, now, week)).isFalse
    assertThat(DatabaseStartupVacuum.isVacuumDue(now.minus(Duration.ofDays(7)).plusSeconds(1), now, week)).isFalse
  }
}
