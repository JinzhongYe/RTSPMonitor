package com.rtspmonitor.app
import android.app.Application
import androidx.room.Room
import com.rtspmonitor.data.*
class MonitorApp: Application() {
 val database by lazy { Room.databaseBuilder(this, MonitorDatabase::class.java, "monitor.db").addMigrations(MONITOR_MIGRATION_1_2).build() }
 val vault by lazy { CredentialVault() }
 val store by lazy { VideoStore(this, database.dao()) }
 val sessions by lazy { CameraSessionManager(this) }
}

internal fun android.content.Context.hasLocalNetworkAccess(): Boolean =
 android.os.Build.VERSION.SDK_INT < 37 || checkSelfPermission(android.Manifest.permission.ACCESS_LOCAL_NETWORK) == android.content.pm.PackageManager.PERMISSION_GRANTED
