package io.a4.trackme

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Resources
import android.location.Location
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkInfo
import android.support.v4.content.ContextCompat
import android.util.Log
import android.support.v7.app.AppCompatActivity
import android.support.v4.app.ActivityCompat
import android.os.Bundle
import android.support.v7.app.NotificationCompat
import com.github.kittinunf.fuel.Fuel
import com.github.kittinunf.result.Result
import org.jetbrains.anko.*
import org.json.JSONObject
import org.jetbrains.anko.appcompat.v7.tintedEditText
import org.jetbrains.anko.design.textInputLayout
import android.provider.Settings
import android.support.v7.widget.SwitchCompat
import android.text.InputType
import android.text.format.DateFormat
import android.view.Gravity
import android.view.ViewManager
import android.widget.EditText
import android.widget.TextView
import org.jetbrains.anko.appcompat.v7.tintedButton
import org.jetbrains.anko.appcompat.v7.tintedTextView
import org.jetbrains.anko.custom.ankoView
import java.util.concurrent.atomic.AtomicInteger


val PREFS_FILENAME = "io.a4.trackme.prefs"

inline fun ViewManager.switchCompat(theme: Int = 0) = switch(theme) {}

inline fun ViewManager.switchCompat(theme: Int = 0, init: SwitchCompat.() -> Unit): SwitchCompat {
    return ankoView({ SwitchCompat(it) }, theme, init)
}


class MainActivity : AppCompatActivity() {

    var prefs: SharedPreferences? = null

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
        prefs = this.getSharedPreferences(PREFS_FILENAME, 0)
        var intent = Intent(getBaseContext(), LocationTrackingService::class.java)
        var servicerunning = isServiceRunning(LocationTrackingService::class.java)

        var endpoint: EditText? = null
        var user: EditText? = null
        var pass: EditText? = null
        var lastReq: TextView? = null
        var lastErr: TextView? = null
        var switchLabel = "Stopped"
        if (servicerunning) {
            switchLabel = "Running"
        }
        verticalLayout {
            padding = dip(16)
            tintedTextView {
                textSize = 18f
                text = "Status"
                //gravity = Gravity.CENTER
                bottomPadding = dip(12)
            }
            switchCompat {
                text = switchLabel
                textSize = 18f
                isChecked = servicerunning
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
            lastReq = tintedTextView{
                text = ""
            }
            lastErr = tintedTextView{
                text = ""
                bottomPadding = dip(24)
            }
            tintedTextView {
                textSize = 18f
                text = "Settings"
                //gravity = Gravity.CENTER
                bottomPadding = dip(24)
            }
            textInputLayout {
                endpoint = tintedEditText {
                    hint= "HTTP Endpoint URL"
                    singleLine = true
                }
            }.lparams(width = matchParent, height = wrapContent)
            textInputLayout {
                user = tintedEditText {
                    hint= "Username"
                    singleLine = true
                }
            }.lparams(width = matchParent, height = wrapContent)
            textInputLayout {
                pass = tintedEditText {
                    hint= "Password"
                    singleLine = true
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                }
            }.lparams(width = matchParent, height = wrapContent)


            endpoint!!.setText(prefs!!.getString("endpoint", ""))
            user!!.setText(prefs!!.getString("user", ""))
            pass!!.setText(prefs!!.getString("pass", ""))
            lastReq!!.setText("Last request: ${prefs!!.getString("last_request", "never")}")
            lastErr!!.setText("Last error: ${prefs!!.getString("last_error", "never")}")
            tintedButton("Save") {
                onClick {
                    val editor = prefs!!.edit()
                    editor.putString("endpoint", endpoint!!.text.toString())
                    editor.putString("user", user!!.text.toString())
                    editor.putString("pass", pass!!.text.toString())
                    editor.apply()
                    longToast("Settings saved")
                }
            }
        }

        if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this@MainActivity, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 123)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (permissions.size == 0) return
    }
}

//if (requestCode == 123) {
//        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            // Permission granted.
            //doLocationAccessRelatedJob();
        //}
        //else {
        //    // User refused to grant permission. You can add AlertDialog here
        //    Toast.makeText(this, "You didn't give permission to access device location", Toast.LENGTH_LONG).show();
        //    startInstalledAppDetailsActivity();
        //}

object NotificationID {
    private val c = AtomicInteger(0)
    val id: Int
        get() = c.incrementAndGet()
}



class LocationTrackingService    : Service() {
    var locationManager: LocationManager? = null
    var notifID: Int = 0
    var prefs: SharedPreferences? = null


    //: Notification? = null

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "start called")
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    override fun onCreate() {
        this.prefs = this.getSharedPreferences(PREFS_FILENAME, 0)
        if (locationManager == null)
            locationManager = applicationContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        try {
            var listener = locationListeners[0]
            listener.prefs = this.prefs
            listener.deviceID = Settings.Secure.getString(applicationContext.getContentResolver(), Settings.Secure.ANDROID_ID)
            locationManager?.requestLocationUpdates(LocationManager.GPS_PROVIDER, INTERVAL, DISTANCE, listener)
        } catch (e: SecurityException) {
            Log.e(TAG, "Fail to request location update", e)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "GPS provider does not exist", e)
        }
        Log.i(TAG, "look good")

        var notif = NotificationCompat.Builder(this@LocationTrackingService)
                .setContentTitle("Yes, I'm tracking you!")
                .setSubText("Running")
                .setOngoing(true)
                .setSmallIcon(R.mipmap.ic_launcher)
                .build()
        val mNotifyMgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
// Builds the notification and issues it.
        notifID = NotificationID.id
        mNotifyMgr.notify(notifID, notif)
        RUNNING = true
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

        RUNNING = false
    }


    companion object {
        val TAG = "LocationTrackingService"
        var RUNNING = false
        val INTERVAL = 600000.toLong()
        val DISTANCE = 0.toFloat() // In meters

        //var lol = applicationContext.getSharedPreferences(PREFS_FILENAME, 0)
        val locationListeners = arrayOf(
                LTRLocationListener(LocationManager.GPS_PROVIDER)
                //LTRLocationListener(LocationManager.NETWORK_PROVIDER)
        )

        class LTRLocationListener(provider: String) : android.location.LocationListener {

            val lastLocation = Location(provider)
            var prefs: SharedPreferences? = null
            var deviceID: String? = null

            override fun onLocationChanged(location: Location?) {
                lastLocation.set(location)
                var payload = JSONObject()
                payload.put("lat", location!!.latitude)
                payload.put("lng", location!!.longitude)
                payload.put("ts", System.currentTimeMillis())
                payload.put("device_id", this.deviceID!!)

                Log.i(TAG, "Sending payload to server")

                val endpoint = prefs!!.getString("endpoint", "")
                val user = prefs!!.getString("user", "")
                val pass = prefs!!.getString("pass", "")
                val editor = prefs!!.edit()

                // TODO get the connectivitymanager the same way as the android id
                //var cm: ConnectivityManager = .getSystemService(Context.CONNECTIVITY_SERVICE)
                //var activeNetwork: NetworkInfo  = cm.getActiveNetworkInfo()
                //val isConnected = activeNetwork != null and activeNetwork.isConnectedOrConnecting()



                val date = DateFormat.format("yyyy-MM-ddThh:mm:ss a", java.util.Date()).toString()
                editor.putString("last_request", date)
                Fuel.post(endpoint).authenticate(user, pass).body(payload.toString()).response { _, response, result ->
                    when (result) {
                        is Result.Failure -> {
                            val body = response.data.toString()
                            val code = response.httpStatusCode
                            Log.e(TAG, "failed to send payload $code: $body")
                            editor.putString("last_error", "$date, error $code: $body")
                            editor.apply()
                        }
                        is Result.Success -> {
                            Log.i(TAG, "payload sent")
                            editor.apply()
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