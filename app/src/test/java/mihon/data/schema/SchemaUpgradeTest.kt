package mihon.data.schema

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import tachiyomi.data.Database
import java.io.File

/**
 * Runs the real SQLDelight migration for the pinned-manga column against a
 * database shaped like the pre-migration schema and asserts the upgrade is
 * safe: `state_pinned` appears with the right default and existing rows keep
 * their data. A `.sqm` that compiles but fails or corrupts on device would
 * otherwise only show up as a startup crash on user upgrades.
 */
class SchemaUpgradeTest {

    @Test
    fun `migration 20 to 21 adds state_pinned and preserves rows`() {
        runBlocking {
            withDriver { driver ->
                Database.Schema.create(driver).await()
                driver.exec("ALTER TABLE manga DROP COLUMN state_pinned")
                driver.exec(
                    "INSERT INTO manga (id, source_id, remote_url, remote_title, remote_status, " +
                        "remote_update_strategy, remote_memo, user_notes, user_reader_flags, " +
                        "user_chapter_flags, state_chapter_fetch_interval, state_initialized) " +
                        "VALUES (7, 1, 'https://example.org/manga/1', 'Existing Title', 0, 0, " +
                        "'{}', 'notes', 3, 5, 24, 1)",
                )

                Database.Schema.migrate(driver, 20, 21).await()

                driver.tableColumns("manga") shouldContain "state_pinned"

                driver.queryLong("SELECT state_pinned FROM manga WHERE id = 7") shouldBe 0L
                driver.queryString("SELECT remote_title FROM manga WHERE id = 7") shouldBe "Existing Title"
                driver.queryString("SELECT user_notes FROM manga WHERE id = 7") shouldBe "notes"
                driver.queryLong("SELECT user_reader_flags FROM manga WHERE id = 7") shouldBe 3L

                driver.exec("UPDATE manga SET state_pinned = 1 WHERE id = 7")
                driver.queryLong("SELECT state_pinned FROM manga WHERE id = 7") shouldBe 1L
            }
        }
    }

    @Test
    fun `migrated manga table matches a fresh install's manga table`() {
        runBlocking {
            val freshColumns = withDriver { driver ->
                Database.Schema.create(driver).await()
                driver.tableColumns("manga")
            }
            val migratedColumns = withDriver { driver ->
                Database.Schema.create(driver).await()
                driver.exec("ALTER TABLE manga DROP COLUMN state_pinned")
                Database.Schema.migrate(driver, 20, 21).await()
                driver.tableColumns("manga")
            }

            migratedColumns shouldBe freshColumns
            migratedColumns shouldContain "state_pinned"
        }
    }

    companion object {
        suspend fun <R> withDriver(block: suspend (SqlDriver) -> R): R {
            val dbFile = File.createTempFile("yomi-schema-test", ".db")
            try {
                val driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}")
                try {
                    return block(driver)
                } finally {
                    driver.close()
                }
            } finally {
                dbFile.delete()
            }
        }
    }
}

private suspend fun SqlDriver.exec(sql: String) {
    execute(null, sql, 0).await()
}

private fun SqlDriver.tableColumns(table: String): List<String> {
    return executeQuery(
        null,
        "PRAGMA table_info($table)",
        { cursor ->
            val names = mutableListOf<String>()
            while (cursor.next().value) {
                names.add(cursor.getString(1)!!)
            }
            QueryResult.Value(names)
        },
        0,
    ) {}.value
}

private fun SqlDriver.queryLong(sql: String): Long? = queryOne(sql) { it.getLong(0) }

private fun SqlDriver.queryString(sql: String): String? = queryOne(sql) { it.getString(0) }

private fun <T> SqlDriver.queryOne(sql: String, read: (SqlCursor) -> T): T? {
    return executeQuery(
        null,
        sql,
        { cursor ->
            QueryResult.Value(if (cursor.next().value) read(cursor) else null)
        },
        0,
    ) {}.value
}
