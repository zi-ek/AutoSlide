package com.ltx.chat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.ltx.R
import com.ltx.databinding.ActivityChatBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/** 频道聊天页：轮询拉取新消息 + 发送消息 */
class ChatActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CHANNEL_ID = "channel_id"
        const val EXTRA_CHANNEL_NAME = "channel_name"
        private const val POLL_INTERVAL_MS = 2000L
    }

    private lateinit var binding: ActivityChatBinding
    private val messages = mutableListOf<ChatMessage>()
    private lateinit var adapter: MessageAdapter
    private var channelId = ""
    private var lastSeq = 0L
    private var myDeviceId = ""

    private val imagePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) sendImageMessage(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        channelId = intent.getStringExtra(EXTRA_CHANNEL_ID) ?: ""
        binding.chatTitleText.text = intent.getStringExtra(EXTRA_CHANNEL_NAME) ?: ""
        myDeviceId = ChatStorage.deviceId(this)

        adapter = MessageAdapter()
        binding.messageListView.adapter = adapter

        binding.chatBackButton.setOnClickListener { finish() }
        binding.sendButton.setOnClickListener { sendMessage() }
        binding.chatImageButton.setOnClickListener { imagePicker.launch("image/*") }

        // Android 15+ 强制边到边，adjustResize 不再自动避让输入法，
        // 需要手动监听 IME insets 给根布局加底部内边距（修复一加等新系统输入法遮挡输入框）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
                val bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
                )
                val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
                view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
                insets
            }
        }

        loadChannelInfo()
        startPolling()
    }

    private fun loadChannelInfo() {
        lifecycleScope.launch {
            runCatching { ChatApi.getChannel(channelId, myDeviceId) }
                .onSuccess { channel ->
                    binding.chatTitleText.text = channel.name
                    binding.chatSubtitleText.text =
                        getString(R.string.chat_subtitle, channel.members.size)
                }
        }
    }

    private fun startPolling() {
        lifecycleScope.launch {
            while (isActive) {
                try {
                    val newMessages = ChatApi.getMessages(channelId, lastSeq)
                    if (newMessages.isNotEmpty()) {
                        messages.addAll(newMessages)
                        lastSeq = newMessages.last().seq
                        adapter.notifyDataSetChanged()
                        binding.messageListView.setSelection(messages.size - 1)
                    }
                } catch (e: Exception) {
                    // 网络抖动时静默重试
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private fun sendMessage() {
        val text = binding.chatInputEditText.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        binding.sendButton.isEnabled = false
        lifecycleScope.launch {
            runCatching {
                ChatApi.sendMessage(channelId, myDeviceId, ChatStorage.nickName(this@ChatActivity), text)
            }.onSuccess { msg ->
                binding.chatInputEditText.text?.clear()
                appendMessage(msg)
            }.onFailure { e ->
                Toast.makeText(this@ChatActivity, getString(R.string.chat_send_failed, e.message ?: ""), Toast.LENGTH_SHORT).show()
            }
            binding.sendButton.isEnabled = true
        }
    }

    private fun appendMessage(msg: ChatMessage) {
        if (msg.seq > lastSeq) {
            messages.add(msg)
            lastSeq = msg.seq
            adapter.notifyDataSetChanged()
            binding.messageListView.setSelection(messages.size - 1)
        }
    }

    private fun sendImageMessage(uri: Uri) {
        binding.sendButton.isEnabled = false
        lifecycleScope.launch {
            val base64 = withContext(Dispatchers.IO) { encodeImage(uri) }
            if (base64 == null) {
                Toast.makeText(
                    this@ChatActivity,
                    getString(R.string.chat_image_failed, "无法读取图片"),
                    Toast.LENGTH_SHORT,
                ).show()
                binding.sendButton.isEnabled = true
                return@launch
            }
            runCatching {
                ChatApi.sendMessage(
                    channelId,
                    myDeviceId,
                    ChatStorage.nickName(this@ChatActivity),
                    "",
                    base64,
                )
            }.onSuccess { msg ->
                appendMessage(msg)
            }.onFailure { e ->
                Toast.makeText(
                    this@ChatActivity,
                    getString(R.string.chat_image_failed, e.message ?: ""),
                    Toast.LENGTH_SHORT,
                ).show()
            }
            binding.sendButton.isEnabled = true
        }
    }

    /** 读取并压缩图片（最长边 1280、JPEG 80%），返回 data URL 形式 base64 */
    private fun encodeImage(uri: Uri): String? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            val boundsStream = contentResolver.openInputStream(uri) ?: return null
            boundsStream.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (bounds.outWidth / sample > 1280 || bounds.outHeight / sample > 1280) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val src = contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            } ?: return null
            val rotated = rotateIfNeeded(uri, src)
            val out = ByteArrayOutputStream()
            rotated.compress(Bitmap.CompressFormat.JPEG, 80, out)
            if (rotated !== src) rotated.recycle()
            src.recycle()
            "data:image/jpeg;base64," + android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
        } catch (e: Exception) {
            null
        }
    }

    private fun rotateIfNeeded(uri: Uri, bmp: Bitmap): Bitmap {
        val orientation = try {
            contentResolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } ?: ExifInterface.ORIENTATION_NORMAL
        } catch (e: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            else -> return bmp
        }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private inner class MessageAdapter : BaseAdapter() {
        override fun getCount() = messages.size
        override fun getItem(position: Int) = messages[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(this@ChatActivity)
                .inflate(R.layout.item_chat_message, parent, false)
            val msg = messages[position]
            val mine = msg.deviceId == myDeviceId

            val row = view.findViewById<LinearLayout>(R.id.messageRow)
            val sender = view.findViewById<TextView>(R.id.msgSenderText)
            val bubble = view.findViewById<TextView>(R.id.msgTextText)
            val imageView = view.findViewById<ImageView>(R.id.msgImageView)

            row.gravity = if (mine) Gravity.END else Gravity.START
            sender.text = if (mine) getString(R.string.chat_me_hint, msg.time) else "${msg.name} · ${msg.time}"
            val isImage = msg.type == "image" && msg.image.isNotBlank()
            imageView.isVisible = isImage
            bubble.isVisible = !isImage
            if (isImage) {
                imageView.setBackgroundResource(if (mine) R.drawable.bg_bubble_mine else R.drawable.bg_bubble_other)
                imageView.setPadding(dp(6), dp(6), dp(6), dp(6))
                val url = ChatApi.imageUrl(msg.image)
                imageView.tag = url
                imageView.setImageBitmap(null)
                lifecycleScope.launch {
                    val bmp = ChatImageLoader.load(url)
                    if (imageView.tag == url && bmp != null) {
                        imageView.setImageBitmap(bmp)
                    }
                }
                return view
            }
            bubble.text = msg.text
            if (mine) {
                bubble.setTextColor(android.graphics.Color.WHITE)
                bubble.setBackgroundResource(R.drawable.bg_bubble_mine)
            } else {
                bubble.setTextColor(ContextCompat.getColor(this@ChatActivity, R.color.text_primary))
                bubble.setBackgroundResource(R.drawable.bg_bubble_other)
            }
            return view
        }
    }
}
