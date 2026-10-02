package com.example.neuraauto.data

/**
 * Single place that decides how a workflow row is written.
 *
 * [AutomationWorkflowDao.upsert] uses `OnConflictStrategy.REPLACE`, which is a
 * DELETE followed by an INSERT: the row gets a *new* auto-generated id. Since
 * [com.example.neuraauto.service.WorkflowScheduler] keys each alarm's
 * PendingIntent request code on the workflow id, a changed id leaves the old
 * alarm registered under the previous code — so the same slot fires twice.
 *
 * Every write therefore goes through here: an existing row in the slot is
 * updated in place (id and creation time preserved), and only a genuinely new
 * slot is inserted.
 */
object WorkflowRepository {

    /** Separator between the app id and the step list inside `targetMessage`. */
    private const val STEPS_PREFIX = "\u0001STEPS:"

    /**
     * Persist [workflow] into its `(targetApp, hour, minute)` slot.
     *
     * @return the stored row, carrying the stable id.
     */
    suspend fun save(
        dao: AutomationWorkflowDao,
        workflow: AutomationWorkflow
    ): AutomationWorkflow {
        val existing = dao.findBySlot(
            packageName = workflow.targetApp,
            hour = workflow.scheduledHour,
            minute = workflow.scheduledMinute
        )

        return if (existing != null) {
            // Keep the original id (alarm slot) and creation time.
            val merged = workflow.copy(
                id = existing.id,
                createdAtMillis = existing.createdAtMillis
            )
            dao.update(merged)
            merged
        } else {
            val id = dao.upsert(workflow)
            workflow.copy(id = id)
        }
    }

    /**
     * Encode a learned step list for storage.
     *
     * The step list is appended to the message behind a control-character
     * marker so that adding multi-step execution needs no schema migration and
     * no change to the public [AutomationWorkflow] contract. [messageOf]
     * reverses it, and messages written by earlier versions decode to an empty
     * step list (i.e. the legacy single-step behaviour).
     */
    fun encodeMessage(message: String, steps: List<String>): String =
        if (steps.isEmpty()) message else "$message$STEPS_PREFIX${steps.joinToString(",")}"

    /** The human-readable message, with any encoded step list stripped. */
    fun messageOf(workflow: AutomationWorkflow): String =
        workflow.targetMessage.substringBefore(STEPS_PREFIX)

    /**
     * Steps to execute for [workflow]; empty means the legacy
     * open-app / type / send path.
     */
    fun stepsFor(workflow: AutomationWorkflow): List<String> {
        val encoded = workflow.targetMessage.substringAfter(STEPS_PREFIX, missingDelimiterValue = "")
        if (encoded.isEmpty()) return emptyList()
        return encoded.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }
}
