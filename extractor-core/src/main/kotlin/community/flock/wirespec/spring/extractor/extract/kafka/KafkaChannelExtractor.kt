package community.flock.wirespec.spring.extractor.extract.kafka

import community.flock.wirespec.spring.extractor.extract.TypeExtractor
import community.flock.wirespec.spring.extractor.model.Channel

/**
 * Turns Kafka scan results into [Channel] domain values. Payload selection is
 * delegated to [KafkaPayloadSelector]; the resolved JVM [java.lang.reflect.Type]
 * is then converted by [TypeExtractor] so existing DTO/enum/refined logic is
 * reused (Jackson naming, validation constraints, generic flattening, nullability).
 *
 * Channel naming = PascalCase(method name), matching the existing endpoint
 * naming convention used by HTTP extraction.
 */
internal class KafkaChannelExtractor(
    private val types: TypeExtractor,
    private val onWarn: (String) -> Unit = {},
) {

    /** Build channels from consumer (listener) sites. */
    fun fromListenerSites(sites: List<KafkaListenerScanner.Site>): List<Channel> =
        sites.mapNotNull { site ->
            when (val r = KafkaPayloadSelector.select(site.method)) {
                is KafkaPayloadSelector.Result.Selected -> Channel(
                    ownerSimpleName = site.ownerClass.simpleName,
                    name = pascalCase(site.method.name),
                    payload = types.extract(r.payloadType),
                )
                is KafkaPayloadSelector.Result.Skipped -> {
                    onWarn("kafka.consumer: skipped ${site.ownerClass.name}.${site.method.name}: ${r.reason}")
                    null
                }
            }
        }

    private fun pascalCase(name: String): String =
        if (name.isEmpty()) name else name[0].uppercaseChar() + name.substring(1)
}
