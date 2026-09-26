package com.watchdawg.market.ingest

import com.watchdawg.market.wfm.Envelope
import com.watchdawg.market.wfm.Order
import org.junit.jupiter.api.Test
import org.springframework.core.io.ClassPathResource
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** R4.3 in plain JUnit, on the captured ayatan book (2026-09-25), whose lots make it the hard case. */
class QuotesTest {

    @Test
    fun `best prices are per unit, so a lot of six does not pose as the best bid`() {
        val quote = ayatan().getValue(TWO_TWO)

        // Raw platinum would make a buy of six for 42 the best bid, against a best sell of 6.
        assertEquals(BigDecimal("7.0000"), quote.bestBuy)
        assertEquals(BigDecimal("6.0000"), quote.bestSell)
        assertEquals(109 to 600, quote.buyCount to quote.sellCount)
    }

    @Test
    fun `the online pair counts only owners online or in game, and is not crossed`() {
        // Offline owners' orders stay listed for up to 48h, which is why the full book above still
        // crosses (7 against 6). The online pair is the one a trade can act on.
        val quote = ayatan().getValue(TWO_TWO)

        assertEquals(BigDecimal("7.0000"), quote.bestBuyOnline)
        assertEquals(BigDecimal("7.0000"), quote.bestSellOnline)
        assertEquals(18 to 52, quote.buyOnlineCount to quote.sellOnlineCount)
    }

    @Test
    fun `a market with no orders in the book still gets a row, empty`() {
        val quote = quotes(listOf(EMPTY), emptyList()).single()

        assertEquals(Quote(EMPTY, null, null, null, null, 0, 0, 0, 0), quote)
    }

    @Test
    fun `a side with no online owners has no online price`() {
        val quote = ayatan().getValue(ONE_TWO)

        assertEquals(BigDecimal("15.0000"), quote.bestSell)
        assertNull(quote.bestSellOnline)
        assertNull(quote.bestBuy)
    }

    /** The captured book, with each star combination standing in for a resolved market id. */
    private fun ayatan(): Map<Long, Quote> {
        val book = ClassPathResource("fixtures/v2-orders/ayatan_anasa_sculpture.json").inputStream.use {
            mapper.readValue(it, object : TypeReference<Envelope<List<Order>>>() {})
        }.data!!
        val observed = book.map { order ->
            ObservedOrder(
                id = order.id,
                marketId = (order.amberStars!! * 10 + order.cyanStars!!).toLong(),
                type = order.type,
                platinum = order.platinum,
                perTrade = order.perTrade,
                quantity = order.quantity,
                ownerPlatform = order.user?.platform,
                ownerOnline = order.ownerOnline,
                unitPrice = order.unitPrice,
            )
        }
        return quotes(listOf(0L, ONE_TWO, TWO_TWO), observed).associateBy { it.marketId }
    }

    private val mapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()

    private companion object {
        const val ONE_TWO = 12L
        const val TWO_TWO = 22L
        const val EMPTY = 99L
    }
}
