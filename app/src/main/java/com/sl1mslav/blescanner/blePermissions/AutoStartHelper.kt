package com.sl1mslav.blescanner.blePermissions

import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import java.util.Locale

class AutoStartHelper private constructor() {
    /***
     * Xiaomi
     */
    private val BRAND_REDMI = "redmi"
    private val BRAND_XIAOMI = "xiaomi"
    private val PACKAGE_XIAOMI_MAIN = "com.miui.securitycenter"
    private val PACKAGE_XIAOMI_COMPONENT =
        "com.miui.permcenter.autostart.AutoStartManagementActivity"

    /***
     * Letv
     */
    private val BRAND_LETV = "letv"
    private val PACKAGE_LETV_MAIN = "com.letv.android.letvsafe"
    private val PACKAGE_LETV_COMPONENT = "com.letv.android.letvsafe.AutobootManageActivity"

    /***
     * ASUS ROG
     */
    private val BRAND_ASUS = "asus"
    private val PACKAGE_ASUS_MAIN = "com.asus.mobilemanager"
    private val PACKAGE_ASUS_COMPONENT = "com.asus.mobilemanager.powersaver.PowerSaverSettings"

    /***
     * Honor
     */
    private val BRAND_HONOR = "honor"
    private val PACKAGE_HONOR_MAIN = "com.huawei.systemmanager"
    private val PACKAGE_HONOR_COMPONENT =
        "com.huawei.systemmanager.optimize.process.ProtectActivity"

    /**
     * Oppo
     */
    private val BRAND_OPPO = "oppo"
    private val PACKAGE_OPPO_MAIN = "com.coloros.safecenter"
    private val PACKAGE_OPPO_FALLBACK = "com.oppo.safe"
    private val PACKAGE_OPPO_COMPONENT =
        "com.coloros.safecenter.permission.startup.StartupAppListActivity"
    private val PACKAGE_OPPO_COMPONENT_FALLBACK =
        "com.oppo.safe.permission.startup.StartupAppListActivity"
    private val PACKAGE_OPPO_COMPONENT_FALLBACK_A =
        "com.coloros.safecenter.startupapp.StartupAppListActivity"

    /**
     * Vivo
     */
    private val BRAND_VIVO = "vivo"
    private val PACKAGE_VIVO_MAIN = "com.iqoo.secure"
    private val PACKAGE_VIVO_FALLBACK = "com.vivo.perm;issionmanager"
    private val PACKAGE_VIVO_COMPONENT = "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"
    private val PACKAGE_VIVO_COMPONENT_FALLBACK =
        "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
    private val PACKAGE_VIVO_COMPONENT_FALLBACK_A =
        "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"

    /**
     * Nokia
     */
    private val BRAND_NOKIA = "nokia"
    private val PACKAGE_NOKIA_MAIN = "com.evenwell.powersaving.g3"
    private val PACKAGE_NOKIA_COMPONENT =
        "com.evenwell.powersaving.g3.exception.PowerSaverExceptionActivity"

    fun needAutostart(): Boolean {
        val buildInfo = Build.BRAND.lowercase(Locale.getDefault())
        return (buildInfo in listOf(
            BRAND_ASUS,
            BRAND_REDMI,
            BRAND_XIAOMI,
            BRAND_LETV,
            BRAND_HONOR,
            BRAND_OPPO,
            BRAND_VIVO,
            BRAND_NOKIA
        ))
    }

    fun getAutoStartPermission(context: Context) {
        val buildInfo = Build.BRAND.lowercase(Locale.getDefault())
        when (buildInfo) {
            BRAND_ASUS -> autoStartAsus(context)
            BRAND_REDMI, BRAND_XIAOMI -> autoStartXiaomi(context)
            BRAND_LETV -> autoStartLetv(context)
            BRAND_HONOR -> autoStartHonor(context)
            BRAND_OPPO -> autoStartOppo(context)
            BRAND_VIVO -> autoStartVivo(context)
            BRAND_NOKIA -> autoStartNokia(context)
        }
    }

    private fun autoStartAsus(context: Context) {
        if (isPackageExists(context, PACKAGE_ASUS_MAIN)) {
            showAlert(context) { dialog: DialogInterface, which: Int ->
                try {
                    //   PrefUtil.writeBoolean(context, PrefUtil.PREF_KEY_APP_AUTO_START, true)
                    startIntent(context, PACKAGE_ASUS_MAIN, PACKAGE_ASUS_COMPONENT)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                dialog.dismiss()
            }
        }
    }

    private fun showAlert(context: Context, onClickListener: DialogInterface.OnClickListener) {
        AlertDialog.Builder(context).setTitle("Включение автозагрузки")
            .setMessage("Включите автозагрузку для приложения.")
            .setPositiveButton("ОК", onClickListener).show().setCancelable(false)
    }

    private fun autoStartXiaomi(context: Context) {
        if (isPackageExists(context, PACKAGE_XIAOMI_MAIN)) {
            showAlert(
                context
            ) { dialog: DialogInterface?, which: Int ->
                try {
                    //PrefUtil.writeBoolean(context, PrefUtil.PREF_KEY_APP_AUTO_START, true)
                    startIntent(context, PACKAGE_XIAOMI_MAIN, PACKAGE_XIAOMI_COMPONENT)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    private fun autoStartLetv(context: Context) {
        if (isPackageExists(context, PACKAGE_LETV_MAIN)) {
            showAlert(context) { dialog, which ->
                try {
                    //  PrefUtil.writeBoolean(context, PrefUtil.PREF_KEY_APP_AUTO_START, true)
                    startIntent(context, PACKAGE_LETV_MAIN, PACKAGE_LETV_COMPONENT)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }


    private fun autoStartHonor(context: Context) {
        if (isPackageExists(context, PACKAGE_HONOR_MAIN)) {
            showAlert(context) { dialog, which ->
                try {
                    //PrefUtil.writeBoolean(context, PrefUtil.PREF_KEY_APP_AUTO_START, true)
                    startIntent(context, PACKAGE_HONOR_MAIN, PACKAGE_HONOR_COMPONENT)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    private fun autoStartOppo(context: Context) {
        if (isPackageExists(context, PACKAGE_OPPO_MAIN) || isPackageExists(
                context,
                PACKAGE_OPPO_FALLBACK
            )
        ) {
            showAlert(context) { dialog, which ->
                try {
                    //PrefUtil.writeBoolean(context, PrefUtil.PREF_KEY_APP_AUTO_START, true)
                    startIntent(context, PACKAGE_OPPO_MAIN, PACKAGE_OPPO_COMPONENT)
                } catch (e: Exception) {
                    e.printStackTrace()
                    try {
                        //PrefUtil.writeBoolean(context, PrefUtil.PREF_KEY_APP_AUTO_START, true)
                        startIntent(context, PACKAGE_OPPO_FALLBACK, PACKAGE_OPPO_COMPONENT_FALLBACK)
                    } catch (ex: Exception) {
                        ex.printStackTrace()
                        try {
                            //PrefUtil.writeBoolean(context, PrefUtil.PREF_KEY_APP_AUTO_START, true)
                            startIntent(
                                context,
                                PACKAGE_OPPO_MAIN,
                                PACKAGE_OPPO_COMPONENT_FALLBACK_A
                            )
                        } catch (exx: Exception) {
                            exx.printStackTrace()
                        }
                    }
                }
            }
        }
    }

    private fun autoStartVivo(context: Context) {
        if (isPackageExists(context, PACKAGE_VIVO_MAIN) || isPackageExists(
                context,
                PACKAGE_VIVO_FALLBACK
            )
        ) {
            showAlert(context) { dialog, which ->
                try {
                    //PrefUtil.writeBoolean(context, PrefUtil.PREF_KEY_APP_AUTO_START, true)
                    startIntent(context, PACKAGE_VIVO_MAIN, PACKAGE_VIVO_COMPONENT)
                } catch (e: Exception) {
                    e.printStackTrace()
                    try {
                        //PrefUtil.writeBoolean(context, PrefUtil.PREF_KEY_APP_AUTO_START, true)
                        startIntent(context, PACKAGE_VIVO_FALLBACK, PACKAGE_VIVO_COMPONENT_FALLBACK)
                    } catch (ex: Exception) {
                        ex.printStackTrace()
                        try {
                            //PrefUtil.writeBoolean(context, PrefUtil.PREF_KEY_APP_AUTO_START, true)
                            startIntent(
                                context,
                                PACKAGE_VIVO_MAIN,
                                PACKAGE_VIVO_COMPONENT_FALLBACK_A
                            )
                        } catch (exx: Exception) {
                            exx.printStackTrace()
                        }
                    }
                }
            }
        }
    }

    private fun autoStartNokia(context: Context) {
        if (isPackageExists(context, PACKAGE_NOKIA_MAIN)) {
            showAlert(context) { dialog, which ->
                try {
                    //PrefUtil.writeBoolean(context, PrefUtil.PREF_KEY_APP_AUTO_START, true)
                    startIntent(context, PACKAGE_NOKIA_MAIN, PACKAGE_NOKIA_COMPONENT)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }


    @Throws(Exception::class)
    private fun startIntent(context: Context, packageName: String, componentName: String) {
        try {
            val intent = Intent()
            intent.setComponent(ComponentName(packageName, componentName))
            context.startActivity(intent)
        } catch (var5: Exception) {
            var5.printStackTrace()
            throw var5
        }
    }

    private fun isPackageExists(context: Context, targetPackage: String): Boolean {
        val packages: List<ApplicationInfo>
        val pm = context.packageManager
        packages = pm.getInstalledApplications(0)
        for (packageInfo in packages) {
            if (packageInfo.packageName == targetPackage) {
                return true
            }
        }

        return false
    }

    companion object {
        val instance: AutoStartHelper
            get() = AutoStartHelper()
    }
}