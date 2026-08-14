package com.ziek.autoslide.chat

import android.content.Context
import android.os.Build
import android.provider.Settings

/** 聊天本地身份：设备ID（ANDROID_ID）+ 用户昵称 */
object ChatStorage {
    private const val PREFS = "chat_prefs"
    private const val KEY_NICK = "chat_nickname"
    private const val KEY_DEVICE_ID = "chat_device_id"

    fun deviceId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_DEVICE_ID, null)?.let { return it }
        val id = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
        prefs.edit().putString(KEY_DEVICE_ID, id).apply()
        return id
    }

    fun nickName(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_NICK, "") ?: ""

    fun setNickName(context: Context, name: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_NICK, name.trim())
            .apply()
    }

    fun defaultNickName(): String = Build.MODEL.ifBlank { "Android" }
}
