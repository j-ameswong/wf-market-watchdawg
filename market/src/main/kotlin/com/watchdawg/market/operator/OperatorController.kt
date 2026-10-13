package com.watchdawg.market.operator

import com.watchdawg.market.watch.InvalidWatchException
import org.springframework.http.HttpStatus
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestController
@RequestMapping("/api/operator")
class OperatorController(private val configuration: OperatorConfiguration) {
    @GetMapping("/configuration")
    fun configuration() = configuration.current()

    @PutMapping("/configuration", consumes = ["application/json"])
    fun save(@RequestBody request: ConfigurationView) = configuration.save(request)
}

@RestControllerAdvice(basePackages = ["com.watchdawg.market.operator"])
class OperatorErrors {
    @ExceptionHandler(IllegalArgumentException::class, InvalidWatchException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun invalid(error: RuntimeException) = mapOf("message" to (error.message ?: "Invalid configuration."))

    @ExceptionHandler(HttpMessageNotReadableException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun unreadable() = mapOf("message" to "Invalid form values. Check numbers, durations and required fields.")

    @ExceptionHandler(org.springframework.web.server.ResponseStatusException::class)
    fun status(error: org.springframework.web.server.ResponseStatusException) =
        org.springframework.http.ResponseEntity.status(error.statusCode)
            .body(mapOf("message" to (error.reason ?: "Request failed.")))
}
