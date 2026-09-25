package com.jawa.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

/** Settings: places, location permission, battery, manual refresh. */
class MainActivity : Activity() {

    private val scope = MainScope()
    private var searchJob: Job? = null

    private lateinit var status: TextView
    private lateinit var btnLocation: Button
    private lateinit var btnBackground: Button
    private lateinit var locCount: TextView
    private lateinit var search: EditText
    private lateinit var searchStatus: TextView
    private lateinit var searchResults: LinearLayout
    private lateinit var adapter: PlacesAdapter
    private lateinit var touchHelper: ItemTouchHelper

    // Kept as a field: SharedPreferences only holds a weak reference to it.
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> renderStatus() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        btnLocation = findViewById(R.id.btn_location)
        btnBackground = findViewById(R.id.btn_background)
        locCount = findViewById(R.id.loc_count)
        search = findViewById(R.id.search)
        searchStatus = findViewById(R.id.search_status)
        searchResults = findViewById(R.id.search_results)

        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }

        // Current location on/off
        findViewById<Switch>(R.id.switch_current).apply {
            isChecked = Places.useCurrent(this@MainActivity)
            setOnCheckedChangeListener { _, on ->
                Places.setUseCurrent(this@MainActivity, on)
                placesChanged(refetch = on)
            }
        }

        setupSavedList()
        setupSearch()
        setupInterval()

        btnLocation.setOnClickListener {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION), REQ_LOCATION)
        }
        btnBackground.setOnClickListener {
            if (Build.VERSION.SDK_INT >= 29) {
                requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), REQ_BACKGROUND)
            }
        }
        findViewById<Button>(R.id.btn_refresh).setOnClickListener { refresh() }
        findViewById<Button>(R.id.btn_battery).setOnClickListener {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }

        WeatherWorker.schedulePeriodic(this)
    }

    override fun onResume() {
        super.onResume()
        Store.prefs(this).registerOnSharedPreferenceChangeListener(prefsListener)
        renderStatus()
    }

    override fun onPause() {
        Store.prefs(this).unregisterOnSharedPreferenceChangeListener(prefsListener)
        super.onPause()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // --- saved places list ---

    private fun setupSavedList() {
        adapter = PlacesAdapter(
            items = Places.saved(this).toMutableList(),
            onRemove = { place ->
                Places.remove(this, place.id)
                adapter.removeItem(place)
                placesChanged(refetch = false)
            },
            onStartDrag = { holder -> touchHelper.startDrag(holder) },
        )
        touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0,
        ) {
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                adapter.move(vh.bindingAdapterPosition, target.bindingAdapterPosition)
                return true
            }

            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) = Unit

            override fun isLongPressDragEnabled() = false // drag starts from the ≡ handle

            override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
                super.clearView(rv, vh)
                Places.setSaved(this@MainActivity, adapter.items) // save the new order
                placesChanged(refetch = false)
            }
        })
        findViewById<RecyclerView>(R.id.saved_list).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
            touchHelper.attachToRecyclerView(this)
        }
        renderCount()
    }

    private fun renderCount() {
        locCount.text = "${adapter.items.size} of ${Places.MAX_SAVED}"
    }

    /** Places were added/removed/reordered: update widgets, optionally fetch fresh data. */
    private fun placesChanged(refetch: Boolean) {
        renderCount()
        if (refetch) WeatherWorker.refreshNow(this)
        WeatherWidgetProvider.updateAll(this)
    }

    // --- refresh interval ---

    private val intervalButtons = mapOf(
        10 to R.id.interval_10, 15 to R.id.interval_15, 30 to R.id.interval_30,
        60 to R.id.interval_60, 90 to R.id.interval_90,
    )

    private fun setupInterval() {
        val group = findViewById<RadioGroup>(R.id.interval_group)
        intervalButtons[Store.refreshMinutes(this)]?.let { group.check(it) }
        renderIntervalNote()
        group.setOnCheckedChangeListener { _, checkedId ->
            val minutes = intervalButtons.entries.firstOrNull { it.value == checkedId }?.key
                ?: return@setOnCheckedChangeListener
            if (minutes == Store.refreshMinutes(this)) return@setOnCheckedChangeListener
            Store.setRefreshMinutes(this, minutes)
            WeatherWorker.schedulePeriodic(this, changed = true)
            renderIntervalNote()
        }
    }

    private fun renderIntervalNote() {
        findViewById<TextView>(R.id.interval_note).text =
            if (Store.refreshMinutes(this) < 15) {
                "Uses a little more battery. When the phone is idle with the screen off, " +
                    "Android may stretch it to about 15 min."
            } else {
                "Refreshes in the background, also when the app is closed."
            }
    }

    // --- place search ---

    private fun setupSearch() {
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = onQueryChanged(s?.toString().orEmpty())
        })
    }

    private fun onQueryChanged(query: String) {
        searchJob?.cancel()
        val q = query.trim()
        if (q.length < 2) {
            showResults(emptyList())
            searchStatus.visibility = View.GONE
            return
        }
        searchJob = scope.launch {
            delay(400) // wait until typing pauses
            searchStatus.visibility = View.VISIBLE
            searchStatus.text = "Searching…"
            val result = withContext(Dispatchers.IO) { runCatching { WeatherApi.searchPlaces(q) } }
            result.onSuccess { list ->
                showResults(list)
                if (list.isEmpty()) searchStatus.text = "No places found" else searchStatus.visibility = View.GONE
            }.onFailure {
                showResults(emptyList())
                searchStatus.text = "Search failed – check your connection"
            }
        }
    }

    private fun showResults(list: List<Place>) {
        searchResults.removeAllViews()
        searchResults.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
        list.forEachIndexed { i, place ->
            if (i > 0) searchResults.addView(View(this).apply {
                setBackgroundColor(0x14808080)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
            })
            searchResults.addView(resultRow(place))
        }
    }

    private fun resultRow(place: Place): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), dp(10), dp(14), dp(10))
        isClickable = true
        setBackgroundResource(TypedValue().also {
            theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true)
        }.resourceId)
        setOnClickListener { addPlace(place) }

        addView(TextView(context).apply {
            text = place.name
            setTypeface(typeface, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        })
        addView(TextView(context).apply {
            text = place.subtitle
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            alpha = 0.7f
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = dp(8) }
        })
        addView(TextView(context).apply {
            text = "＋"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTextColor(0xFF3D6FB6.toInt())
        })
    }

    private fun addPlace(place: Place) {
        when {
            adapter.items.size >= Places.MAX_SAVED ->
                toast("You can save up to ${Places.MAX_SAVED} places")
            adapter.items.any { it.id == place.id } ->
                toast("${place.name} is already in your list")
            Places.add(this, place) -> {
                adapter.addItem(place)
                search.setText("")
                (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .hideSoftInputFromWindow(search.windowToken, 0)
                toast("Added ${place.name}")
                placesChanged(refetch = true)
            }
        }
    }

    // --- permissions & status ---

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        val granted = results.isNotEmpty() && results[0] == PackageManager.PERMISSION_GRANTED
        when {
            requestCode == REQ_LOCATION && granted -> refresh()
            // Android may refuse to show the dialog again; send the user to the app's settings.
            requestCode == REQ_BACKGROUND && !granted -> openAppSettings()
        }
        renderStatus()
    }

    private fun refresh() {
        WeatherWorker.refreshNow(this)
        WeatherWidgetProvider.updateAll(this)
    }

    private fun openAppSettings() {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)),
        )
    }

    private fun renderStatus() {
        val hasLoc = LocationHelper.hasLocation(this)
        val hasBg = LocationHelper.hasBackground(this)
        btnLocation.visibility = if (hasLoc) View.GONE else View.VISIBLE
        btnBackground.visibility = if (hasLoc && !hasBg) View.VISIBLE else View.GONE

        Store.currentPlaceName(this)?.let {
            findViewById<TextView>(R.id.cur_sub).text = "Now: $it · always first"
        }

        val snap = Store.snapshot(this)
        status.text = buildString {
            append("Location: ")
            append(
                when {
                    !hasLoc -> "not allowed – the app can't follow you"
                    !hasBg -> "only while the app is open"
                    else -> "allowed all the time ✓"
                },
            )
            append("\nLast update: ")
            append(
                if (snap.updatedMillis > 0) {
                    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(snap.updatedMillis))
                } else "never",
            )
            if (snap.updating) append(" (updating…)")
            snap.error?.let { append("\nLast problem: ").append(it) }
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()

    companion object {
        private const val REQ_LOCATION = 1
        private const val REQ_BACKGROUND = 2
    }
}
