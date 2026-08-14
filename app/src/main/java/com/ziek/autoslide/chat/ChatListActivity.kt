package com.ziek.autoslide.chat

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.widget.FrameLayout
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import android.widget.PopupWindow
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.ziek.autoslide.R
import com.ziek.autoslide.databinding.ActivityChatListBinding
import kotlinx.coroutines.launch
import kotlin.math.abs

/** 聊天室首页：昵称设置、频道列表、新建/加入频道 */
class ChatListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChatListBinding
    private val channels = mutableListOf<ChatChannel>()
    private lateinit var adapter: ChannelAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = ChannelAdapter()
        binding.channelListView.adapter = adapter
        binding.channelListView.setOnItemClickListener { _, _, position, _ ->
            openChannel(channels[position])
        }
        binding.channelListView.setOnItemLongClickListener { _, _, position, _ ->
            val channel = channels[position]
            if (channel.joined) {
                showChannelMenu(channel)
            } else {
                Toast.makeText(this, R.string.chat_tap_to_join, Toast.LENGTH_SHORT).show()
            }
            true
        }
        binding.chatRefreshButton.setOnClickListener { loadChannels() }
        binding.chatAddButton.setOnClickListener { showAddMenu() }
        binding.announcementCard.setOnClickListener { showAnnouncementDialog() }

        // Android 15+ 边到边：内容会顶到状态栏，手动加上系统栏内边距
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
                val bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
                )
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (ChatStorage.nickName(this).isBlank()) {
            showNicknameDialog()
        } else {
            loadAnnouncement()
            loadChannels()
        }
    }

    private fun showAddMenu() {
        val menuBinding = LayoutInflater.from(this).inflate(R.layout.layout_chat_add_menu, binding.root, false)
        val popup = PopupWindow(
            menuBinding,
            dp(200),
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            elevation = dp(8).toFloat()
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }

        menuBinding.findViewById<View>(R.id.menuCreateChannel).setOnClickListener {
            popup.dismiss()
            showCreateDialog()
        }

        // 这里的显示位置根据按钮来定，稍微偏移一点
        popup.showAsDropDown(binding.chatAddButton, -dp(160), 0)
    }

    private fun loadAnnouncement() {
        lifecycleScope.launch {
            runCatching { ChatApi.getAnnouncement() }
                .onSuccess { ann ->
                    binding.announcementTitleText.text = ann.title.ifBlank { getString(R.string.chat_announcement) }
                    binding.announcementContentText.text =
                        ann.content.ifBlank { getString(R.string.chat_announcement_empty) }
                }
                .onFailure {
                    binding.announcementTitleText.text = getString(R.string.chat_announcement)
                    binding.announcementContentText.text = getString(R.string.chat_announcement_empty)
                }
        }
    }

    private fun showAnnouncementDialog() {
        lifecycleScope.launch {
            runCatching { ChatApi.getAnnouncement() }
                .onSuccess { ann ->
                    MaterialAlertDialogBuilder(this@ChatListActivity)
                        .setTitle(ann.title.ifBlank { getString(R.string.chat_announcement) })
                        .setMessage(ann.content)
                        .setPositiveButton(R.string.confirm, null)
                        .show()
                }
        }
    }

    private fun showNicknameDialog() {
        val fields = createDialogInput(R.string.chat_nickname_hint, ChatStorage.defaultNickName())
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.chat_nickname_title)
            .setView(fields.container)
            .setPositiveButton(R.string.confirm) { _, _ ->
                val name = fields.input.text?.toString()?.trim().orEmpty()
                if (name.isEmpty()) {
                    Toast.makeText(this, R.string.chat_nickname_empty, Toast.LENGTH_SHORT).show()
                    showNicknameDialog()
                } else {
                    ChatStorage.setNickName(this, name)
                    loadChannels()
                }
            }
            .setNegativeButton(R.string.cancel) { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }

    private fun showCreateDialog() {
        val fields = createDialogInput(R.string.chat_channel_name_hint)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.chat_create_title)
            .setView(fields.container)
            .setPositiveButton(R.string.confirm) { _, _ ->
                val name = fields.input.text?.toString()?.trim().orEmpty()
                if (name.isEmpty()) {
                    Toast.makeText(this, R.string.chat_channel_name_empty, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                lifecycleScope.launch {
                    runCatching {
                        ChatApi.createChannel(name, ChatStorage.deviceId(this@ChatListActivity), ChatStorage.nickName(this@ChatListActivity))
                    }.onSuccess { channel ->
                        loadChannels()
                        openChat(channel)
                    }.onFailure { e ->
                        Toast.makeText(this@ChatListActivity, getString(R.string.chat_create_failed, e.message ?: ""), Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showChannelMenu(channel: ChatChannel) {
        val members = channel.members.joinToString("\n") { it.name }
        val isOwner = channel.creatorId == ChatStorage.deviceId(this)
        MaterialAlertDialogBuilder(this)
            .setTitle(channel.name)
            .setMessage(getString(R.string.chat_channel_detail, channel.members.size, members))
            .setPositiveButton(if (isOwner) R.string.chat_delete else R.string.chat_leave) { _, _ ->
                if (isOwner) confirmDelete(channel) else confirmLeave(channel)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmDelete(channel: ChatChannel) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.chat_delete)
            .setMessage(R.string.chat_delete_confirm)
            .setPositiveButton(R.string.confirm) { _, _ ->
                lifecycleScope.launch {
                    runCatching {
                        ChatApi.deleteChannel(channel.id, ChatStorage.deviceId(this@ChatListActivity))
                    }.onSuccess {
                        Toast.makeText(this@ChatListActivity, R.string.chat_deleted, Toast.LENGTH_SHORT).show()
                        loadChannels()
                    }.onFailure { e ->
                        Toast.makeText(
                            this@ChatListActivity,
                            getString(R.string.chat_delete_failed, e.message ?: ""),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmLeave(channel: ChatChannel) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.chat_leave)
            .setMessage(R.string.chat_leave_confirm)
            .setPositiveButton(R.string.confirm) { _, _ ->
                lifecycleScope.launch {
                    runCatching {
                        ChatApi.leaveChannel(channel.id, ChatStorage.deviceId(this@ChatListActivity))
                    }.onSuccess {
                        Toast.makeText(this@ChatListActivity, R.string.chat_left, Toast.LENGTH_SHORT).show()
                        loadChannels()
                    }.onFailure { e ->
                        Toast.makeText(this@ChatListActivity, getString(R.string.chat_join_failed, e.message ?: ""), Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun loadChannels() {
        lifecycleScope.launch {
            runCatching { ChatApi.getChannels(ChatStorage.deviceId(this@ChatListActivity)) }
                .onSuccess { list ->
                    channels.clear()
                    channels.addAll(list)
                    adapter.notifyDataSetChanged()
                    binding.channelSectionLabel.isVisible = channels.isNotEmpty()
                    binding.chatEmptyContainer.isVisible = channels.isEmpty()
                }
                .onFailure { e ->
                    Toast.makeText(this@ChatListActivity, getString(R.string.chat_load_failed, e.message ?: ""), Toast.LENGTH_SHORT).show()
                }
        }
    }

    private fun openChat(channel: ChatChannel) {
        startActivity(
            Intent(this, ChatActivity::class.java)
                .putExtra(ChatActivity.EXTRA_CHANNEL_ID, channel.id)
                .putExtra(ChatActivity.EXTRA_CHANNEL_NAME, channel.name)
        )
    }

    /** 公开频道：已加入直接打开，未加入先自动加入再打开 */
    private fun openChannel(channel: ChatChannel) {
        if (channel.joined) {
            openChat(channel)
            return
        }
        lifecycleScope.launch {
            runCatching {
                ChatApi.joinChannel(
                    channel.id,
                    ChatStorage.deviceId(this@ChatListActivity),
                    ChatStorage.nickName(this@ChatListActivity),
                )
            }.onSuccess { joined ->
                loadChannels()
                openChat(joined)
            }.onFailure { e ->
                Toast.makeText(
                    this@ChatListActivity,
                    getString(R.string.chat_join_failed, e.message ?: ""),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private class DialogInputFields(
        val container: FrameLayout,
        val input: TextInputEditText,
    )

    /** 和“新建录制”弹窗统一：Material Outlined 输入框 + 24dp 左右留白 */
    private fun createDialogInput(hintRes: Int, prefill: String = ""): DialogInputFields {
        val inputLayout = TextInputLayout(this).apply {
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            hint = getString(hintRes)
        }
        val input = TextInputEditText(inputLayout.context).apply {
            isSingleLine = true
            if (prefill.isNotEmpty()) setText(prefill)
        }
        inputLayout.addView(input)
        val container = FrameLayout(this).apply {
            setPadding(dp(24), dp(12), dp(24), 0)
            addView(inputLayout)
        }
        return DialogInputFields(container, input)
    }

    private inner class ChannelAdapter : BaseAdapter() {
        override fun getCount() = channels.size
        override fun getItem(position: Int) = channels[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(this@ChatListActivity)
                .inflate(R.layout.item_chat_channel, parent, false)
            val channel = channels[position]
            val avatar = view.findViewById<TextView>(R.id.channelAvatarText)
            val name = channel.name.trim()
            avatar.text = (name.firstOrNull()?.toString()?.uppercase() ?: "#")
            avatar.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(avatarColors[abs(channel.id.hashCode()) % avatarColors.size])
            }
            view.findViewById<TextView>(R.id.channelNameText).text = channel.name
            val preview = if (channel.joined) {
                channel.lastMessageText.ifBlank { getString(R.string.chat_no_message) }
            } else {
                getString(R.string.chat_not_joined)
            }
            view.findViewById<TextView>(R.id.channelPreviewText).text = preview
            view.findViewById<TextView>(R.id.channelTimeText).text =
                if (channel.joined) formatTime(channel.lastMessageTime) else ""
            return view
        }
    }

    private val avatarColors = intArrayOf(
        0xFF545995.toInt(),
        0xFF5B9279.toInt(),
        0xFFD97757.toInt(),
        0xFF6B84A3.toInt(),
        0xFF7A8F6C.toInt(),
        0xFF8B6FA8.toInt(),
    )

    /** 把 "2026/8/12 16:53:34" 转成简洁时间：今天只显示时分，跨天显示 月/日 时分 */
    private fun formatTime(raw: String): String {
        if (raw.isBlank()) return ""
        return try {
            val parser = java.text.SimpleDateFormat("yyyy/M/d HH:mm:ss", java.util.Locale.CHINA)
            val date = parser.parse(raw) ?: return ""
            val cal = java.util.Calendar.getInstance().apply { time = date }
            val now = java.util.Calendar.getInstance()
            val hhmm = java.text.SimpleDateFormat("HH:mm", java.util.Locale.CHINA).format(date)
            if (now.get(java.util.Calendar.YEAR) == cal.get(java.util.Calendar.YEAR) &&
                now.get(java.util.Calendar.DAY_OF_YEAR) == cal.get(java.util.Calendar.DAY_OF_YEAR)
            ) {
                hhmm
            } else {
                java.text.SimpleDateFormat("M/d HH:mm", java.util.Locale.CHINA).format(date)
            }
        } catch (e: Exception) {
            ""
        }
    }
}
