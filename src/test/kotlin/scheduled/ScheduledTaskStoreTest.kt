package scheduled

import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class ScheduledTaskStoreTest {
    @Test
    fun `missing store returns empty tasks`() {
        val path = Files.createTempDirectory("aichat-scheduled-missing").resolve("scheduled-tasks.json")

        assertEquals(emptyList(), ScheduledTaskStore(path).readAll())
    }

    @Test
    fun `scheduled tasks round trip`() {
        val path = Files.createTempDirectory("aichat-scheduled-roundtrip").resolve("scheduled-tasks.json")
        val store = ScheduledTaskStore(path)
        val task = ScheduledTask(
            name = "news-summary",
            originalPrompt = "Summarize news every 30 minutes",
            taskGoal = "Summarize news",
            interval = ScheduledTaskInterval(30, ScheduledTaskTimeUnit.MINUTES),
            status = ScheduledTaskStatus.RUNNING,
            createdAt = Instant.parse("2026-06-27T10:00:00Z"),
            lastRunAt = Instant.parse("2026-06-27T10:30:00Z"),
            records = listOf(
                ScheduledTaskRecord(
                    runId = "run-1",
                    status = ScheduledTaskRecordStatus.SUCCESS,
                    startedAt = Instant.parse("2026-06-27T10:30:00Z"),
                    finishedAt = Instant.parse("2026-06-27T10:31:00Z"),
                    result = "done"
                )
            )
        )

        store.upsert(task)

        assertEquals(listOf(task), store.readAll())
    }
}
