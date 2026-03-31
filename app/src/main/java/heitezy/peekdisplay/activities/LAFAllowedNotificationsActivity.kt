package heitezy.peekdisplay.activities

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.widget.SearchView
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.preference.DropDownPreference
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
        setContentView(R.layout.activity_allowed_notifications)

        val rootView = findViewById<LinearLayout>(R.id.allowed_notifications_root)
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, windowInsets ->
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(insets.left, insets.top, insets.right, insets.bottom)
            WindowInsetsCompat.CONSUMED
        }

        findViewById<SearchView>(R.id.allowed_search).apply {
            isIconified = false
            isSubmitButtonEnabled = false
            queryHint = getString(R.string.pref_look_and_feel_allowed_notifications_search_hint)

            setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String?): Boolean = true

                override fun onQueryTextChange(newText: String?): Boolean {
                    (supportFragmentManager.findFragmentById(R.id.settings) as? PreferenceFragment)
                        ?.setSearchQuery(newText.orEmpty())
                    return true
                }
            })
        }

        supportFragmentManager.beginTransaction()
            .replace(R.id.settings, PreferenceFragment())
            .commit()
    }

    class PreferenceFragment : PreferenceFragmentCompat() {

        private lateinit var allowedCategory: PreferenceCategory
        private lateinit var availableCategory: PreferenceCategory
        private lateinit var scopePreference: DropDownPreference

        private var allowedArray: JSONArray = JSONArray()
        private var selectedScope: String = SCOPE_ALL
        private var searchQuery: String = ""

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            addPreferencesFromResource(R.xml.pref_laf_wf_allowed_notifications)

            allowedCategory = findPreference("allowed") ?: error("Invalid layout.")
            availableCategory = findPreference("available") ?: error("Invalid layout.")
            scopePreference = findPreference("allowed_scope") ?: error("Invalid layout.")

            scopePreference.setOnPreferenceChangeListener { _, newValue ->
                selectedScope = newValue?.toString().orEmpty().ifBlank { SCOPE_ALL }
                preferenceManager.sharedPreferences?.edit {
                    putString(KEY_ALLOWED_SCOPE, selectedScope)
                }
                rebuildList()
                true
            }
        }

        override fun onStart() {
            super.onStart()

            allowedArray = JSONArray(
                preferenceManager.sharedPreferences?.getString(KEY_ALLOWED, "[]")
            )

            selectedScope = preferenceManager.sharedPreferences?.getString(KEY_ALLOWED_SCOPE, SCOPE_ALL)
                ?: SCOPE_ALL

            scopePreference.value = selectedScope
            rebuildList()
        }

        fun setSearchQuery(query: String) {
            val normalized = query.trim()
            if (searchQuery == normalized) return
            searchQuery = normalized
            rebuildList()
        }

        private fun rebuildList() {
            if (!isAdded) return

            val pm = requireContext().packageManager
            allowedCategory.removeAll()
            availableCategory.removeAll()

            scopePreference.summary = scopeSummary(selectedScope)

            val matchingApps = getInstalledApps(pm)
                .asSequence()
                .filter { matchesScope(it, selectedScope) }
                .filter { matchesSearch(pm, it, searchQuery) }
                .sortedWith(compareBy({ getAppLabel(pm, it) }, { it.packageName }))
                .toList()

            if (matchingApps.isEmpty()) {
                allowedCategory.addPreference(
                    Preference(preferenceScreen.context).apply {
                        setIcon(R.drawable.ic_notification)
                        title = getString(R.string.pref_look_and_feel_allowed_notifications_no_results)
                        summary = getString(R.string.pref_look_and_feel_allowed_notifications_no_results_summary)
                    }
                )
                availableCategory.addPreference(
                    Preference(preferenceScreen.context).apply {
                        setIcon(R.drawable.ic_notification)
                        title = getString(R.string.pref_look_and_feel_allowed_notifications_no_results)
                        summary = getString(R.string.pref_look_and_feel_allowed_notifications_no_results_summary)
                    }
                )
                return
            }

            matchingApps.forEach { appInfo ->
                val packageName = appInfo.packageName
                val pref = generatePref(pm, packageName)

                if (JSON.contains(allowedArray, packageName)) {
                    pref.setOnPreferenceClickListener {
                        JSON.remove(allowedArray, packageName)
                        preferenceManager.sharedPreferences?.edit {
                            putString(KEY_ALLOWED, allowedArray.toString())
                        }
                        NotificationService.activeService?.refreshNotifications()
                        allowedCategory.removePreference(it)
                        availableCategory.addPreference(generatePref(pm, packageName))
                        true
                    }
                    allowedCategory.addPreference(pref)
                } else {
                    pref.setOnPreferenceClickListener {
                        if (!JSON.contains(allowedArray, packageName)) {
                            allowedArray.put(packageName)
                            preferenceManager.sharedPreferences?.edit {
                                putString(KEY_ALLOWED, allowedArray.toString())
                            }
                            NotificationService.activeService?.refreshNotifications()
                            availableCategory.removePreference(it)
                            allowedCategory.addPreference(generatePref(pm, packageName))
                        }
                        true
                    }
                    availableCategory.addPreference(pref)
                }
            }

            if (allowedCategory.preferenceCount == 0) {
                allowedCategory.addPreference(
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
                putString(KEY_ALLOWED, allowedArray.toString())
                putString(KEY_ALLOWED_SCOPE, selectedScope)
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

        private fun matchesScope(appInfo: ApplicationInfo, scope: String): Boolean {
            val isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            return when (scope) {
                SCOPE_INSTALLED -> !isSystemApp
                SCOPE_SYSTEM -> isSystemApp
                else -> true
            }
        }

        private fun matchesSearch(pm: PackageManager, appInfo: ApplicationInfo, query: String): Boolean {
            val normalized = query.trim().lowercase()
            if (normalized.isEmpty()) return true

            val label = getAppLabel(pm, appInfo).lowercase()
            val packageName = appInfo.packageName.lowercase()

            return label.contains(normalized) || packageName.contains(normalized)
        }

        private fun scopeSummary(scope: String): String {
            return when (scope) {
                SCOPE_INSTALLED -> getString(R.string.pref_look_and_feel_allowed_notifications_scope_installed_summary)
                SCOPE_SYSTEM -> getString(R.string.pref_look_and_feel_allowed_notifications_scope_system_summary)
                else -> getString(R.string.pref_look_and_feel_allowed_notifications_scope_all_summary)
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

        companion object {
            private const val KEY_ALLOWED = "allowed_notifications"
            private const val KEY_ALLOWED_SCOPE = "allowed_notifications_scope"

            private const val SCOPE_INSTALLED = "installed"
            private const val SCOPE_SYSTEM = "system"
            private const val SCOPE_ALL = "all"
        }
    }
}
