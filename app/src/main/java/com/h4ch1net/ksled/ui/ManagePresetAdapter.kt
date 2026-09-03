package com.h4ch1net.ksled.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.h4ch1net.ksled.R
import com.h4ch1net.ksled.model.ColorPreset

class ManagePresetAdapter(
    private val items: MutableList<ColorPreset>,
    private val onDelete: (ColorPreset) -> Unit
) : RecyclerView.Adapter<ManagePresetAdapter.VH>() {

    fun replace(newItems: List<ColorPreset>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    class VH(view: android.view.View) : RecyclerView.ViewHolder(view) {
        val swatch: android.view.View = view.findViewById(R.id.swatch)
        val name: android.widget.TextView = view.findViewById(R.id.name)
        val deleteAction: android.widget.TextView = view.findViewById(R.id.deleteAction)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_manage_preset, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val p = items[position]
        holder.swatch.setBackgroundColor(Color.rgb(p.r, p.g, p.b))
        holder.name.text = "${p.name} (R:${p.r} G:${p.g} B:${p.b})"
        holder.deleteAction.setOnClickListener {
            onDelete(p)
            items.removeAt(holder.bindingAdapterPosition)
            notifyDataSetChanged()
        }
    }

    override fun getItemCount() = items.size
}
