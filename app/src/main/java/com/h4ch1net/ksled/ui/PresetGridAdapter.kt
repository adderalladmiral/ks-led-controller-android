package com.h4ch1net.ksled.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.h4ch1net.ksled.R
import com.h4ch1net.ksled.model.ColorPreset

class PresetGridAdapter(
    private val onClick: (ColorPreset) -> Unit
) : RecyclerView.Adapter<PresetGridAdapter.VH>() {

    private val items = mutableListOf<ColorPreset>()

    fun submit(presets: List<ColorPreset>) {
        items.clear()
        items.addAll(presets)
        notifyDataSetChanged()
    }

    class VH(view: android.view.View) : RecyclerView.ViewHolder(view) {
        val swatch: android.view.View = view.findViewById(R.id.swatch)
        val name: android.widget.TextView = view.findViewById(R.id.presetName)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_preset, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val p = items[position]
        holder.swatch.setBackgroundColor(Color.rgb(p.r, p.g, p.b))
        holder.name.text = p.name
        holder.itemView.setOnClickListener { onClick(p) }
    }

    override fun getItemCount() = items.size
}
