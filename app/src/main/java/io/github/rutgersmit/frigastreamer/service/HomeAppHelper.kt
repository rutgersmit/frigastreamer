package io.github.rutgersmit.frigastreamer.service

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings

/**
 * Hulpfuncties voor de optionele home-app-modus (automatisch starten na reboot).
 * Sinds Android 10 mag een BOOT_COMPLETED-receiver geen Activity starten; als standaard
 * home-app start Android de app na een reboot zelf.
 */
object HomeAppHelper {
    fun isDefaultHome(context: Context): Boolean {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val info = context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        return info?.activityInfo?.packageName == context.packageName
    }

    /** Opent de systeeminstelling voor de standaard home-app (om in of uit te schakelen). */
    fun openHomeSettings(context: Context) {
        val candidates = listOf(
            Intent(Settings.ACTION_HOME_SETTINGS),
            Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
            Intent(Settings.ACTION_SETTINGS),
        )
        for (i in candidates) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { context.startActivity(i) }.isSuccess) return
        }
    }
}
