package com.ziek.autoslide

/**
 * 导入配置中转 Activity（透明无 UI）：
 * 打开系统文件选择器选择 slide_settings.xml，解析后逐键合并写入配置，
 * 并让无障碍服务立即重载（宏、关键词、跳过配置等）。
 */

import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.ziek.autoslide.service.AutoSlideService
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

class ImportSettingsActivity : AppCompatActivity() {

    private val filePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) {
            Toast.makeText(this, R.string.macro_import_cancelled, Toast.LENGTH_SHORT).show()
        } else {
            val ok = importSlideSettings(uri)
            Toast.makeText(
                this,
                if (ok) R.string.macro_import_success else R.string.macro_import_failed,
                Toast.LENGTH_LONG
            ).show()
            if (ok) {
                // 让运行中的服务立即重载配置
                AutoSlideService.getInstance()?.reloadConfig()
            }
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 打开系统文件选择器，选择导出的 slide_settings.xml
        filePicker.launch(arrayOf("*/*"))
    }

    /* 读取选择的文件并逐键合并写入配置 */
    private fun importSlideSettings(uri: Uri): Boolean {
        return try {
            val content = contentResolver.openInputStream(uri)
                ?.bufferedReader()
                ?.use { it.readText() }
                ?: return false
            val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            val editor = prefs.edit()
            val parser = XmlPullParserFactory.newInstance().newPullParser()
            parser.setInput(StringReader(content))
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    val name = parser.getAttributeValue(null, "name") ?: ""
                    if (name.isNotEmpty()) {
                        when (parser.name) {
                            "string" -> editor.putString(name, parser.nextText())
                            "boolean" -> editor.putBoolean(
                                name,
                                parser.getAttributeValue(null, "value").toBooleanStrictOrNull() ?: false
                            )
                            "int" -> editor.putInt(
                                name,
                                parser.getAttributeValue(null, "value")?.toIntOrNull() ?: 0
                            )
                            "long" -> editor.putLong(
                                name,
                                parser.getAttributeValue(null, "value")?.toLongOrNull() ?: 0L
                            )
                            "float" -> editor.putFloat(
                                name,
                                parser.getAttributeValue(null, "value")?.toFloatOrNull() ?: 0f
                            )
                        }
                    }
                }
                event = parser.next()
            }
            editor.apply()
            true
        } catch (e: Exception) {
            LogX.e("ImportSettings", "Import slide_settings failed", e)
            false
        }
    }
}
