package community.flock.wirespec.extractor.extract.messaging

import community.flock.wirespec.extractor.extract.TypeExtractor
import community.flock.wirespec.extractor.model.Channel

/**
 * Turns messaging scan results into [Channel] domain values. Payload selection
 * is delegated to [MessagingPayloadSelector]; the resolved JVM type is converted
 * by [TypeExtractor] so existing DTO/enum/refined logic is reused.
 *
 * Channel naming matches HTTP/Kafka: PascalCase(method name) for consumers,
 * PascalCase(enclosing method) for producers (with a value-type suffix when one
 * method sends multiple types).
 */
internal class MessagingChannelExtractor(
    private val types: TypeExtractor,
    private val onWarn: (String) -> Unit = {},
) {

    /** Build channels from consumer (listener) sites for the given broker. */
    fun fromListenerSites(sites: List<MessagingListenerScanner.Site>, broker: MessagingBroker): List<Channel> =
        sites.mapNotNull { site ->
            when (val r = MessagingPayloadSelector.select(site.method, broker)) {
                is MessagingPayloadSelector.Result.Selected -> Channel(
                    ownerSimpleName = site.ownerClass.simpleName,
                    name = pascalCase(site.method.name),
                    payload = types.extract(r.payloadType),
                )
                is MessagingPayloadSelector.Result.Skipped -> {
                    onWarn("messaging.${broker.id}.consumer: skipped ${site.ownerClass.name}.${site.method.name}: ${r.reason}")
                    null
                }
            }
        }

    /** Build channels from producer (send) sites. */
    fun fromProducerSites(sites: List<MessagingProducerWalker.ProducerSite>): List<Channel> {
        val byMethodKey = sites.groupBy { Triple(it.ownerClass, it.enclosingMethod, it.valueClass) }
            .keys.toList()
        val methodCounts = byMethodKey.groupingBy { it.first to it.second }.eachCount()
        return byMethodKey.map { (owner, methodName, valueClass) ->
            val base = pascalCase(methodName)
            val name = if ((methodCounts[owner to methodName] ?: 0) > 1) "${base}_${valueClass.simpleName}" else base
            Channel(
                ownerSimpleName = owner.simpleName,
                name = name,
                payload = types.extract(valueClass),
            )
        }
    }

    private fun pascalCase(name: String): String =
        if (name.isEmpty()) name else name[0].uppercaseChar() + name.substring(1)
}
