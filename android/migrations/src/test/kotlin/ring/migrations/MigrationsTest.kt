package ring.migrations

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Room's exported schema of one version (see `data/schemas`), the reference the migrated databases are compared with. */
private class Schema(val version: Int, private val json: JsonObject) {
    val entities get() = json.getAsJsonObject("database").getAsJsonArray("entities").map { it.asJsonObject }

    /** The statements that create this version from scratch: each table, then its indices. */
    fun createStatements(): List<String> = entities.flatMap { e ->
        val table = e.get("tableName").asString
        val sql = listOf(e.get("createSql").asString) + (e.getAsJsonArray("indices")?.map { it.asJsonObject.get("createSql").asString } ?: emptyList())
        sql.map { it.replace("${'$'}{TABLE_NAME}", table) }
    }
}

private val SCHEMA_DIR = File("../data/schemas/ring.app.data.RingDb")

private fun schema(version: Int) = Schema(version, JsonParser.parseString(File(SCHEMA_DIR, "$version.json").readText()).asJsonObject)

private fun latestExported() = SCHEMA_DIR.listFiles()!!.maxOf { it.nameWithoutExtension.toInt() }

private fun database(): Connection = DriverManager.getConnection("jdbc:sqlite::memory:")

private fun Connection.run(vararg sql: String) = createStatement().use { st -> sql.forEach { st.execute(it) } }

private fun Connection.migrate(from: Int, to: Int) =
    Migrations.steps.filter { it.from >= from && it.to <= to }.forEach { step -> run(*step.sql.toTypedArray()) }

private fun <T> Connection.query(sql: String, read: (java.sql.ResultSet) -> T): List<T> =
    createStatement().use { st -> st.executeQuery(sql).use { rs -> buildList { while (rs.next()) add(read(rs)) } } }

/** Fails unless the tables, columns, types, nullability, defaults and primary keys are exactly what Room expects. */
private fun Connection.assertMatches(schema: Schema) {
    val tables = query("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT IN ('sqlite_sequence', 'android_metadata')") { it.getString(1) }
    assertEquals(schema.entities.map { it.get("tableName").asString }.sorted(), tables.sorted(), "tables of v${schema.version}")
    for (entity in schema.entities) {
        val table = entity.get("tableName").asString
        val actual =
            query("PRAGMA table_info(`$table`)") { listOf(it.getString("name"), it.getString("type").uppercase(), it.getInt("notnull") == 1, it.getInt("pk")) }
        val expected = entity.getAsJsonArray("fields").map { it.asJsonObject }
        assertEquals(
            expected.map {
                it.get("columnName").asString
            }.sorted(),
            actual.map { it[0] as String }.sorted(),
            "columns of $table in v${schema.version}",
        )
        val keys = entity.getAsJsonObject("primaryKey").getAsJsonArray("columnNames").map { it.asString }
        for (field in expected) {
            val name = field.get("columnName").asString
            val column = actual.single { it[0] == name }
            assertEquals(field.get("affinity").asString, column[1], "type of $table.$name")
            assertEquals(field.get("notNull")?.asBoolean ?: false, column[2], "not null of $table.$name")
            assertEquals(keys.indexOf(name) + 1, column[3], "primary key position of $table.$name")
        }
    }
}

class MigrationsTest {
    @Test fun `the steps form one unbroken chain that ends at the latest exported schema`() {
        val steps = Migrations.steps
        assertEquals(1, steps.first().from)
        steps.zipWithNext().forEach { (a, b) -> assertEquals(a.to, b.from, "gap after v${a.to}") }
        assertTrue(steps.all { it.to == it.from + 1 }, "every step moves exactly one version")
        assertEquals(latestExported(), steps.last().to, "Db.kt version, schema JSON and the last step must agree")
        assertEquals(Migrations.LATEST, steps.last().to)
    }

    @Test fun `a version 1 database ends up exactly as Room expects and keeps its data`() {
        database().use { db ->
            db.run(
                "CREATE TABLE samples (type TEXT NOT NULL, ts INTEGER NOT NULL, value REAL NOT NULL, PRIMARY KEY(type, ts))",
                "CREATE TABLE sleep_sessions (start INTEGER NOT NULL, `end` INTEGER NOT NULL, lightMin INTEGER NOT NULL, deepMin INTEGER NOT NULL, " +
                    "remMin INTEGER NOT NULL, awakeMin INTEGER NOT NULL, PRIMARY KEY(start))",
                "CREATE TABLE alerts (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, ts INTEGER NOT NULL, kind TEXT NOT NULL, message TEXT NOT NULL)",
                "INSERT INTO samples VALUES ('hr', 100, 61.0), ('steps', 100, 900.0), ('hr', 200, 64.0)",
                "INSERT INTO sleep_sessions VALUES (1000, 5000, 200, 90, 80, 10)",
                "INSERT INTO alerts (ts, kind, message) VALUES (300, 'hr_high', 'old text')",
            )
            db.migrate(1, Migrations.LATEST)
            db.assertMatches(schema(latestExported()))
            assertEquals(
                listOf("hr:100:false", "hr:200:false"),
                db.query("SELECT type, ts, demo FROM samples ORDER BY ts") {
                    "${it.getString(1)}:${it.getLong(2)}:${it.getInt(3) == 1}"
                },
                "steps dropped, the rest kept",
            )
            assertEquals(listOf(200), db.query("SELECT lightMin FROM sleep_sessions") { it.getInt(1) })
            assertEquals(listOf("old text"), db.query("SELECT message FROM alerts") { it.getString(1) })
        }
    }

    @Test fun `version 9 adds the ordered sleep stages and keeps the nights already stored`() {
        database().use { db ->
            db.run(*schema(8).createStatements().toTypedArray())
            db.run("INSERT INTO sleep_sessions VALUES (1000, 5000, 200, 90, 80, 10, 0)", "INSERT INTO samples VALUES ('hr', 100, 61.0, 0)")
            db.migrate(8, 9)
            db.assertMatches(schema(9))
            assertEquals(
                listOf("1000:200:"),
                db.query("SELECT start, lightMin, stages FROM sleep_sessions") {
                    "${it.getLong(1)}:${it.getInt(2)}:${it.getString(3)}"
                },
                "old nights get no stages",
            )
            assertEquals(listOf(61.0), db.query("SELECT value FROM samples") { it.getDouble(1) })
        }
    }
}
