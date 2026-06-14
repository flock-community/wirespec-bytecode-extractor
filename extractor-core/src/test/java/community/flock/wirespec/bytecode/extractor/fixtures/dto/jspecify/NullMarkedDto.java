package community.flock.wirespec.bytecode.extractor.fixtures.dto.jspecify;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * JSpecify @NullMarked scope: every reference defaults to non-null unless it
 * carries a (TYPE_USE) @Nullable. Exercises that the extractor flips its default
 * from "nullable" to "non-null" inside null-marked code.
 */
@NullMarked
public record NullMarkedDto(
        String required,
        @Nullable String optional,
        @Nullable Integer optionalBoxed,
        int primitive
) {
}
