package org.example.androidtunnel

/** Pure startup policy, shared by the Activity, boot receiver and service. */
object StartupPolicy {
    const val BOOT_COMPLETED = "android.intent.action.BOOT_COMPLETED"
    fun shouldStartOnBoot(action: String?): Boolean = action == BOOT_COMPLETED
    fun rootGranted(exitCode: Int, uid: String): Boolean = exitCode == 0 && uid.trim() == "0"
}
