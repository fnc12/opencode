package studio.eugenezakharov.opencode

import app.cash.paparazzi.Environment
import app.cash.paparazzi.detectEnvironment

/**
 * Paparazzi 1.3.5 cannot render against compileSdk 36 (it crashes in
 * Renderer.configureBuildProperties), but the app must compile against 36 for
 * Play. Render the goldens with the android-35 platform instead; the composables
 * under test use nothing from API 36.
 */
val snapshotEnvironment: Environment = detectEnvironment().run {
    copy(
        platformDir = platformDir.replace("android-$compileSdkVersion", "android-35"),
        compileSdkVersion = 35,
    )
}
