package com.group16.dinoblog.util

import android.content.Context
import android.annotation.SuppressLint
import android.os.Build
import java.util.UUID

class DeviceIdentity(context: Context) {
    private val preferences = context.getSharedPreferences("device_identity", Context.MODE_PRIVATE)
    // App data is excluded from backup, so restoring another phone cannot clone its ID.
    // commit() persists the ID before it is ever used in a packet.
    @SuppressLint("UseKtx") // Need commit's Boolean result; the KTX edit helper returns Unit.
    val id: String = preferences.getString("id", null) ?: UUID.randomUUID().toString().also {
        check(preferences.edit().putString("id", it).commit()) { "Cannot persist Device ID" }
    }
    val name: String = "${Build.MANUFACTURER} ${Build.MODEL}".take(80)
}
