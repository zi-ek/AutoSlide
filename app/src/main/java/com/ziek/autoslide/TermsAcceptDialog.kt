package com.ziek.autoslide

/**
 * 首次启动的使用声明弹窗（对照 GKD 的 `ui/component/TermsAcceptDialog.kt` 移植）
 *
 * GKD 的实现是**一个 AlertDialog 分两步**，不是两个弹窗：
 * 1. 第一步标题「使用声明」，正文里「用户协议」「隐私政策」是两个可点链接，点「同意」进入第二步；
 * 2. 第二步标题「关于无障碍」，说明为什么要用无障碍 API，再点「同意」才算通过。
 *
 * 两步共用同一组按钮：「同意」推进/完成，「不同意」直接 finish() 退出应用。
 * 弹窗不可取消（GKD 的 onDismissRequest 是空实现），返回键和点击外部都关不掉。
 */

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.text.SpannableString
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.google.android.material.dialog.MaterialAlertDialogBuilder

object TermsAcceptDialog {

    /**
     * 是否已同意（GKD: termsAcceptedFlow）
     *
     * @param activity 宿主界面
     */
    fun isAccepted(activity: Activity): Boolean =
        activity.getSharedPreferences(PREFS_NAME, Activity.MODE_PRIVATE)
            .getBoolean(KEY_TERMS_ACCEPTED, false)

    /**
     * 展示声明弹窗；已同意过则什么都不做。
     *
     * @param activity 宿主界面，用户点「不同意」时会被 finish
     * @param onAccepted 全部步骤同意后的回调
     */
    fun showIfNeeded(activity: Activity, onAccepted: () -> Unit) {
        if (isAccepted(activity)) {
            onAccepted()
            return
        }
        showStep(activity, 0, onAccepted)
    }

    /* 逐步展示：step 0 = 使用声明，step 1 = 关于无障碍（GKD: stepDataList + step 状态） */
    private fun showStep(activity: Activity, step: Int, onAccepted: () -> Unit) {
        val isLastStep = step >= LAST_STEP
        val titleRes = if (step == 0) R.string.terms_title else R.string.terms_a11y_title
        val messageView = if (step == 0) {
            buildTermsMessageView(activity)
        } else {
            buildPlainMessageView(activity, activity.getString(R.string.terms_a11y_message))
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(titleRes)
            .setView(messageView)
            .setCancelable(false)
            .setPositiveButton(R.string.terms_agree) { _, _ ->
                if (isLastStep) {
                    activity.getSharedPreferences(PREFS_NAME, Activity.MODE_PRIVATE)
                        .edit()
                        .putBoolean(KEY_TERMS_ACCEPTED, true)
                        .apply()
                    onAccepted()
                } else {
                    showStep(activity, step + 1, onAccepted)
                }
            }
            .setNegativeButton(R.string.terms_disagree) { _, _ ->
                activity.finish()
            }
            .show()
    }

    /* 第一步正文：把「用户协议」「隐私政策」做成可点链接（GKD: buildAnnotatedString + withLink） */
    private fun buildTermsMessageView(activity: Activity): TextView {
        val prefix = activity.getString(R.string.terms_message_prefix)
        val middle = activity.getString(R.string.terms_message_middle)
        val suffix = activity.getString(R.string.terms_message_suffix)
        val termsLabel = activity.getString(R.string.terms_link_agreement)
        val privacyLabel = activity.getString(R.string.terms_link_privacy)
        val full = prefix + termsLabel + middle + privacyLabel + suffix
        val spannable = SpannableString(full)
        val termsStart = prefix.length
        val privacyStart = termsStart + termsLabel.length + middle.length
        applyLinkSpan(activity, spannable, termsStart, termsLabel.length, URL_TERMS)
        applyLinkSpan(activity, spannable, privacyStart, privacyLabel.length, URL_PRIVACY)
        return buildPlainMessageView(activity, spannable).apply {
            movementMethod = LinkMovementMethod.getInstance()
        }
    }

    /* 链接样式与 GKD 一致：主题色 + 加粗 */
    private fun applyLinkSpan(
        activity: Activity,
        spannable: SpannableString,
        start: Int,
        length: Int,
        url: String,
    ) {
        val end = start + length
        val flag = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        spannable.setSpan(object : ClickableSpan() {
            override fun onClick(widget: View) = openUrl(activity, url)

            override fun updateDrawState(ds: android.text.TextPaint) {
                // 不要系统默认的下划线，样式由下面两个 span 控制
                ds.isUnderlineText = false
            }
        }, start, end, flag)
        spannable.setSpan(
            ForegroundColorSpan(ContextCompat.getColor(activity, R.color.primary)),
            start, end, flag
        )
        spannable.setSpan(StyleSpan(android.graphics.Typeface.BOLD), start, end, flag)
    }

    /* 正文容器：与对话框标题左右对齐 */
    private fun buildPlainMessageView(activity: Activity, text: CharSequence): TextView {
        val density = activity.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        return TextView(activity).apply {
            setText(text)
            textSize = 16f
            setTextColor(ContextCompat.getColor(activity, R.color.text_primary))
            setLineSpacing(dp(4).toFloat(), 1f)
            setPadding(dp(24), dp(8), dp(24), 0)
        }
    }

    private fun openUrl(activity: Activity, url: String) {
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
        } catch (_: ActivityNotFoundException) {
            // 没有浏览器时静默忽略，不影响用户继续同意
        }
    }

    /* 最后一步的下标（GKD: stepDataList.size - 1） */
    private const val LAST_STEP = 1
}
