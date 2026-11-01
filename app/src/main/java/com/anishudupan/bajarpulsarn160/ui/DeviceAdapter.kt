package com.anishudupan.bajarpulsarn160.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.anishudupan.bajarpulsarn160.ble.DiscoveredDevice
import com.anishudupan.bajarpulsarn160.databinding.ItemDeviceBinding

class DeviceAdapter(
    private val onItemClick: (DiscoveredDevice) -> Unit
) : RecyclerView.Adapter<DeviceAdapter.ViewHolder>() {

    private var devices: List<DiscoveredDevice> = emptyList()

    fun submitList(newList: List<DiscoveredDevice>) {
        devices = newList
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemDeviceBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(devices[position])
    }

    override fun getItemCount(): Int = devices.size

    inner class ViewHolder(private val binding: ItemDeviceBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(device: DiscoveredDevice) {
            val pairedTag = if (device.isBonded) " [PAIRED]" else ""
            val rssiTag = if (device.rssi != 0) " (${device.rssi} dBm)" else ""
            binding.tvDeviceName.text = "${device.name}$pairedTag$rssiTag"
            binding.tvDeviceAddress.text = device.address
            binding.root.setOnClickListener {
                onItemClick(device)
            }
        }
    }
}
