package com.goodwy.gallery.dialogs

import android.annotation.SuppressLint
import android.app.Activity
import android.content.SharedPreferences
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
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

    fun isItemHidden(activity: Activity, menuTag: String, itemId: Int): Boolean {
        val prefs: SharedPreferences = activity.getSharedPrefs()
        val hiddenSet: Set<String> = prefs.getStringSet(getHiddenKey(menuTag), emptySet()) ?: emptySet()
        return hiddenSet.contains(itemId.toString())
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
        val hiddenSet: MutableSet<String> = HashSet(prefs.getStringSet(getHiddenKey(menuTag), emptySet()) ?: emptySet())

        val map: Map<Int, CustomMenuItem> = originalItems.associateBy { it.id }
        val currentItems: MutableList<CustomMenuItem> = mutableListOf()

        if (orderStr.isNotEmpty()) {
            val savedIds = orderStr.split(",").mapNotNull { it.toIntOrNull() }
            for (id in savedIds) {
                map[id]?.let { currentItems.add(it) }
            }
            for (item in originalItems) {
                if (!currentItems.contains(item)) {
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

        activity.getAlertDialogBuilder()
            .setPositiveButton(com.goodwy.commons.R.string.ok) { _, _ ->
                val orderToSave = currentItems.joinToString(",") { it.id.toString() }
                prefs.edit()
                    .putString(getPrefKey(menuTag), orderToSave)
                    .putStringSet(getHiddenKey(menuTag), hiddenSet)
                    .apply()
                onDismiss()
            }
            .setNeutralButton(com.goodwy.commons.R.string.reset) { _, _ ->
                prefs.edit()
                    .remove(getPrefKey(menuTag))
                    .remove(getHiddenKey(menuTag))
                    .apply()
                activity.toast(R.string.menu_order_reset)
                onDismiss()
            }
            .setNegativeButton(com.goodwy.commons.R.string.cancel, null)
            .apply {
                activity.setupDialogStuff(dialogView, this, R.string.customize_options_menu)
            }
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

            if (item.icon != null) {
                holder.icon.visibility = View.VISIBLE
                holder.icon.setImageDrawable(item.icon)
            } else {
                holder.icon.visibility = View.GONE
            }

            val isHidden = hiddenSet.contains(item.id.toString())
            updateVisibilityState(holder, isHidden)

            holder.hideButton.setOnClickListener {
                val currentPos = holder.bindingAdapterPosition
                if (currentPos != RecyclerView.NO_POSITION) {
                    val currentItem = items[currentPos]
                    val idStr = currentItem.id.toString()
                    val nowHidden = if (hiddenSet.contains(idStr)) {
                        hiddenSet.remove(idStr)
                        false
                    } else {
                        hiddenSet.add(idStr)
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
