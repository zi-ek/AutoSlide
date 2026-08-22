package com.ziek.autoslide

/**
 * 内置使用教程页
 *
 * 正文来自 assets/tutorial.html——由 tools/gen_tutorial_html.py 从 docs/使用教程.md 生成，
 * 完全离线、不联网、不加载任何外部资源，所以 WebView 只开启 JavaScript（页面自己要用它
 * 切主题和给表格套横向滚动容器），其余能力一律保持关闭。
 */

import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.ziek.autoslide.databinding.ActivityTutorialBinding

class TutorialActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTutorialBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTutorialBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.tutorialBackButton.setOnClickListener { finish() }

        // Android 15+ 强制边到边，手动给根布局补上系统栏内边距，避免标题被状态栏压住
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
                val bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
                )
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        }

        setupWebView()

        // 页内目录跳转会进 WebView 的历史栈：先在页内后退，退无可退才关页面
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.tutorialWebView.canGoBack()) {
                    binding.tutorialWebView.goBack()
                } else {
                    finish()
                }
            }
        })
    }

    private fun setupWebView() {
        val webView = binding.tutorialWebView
        webView.settings.apply {
            javaScriptEnabled = true
            // 本地文件不需要任何网络/存储能力，全部关掉
            allowFileAccess = false
            allowContentAccess = false
            domStorageEnabled = false
            setSupportZoom(false)
        }
        webView.setBackgroundColor(getColor(R.color.window_background))
        webView.webViewClient = object : WebViewClient() {
            /* 教程里的外链（GitHub 等）交给系统浏览器，页内锚点仍由 WebView 自己处理 */
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?,
            ): Boolean {
                val url = request?.url ?: return false
                if (url.scheme == "http" || url.scheme == "https") {
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, url)) }
                    return true
                }
                return false
            }
        }
        // 用 # 把 App 当前的深浅色传给页面，页面据此切换配色
        val night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
        webView.loadUrl("file:///android_asset/tutorial.html#" + if (night) "dark" else "light")
    }

    override fun onDestroy() {
        // 退出时先摘下再销毁 WebView，避免 Activity 泄漏
        (binding.tutorialWebView.parent as? ViewGroup)?.removeView(binding.tutorialWebView)
        binding.tutorialWebView.destroy()
        super.onDestroy()
    }
}
