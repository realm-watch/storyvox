package `in`.jphe.storyvox.feature.settings

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #478 — regression guard for `SettingsSwitchRow` TalkBack
 * labeling.
 *
 * Before the Phase 2 refit, `SettingsSwitchRow` delegated to
 * `SettingsRow` (which applied `Modifier.clickable(role = Role.Button)`)
 * and rendered the actual `Switch` as a separate clickable child. The
 * net effect: TalkBack walked the tree and announced two siblings
 * ("<title>, button" + "switch, on/off"), with no label attached to
 * the Switch.
 *
 * The fix moves `Modifier.toggleable(role = Role.Switch)` to the
 * outer Row and makes the inner Switch passive
 * (`onCheckedChange = null`). TalkBack now merges the subtree under a
 * single `Role.Switch` node with the title as its label.
 *
 * We can't run a Compose UI test from the unit-test source set. The
 * structural canary [settingsSwitchRowUsesToggleable] in
 * `SettingsComposables.kt` must stay `true` unless a future refactor
 * proves on a real device with TalkBack that an alternative carries
 * the same merged announcement.
 */
class SettingsSwitchRowToggleableTest {

    @Test
    fun `SettingsSwitchRow uses Modifier-toggleable with Role-Switch per issue #478`() {
        assertTrue(
            "SettingsSwitchRow must wrap its row in Modifier.toggleable(role = Role.Switch) " +
                "so TalkBack reads a single labelled switch node (issue #478)",
            settingsSwitchRowUsesToggleable,
        )
    }
}
