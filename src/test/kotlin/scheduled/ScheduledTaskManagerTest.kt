package scheduled

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScheduledTaskManagerTest {
    @Test
    fun `schedule stop and clear update persisted tasks`() {
        val path = Files.createTempDirectory("aichat-scheduled-manager").resolve("scheduled-tasks.json")
        val store = ScheduledTaskStore(path)
        val manager = ScheduledTaskManager(
            store = store,
            orchestratorFactory = { _, _ -> error("Interval should not elapse in this test.") }
        )

        val task = manager.schedule(
            name = "news-summary",
            originalPrompt = "Summarize news every 1 day",
            parsed = ParsedScheduleRequest(
                task = "Summarize news",
                interval = ScheduledTaskInterval(1, ScheduledTaskTimeUnit.DAYS)
            )
        )

        assertEquals(ScheduledTaskStatus.RUNNING, task.status)
        assertEquals(1, store.readAll().size)
        assertTrue(manager.stop("news-summary"))
        assertEquals(ScheduledTaskStatus.STOPPED, store.readAll().single().status)

        manager.clear()

        assertEquals(emptyList(), store.readAll())
    }
}
