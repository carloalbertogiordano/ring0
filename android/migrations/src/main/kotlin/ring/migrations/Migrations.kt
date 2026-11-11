package ring.migrations

/** One schema step as plain SQL, so a JVM SQLite can run it in tests and the app can wrap it in a Room migration. */
data class MigrationStep(val from: Int, val to: Int, val sql: List<String>)

/** Every schema change since the first release, in order. The app applies them through Room; the tests apply them to real SQLite. */
object Migrations {
    const val LATEST = 9

    val steps = listOf(
        // demo-data flag
        MigrationStep(
            1,
            2,
            listOf(
                "ALTER TABLE samples ADD COLUMN demo INTEGER NOT NULL DEFAULT 0",
                "ALTER TABLE sleep_sessions ADD COLUMN demo INTEGER NOT NULL DEFAULT 0",
            ),
        ),
        // alerts store kind and numbers instead of localized text
        MigrationStep(
            2,
            3,
            listOf(
                "ALTER TABLE alerts ADD COLUMN value REAL NOT NULL DEFAULT 0",
                "ALTER TABLE alerts ADD COLUMN threshold REAL NOT NULL DEFAULT 0",
                "ALTER TABLE alerts ADD COLUMN detail TEXT NOT NULL DEFAULT ''",
            ),
        ),
        // incident log
        MigrationStep(
            3,
            4,
            listOf(
                "CREATE TABLE IF NOT EXISTS incidents (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, ts INTEGER NOT NULL, " +
                    "source TEXT NOT NULL, triggerValue REAL NOT NULL, level TEXT NOT NULL, causes TEXT NOT NULL, " +
                    "advice TEXT NOT NULL, symptoms TEXT NOT NULL, bpm INTEGER, rmssd REAL, spo2 INTEGER, stress REAL, " +
                    "skinTemp REAL, hrTrend TEXT NOT NULL, checked INTEGER NOT NULL)",
            ),
        ),
        // user categories and note on incidents
        MigrationStep(
            4,
            5,
            listOf(
                "ALTER TABLE incidents ADD COLUMN categories TEXT NOT NULL DEFAULT ''",
                "ALTER TABLE incidents ADD COLUMN note TEXT NOT NULL DEFAULT ''",
            ),
        ),
        // alarms (feature removed again in the next step)
        MigrationStep(
            5,
            6,
            listOf(
                "CREATE TABLE IF NOT EXISTS alarms (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, hour INTEGER NOT NULL, " +
                    "minute INTEGER NOT NULL, weekdays INTEGER NOT NULL, enabled INTEGER NOT NULL, name TEXT NOT NULL)",
            ),
        ),
        // the R09 has no vibration motor, so the alarms table is dropped
        MigrationStep(6, 7, listOf("DROP TABLE IF EXISTS alarms")),
        // step counting was removed (the ring's step data is unreliable), so its samples are dropped
        MigrationStep(7, 8, listOf("DELETE FROM samples WHERE type = 'steps'")),
        // the order of the sleep stages, for the hypnogram (nights stored before this have none)
        MigrationStep(8, 9, listOf("ALTER TABLE sleep_sessions ADD COLUMN stages TEXT NOT NULL DEFAULT ''")),
    )
}
