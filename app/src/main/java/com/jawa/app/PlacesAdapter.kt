package com.jawa.app

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.util.Collections

/** Saved places in Settings: drag ≡ to reorder, ✕ to remove. */
class PlacesAdapter(
    val items: MutableList<Place>,
    private val onRemove: (Place) -> Unit,
    private val onStartDrag: (RecyclerView.ViewHolder) -> Unit,
) : RecyclerView.Adapter<PlacesAdapter.Holder>() {

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.name)
        val subtitle: TextView = view.findViewById(R.id.subtitle)
        val drag: View = view.findViewById(R.id.drag)
        val remove: View = view.findViewById(R.id.remove)
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_place, parent, false))

    @SuppressLint("ClickableViewAccessibility")
    override fun onBindViewHolder(holder: Holder, position: Int) {
        val p = items[position]
        holder.name.text = p.name
        holder.subtitle.text = p.subtitle
        holder.remove.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) onRemove(items[pos])
        }
        holder.drag.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN) onStartDrag(holder)
            false
        }
    }

    fun move(from: Int, to: Int) {
        Collections.swap(items, from, to)
        notifyItemMoved(from, to)
    }

    fun removeItem(place: Place) {
        val i = items.indexOfFirst { it.id == place.id }
        if (i >= 0) {
            items.removeAt(i)
            notifyItemRemoved(i)
        }
    }

    fun addItem(place: Place) {
        items.add(place)
        notifyItemInserted(items.size - 1)
    }
}
