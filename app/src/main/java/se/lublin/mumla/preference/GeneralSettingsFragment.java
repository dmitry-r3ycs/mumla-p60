package se.lublin.mumla.preference;

import static java.util.Objects.requireNonNull;

import android.os.Bundle;

import androidx.preference.Preference;

import info.guardianproject.netcipher.proxy.OrbotHelper;
import se.lublin.mumla.R;

public class GeneralSettingsFragment extends MumlaPreferenceFragment {
    private static final String USE_TOR_KEY = "useTor";

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.settings_general, rootKey);

        Preference useOrbotPreference = getPreferenceScreen().findPreference(USE_TOR_KEY);
        requireNonNull(useOrbotPreference).setEnabled(OrbotHelper.isOrbotInstalled(requireContext()));

        Preference batteryPreference = findPreference(BATTERY_KEY);
        if (batteryPreference != null) {
            batteryPreference.setOnPreferenceClickListener(preference -> {
                if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.M) return true;
                android.content.Context context = requireContext();
                try {
                    startActivity(new android.content.Intent(
                            android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            android.net.Uri.parse("package:" + context.getPackageName())));
                } catch (RuntimeException e) {
                    try {
                        startActivity(new android.content.Intent(
                                android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                    } catch (RuntimeException e2) {
                        // No such settings screen on this device
                    }
                }
                return true;
            });
        }
    }

    private static final String BATTERY_KEY = "p60BatteryOptimization";

    @Override
    public void onResume() {
        super.onResume();
        Preference batteryPreference = findPreference(BATTERY_KEY);
        if (batteryPreference == null) return;
        boolean unrestricted = false;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            android.os.PowerManager pm = (android.os.PowerManager)
                    requireContext().getSystemService(android.content.Context.POWER_SERVICE);
            unrestricted = pm != null && pm.isIgnoringBatteryOptimizations(requireContext().getPackageName());
        }
        batteryPreference.setSummary(unrestricted ? R.string.p60BatterySumOn : R.string.p60BatterySum);
    }
}
