package com.ziek.autoslide.chat

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.core.content.edit

/** 聊天本地身份：设备ID（ANDROID_ID）+ 用户昵称 */
object ChatStorage {
    private const val PREFS = "chat_prefs"
    private const val KEY_NICK = "chat_nickname"
    private const val KEY_DEVICE_ID = "chat_device_id"

    /* ANDROID_ID 是授权与统计的设备身份：它由「设备 + 用户 + 应用签名」派生，
       不同签名的应用拿到的值不同，无法跨应用关联用户，是 Google 推荐的
       IMEI/序列号替代方案。这里不能换成随机 UUID —— 清一次应用数据就能
       重置试用期，授权体系会直接失效。 */
    @SuppressLint("HardwareIds")
    fun deviceId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_DEVICE_ID, null)?.let { return it }
        val id = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
        prefs.edit { putString(KEY_DEVICE_ID, id) }
        return id
    }

    fun nickName(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_NICK, "") ?: ""

    fun setNickName(context: Context, name: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit {
                putString(KEY_NICK, name.trim())
            }
    }

    fun defaultNickName(): String = Build.MODEL.ifBlank { "Android" }
}
