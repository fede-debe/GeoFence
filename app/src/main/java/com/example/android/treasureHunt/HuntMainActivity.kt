/*
 * Copyright (C) 2019 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.treasureHunt

import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.Manifest
import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.content.IntentSender
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.databinding.DataBindingUtil
import androidx.lifecycle.SavedStateViewModelFactory
import androidx.lifecycle.ViewModelProvider
import com.example.android.treasureHunt.databinding.ActivityHuntMainBinding
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationSettingsRequest
import com.google.android.material.snackbar.Snackbar

/**
 * The Treasure Hunt app is a single-player game based on geofences.
 *
 * This app demonstrates how to create and remove geofences using the GeofencingApi. Uses an
 * BroadcastReceiver to monitor geofence transitions and creates notification and finishes the game
 * when the user enters the final geofence (destination).
 *
 * This app requires a device's Location settings to be turned on. It also requires
 * the ACCESS_FINE_LOCATION permission and user consent. For geofences to work
 * in Android Q, app also needs the ACCESS_BACKGROUND_LOCATION permission and user consent.
 */

class HuntMainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHuntMainBinding
    private lateinit var geofencingClient: GeofencingClient
    private lateinit var viewModel: GeofenceViewModel

    // add in variable to check if device is running Q or later
    private val runningQOrLater = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q

    // add in a pending intent to handle the geofence transition
    private val geofencePendingIntent: PendingIntent by lazy {
        // The broadcast receiver is how Android apps can send or receive broadcast messages from the Android system and other Android apps
        val intent = Intent(this, GeofenceBroadcastReceiver::class.java)
        intent.action = ACTION_GEOFENCE_EVENT
        PendingIntent.getBroadcast(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = DataBindingUtil.setContentView(this, R.layout.activity_hunt_main)
        viewModel = ViewModelProvider(this, SavedStateViewModelFactory(this.application,
            this)).get(GeofenceViewModel::class.java)
        binding.viewmodel = viewModel
        binding.lifecycleOwner = this
        // instantiate the geofencing client -> the main entry point for interacting with geofencing APIs.
        geofencingClient = LocationServices.getGeofencingClient(this)

        // Create channel for notifications
        createChannel(this )
    }

    override fun onStart() {
        super.onStart()
        checkPermissionsAndStartGeofencing()
    }

    /*
 *  When we get the result from asking the user to turn on device location, we call
 *  checkDeviceLocationSettingsAndStartGeofence again to make sure it's actually on, but
 *  we don't resolve the check to keep the user from seeing an endless loop.
 */
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        // TODO: Step 7 add code to check that the user turned on their device location and ask
        //  again if they did not
        if (requestCode == REQUEST_TURN_DEVICE_LOCATION_ON) {
            checkDeviceLocationSettingsAndStartGeofence(false)
        }
    }

    /*
     *  When the user clicks on the notification, this method will be called, letting us know that
     *  the geofence has been triggered, and it's time to move to the next one in the treasure
     *  hunt.
     */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        val extras = intent?.extras
        if(extras != null){
            if(extras.containsKey(GeofencingConstants.EXTRA_GEOFENCE_INDEX)){
                viewModel.updateHint(extras.getInt(GeofencingConstants.EXTRA_GEOFENCE_INDEX))
                checkPermissionsAndStartGeofencing()
            }
        }
    }

    /*
     * In all cases, we need to have the location permission.  On Android 10+ (Q) we need to have
     * the background permission as well.
     */
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        // TODO: Step 5 add code to handle the result of the user's permission
        Log.d(TAG, "onRequestPermissionResult")

        if (
            grantResults.isEmpty() ||
            grantResults[LOCATION_PERMISSION_INDEX] == PackageManager.PERMISSION_DENIED ||
            (requestCode == REQUEST_FOREGROUND_AND_BACKGROUND_PERMISSION_RESULT_CODE &&
                    grantResults[BACKGROUND_LOCATION_PERMISSION_INDEX] ==
                    PackageManager.PERMISSION_DENIED))
        {
            Snackbar.make(
                binding.activityMapsMain,
                R.string.permission_denied_explanation,
                Snackbar.LENGTH_INDEFINITE
            )
                .setAction(R.string.settings) {
                    startActivity(Intent().apply {
                        action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                        data = Uri.fromParts("package", BuildConfig.APPLICATION_ID, null)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    })
                }.show()
        } else {
            checkDeviceLocationSettingsAndStartGeofence()
        }

    }

    /**
     * This will also destroy any saved state in the associated ViewModel, so we remove the
     * geofences here.
     */
    override fun onDestroy() {
        super.onDestroy()
        removeGeoFences()
    }

    /**
     * Starts the permission check and Geofence process only if the Geofence associated with the
     * current hint isn't yet active.
     */
    private fun checkPermissionsAndStartGeofencing() {
        if (viewModel.geofenceIsActive()) return
        if (foregroundAndBackgroundLocationPermissionApproved()) {
            checkDeviceLocationSettingsAndStartGeofence()
        } else {
            requestForegroundAndBackgroundLocationPermissions()
        }
    }

    /*
     *  Uses the Location Client to check the current state of location settings, and gives the user
     *  the opportunity to turn on location services within our app.
     */
    private fun checkDeviceLocationSettingsAndStartGeofence(resolve:Boolean = true) {
        // TODO: Step 6 add code to check that the device's location is on
        // create a LocationRequest, a LocationSettingsRequest Builder
        val locationRequest = LocationRequest.create().apply {
            priority = LocationRequest.PRIORITY_LOW_POWER
        }
        val builder = LocationSettingsRequest.Builder().addLocationRequest(locationRequest)
        // use LocationServices to get the Settings Client and create a val called locationSettingsResponseTask to check the location settings.
        val settingsClient = LocationServices.getSettingsClient(this)
        val locationSettingsResponseTask =
            settingsClient.checkLocationSettings(builder.build())
        // Since the case we are most interested in here is finding out if the location settings are not satisfied, add an onFailureListener() to the locationSettingsResponseTask
        locationSettingsResponseTask.addOnFailureListener { exception ->
            // Check if the exception is of type ResolvableApiException and if so, try calling the startResolutionForResult() method in order to prompt the user to turn on device location.
            if (exception is ResolvableApiException && resolve){
                try {
                    exception.startResolutionForResult(this@HuntMainActivity,
                        REQUEST_TURN_DEVICE_LOCATION_ON)
                } // If calling startResolutionForResult enters the catch block, print a log
                catch (sendEx: IntentSender.SendIntentException) {
                    Log.d(TAG, "Error getting location settings resolution: " + sendEx.message)
                }
            } // If the exception is not of type ResolvableApiException, present a snackbar that alerts the user that location needs to be enabled to play the treasure hunt.
            else {
                Snackbar.make(
                    binding.activityMapsMain,
                    R.string.location_required_error, Snackbar.LENGTH_INDEFINITE
                ).setAction(android.R.string.ok) {
                    checkDeviceLocationSettingsAndStartGeofence()
                }.show()
            }
        }
        // If the locationSettingsResponseTask does complete, check that it is successful, if so you will want to add the geofence.
        locationSettingsResponseTask.addOnCompleteListener {
            if ( it.isSuccessful ) {
                addGeofenceForClue()
            }
        }
    }

    /*
     *  Determines whether the app has the appropriate permissions across Android 10+ and all other
     *  Android versions.
     */
    @TargetApi(29)
    private fun foregroundAndBackgroundLocationPermissionApproved(): Boolean {
        // TODO: Step 3 replace this with code to check that the foreground and background
        //  permissions were approved
        // check if the ACCESS_FINE_LOCATION permission is granted.
        val foregroundLocationApproved = (
                PackageManager.PERMISSION_GRANTED ==
                        ActivityCompat.checkSelfPermission(this,
                            Manifest.permission.ACCESS_FINE_LOCATION))
        // If the device is running Q or higher, check that the ACCESS_BACKGROUND_LOCATION permission is granted.
        // Return true if the device is running lower than Q where you don't need a permission to access location in the background.
        val backgroundPermissionApproved =
            if (runningQOrLater) {
                PackageManager.PERMISSION_GRANTED ==
                        ActivityCompat.checkSelfPermission(
                            this, Manifest.permission.ACCESS_BACKGROUND_LOCATION
                        )
            } else {
                true
            }
        // *Return true if the permissions are granted and false if not.
        return foregroundLocationApproved && backgroundPermissionApproved
    }

    /*
     *  Requests ACCESS_FINE_LOCATION and (on Android 10+ (Q) ACCESS_BACKGROUND_LOCATION.
     */
    @TargetApi(29 )
    private fun requestForegroundAndBackgroundLocationPermissions() {
        // TODO: Step 4 add code to request foreground and background permissions
        if (foregroundAndBackgroundLocationPermissionApproved())
            // If the permissions have already been approved, you don’t need to ask again. Return out of the method
            return
        // The permissionsArray contains the permissions that are going to be requested. Initially, add ACCESS_FINE_LOCATION since that will be needed on all API levels
        var permissionsArray = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        /** you will need a resultCode. The code will be different depending on if the device is running Q or later and will
         * inform us if you need to check for one permission (fine location) or multiple permissions (fine and background
         * location) when the user returns from the permission request screen. Add a when statement to check the version running
         * and assign result code to REQUEST_FOREGROUND_AND_BACKGROUND_PERMISSION_RESULT_CODE if the device is running Q or
         * later and REQUEST_FOREGROUND_ONLY_PERMISSIONS_REQUEST_CODE if not.*/
        val resultCode = when {
            runningQOrLater -> {
                permissionsArray += Manifest.permission.ACCESS_BACKGROUND_LOCATION
                REQUEST_FOREGROUND_AND_BACKGROUND_PERMISSION_RESULT_CODE
            }
            else -> REQUEST_FOREGROUND_ONLY_PERMISSIONS_REQUEST_CODE
        }
        Log.d(TAG, "Request foreground only location permission")
        // Request permissions passing in the current activity, the permissions array and the result code
        ActivityCompat.requestPermissions(
            this@HuntMainActivity,
            permissionsArray,
            resultCode
        )
    }

    /*
     * Adds a Geofence for the current clue if needed, and removes any existing Geofence. This
     * method should be called after the user has granted the location permission.  If there are
     * no more geoFences, we remove the geofence and let the viewModel know that the ending hint
     * is now "active."
     */
    @SuppressLint("MissingPermission")
    private fun addGeofenceForClue() {
        // TODO: Step 10 add in code to add the geofence
        // if there's already an active geoFences, you shouldn't add another.
        if (viewModel.geofenceIsActive()) return
        // find out the current geoFence by index - If the index is higher than the number of landmarks we have, it means the user has found all the treasures.
        // Remove geoFences, call geofenceActivated on the viewModel, then return.
        val currentGeofenceIndex = viewModel.nextGeofenceIndex()
        if(currentGeofenceIndex >= GeofencingConstants.NUM_LANDMARKS) {
            removeGeoFences()
            viewModel.geofenceActivated()
            return
        }
        // Once you have the index and know it is valid, get the data surrounding the geofence.
        val currentGeofenceData = GeofencingConstants.LANDMARK_DATA[currentGeofenceIndex]
        // Build the geofence using the geofence builder, the information in currentGeofenceData, like the id and the latitude and longitude. Set the expiration duration using the constant set in GeofencingConstants.
        val geofence = Geofence.Builder()
            .setRequestId(currentGeofenceData.id)
            .setCircularRegion(currentGeofenceData.latLong.latitude,
                currentGeofenceData.latLong.longitude,
                GeofencingConstants.GEOFENCE_RADIUS_IN_METERS
            )
            // Set the transition type to GEOFENCE_TRANSITION_ENTER. Finally, build the geofence.
            .setExpirationDuration(GeofencingConstants.GEOFENCE_EXPIRATION_IN_MILLISECONDS)
            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER)
            .build()
        // Build the geofence request. Set the initial trigger to INITIAL_TRIGGER_ENTER, add the geofence you just built and then build.
        val geofencingRequest = GeofencingRequest.Builder()
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
            .addGeofence(geofence)
            .build()
        // Call removeGeoFences() on the geofencingClient to remove any geoFences already associated to the pending intent
        geofencingClient.removeGeofences(geofencePendingIntent)?.run {
            addOnCompleteListener {
                geofencingClient.addGeofences(geofencingRequest, geofencePendingIntent)?.run {
                    // If adding the geoFences is successful, let the user know through a toast that they were successful.
                    addOnSuccessListener {
                        Toast.makeText(this@HuntMainActivity, R.string.geofences_added,
                            Toast.LENGTH_SHORT)
                            .show()
                        Log.e("Add Geofence", geofence.requestId)
                        viewModel.geofenceActivated()
                    }
                    // If adding the geoFences fails, present a toast letting the user know that there was an issue in adding the geoFences.
                    addOnFailureListener {
                        Toast.makeText(this@HuntMainActivity, R.string.geofences_not_added,
                            Toast.LENGTH_SHORT).show()
                        if ((it.message != null)) {
                            Log.w(TAG, it.message!!)
                        }
                    }
                }
            }
        }

    }

    /**
     * Removes geoFences. This method should be called after the user has granted the location
     * permission. When you no longer need geoFences, it is the best practice to remove them in
     * order to save battery and CPU cycles to stop monitoring. This method is called into the
     * onDestroy method of the app
     */
    private fun removeGeoFences() {
     // TODO: Step 12 add in code to remove the geoFences
        // Initially, check if foreground permissions have been approved, if they have not then return because if they have not means that there isn't a GeoFence.
        if (!foregroundAndBackgroundLocationPermissionApproved()) {
            return
        }
        // Call removeGeoFences() on the geofencingClient and pass in the geofencePendingIntent.
        geofencingClient.removeGeofences(geofencePendingIntent)?.run {
            // Add an onSuccessListener(), update the user that the geoFences were successfully removed through a toast.
            addOnSuccessListener {
                Log.d(TAG, getString(R.string.geofences_removed))
                Toast.makeText(applicationContext, R.string.geofences_removed, Toast.LENGTH_SHORT)
                    .show()
            }
            // Add an onFailureListener() where you log that the geoFences weren't removed.
            addOnFailureListener {
                Log.d(TAG, getString(R.string.geofences_not_removed))
            }
        }
    }
    companion object {
        internal const val ACTION_GEOFENCE_EVENT =
            "HuntMainActivity.treasureHunt.action.ACTION_GEOFENCE_EVENT"
    }
}

private const val REQUEST_FOREGROUND_AND_BACKGROUND_PERMISSION_RESULT_CODE = 33
private const val REQUEST_FOREGROUND_ONLY_PERMISSIONS_REQUEST_CODE = 34
private const val REQUEST_TURN_DEVICE_LOCATION_ON = 29
private const val TAG = "HuntMainActivity"
private const val LOCATION_PERMISSION_INDEX = 0
private const val BACKGROUND_LOCATION_PERMISSION_INDEX = 1
