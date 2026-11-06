package site.marketmix.camera

/**
 * A lens look, not a camera lens. Each entry pairs a focus falloff with the
 * optical habits of the focal length it is named after: longer glass throws the
 * background further out of focus, vignettes a little harder and blooms
 * highlights more.
 */
enum class LensPreset(
    val label: String,
    val blur: Float,
    val vignette: Float,
    val bloom: Float,
    val falloff: Float,
    val desat: Float
) {
    WIDE_35("35mm", 0.45f, 0.10f, 0.05f, 0.30f, 0.03f),
    STANDARD_50("50mm", 0.62f, 0.14f, 0.10f, 0.42f, 0.05f),
    PORTRAIT_85("85mm", 0.82f, 0.18f, 0.16f, 0.52f, 0.07f),
    TELE_135("135mm", 1.00f, 0.24f, 0.22f, 0.62f, 0.09f);

    companion object {
        val DEFAULT = STANDARD_50
    }
}
