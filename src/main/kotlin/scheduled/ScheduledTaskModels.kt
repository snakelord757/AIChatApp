package scheduled

import java.time.Instant

enum class ScheduledTaskTimeUnit {
    SECONDS,
    MINUTES,
    HOURS,
    DAYS
}

enum class ScheduledTaskStatus {
    RUNNING,
    STOPPED
}

enum class ScheduledTaskRecordStatus {
    SUCCESS,
    ERROR
}

data class ScheduledTaskInterval(
    val time: Long,
    val timeUnit: ScheduledTaskTimeUnit
) {
    fun toMillis(): Long {
        val multiplier = when (timeUnit) {
            ScheduledTaskTimeUnit.SECONDS -> 1_000L
            ScheduledTaskTimeUnit.MINUTES -> 60_000L
            ScheduledTaskTimeUnit.HOURS -> 3_600_000L
            ScheduledTaskTimeUnit.DAYS -> 86_400_000L
        }
        return time.coerceAtLeast(1L) * multiplier
    }

    override fun toString(): String = "$time $timeUnit"
}

data class ScheduledTask(
    val name: String,
    val originalPrompt: String,
    val taskGoal: String,
    val interval: ScheduledTaskInterval,
    val status: ScheduledTaskStatus = ScheduledTaskStatus.RUNNING,
    val createdAt: Instant = Instant.now(),
    val lastRunAt: Instant? = null,
    val records: List<ScheduledTaskRecord> = emptyList()
)

data class ScheduledTaskRecord(
    val runId: String,
    val status: ScheduledTaskRecordStatus,
    val startedAt: Instant,
    val finishedAt: Instant,
    val result: String? = null,
    val error: String? = null
)

data class ParsedScheduleRequest(
    val task: String,
    val interval: ScheduledTaskInterval
)
