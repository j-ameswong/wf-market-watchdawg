package com.watchdawg.market

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling

/**
 * Runs `@Scheduled` methods unless [SCHEDULING_ENABLED] is `false`.
 *
 * Every scheduled component calls something outside the process on a timer, so the test harness
 * turns this off in every Spring test context (R2.6).
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnBooleanProperty(SCHEDULING_ENABLED, matchIfMissing = true)
class SchedulingConfig

const val SCHEDULING_ENABLED = "watchdawg.scheduling.enabled"
