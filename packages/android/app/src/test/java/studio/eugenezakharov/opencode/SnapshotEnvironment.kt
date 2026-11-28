package studio.eugenezakharov.opencode

import app.cash.paparazzi.Environment
import app.cash.paparazzi.detectEnvironment

/**
 * Paparazzi 1.3.5 cannot render with compileSdk 36 (it crashes in
 * Renderer.configureBuildProperties), but the app must compile against 36 for
 * Play. Render the goldens as API 35 instead; the composables under test use
 * nothing from API 36.
 */
val snapshotEnvironment: Environment = detectEnvironment().copy(compileSdkVersion = 35)
