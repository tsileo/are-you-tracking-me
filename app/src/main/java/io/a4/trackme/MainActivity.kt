package io.a4.trackme

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.content.res.Resources
import android.database.sqlite.SQLiteDatabase
import android.location.Location
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkInfo
import android.support.v4.content.ContextCompat
import android.util.Log
import android.support.v7.app.AppCompatActivity
import android.support.v4.app.ActivityCompat
import android.os.Bundle
import android.os.Handler
import android.support.v7.app.NotificationCompat
import com.github.kittinunf.fuel.Fuel
import com.github.kittinunf.result.Result
import org.jetbrains.anko.*
import org.json.JSONObject
import org.jetbrains.anko.appcompat.v7.tintedEditText
import org.jetbrains.anko.design.textInputLayout
import android.provider.Settings
import android.support.v4.content.LocalBroadcastManager
import android.support.v4.widget.SwipeRefreshLayout
import android.support.v7.widget.SwitchCompat
import android.text.InputType
import android.text.format.DateFormat
import android.view.Gravity
import android.view.ViewManager
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import com.github.kittinunf.fuel.core.FuelManager
import org.jetbrains.anko.appcompat.v7.tintedButton
import org.jetbrains.anko.appcompat.v7.tintedTextView
import org.jetbrains.anko.custom.ankoView
import org.jetbrains.anko.db.*
import org.jetbrains.anko.support.v4.swipeRefreshLayout
import java.util.concurrent.atomic.AtomicInteger


val PREFS_FILENAME = "io.a4.trackme.prefs"


inline fun ViewManager.switchCompat(theme: Int = 0) = switchCompat(theme) {}

inline fun ViewManager.switchCompat(theme: Int = 0, init: SwitchCompat.() -> Unit): SwitchCompat {
    return ankoView({ SwitchCompat(it) }, theme, init)
}

class App : Application() {
    companion object {
        lateinit var instance: App
            private  set

        lateinit var cm: ConnectivityManager
            private  set

        lateinit var prefs: SharedPreferences
            private set

        lateinit var lm: LocationManager
            private set

        lateinit var deviceId: String
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        cm = this.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        lm = this.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        prefs = this.getSharedPreferences(PREFS_FILENAME, Context.MODE_PRIVATE)
        deviceId = Settings.Secure.getString(this.contentResolver, Settings.Secure.ANDROID_ID)
    }
}

class DbHelper : ManagedSQLiteOpenHelper(App.instance, "locations.db", null, 5) {

    companion object {
        val instance by lazy { DbHelper() }
    }

    override fun onCreate(db: SQLiteDatabase?) {
        db?.createTable("locations", true,
                "_id" to INTEGER + PRIMARY_KEY,
                "lat" to REAL,
                "lng" to REAL,
                "ts" to INTEGER
        )
        db?.createTable("logs", true,
                "_id" to INTEGER + PRIMARY_KEY,
                "date" to TEXT,
                "status_code" to INTEGER,
                "response" to TEXT
        )
    }


    override fun onUpgrade(db: SQLiteDatabase?, oldVersion: Int, newVersion: Int) {
        db?.dropTable("locations", true)
        db?.dropTable("logs", true)
        onCreate(db)
    }
}
// Access property for Context
val Context.database: DbHelper
    get() = DbHelper.instance

class Loc(val _id: Int, val ts: Int, val lat: Float, val lng: Float)
val locParser = classParser<Loc>()
class ReqLog(val _id: Int, val date: String, val status_code: Int, val response: String)
val logParser = classParser<ReqLog>()

class MainActivity : AppCompatActivity() {

    var logsView: TextView? = null

    // Custom method to determine whether a service is running
    fun isServiceRunning(serviceClass: Class<*>): Boolean {
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

        // Loop through the running services
        for (service in activityManager.getRunningServices(Integer.MAX_VALUE)) {
            if (serviceClass.name == service.service.className) {
                // If the service is running then return true
                return true
            }
        }
        return false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        var intent = Intent(getBaseContext(), LocationTrackingService::class.java)
        var RUNNING = isServiceRunning(LocationTrackingService::class.java)

        var endpoint: EditText? = null
        var user: EditText? = null
        var pass: EditText? = null
        var switchLabel = "Stopped"
        if (RUNNING) {
            switchLabel = "Running"
        }

        swipeRefreshLayout {
            setOnRefreshListener {
                reloadLogs()
                this.isRefreshing = false
                toast("logs reloaded successfully")
            }
            scrollView {
                verticalLayout {
                    // Prevent the initial input focus
                    isFocusableInTouchMode = true
                    padding = dip(16)

                    // Status section
                    tintedTextView {
                        textSize = 18f
                        text = "Status"
                        bottomPadding = dip(12)
                    }
                    switchCompat {
                        text = switchLabel
                        textSize = 18f
                        isChecked = RUNNING
                        setOnCheckedChangeListener { buttonView, isChecked ->
                            if (isChecked) {
                                startService(intent)
                                this.text = "Running"
                                toast("Service started")
                            } else {
                                stopService(intent)
                                this.text = "Stopped"
                                toast("Service stopped")
                            }
                        }
                        bottomPadding = dip(24)
                    }

                    // Logs section
                    tintedTextView {
                        textSize = 18f
                        text = "Logs"
                        bottomPadding = dip(12)
                    }
                    logsView = tintedTextView {
                        text = ""
                    }

                    // Settings section
                    tintedTextView {
                        textSize = 18f
                        text = "Settings"
                        bottomPadding = dip(24)
                    }
                    textInputLayout {
                        endpoint = tintedEditText {
                            hint = "HTTP Endpoint URL"
                            singleLine = true
                        }
                    }.lparams(width = matchParent, height = wrapContent)
                    textInputLayout {
                        user = tintedEditText {
                            hint = "Username"
                            singleLine = true
                        }
                    }.lparams(width = matchParent, height = wrapContent)
                    textInputLayout {
                        pass = tintedEditText {
                            hint = "Password"
                            singleLine = true
                            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                        }
                    }.lparams(width = matchParent, height = wrapContent)

                    endpoint!!.setText(App.prefs!!.getString("endpoint", ""))
                    user!!.setText(App.prefs!!.getString("user", ""))
                    pass!!.setText(App.prefs!!.getString("pass", ""))
                    tintedButton("Save") {
                        setOnClickListener {
                            val editor = App.prefs!!.edit()
                            editor.putString("endpoint", endpoint!!.text.toString())
                            editor.putString("user", user!!.text.toString())
                            editor.putString("pass", pass!!.text.toString())
                            editor.apply()
                            longToast("Settings saved")
                        }
                    }
                }
            }
        }

        // Ensure the ACCESS_FINE_LOCATION permission is granted
        if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this@MainActivity, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 123)
        }
    }

    private fun reloadLogs() {
        database.use {
            var out: String = ""
            val logs = select("logs", "_id", "date", "status_code", "response").parseList(logParser)
            for (log in logs) {
                out = out + "${log.date}: ${log.status_code} => ${log.response}\n"
            }
            // TODO remov oldest logs and only keep last 5
            logsView!!.setText(out)
        }
    }

    override fun onResume() {
        super.onResume()
        reloadLogs()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (permissions.size == 0) return
    }
}

private object notificationID {
    private val c = AtomicInteger(0)
    val id: Int
        get() = c.incrementAndGet()
}


class LocationTrackingService    : Service() {
    var notifID: Int = 0

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "start called")
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    override fun onCreate() {
        try {
            var listener = locationListeners[0]
            locationManager?.requestLocationUpdates(LocationManager.GPS_PROVIDER, INTERVAL, DISTANCE, listener)
        } catch (e: SecurityException) {
            Log.e(TAG, "Fail to request location update", e)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "GPS provider does not exist", e)
        }

        FuelManager.instance.baseHeaders = mapOf(
                "User-Agent" to "Are You Tracking Me? - ${BuildConfig.VERSION_NAME}")

        var notif = NotificationCompat.Builder(this@LocationTrackingService)
                .setContentTitle("Yes, I'm tracking you!")
                .setSubText("Running")
                .setOngoing(true)
                .setVisibility(NotificationCompat.VISIBILITY_SECRET)
                .setSmallIcon(R.mipmap.ic_launcher)
                .build()

        val mNotifyMgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // Builds the notification and issues it.
        notifID = notificationID.id
        mNotifyMgr.notify(notifID, notif)
    }

    override fun onDestroy() {
        super.onDestroy()
        val mNotifyMgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        mNotifyMgr.cancel(notifID)
        if (locationManager != null)
            for (i in 0..locationListeners.size) {
                try {
                    locationManager?.removeUpdates(locationListeners[i])
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to remove location listeners")
                }
            }
    }


    companion object {
        val TAG = "LocationTrackingService"
        //val INTERVAL = 600000.toLong()
        val INTERVAL = 5000.toLong()
        val DISTANCE = 0.toFloat() // In meters

        //var lol = applicationContext.getSharedPreferences(PREFS_FILENAME, 0)
        val locationListeners = arrayOf(
                LTRLocationListener(LocationManager.GPS_PROVIDER)
                //LTRLocationListener(LocationManager.NETWORK_PROVIDER)
        )

        class LTRLocationListener(provider: String) : android.location.LocationListener {

            val lastLocation = Location(provider)

            override fun onLocationChanged(location: Location?) {
                lastLocation.set(location)
                val ts = System.currentTimeMillis()
                var payload = JSONObject()
                payload.put("lat", location!!.latitude)
                payload.put("lng", location!!.longitude)
                payload.put("ts", ts)
                payload.put("device_id", App.deviceId)

                Log.i(TAG, "Sending payload to server")

                // Fetch creds from settings
                val endpoint = App.prefs!!.getString("endpoint", "")
                val user = App.prefs!!.getString("user", "")
                val pass = App.prefs!!.getString("pass", "")

                var activeNetwork: NetworkInfo  = App.cm.getActiveNetworkInfo()
                if (activeNetwork != null) {
                    if (activeNetwork.isConnectedOrConnecting()) {
                        // TODO use this, try to POST data, and POST previous offline saved data
                        // TODO ge0 bulk support?
                    }
                }
                App.instance.applicationContext.database.use {
                    val values = ContentValues()
                    values.put("lat", location!!.latitude)
                    values.put("lng", location!!.longitude)
                    values.put("ts", ts)
                    values.put("_id", ts)
                    insert("locations", null, values)
                }

                doAsync {
                    val date = DateFormat.format("yyyy-MM-ddThh:mm:ss a", java.util.Date()).toString()
                    Fuel.post(endpoint).authenticate(user, pass).body(payload.toString()).response { _, response, result ->
                        App.instance.applicationContext.database.use {
                            val values = ContentValues()
                            values.put("status_code", response.httpStatusCode)
                            values.put("response", String(response.data))
                            values.put("_id", ts)
                            values.put("date", date)
                            insert("logs", null, values)
                        }
                        when (result) {
                            is Result.Failure -> {
                                val body = String(response.data)
                                val code = response.httpStatusCode
                                Log.e(TAG, "failed to send payload $code: $body")

                            }
                            is Result.Success -> {
                                Log.i(TAG, "payload sent")
                            }
                        }
                    }
                }
            }
            override fun onProviderDisabled(provider: String?) {
            }

            override fun onProviderEnabled(provider: String?) {
            }

            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
            }

        }
    }

}