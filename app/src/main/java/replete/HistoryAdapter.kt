package replete

import android.content.Context
import android.graphics.Color
import android.text.SpannableString
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView

enum class ItemType {
    INPUT, OUTPUT, ERROR
}

data class Item(val text: SpannableString, val type: ItemType)

fun inflateItem(viewHolder: HistoryAdapter.ViewHolder, item: Item) {
    viewHolder.item.text = item.text
    when (item.type) {
        ItemType.INPUT -> viewHolder.item.setTextColor(Color.DKGRAY)
        ItemType.OUTPUT -> viewHolder.item.setTextColor(Color.BLACK)
        ItemType.ERROR -> viewHolder.item.setTextColor(Color.RED)
    }
}

class HistoryAdapter(context: Context, id: Int, val parent: ListView) :
    ArrayAdapter<Item>(context, id) {

    val animation = AnimationUtils.loadAnimation(parent.context, R.anim.entry_appears)

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
                inflateItem(viewHolder, item)
            }

            _itemView.startAnimation(animation)

            return _itemView
        } else {
            if (item != null) {
                inflateItem(itemView.tag as ViewHolder, item)
            }
            return itemView
        }
    }
}
