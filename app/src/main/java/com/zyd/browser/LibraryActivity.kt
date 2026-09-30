package com.zyd.browser

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import java.text.DateFormat
import java.util.Date

class LibraryActivity : Activity() {
    private lateinit var database: BrowserDatabase
    private lateinit var listView: ListView
    private lateinit var emptyView: TextView
    private lateinit var adapter: EntryAdapter
    private var mode: String = MODE_BOOKMARKS

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        database = BrowserDatabase(this)
        mode = intent.getStringExtra(EXTRA_MODE).takeIf { it == MODE_HISTORY } ?: MODE_BOOKMARKS
        buildUi()
        reloadEntries()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(247, 249, 252))
        }
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(6), dp(10), dp(6))
            setBackgroundColor(Color.WHITE)
        }
        val back = Button(this).apply {
            text = "‹"
            textSize = 28f
            minWidth = 0
            minimumWidth = 0
            background = null
            contentDescription = "返回"
            setOnClickListener { finish() }
        }
        val title = TextView(this).apply {
            text = if (mode == MODE_HISTORY) "浏览历史" else "收藏夹"
            textSize = 20f
            setTextColor(Color.rgb(24, 33, 47))
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, 0, 0)
        }
        toolbar.addView(back, LinearLayout.LayoutParams(dp(48), dp(48)))
        toolbar.addView(title, LinearLayout.LayoutParams(0, dp(48), 1f))
        if (mode == MODE_HISTORY) {
            toolbar.addView(Button(this).apply {
                text = "清空"
                isAllCaps = false
                background = null
                setTextColor(Color.rgb(11, 87, 208))
                setOnClickListener { confirmClearHistory() }
            })
        }

        val content = FrameLayout(this)
        listView = ListView(this).apply {
            divider = android.graphics.drawable.ColorDrawable(Color.rgb(228, 232, 239))
            dividerHeight = 1
            setPadding(dp(8), dp(6), dp(8), dp(12))
            clipToPadding = false
            setOnItemClickListener { _, _, position, _ ->
                val entry = this@LibraryActivity.adapter.getItem(position)
                setResult(RESULT_OK, Intent().putExtra(EXTRA_URL, entry.url))
                finish()
            }
            setOnItemLongClickListener { _, _, position, _ ->
                confirmDelete(this@LibraryActivity.adapter.getItem(position))
                true
            }
        }
        emptyView = TextView(this).apply {
            text = if (mode == MODE_HISTORY) "还没有浏览记录" else "还没有收藏网页"
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(104, 115, 134))
        }
        content.addView(listView, FrameLayout.LayoutParams(-1, -1))
        content.addView(emptyView, FrameLayout.LayoutParams(-1, -1))
        listView.emptyView = emptyView

        root.addView(toolbar, LinearLayout.LayoutParams(-1, dp(60)))
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun reloadEntries() {
        val entries = if (mode == MODE_HISTORY) database.getHistory() else database.getBookmarks()
        adapter = EntryAdapter(entries)
        listView.adapter = adapter
    }

    private fun confirmDelete(entry: BrowserEntry) {
        AlertDialog.Builder(this)
            .setTitle(if (mode == MODE_HISTORY) "删除这条记录？" else "取消收藏？")
            .setMessage(entry.title)
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                database.deleteEntry(mode, entry.id)
                reloadEntries()
            }
            .show()
    }

    private fun confirmClearHistory() {
        AlertDialog.Builder(this)
            .setTitle("清空浏览历史？")
            .setMessage("此操作不能撤销。")
            .setNegativeButton("取消", null)
            .setPositiveButton("清空") { _, _ ->
                database.clearHistory()
                reloadEntries()
            }
            .show()
    }

    override fun onDestroy() {
        database.close()
        super.onDestroy()
    }

    private inner class EntryAdapter(private val entries: List<BrowserEntry>) : BaseAdapter() {
        private val iconCache = mutableMapOf<Long, Bitmap>()

        override fun getCount(): Int = entries.size
        override fun getItem(position: Int): BrowserEntry = entries[position]
        override fun getItemId(position: Int): Long = entries[position].id

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = (convertView as? LinearLayout) ?: LinearLayout(this@LibraryActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(10))
                if (mode == MODE_HISTORY) {
                    addView(ImageView(this@LibraryActivity).apply {
                        tag = TAG_ICON
                        scaleType = ImageView.ScaleType.CENTER_INSIDE
                        contentDescription = "网站图标"
                        setImageResource(R.drawable.ic_public)
                    }, LinearLayout.LayoutParams(dp(42), dp(42)).apply {
                        marginEnd = dp(12)
                    })
                }
                addView(LinearLayout(this@LibraryActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(TextView(this@LibraryActivity).apply {
                        tag = TAG_TITLE
                        textSize = 16f
                        setTextColor(Color.rgb(24, 33, 47))
                        maxLines = 1
                    })
                    addView(TextView(this@LibraryActivity).apply {
                        tag = TAG_URL
                        textSize = 12f
                        setTextColor(Color.rgb(75, 100, 142))
                        maxLines = 1
                        setPadding(0, dp(3), 0, 0)
                    })
                    addView(TextView(this@LibraryActivity).apply {
                        tag = TAG_TIME
                        textSize = 11f
                        setTextColor(Color.rgb(130, 139, 154))
                        setPadding(0, dp(3), 0, 0)
                    })
                }, LinearLayout.LayoutParams(0, -2, 1f))
            }
            val entry = getItem(position)
            view.findViewWithTag<ImageView>(TAG_ICON)?.let { iconView ->
                val icon = iconCache[entry.id] ?: entry.favicon?.let { bytes ->
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.also {
                        iconCache[entry.id] = it
                    }
                }
                if (icon != null) iconView.setImageBitmap(icon) else iconView.setImageResource(R.drawable.ic_public)
            }
            view.findViewWithTag<TextView>(TAG_TITLE).text = entry.title
            view.findViewWithTag<TextView>(TAG_URL).text = entry.url
            view.findViewWithTag<TextView>(TAG_TIME).text =
                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(entry.timestamp))
            return view
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_MODE = "library_mode"
        const val EXTRA_URL = "selected_url"
        const val MODE_BOOKMARKS = "bookmarks"
        const val MODE_HISTORY = "history"
        private const val TAG_TITLE = "entry_title"
        private const val TAG_URL = "entry_url"
        private const val TAG_TIME = "entry_time"
        private const val TAG_ICON = "entry_icon"
    }
}
