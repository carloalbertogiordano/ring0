package ring.domain

enum class Sex { MALE, FEMALE }

/** The user's body data, entered in Settings and kept on the phone only. */
data class Profile(
    val ageYears: Int,
    val sex: Sex,
    val weightKg: Double,
    val heightCm: Double,
    /** A measured maximum heart rate; when null the age formula is used. */
    val hrMaxOverride: Int? = null,
)
