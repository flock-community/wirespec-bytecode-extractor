package community.flock.wirespec.extractor.fixtures.dto

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * Fixture covering JDK value types that Jackson serializes as ISO/string forms.
 * These must be treated as STRING primitives — never walked into nested
 * Wirespec types — otherwise their private fields would leak into the schema.
 */
data class TemporalDto(
    val createdAt: LocalDateTime,
    val birthDate: LocalDate,
    val occurredAt: Instant,
    val timezone: ZoneOffset,
    val zoned: ZonedDateTime,
    val price: BigDecimal,
)
