package ring.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ring.app.Graph
import ring.app.R
import ring.domain.HeartRateModel
import ring.domain.Profile
import ring.domain.Sex

private val DEFAULT_PROFILE = Profile(ageYears = 30, sex = Sex.MALE, weightKg = 70.0, heightCm = 170.0)
private const val MIN_AGE = 14
private const val MAX_AGE = 100
private const val MIN_WEIGHT = 30.0
private const val MAX_WEIGHT = 250.0
private const val MIN_HEIGHT = 120.0
private const val MAX_HEIGHT = 230.0
private const val MIN_HR_MAX = 120
private const val MAX_HR_MAX = 230

/** Body data used for calories, training load and fitness estimates. Stored on the phone only. */
@Composable
fun ProfileCard(graph: Graph) {
    val saved by graph.settings.profile.collectAsStateWithLifecycle()
    val p = saved ?: DEFAULT_PROFILE

    fun save(updated: Profile) = graph.settings.setProfile(updated)
    TCard {
        Label(stringResource(R.string.profile_title))
        T(
            stringResource(if (saved == null) R.string.profile_not_set else R.string.profile_hint),
            size = 11.sp,
            color = if (saved == null) Term.warn else Term.dim,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TChip(stringResource(R.string.sex_male), saved != null && p.sex == Sex.MALE) { save(p.copy(sex = Sex.MALE)) }
            TChip(stringResource(R.string.sex_female), saved != null && p.sex == Sex.FEMALE) { save(p.copy(sex = Sex.FEMALE)) }
        }
        Stepper(
            stringResource(R.string.profile_age),
            "${p.ageYears}",
            { save(p.copy(ageYears = (p.ageYears - 1).coerceAtLeast(MIN_AGE))) },
            { save(p.copy(ageYears = (p.ageYears + 1).coerceAtMost(MAX_AGE))) },
        )
        Stepper(
            stringResource(R.string.profile_weight),
            "%.0f".format(p.weightKg),
            { save(p.copy(weightKg = (p.weightKg - 1).coerceAtLeast(MIN_WEIGHT))) },
            { save(p.copy(weightKg = (p.weightKg + 1).coerceAtMost(MAX_WEIGHT))) },
        )
        Stepper(
            stringResource(R.string.profile_height),
            "%.0f".format(p.heightCm),
            { save(p.copy(heightCm = (p.heightCm - 1).coerceAtLeast(MIN_HEIGHT))) },
            { save(p.copy(heightCm = (p.heightCm + 1).coerceAtMost(MAX_HEIGHT))) },
        )
        val hrMax = HeartRateModel.hrMax(p)
        Stepper(
            stringResource(R.string.profile_hr_max),
            if (p.hrMaxOverride == null) stringResource(R.string.profile_hr_max_auto, hrMax) else "$hrMax",
            { save(p.copy(hrMaxOverride = (hrMax - 1).coerceAtLeast(MIN_HR_MAX))) },
            { save(p.copy(hrMaxOverride = (hrMax + 1).coerceAtMost(MAX_HR_MAX))) },
        )
        if (p.hrMaxOverride != null) {
            TButton(stringResource(R.string.profile_hr_max_reset), { save(p.copy(hrMaxOverride = null)) }, kind = BtnKind.GHOST)
        }
    }
}
