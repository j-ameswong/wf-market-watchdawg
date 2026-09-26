package com.watchdawg.market

import org.springframework.context.SmartLifecycle
import org.springframework.scheduling.Trigger
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import java.util.concurrent.ScheduledFuture

/**
 * Runs [task] by [trigger] on a thread of its own, named after [name].
 *
 * Spring's shared scheduler has one thread, so a slow catalog refresh would make a poll or a send
 * late behind it. The scheduler here is not a bean on purpose: a second `TaskScheduler` bean would
 * replace Boot's default and take over every `@Scheduled` method.
 *
 * The next run is asked of [trigger] only once a run has finished, so runs never overlap. Declare
 * one only where [SCHEDULING_ENABLED] allows, so the test harness keeps it off (R2.6).
 */
class OwnThreadSchedule(private val name: String, private val task: Runnable, private val trigger: Trigger) :
    SmartLifecycle {
    private var scheduler: ThreadPoolTaskScheduler? = null
    private var future: ScheduledFuture<*>? = null

    override fun start() {
        val own = ThreadPoolTaskScheduler().apply {
            setPoolSize(1)
            setThreadNamePrefix("$name-")
            setWaitForTasksToCompleteOnShutdown(true)
            initialize()
        }
        scheduler = own
        future = own.schedule(task, trigger)
    }

    override fun stop() {
        future?.cancel(false)
        scheduler?.shutdown()
        future = null
        scheduler = null
    }

    override fun isRunning(): Boolean = scheduler != null
}
