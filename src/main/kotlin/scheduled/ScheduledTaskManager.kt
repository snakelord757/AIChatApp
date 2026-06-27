package scheduled

import task.OrchestratorResponse
import task.TaskOrchestrator
import task.TaskOrchestratorEvents
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class ScheduledTaskManager(
    private val store: ScheduledTaskStore,
    private val orchestratorFactory: (String, String) -> TaskOrchestrator,
    private val onTaskEvent: (String) -> Unit = {}
) {
    private val workers = ConcurrentHashMap<String, Thread>()

    fun startPersistedTasks() {
        store.readAll()
            .filter { it.status == ScheduledTaskStatus.RUNNING }
            .forEach(::startLoop)
    }

    fun schedule(name: String, originalPrompt: String, parsed: ParsedScheduleRequest): ScheduledTask {
        require(name.matches(Regex("""[A-Za-z0-9._-]+"""))) {
            "Task name may contain only letters, digits, dot, underscore, and dash."
        }
        stop(name)
        val task = ScheduledTask(
            name = name,
            originalPrompt = originalPrompt,
            taskGoal = parsed.task,
            interval = parsed.interval
        )
        store.upsert(task)
        startLoop(task)
        return task
    }

    fun stop(name: String): Boolean {
        val stopped = workers.remove(name)?.let {
            it.interrupt()
            true
        } ?: false
        store.update(name) { it.copy(status = ScheduledTaskStatus.STOPPED) }
        return stopped || store.readAll().any { it.name == name }
    }

    fun clear() {
        workers.keys.toList().forEach(::stop)
        store.clear()
    }

    fun allTasks(): List<ScheduledTask> = store.readAll()

    fun shutdown() {
        workers.values.forEach(Thread::interrupt)
        workers.clear()
    }

    private fun startLoop(task: ScheduledTask) {
        val thread = Thread {
            onTaskEvent("Scheduled task '${task.name}' started with interval ${task.interval}.")
            while (!Thread.currentThread().isInterrupted) {
                try {
                    Thread.sleep(task.interval.toMillis())
                    runOnce(task.name)
                    if (store.readAll().none { it.name == task.name && it.status == ScheduledTaskStatus.RUNNING }) {
                        break
                    }
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
            workers.remove(task.name, Thread.currentThread())
            onTaskEvent("Scheduled task '${task.name}' stopped.")
        }.also {
            it.name = "aichat-scheduled-${task.name}"
            it.isDaemon = true
        }
        workers[task.name]?.interrupt()
        workers[task.name] = thread
        thread.start()
    }

    private fun runOnce(name: String) {
        val task = store.readAll().firstOrNull { it.name == name && it.status == ScheduledTaskStatus.RUNNING } ?: return
        val runId = UUID.randomUUID().toString()
        val started = Instant.now()
        onTaskEvent("Scheduled task '$name' run started.")
        val record = try {
            val response = orchestratorFactory(name, runId).runTask(
                userTask = """
                    Scheduled background task '${task.name}':
                    ${task.taskGoal}

                    Use the normal task orchestration flow. If available MCP tools are relevant for this task, use them through the existing MCP tool gateway before producing the result.
                """.trimIndent(),
                events = TaskOrchestratorEvents.None
            )
            successRecord(runId, started, response)
        } catch (exception: RuntimeException) {
            ScheduledTaskRecord(
                runId = runId,
                status = ScheduledTaskRecordStatus.ERROR,
                startedAt = started,
                finishedAt = Instant.now(),
                error = exception.message ?: exception::class.simpleName
            )
        }
        store.update(name) {
            it.copy(
                status = if (record.status == ScheduledTaskRecordStatus.ERROR) ScheduledTaskStatus.STOPPED else it.status,
                lastRunAt = record.finishedAt,
                records = it.records + record
            )
        }
        onTaskEvent("Scheduled task '$name' run ${record.status}.")
        if (record.status == ScheduledTaskRecordStatus.ERROR) {
            onTaskEvent("Scheduled task '$name' stopped after an error. Review the last record before restarting it.")
        }
    }

    private fun successRecord(runId: String, started: Instant, response: OrchestratorResponse): ScheduledTaskRecord =
        ScheduledTaskRecord(
            runId = runId,
            status = ScheduledTaskRecordStatus.SUCCESS,
            startedAt = started,
            finishedAt = Instant.now(),
            result = response.content
        )
}
