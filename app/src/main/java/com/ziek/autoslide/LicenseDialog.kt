package com.ziek.autoslide

/**
 * 使用时长相关的两个弹窗
 *
 * - [showIfExpired]：试用到期后的强制弹窗，没有取消键、返回键点击外部都关不掉，
 *   只能去分享获取时长；此时自动功能已经被 [License] 停掉。
 * - [showShareDialog]：主界面「分享得时长」入口，展示剩余天数、邀请码与规则，可以正常关闭。
 *
 * 判定与发放全在服务端，这里只负责展示和把邀请码递出去，见 `server/src/license.js`。
 */

import android.app.Activity
import android.content.Intent
import android.text.InputFilter
import android.util.TypedValue
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object LicenseDialog {

    /* 当前正在展示的弹窗，避免 onResume 重复弹 */
    private var dialog: AlertDialog? = null

    /* 单独记一下到期强制弹窗：时长到账后要把它主动收掉，普通分享弹窗不受影响 */
    private var forcedDialog: AlertDialog? = null

    /**
     * 到期时弹出强制弹窗；没到期则什么也不做。
     *
     * @param activity 活动
     */
    fun showIfExpired(activity: Activity) {
        if (!License.blocked) {
            // 刚同步到新时长：把还挂着的到期弹窗收掉，用户不用自己再点一次刷新
            forcedDialog?.dismiss()
            return
        }
        if (!canShow(activity)) return
        val status = License.statusFlow.value
        show(
            activity = activity,
            title = activity.getString(R.string.license_expired_title),
            message = buildMessage(activity, expired = true),
            cancelable = false,
            showBind = status.canBind
        )
    }

    /**
     * 主界面入口：查看剩余时长、拿邀请码、填写好友邀请码。
     *
     * @param activity 活动
     */
    fun showShareDialog(activity: Activity) {
        if (!canShow(activity)) return
        // 已到期时直接走强制弹窗那一套，避免用户从这个入口拿到一个能关掉的弹窗
        if (License.blocked) {
            showIfExpired(activity)
            return
        }
        show(
            activity = activity,
            title = activity.getString(R.string.license_dialog_title),
            message = buildMessage(activity, expired = false),
            cancelable = true,
            showBind = License.statusFlow.value.canBind
        )
    }

    /* 统一的弹窗构建：到期版没有取消键，正常版有 */
    private fun show(
        activity: Activity,
        title: String,
        message: CharSequence,
        cancelable: Boolean,
        showBind: Boolean
    ) {
        val builder = MaterialAlertDialogBuilder(activity)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(R.string.license_share, null)
            .setCancelable(cancelable)
        if (showBind) {
            builder.setNeutralButton(R.string.license_bind, null)
        } else if (!cancelable) {
            // 到期弹窗没有取消键，留一个「刷新」让刚到账的时长能立刻生效
            builder.setNeutralButton(R.string.license_refresh, null)
        }
        if (cancelable) {
            builder.setNegativeButton(R.string.license_close, null)
        }

        val lifecycleOwner = activity as? LifecycleOwner
        dialog = builder.create().also { created ->
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_DESTROY) {
                    created.dismiss()
                    if (dialog == created) dialog = null
                }
            }
            lifecycleOwner?.lifecycle?.addObserver(observer)
            if (!cancelable) forcedDialog = created
            created.setCanceledOnTouchOutside(cancelable)
            created.setOnShowListener {
                setupButtons(created, activity, cancelable, showBind)
            }
            created.setOnDismissListener {
                lifecycleOwner?.lifecycle?.removeObserver(observer)
                if (dialog == created) dialog = null
                if (forcedDialog == created) forcedDialog = null
            }
            created.show()
        }
    }

    private fun setupButtons(
        current: AlertDialog,
        activity: Activity,
        cancelable: Boolean,
        showBind: Boolean
    ) {
        // 分享：不关闭弹窗，用户从分享面板回来还能接着操作
        current.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
            shareInvite(activity)
        }
        current.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
            if (showBind) {
                showBindDialog(activity)
            } else {
                refresh(activity, current)
            }
        }
        current.getButton(AlertDialog.BUTTON_NEGATIVE)?.setOnClickListener {
            if (cancelable) current.dismiss()
        }
    }

    /* 重新同步一次：时长已到账就自动关掉到期弹窗 */
    private fun refresh(activity: Activity, current: AlertDialog) {
        val button = current.getButton(AlertDialog.BUTTON_NEUTRAL)
        button?.isEnabled = false
        License.syncIfNeeded(activity, force = true) {
            button?.isEnabled = true
            if (activity.isFinishing || activity.isDestroyed) return@syncIfNeeded
            if (!License.blocked) {
                current.dismiss()
                Toast.makeText(activity, R.string.license_refreshed, Toast.LENGTH_SHORT).show()
            } else {
                current.setMessage(buildMessage(activity, expired = true))
                Toast.makeText(activity, R.string.license_still_expired, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /* 拉起系统分享面板，把邀请码和落地页链接发给好友 */
    private fun shareInvite(activity: Activity) {
        val status = License.statusFlow.value
        if (status.code.isEmpty()) {
            Toast.makeText(activity, R.string.license_no_code, Toast.LENGTH_SHORT).show()
            License.syncIfNeeded(activity, force = true)
            return
        }
        val url = status.inviteUrl.ifEmpty { URL_INVITE_PREFIX + status.code }
        val text = activity.getString(R.string.license_share_text, status.code, url)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        runCatching {
            activity.startActivity(Intent.createChooser(intent, activity.getString(R.string.license_share)))
        }.onFailure {
            Toast.makeText(activity, R.string.license_share_failed, Toast.LENGTH_SHORT).show()
        }
    }

    /* 填写好友的邀请码（只有新设备能填，是否可填由服务端说了算） */
    private fun showBindDialog(activity: Activity) {
        val input = EditText(activity).apply {
            hint = activity.getString(R.string.license_bind_hint)
            filters = arrayOf(InputFilter.AllCaps(), InputFilter.LengthFilter(8))
            setSingleLine()
        }
        val padding = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 24f, activity.resources.displayMetrics
        ).toInt()
        val container = FrameLayout(activity).apply {
            setPadding(padding, padding / 2, padding, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.license_bind_title)
            .setMessage(activity.getString(R.string.license_bind_message))
            .setView(container)
            .setPositiveButton(R.string.license_bind_confirm, null)
            .setNegativeButton(R.string.cancel, null)
            .create().also { bindDialog ->
                bindDialog.setOnShowListener {
                    bindDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val code = input.text.toString().trim()
                        if (code.isEmpty()) {
                            Toast.makeText(activity, R.string.license_bind_empty, Toast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }
                        val confirmButton = bindDialog.getButton(AlertDialog.BUTTON_POSITIVE)
                        confirmButton.isEnabled = false
                        License.bind(activity, code) { error ->
                            confirmButton.isEnabled = true
                            if (activity.isFinishing || activity.isDestroyed) return@bind
                            if (error == null) {
                                bindDialog.dismiss()
                                dialog?.dismiss()
                                Toast.makeText(activity, R.string.license_bind_success, Toast.LENGTH_LONG).show()
                            } else {
                                Toast.makeText(activity, error, Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }
                bindDialog.show()
            }
    }

    /* 弹窗正文：剩余时长 + 邀请码 + 奖励规则 */
    private fun buildMessage(activity: Activity, expired: Boolean): CharSequence {
        val status = License.statusFlow.value
        val header = if (expired) {
            activity.getString(R.string.license_expired_message)
        } else if (status.known) {
            val date = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(status.expireAt))
            activity.getString(R.string.license_remain_message, License.remainDays(activity), date)
        } else {
            activity.getString(R.string.license_unknown_message)
        }
        val code = status.code.ifEmpty { "——" }
        return header + "\n\n" +
            activity.getString(R.string.license_code_line, code) + "\n" +
            activity.getString(R.string.license_invited_line, status.invitedCount, status.bonusDays) + "\n\n" +
            activity.getString(R.string.license_rule)
    }

    /* 页面还活着、且没有同款弹窗在显示时才弹 */
    private fun canShow(activity: Activity): Boolean {
        if (activity.isFinishing || activity.isDestroyed) return false
        if (dialog?.isShowing == true) return false
        val lifecycleOwner = activity as? LifecycleOwner
        return lifecycleOwner == null ||
            lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
    }
}
