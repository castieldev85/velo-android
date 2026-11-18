package eu.siacs.conversations.velo;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;

import eu.siacs.conversations.R;

/**
 * Velo 0.19: the launcher icon can be disguised (Calculator, Notes) so whoever picks up the phone
 * does not see a secure chat. Each choice is an activity-alias in the manifest; exactly one is
 * enabled.
 */
public final class VeloIcons {

    public static final class Choice {
        public final String alias;
        public final int label;
        public final int icon;

        Choice(final String alias, final int label, final int icon) {
            this.alias = alias;
            this.label = label;
            this.icon = icon;
        }
    }

    public static final Choice[] CHOICES = {
        new Choice("eu.siacs.conversations.VeloIconDefault", R.string.app_name, R.mipmap.ic_launcher),
        new Choice("eu.siacs.conversations.VeloIconCalculator", R.string.velo_icon_calculator, R.mipmap.velo_launcher_calc),
        new Choice("eu.siacs.conversations.VeloIconNotes", R.string.velo_icon_notes, R.mipmap.velo_launcher_notes),
    };

    private VeloIcons() {}

    public static int current(final Context context) {
        final PackageManager pm = context.getPackageManager();
        for (int i = 1; i < CHOICES.length; i++) {
            final int state = pm.getComponentEnabledSetting(
                    new ComponentName(context.getPackageName(), CHOICES[i].alias));
            if (state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
                return i;
            }
        }
        return 0;
    }

    public static void apply(final Context context, final int choice) {
        final PackageManager pm = context.getPackageManager();
        // enable the new one first, so there is never a moment without a launcher entry
        pm.setComponentEnabledSetting(
                new ComponentName(context.getPackageName(), CHOICES[choice].alias),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP);
        for (int i = 0; i < CHOICES.length; i++) {
            if (i != choice) {
                pm.setComponentEnabledSetting(
                        new ComponentName(context.getPackageName(), CHOICES[i].alias),
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP);
            }
        }
    }
}
