package community.flock.wirespec.extractor.fixtures.dto.jspecify;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/** Fixtures for parameter-level JSpecify nullness (TYPE_USE annotations). */
public final class JSpecifyParams {

    private JSpecifyParams() {
    }

    @SuppressWarnings("unused")
    public static void nullableParam(@Nullable String value) {
    }

    @SuppressWarnings("unused")
    public static void nonNullParam(@NonNull String value) {
    }

    @SuppressWarnings("unused")
    public static void unannotatedParam(String value) {
    }
}
