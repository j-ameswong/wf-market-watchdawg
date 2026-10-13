package com.watchdawg.market.contracts

import com.watchdawg.market.sync.CollectionSync
import com.watchdawg.market.wfm.WfmClient
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate

class ContractManifest(
    private val kind: String,
    private val props: ContractProperties,
    private val client: WfmClient,
    private val jdbc: JdbcTemplate,
) : CollectionSync {
    override val collection: String = when (kind) {
        "riven" -> "rivens"
        "lich" -> "liches"
        else -> "sisters"
    }
    override fun refresh() {
        // Do not mark an unfetched manifest current: CollectionSyncScheduler owns hash writes.
        if (kind == "riven") {
            client.getRivenAttributes().forEach {
                jdbc.update(
                    """
                    insert into contract_attribute(slug, positive_is_negative) values (?, ?)
                    on conflict(slug) do update set positive_is_negative = excluded.positive_is_negative
                    """,
                    it.slug,
                    it.positiveIsNegative,
                )
            }
        }
        val rows = client.getContractWeapons(kind)
        rows.forEach {
            jdbc.update(
                """
                insert into contract_weapon(kind, slug, name) values (?, ?, ?)
                on conflict(kind, slug) do update set name = excluded.name
                """,
                kind,
                it.slug,
                it.i18n["en"]?.name ?: it.slug,
            )
        }
    }
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ContractProperties::class)
class ContractCatalogConfig {
    @Bean
    @ConditionalOnBooleanProperty("watchdawg.contracts.enabled")
    fun rivenManifest(props: ContractProperties, client: WfmClient, jdbc: JdbcTemplate): CollectionSync =
        ContractManifest("riven", props, client, jdbc)

    @Bean
    @ConditionalOnBooleanProperty("watchdawg.contracts.enabled")
    fun lichManifest(props: ContractProperties, client: WfmClient, jdbc: JdbcTemplate): CollectionSync =
        ContractManifest("lich", props, client, jdbc)

    @Bean
    @ConditionalOnBooleanProperty("watchdawg.contracts.enabled")
    fun sisterManifest(props: ContractProperties, client: WfmClient, jdbc: JdbcTemplate): CollectionSync =
        ContractManifest("sister", props, client, jdbc)
}
