package com.varizpardaz.listener.app.guide

import androidx.annotation.StringRes
import com.varizpardaz.listener.app.R

/**
 * An activity of a phone maker's own settings app. The names come from public lists of such screens, were not checked on a
 * real phone from here, and change between versions: every one is only tried, and a screen that is not there is skipped.
 */
class SettingsComponent(val packageName: String, val className: String, val stringExtras: Map<String, String> = emptyMap())

sealed interface StepAction {
    /** Instructions only. */
    data object None : StepAction

    /** The app's own page in system settings (it holds the Battery entry on most phones). */
    data object AppSettings : StepAction

    /** Try these maker screens in order; if none opens, fall back to [AppSettings]. */
    class Maker(val components: List<SettingsComponent>) : StepAction
}

class GuideStep(@param:StringRes val text: Int, val action: StepAction = StepAction.None)

class MakerGuide(
    val maker: Maker,
    @param:StringRes val chipLabel: Int,
    @param:StringRes val title: Int,
    val steps: List<GuideStep>,
)

object MakerGuides {
    val all: List<MakerGuide> = listOf(
        MakerGuide(
            Maker.XIAOMI, R.string.maker_chip_xiaomi, R.string.guide_title_xiaomi,
            listOf(
                GuideStep(
                    R.string.guide_xiaomi_1,
                    StepAction.Maker(listOf(SettingsComponent("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"))),
                ),
                GuideStep(
                    R.string.guide_xiaomi_2,
                    StepAction.Maker(
                        listOf(
                            SettingsComponent(
                                "com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity",
                                mapOf("package_name" to PACKAGE_PLACEHOLDER, "package_label" to LABEL_PLACEHOLDER),
                            ),
                        ),
                    ),
                ),
                GuideStep(R.string.guide_recents_lock_xiaomi),
            ),
        ),
        MakerGuide(
            Maker.SAMSUNG, R.string.maker_chip_samsung, R.string.guide_title_samsung,
            listOf(
                GuideStep(
                    R.string.guide_samsung_1,
                    StepAction.Maker(
                        listOf(
                            SettingsComponent("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity"),
                            SettingsComponent("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"),
                        ),
                    ),
                ),
                GuideStep(R.string.guide_samsung_2),
                GuideStep(R.string.guide_samsung_3, StepAction.AppSettings),
                GuideStep(R.string.guide_recents_lock_samsung),
            ),
        ),
        MakerGuide(
            Maker.HUAWEI, R.string.maker_chip_huawei, R.string.guide_title_huawei,
            listOf(
                GuideStep(
                    R.string.guide_huawei_1,
                    StepAction.Maker(
                        listOf(
                            SettingsComponent("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
                            SettingsComponent("com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"),
                            SettingsComponent("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
                        ),
                    ),
                ),
                GuideStep(R.string.guide_huawei_2),
                GuideStep(R.string.guide_recents_lock_generic),
            ),
        ),
        MakerGuide(
            Maker.OPPO, R.string.maker_chip_oppo, R.string.guide_title_oppo,
            listOf(
                GuideStep(
                    R.string.guide_oppo_1,
                    StepAction.Maker(
                        listOf(
                            SettingsComponent("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
                            SettingsComponent("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
                            SettingsComponent("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
                        ),
                    ),
                ),
                GuideStep(R.string.guide_oppo_2, StepAction.AppSettings),
                GuideStep(R.string.guide_recents_lock_generic),
            ),
        ),
        MakerGuide(
            Maker.VIVO, R.string.maker_chip_vivo, R.string.guide_title_vivo,
            listOf(
                GuideStep(
                    R.string.guide_vivo_1,
                    StepAction.Maker(
                        listOf(
                            SettingsComponent("com.vivo.abe", "com.vivo.applicationbehaviorengine.ui.ExcessivePowerManagerActivity"),
                            SettingsComponent("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
                        ),
                    ),
                ),
                GuideStep(
                    R.string.guide_vivo_2,
                    StepAction.Maker(
                        listOf(
                            SettingsComponent("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
                            SettingsComponent("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
                        ),
                    ),
                ),
                GuideStep(R.string.guide_recents_lock_generic),
            ),
        ),
        MakerGuide(
            Maker.NOKIA, R.string.maker_chip_nokia, R.string.guide_title_nokia,
            listOf(
                GuideStep(
                    R.string.guide_nokia_1,
                    StepAction.Maker(listOf(SettingsComponent("com.evenwell.powersaving.g3", "com.evenwell.powersaving.g3.exception.PowerSaverExceptionActivity"))),
                ),
                GuideStep(R.string.guide_nokia_2, StepAction.AppSettings),
            ),
        ),
        MakerGuide(
            Maker.OTHER, R.string.maker_chip_other, R.string.guide_title_other,
            listOf(
                GuideStep(R.string.guide_other_1, StepAction.AppSettings),
                GuideStep(R.string.guide_other_2),
            ),
        ),
    )

    fun of(maker: Maker): MakerGuide = all.first { it.maker == maker }

    /** The detected maker's guide first, then the rest in a fixed order (the phone's label does not always match its software). */
    fun ordered(detected: Maker): List<MakerGuide> = listOf(of(detected)) + all.filter { it.maker != detected }

    /** Replaced by the app's package name / label when a component is launched. */
    const val PACKAGE_PLACEHOLDER = "{package}"
    const val LABEL_PLACEHOLDER = "{label}"
}
