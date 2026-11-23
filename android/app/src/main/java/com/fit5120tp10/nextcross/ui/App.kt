package com.fit5120tp10.nextcross.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.rememberNavController
import com.fit5120tp10.nextcross.ui.navigation.NextCrossNavGraph
import com.fit5120tp10.nextcross.ui.navigation.NextCrossViewModel

@Composable
fun NextCrossApp(viewModel: NextCrossViewModel = viewModel()) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val activity = context.findActivity()
    val fragmentActivity = activity as? FragmentActivity
    val lifecycleOwner = LocalLifecycleOwner.current
    val navController = rememberNavController()
    val homePreferences = remember(appContext) {
        appContext.getSharedPreferences("nextcross_home", Context.MODE_PRIVATE)
    }

    LaunchedEffect(homePreferences) {
        val savedDefault = homePreferences.getString("default_recipient_email", "").orEmpty()
        val savedEmails = homePreferences.getString("common_recipient_emails", "")
            .orEmpty()
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toList()
        viewModel.setHomePreferences(
            defaultEmail = savedDefault,
            emails = if (savedEmails.isEmpty() && savedDefault.isNotBlank()) listOf(savedDefault) else savedEmails
        )
    }

    DisposableEffect(lifecycleOwner, appContext) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.setFineLocationGranted(appContext.hasFineLocationPermission())
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        viewModel.onLocationPermissionResult(
            granted = granted,
            shouldShowRationale = !granted && activity != null && ActivityCompat.shouldShowRequestPermissionRationale(
                activity,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        )
    }

    NextCrossNavGraph(
        navController = navController,
        viewModel = viewModel,
        appContext = appContext,
        fragmentActivity = fragmentActivity,
        homePreferences = homePreferences,
        requestFineLocationPermission = {
            locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        },
        openAppLocationSettings = { appContext.openAppLocationSettings() }
    )
}

private fun Context.hasFineLocationPermission(): Boolean {
    return ContextCompat.checkSelfPermission(
        this,
        Manifest.permission.ACCESS_FINE_LOCATION
    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
}

private fun Context.openAppLocationSettings() {
    startActivity(
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null)
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}

private tailrec fun Context.findActivity(): Activity? {
    return when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}
