package com.h4ch1net.ksled.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.h4ch1net.ksled.R
import com.h4ch1net.ksled.ble.ScannedDevice
import com.h4ch1net.ksled.data.NicknameStore

class DeviceListAdapter(
    private val nicknameStore: NicknameStore,
    private val onClick: (ScannedDevice) -> Unit
) : RecyclerView.Adapter<DeviceListAdapter.VH>() {

    private val items = mutableListOf<ScannedDevice>()

    fun submit(devices: List<ScannedDevice>) {
        items.clear()
        items.addAll(devices)
        notifyDataSetChanged()
    }

    class VH(view: android.view.View) : RecyclerView.ViewHolder(view) {
        val name: android.widget.TextView = view.findViewById(R.id.deviceName)
        val address: android.widget.TextView = view.findViewById(R.id.deviceAddress)
        val rssi: android.widget.TextView = view.findViewById(R.id.deviceRssi)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_device, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val d = items[position]
        holder.name.text = nicknameStore.displayName(d.address, d.name)
        holder.address.text = "${d.address} · ${d.profile.prefix}"
        holder.rssi.text = "${d.rssi} dBm"
        holder.itemView.setOnClickListener { onClick(d) }
    }

    override fun getItemCount() = items.size
}
