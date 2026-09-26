package com.watchdawg.market.wfm

import org.junit.jupiter.api.Test
import org.springframework.core.io.ClassPathResource
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Plain JUnit: parsing needs neither Spring nor a database. `WfmClientTest` runs one capture
 * through the application's own mapper, so the two cannot drift apart unnoticed.
 *
 * The fixtures are live captures (2026-09-25) with trader identity replaced by
 * `bruno/scrub-orders.mjs`; every order field is as captured.
 */
class OrderParsingTest {

    private val mapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()

    private fun orders(fixture: String): List<Order> = ClassPathResource("fixtures/v2-orders/$fixture.json")
        .inputStream.use { mapper.readValue(it, object : TypeReference<Envelope<List<Order>>>() {}) }
        .data!!

    @Test
    fun `a mixed-rank book binds every order with its subtype and rank`() {
        val book = orders("serration")

        assertEquals(440, book.size)
        assertEquals(setOf("regular", "atragraph"), book.map { it.subtype }.toSet())
        assertEquals((0..10).toSet() - 3, book.map { it.rank }.toSet(), "the capture has no rank-3 order")
        assertEquals(131, book.count { it.rank == 0 }, "rank 0 is a value, not an absence")
        assertTrue(book.all { it.charges == null && it.amberStars == null })
    }

    @Test
    fun `a requiem mod's orders carry a rank and never charges`() {
        val book = orders("khra")

        assertEquals(setOf(0, 2, 3), book.map { it.rank }.toSet())
        assertTrue(book.all { it.charges == null }, "R7.5: requiem mods trade by rank")
    }

    @Test
    fun `a sculpture's orders carry both star counts, zero included`() {
        val book = orders("ayatan_anasa_sculpture")

        assertEquals(79, book.count { it.amberStars == 0 && it.cyanStars == 0 })
        assertTrue(book.all { it.rank == null && it.subtype == null })
    }

    @Test
    fun `platinum prices a lot, so the unit price divides by the lot size`() {
        val lot = orders("ayatan_anasa_sculpture").first { it.perTrade == 6 && it.platinum == 90 }

        assertEquals(BigDecimal("15.0000"), lot.unitPrice)
        assertEquals(BigDecimal("4.1667"), lot.copy(platinum = 25).unitPrice)
        assertEquals(BigDecimal("7.0000"), lot.copy(platinum = 7, perTrade = null).unitPrice, "no lot size is one unit")
        assertEquals(BigDecimal("7.0000"), lot.copy(platinum = 7, perTrade = 0).unitPrice, "nor is a lot of none")
    }

    @Test
    fun `the owner is reduced to platform and online status`() {
        val book = orders("serration")

        assertEquals(setOf("pc", "ps4", "xbox", "mobile"), book.mapNotNull { it.user?.platform }.toSet())
        assertEquals(48, book.count { it.ownerOnline }, "online and ingame count; offline does not")
        assertEquals(setOf("platform", "status"), OrderOwner::class.java.declaredFields.map { it.name }.toSet())
    }

    @Test
    fun `recent orders name their item and bind like a book`() {
        val recent = orders("recent")

        assertEquals(396, recent.size)
        assertTrue(recent.all { it.itemId != null })
        assertTrue(recent.all { it.ownerOnline }, "/recent carries online users only")
        assertNull(recent.first { it.subtype == null && it.rank == null }.charges)
    }
}
