package ak.dev.khi_backend.khi_app.service.media;

/** Public bridge to the package-private {@link PngFixtures} for tests in other packages. */
public final class PngFixturesAccess {

    private PngFixturesAccess() {}

    public static byte[] rawTransparentGradient(int w, int h) {
        return PngFixtures.rawRgba(w, h, PngFixtures.transparentGradient(w, h));
    }
}
