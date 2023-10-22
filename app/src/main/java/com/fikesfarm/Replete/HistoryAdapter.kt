package com.fikesfarm.Replete

import android.content.Context
import android.graphics.Color
import android.text.SpannableString
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import android.graphics.Typeface

enum class ItemType {
    INPUT, OUTPUT, ERROR
}

data class Item(val text: SpannableString, val type: ItemType)

fun inflateItem(viewHolder: HistoryAdapter.ViewHolder, item: Item, typeface: Typeface) {
    viewHolder.item.text = item.text
    viewHolder.item.setTypeface(typeface)
    when (item.type) {
        ItemType.INPUT -> viewHolder.item.setTextColor(Color.DKGRAY)
        ItemType.OUTPUT -> viewHolder.item.setTextColor(Color.BLACK)
        ItemType.ERROR -> viewHolder.item.setTextColor(Color.RED)
    }
}

class HistoryAdapter(context: Context, id: Int, val typeface: Typeface, val parent: ListView) :
    ArrayAdapter<Item>(context, id) {

    fun update(item: Item) {
        this.add(item)
        parent.post {
            parent.smoothScrollToPosition(this.count - 1)
        }
    }

    class ViewHolder(val item: TextView)

    override fun getView(position: Int, itemView: View?, parent: ViewGroup): View {

        val item = getItem(position)

        if (itemView == null) {
            val _itemView = LayoutInflater.from(this.context).inflate(R.layout.list_item, parent, false)
            val viewHolder = ViewHolder(_itemView.findViewById(R.id.history_item))

            _itemView.tag = viewHolder

            if (item != null) {
                inflateItem(viewHolder, item, typeface)
            }

            return _itemView
        } else {
            if (item != null) {
                inflateItem(itemView.tag as ViewHolder, item, typeface)
            }
            return itemView
        }
    }
}
