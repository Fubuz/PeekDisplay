package heitezy.peekdisplay.activities

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.FrameLayout
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import heitezy.peekdisplay.R
import heitezy.peekdisplay.actions.alwayson.AlwaysOn
import heitezy.peekdisplay.helpers.Global
import heitezy.peekdisplay.helpers.JSON
import heitezy.peekdisplay.services.NotificationService
import org.json.JSONArray

class LAFAllowedNotificationsActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val rootView = findViewById<FrameLayout>(R.id.settings)
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, windowInsets ->
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(insets.left, insets.top, insets.right, insets.bottom)
            WindowInsetsCompat.CONSUMED
        }

        supportFragmentManager.beginTransaction()
            .replace(R.id.settings, PreferenceFragment())
            .commit()
    }

    class PreferenceFragment : PreferenceFragmentCompat() {

        private var allowedArray: JSONArray = JSONArray()
        private lateinit var allowed: PreferenceCategory
        private lateinit var available: PreferenceCategory

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            addPreferencesFromResource(R.xml.pref_laf_wf_allowed_notifications)
            allowed = findPreference("allowed") ?: error("Invalid layout.")
            available = findPreference("available") ?: error("Invalid layout.")
        }

        override fun onStart() {
            super.onStart()

            val pm = requireContext().packageManager
            allowedArray = JSONArray(
                preferenceManager.sharedPreferences?.getString("allowed_notifications", "[]")
            )

            allowed.removeAll()
            available.removeAll()

            val installedApps = getInstalledApps(pm)
                .sortedWith(compareBy({ getAppLabel(pm, it) }, { it.packageName }))

            installedApps.forEach { appInfo ->
                val packageName = appInfo.packageName
                val pref = generatePref(pm, packageName)

                if (JSON.contains(allowedArray, packageName)) {
                    pref.setOnPreferenceClickListener {
                        JSON.remove(allowedArray, packageName)
                        preferenceManager.sharedPreferences?.edit {
                            putString("allowed_notifications", allowedArray.toString())
                        }
                        NotificationService.activeService?.refreshNotifications()
                        allowed.removePreference(it)
                        available.addPreference(generatePref(pm, packageName))
                        true
                    }
                    allowed.addPreference(pref)
                } else {
                    pref.setOnPreferenceClickListener {
                        if (!JSON.contains(allowedArray, packageName)) {
                            allowedArray.put(packageName)
                            preferenceManager.sharedPreferences?.edit {
                                putString("allowed_notifications", allowedArray.toString())
                            }
                            NotificationService.activeService?.refreshNotifications()
                            available.removePreference(it)
                            allowed.addPreference(generatePref(pm, packageName))
                        }
                        true
                    }
                    available.addPreference(pref)
                }
            }

            if (JSON.isEmpty(allowedArray)) {
                allowed.addPreference(
                    Preference(preferenceScreen.context).apply {
                        setIcon(R.drawable.ic_notification)
                        title = getString(R.string.pref_look_and_feel_allowed_notifications_empty)
                        summary = getString(R.string.pref_look_and_feel_allowed_notifications_empty_summary)
                    }
                )
            }
        }

        override fun onStop() {
            super.onStop()
            preferenceManager.sharedPreferences?.edit {
                putString("allowed_notifications", allowedArray.toString())
            }
            AlwaysOn.finish()
        }

        private fun getInstalledApps(pm: PackageManager): List<ApplicationInfo> {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getInstalledApplications(
                    PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong())
                )
            } else {
                @Suppress("DEPRECATION")
                pm.getInstalledApplications(PackageManager.GET_META_DATA)
            }
        }

        private fun getAppLabel(pm: PackageManager, appInfo: ApplicationInfo): String {
            return try {
                pm.getApplicationLabel(appInfo).toString()
            } catch (e: Exception) {
                appInfo.packageName
            }
        }

        private fun generatePref(pm: PackageManager, packageName: String): Preference {
            val pref = Preference(preferenceScreen.context)
            pref.setIcon(R.drawable.ic_notification)
            pref.title = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getApplicationLabel(
                        pm.getApplicationInfo(
                            packageName,
                            PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong())
                        )
                    ).toString()
                } else {
                    @Suppress("DEPRECATION")
                    pm.getApplicationLabel(
                        pm.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
                    ).toString()
                }
            } catch (exception: PackageManager.NameNotFoundException) {
                Log.w(Global.LOG_TAG, exception.toString())
                getString(R.string.pref_look_and_feel_allowed_notifications_unknown)
            }

            pref.summary = packageName
            return pref
        }
    }
}
