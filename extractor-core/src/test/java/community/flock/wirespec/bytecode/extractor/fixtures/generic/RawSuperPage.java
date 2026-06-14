package community.flock.wirespec.bytecode.extractor.fixtures.generic;

/**
 * Java class that extends the raw generic Page without binding T.
 * Used to test the rawGenericSuperclass error path.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public class RawSuperPage extends Page {
    public RawSuperPage() {
        super("x", 0L, 0);
    }
}
