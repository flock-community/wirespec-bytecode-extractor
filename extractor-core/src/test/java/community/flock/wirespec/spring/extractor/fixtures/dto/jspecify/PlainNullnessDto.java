package community.flock.wirespec.spring.extractor.fixtures.dto.jspecify;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Not @NullMarked: the default stays "nullable", but an explicit JSpecify
 * @Nullable / @NonNull (both TYPE_USE-targeted) still decides per-field.
 */
public class PlainNullnessDto {
    public @Nullable String maybe;
    public @NonNull String always;
    public String unspecified;
}
