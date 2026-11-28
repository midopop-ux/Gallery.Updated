package com.goodwy.gallery.dialogs

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.view.menu.MenuItemImpl
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.goodwy.commons.extensions.getAlertDialogBuilder
import com.goodwy.commons.extensions.getSharedPrefs
import com.goodwy.commons.extensions.setupDialogStuff
import com.goodwy.commons.extensions.toast
import com.goodwy.gallery.R
import java.util.Collections

data class CustomMenuItem(
    val id: Int,
    val title: String,
    val icon: Drawable? = null
)

object MenuCustomizer {

    private fun getPrefKey(menuTag: String) = "menu_customizer_order_$menuTag"
    private fun getHiddenKey(menuTag: String) = "menu_customizer_hidden_$menuTag"

    private fun getItemKey(activity: Activity, itemId: Int): String {
        return try {
            activity.resources.getResourceEntryName(itemId)
        } catch (_: Exception) {
            itemId.toString()
        }
    }

    fun isItemHidden(activity: Activity, menuTag: String, itemId: Int): Boolean {
        val prefs: SharedPreferences = activity.getSharedPrefs()
        val hiddenSet: Set<String> = prefs.getStringSet(getHiddenKey(menuTag), emptySet()) ?: emptySet()
        val entryName = getItemKey(activity, itemId)
        return hiddenSet.contains(entryName) || hiddenSet.contains(itemId.toString())
    }

    private data class SavedSubItem(
        val groupId: Int,
        val itemId: Int,
        val title: CharSequence?,
        val icon: Drawable?,
        val isVisible: Boolean,
        val isEnabled: Boolean,
        val isCheckable: Boolean,
        val isChecked: Boolean,
        val intent: Intent?
    )

    private data class SavedMenuItem(
        val groupId: Int,
        val itemId: Int,
        val title: CharSequence?,
        val titleCondensed: CharSequence?,
        val icon: Drawable?,
        val isVisible: Boolean,
        val isEnabled: Boolean,
        val isCheckable: Boolean,
        val isChecked: Boolean,
        val intent: Intent?,
        val actionView: View?,
        val showAsActionFlags: Int,
        val subItems: List<SavedSubItem>
    )

    @SuppressLint("RestrictedApi")
    private fun getShowAsActionFlags(item: MenuItem): Int {
        if (item is MenuItemImpl) {
            var flags = when {
                item.requiresActionButton() -> MenuItem.SHOW_AS_ACTION_ALWAYS
                item.requestsActionButton() -> MenuItem.SHOW_AS_ACTION_IF_ROOM
                else -> MenuItem.SHOW_AS_ACTION_NEVER
            }
            if (item.showsTextAsAction()) {
                flags = flags or MenuItem.SHOW_AS_ACTION_WITH_TEXT
            }
            return flags
        }
        return MenuItem.SHOW_AS_ACTION_IF_ROOM
    }

    @SuppressLint("RestrictedApi")
    fun applyMenuCustomization(activity: Activity, menuTag: String, menu: Menu) {
        val prefs: SharedPreferences = activity.getSharedPrefs()
        val orderStr: String = prefs.getString(getPrefKey(menuTag), "") ?: ""

        if (orderStr.isNotEmpty()) {
            val savedKeys = orderStr.split(",").filter { it.isNotEmpty() }
            val currentKeys = (0 until menu.size()).mapNotNull { idx ->
                val it = menu.getItem(idx)
                if (it.itemId != R.id.menu_customize_options) getItemKey(activity, it.itemId) else null
            }

            // Compute desired key sequence matching currently inflated menu items
            val keyIndexMap = savedKeys.withIndex().associate { it.value to it.index }
            val numericIndexMap = mutableMapOf<String, Int>()
            for ((idx, key) in savedKeys.withIndex()) {
                numericIndexMap[key] = idx
            }

            fun resolveRank(itemId: Int, origPos: Int): Int {
                if (itemId == R.id.menu_customize_options) return Int.MAX_VALUE
                val key = getItemKey(activity, itemId)
                return keyIndexMap[key] ?: numericIndexMap[itemId.toString()] ?: (10000 + origPos)
            }

            val snapshot = ArrayList<SavedMenuItem>(menu.size())
            var needsRebuild = false
            var lastRank = -1

            for (i in 0 until menu.size()) {
                val item = menu.getItem(i)
                val rank = resolveRank(item.itemId, i)
                if (rank < lastRank) {
                    needsRebuild = true
                }
                lastRank = rank

                val subList = ArrayList<SavedSubItem>()
                if (item.hasSubMenu()) {
                    val sub = item.subMenu
                    if (sub != null) {
                        for (j in 0 until sub.size()) {
                            val s = sub.getItem(j)
                            subList.add(
                                SavedSubItem(
                                    groupId = s.groupId,
                                    itemId = s.itemId,
                                    title = s.title,
                                    icon = s.icon,
                                    isVisible = s.isVisible,
                                    isEnabled = s.isEnabled,
                                    isCheckable = s.isCheckable,
                                    isChecked = s.isChecked,
                                    intent = s.intent
                                )
                            )
                        }
                    }
                }

                snapshot.add(
                    SavedMenuItem(
                        groupId = item.groupId,
                        itemId = item.itemId,
                        title = item.title,
                        titleCondensed = item.titleCondensed,
                        icon = item.icon,
                        isVisible = item.isVisible,
                        isEnabled = item.isEnabled,
                        isCheckable = item.isCheckable,
                        isChecked = item.isChecked,
                        intent = item.intent,
                        actionView = item.actionView,
                        showAsActionFlags = getShowAsActionFlags(item),
                        subItems = subList
                    )
                )
            }

            if (needsRebuild && currentKeys.isNotEmpty()) {
                val sorted = snapshot.withIndex().sortedBy { resolveRank(it.value.itemId, it.index) }
                menu.clear()
                for ((newOrder, entry) in sorted.withIndex()) {
                    val item = entry.value
                    val added: MenuItem = if (item.subItems.isNotEmpty()) {
                        val sub = menu.addSubMenu(item.groupId, item.itemId, newOrder, item.title)
                        for ((subOrder, s) in item.subItems.withIndex()) {
                            val addedSub = sub.add(s.groupId, s.itemId, subOrder, s.title)
                            addedSub.icon = s.icon
                            addedSub.isVisible = s.isVisible
                            addedSub.isEnabled = s.isEnabled
                            addedSub.isCheckable = s.isCheckable
                            addedSub.isChecked = s.isChecked
                            if (s.intent != null) addedSub.intent = s.intent
                        }
                        sub.item
                    } else {
                        menu.add(item.groupId, item.itemId, newOrder, item.title)
                    }
                    added.titleCondensed = item.titleCondensed
                    added.icon = item.icon
                    added.isEnabled = item.isEnabled
                    added.isCheckable = item.isCheckable
                    added.isChecked = item.isChecked
                    if (item.intent != null) added.intent = item.intent
                    if (item.actionView != null) added.setActionView(item.actionView)
                    added.setShowAsAction(item.showAsActionFlags)
                    added.isVisible = item.isVisible
                }
            }
        }

        for (i in 0 until menu.size()) {
            val item = menu.getItem(i)
            if (item.itemId != R.id.menu_customize_options && isItemHidden(activity, menuTag, item.itemId)) {
                item.isVisible = false
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    fun showDialog(
        activity: Activity,
        menuTag: String,
        originalItems: List<CustomMenuItem>,
        onDismiss: () -> Unit
    ) {
        val prefs: SharedPreferences = activity.getSharedPrefs()
        val orderStr: String = prefs.getString(getPrefKey(menuTag), "") ?: ""
        val rawHiddenSet: Set<String> = prefs.getStringSet(getHiddenKey(menuTag), emptySet()) ?: emptySet()

        // Normalize hidden set to stable resource entry keys
        val hiddenSet: MutableSet<String> = HashSet()
        for (item in originalItems) {
            val key = getItemKey(activity, item.id)
            if (rawHiddenSet.contains(key) || rawHiddenSet.contains(item.id.toString())) {
                hiddenSet.add(key)
            }
        }

        val byKey = originalItems.associateBy { getItemKey(activity, it.id) }
        val byNumericId = originalItems.associateBy { it.id.toString() }
        val currentItems: MutableList<CustomMenuItem> = mutableListOf()
        val addedKeys = HashSet<String>()

        if (orderStr.isNotEmpty()) {
            val savedTokens = orderStr.split(",").filter { it.isNotEmpty() }
            for (token in savedTokens) {
                val resolved = byKey[token] ?: byNumericId[token]
                if (resolved != null) {
                    val resolvedKey = getItemKey(activity, resolved.id)
                    if (addedKeys.add(resolvedKey)) {
                        currentItems.add(resolved)
                    }
                }
            }
            for (item in originalItems) {
                val key = getItemKey(activity, item.id)
                if (addedKeys.add(key)) {
                    currentItems.add(item)
                }
            }
        } else {
            currentItems.addAll(originalItems)
        }

        val dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_customize_menu, null)
        val recyclerView = dialogView.findViewById<RecyclerView>(R.id.dialogMenuList)

        recyclerView.layoutManager = LinearLayoutManager(activity)
        val adapter = MenuCustomizerAdapter(activity, currentItems, hiddenSet)
        recyclerView.adapter = adapter

        val itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val fromPos = viewHolder.bindingAdapterPosition
                val toPos = target.bindingAdapterPosition
                if (fromPos != RecyclerView.NO_POSITION && toPos != RecyclerView.NO_POSITION) {
                    Collections.swap(currentItems, fromPos, toPos)
                    adapter.notifyItemMoved(fromPos, toPos)
                    return true
                }
                return false
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}
            override fun isLongPressDragEnabled() = true
        })

        itemTouchHelper.attachToRecyclerView(recyclerView)
        adapter.touchHelper = itemTouchHelper

        val builder = activity.getAlertDialogBuilder()
            .setPositiveButton(com.goodwy.commons.R.string.ok) { _, _ ->
                val orderToSave = currentItems.joinToString(",") { getItemKey(activity, it.id) }
                prefs.edit()
                    .putString(getPrefKey(menuTag), orderToSave)
                    .putStringSet(getHiddenKey(menuTag), hiddenSet)
                    .apply()
                onDismiss()
            }
            .setNeutralButton(R.string.reset) { _, _ ->
                prefs.edit()
                    .remove(getPrefKey(menuTag))
                    .remove(getHiddenKey(menuTag))
                    .apply()
                activity.toast(R.string.menu_order_reset)
                onDismiss()
            }
            .setNegativeButton(com.goodwy.commons.R.string.cancel, null)

        activity.setupDialogStuff(dialogView, builder, R.string.customize_options_menu)
    }

    private class MenuCustomizerAdapter(
        private val activity: Activity,
        private val items: List<CustomMenuItem>,
        private val hiddenSet: MutableSet<String>
    ) : RecyclerView.Adapter<MenuCustomizerAdapter.ViewHolder>() {

        var touchHelper: ItemTouchHelper? = null

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val icon: ImageView = view.findViewById(R.id.optionIcon)
            val text: TextView = view.findViewById(R.id.optionText)
            val hideButton: ImageButton = view.findViewById(R.id.optionHideButton)
            val dragHandle: ImageView = view.findViewById(R.id.optionDragHandle)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_customize_menu_option, parent, false)
            return ViewHolder(view)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.text.text = item.title

            val drawableCopy = item.icon?.constantState?.newDrawable()?.mutate() ?: item.icon
            if (drawableCopy != null) {
                holder.icon.visibility = View.VISIBLE
                holder.icon.setImageDrawable(drawableCopy)
            } else {
                holder.icon.visibility = View.GONE
            }

            val itemKey = getItemKey(activity, item.id)
            val isHidden = hiddenSet.contains(itemKey)
            updateVisibilityState(holder, isHidden)

            holder.hideButton.setOnClickListener {
                val currentPos = holder.bindingAdapterPosition
                if (currentPos != RecyclerView.NO_POSITION) {
                    val currentItem = items[currentPos]
                    val key = getItemKey(activity, currentItem.id)
                    val nowHidden = if (hiddenSet.contains(key)) {
                        hiddenSet.remove(key)
                        false
                    } else {
                        hiddenSet.add(key)
                        true
                    }
                    updateVisibilityState(holder, nowHidden)
                }
            }

            holder.dragHandle.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    touchHelper?.startDrag(holder)
                }
                false
            }
        }

        private fun updateVisibilityState(holder: ViewHolder, isHidden: Boolean) {
            val alpha = if (isHidden) 0.4f else 1.0f
            holder.text.alpha = alpha
            holder.icon.alpha = alpha
            holder.hideButton.setImageResource(if (isHidden) R.drawable.ic_eye_off else R.drawable.ic_eye_on)
        }

        override fun getItemCount() = items.size
    }
}
