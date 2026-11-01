package com.anishudupan.bajarpulsarn160

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.anishudupan.bajarpulsarn160.ble.ConnectionState
import com.anishudupan.bajarpulsarn160.ble.PulsarBleManager
import com.anishudupan.bajarpulsarn160.databinding.FragmentFirstBinding
import com.anishudupan.bajarpulsarn160.protocol.PulsarProtocol
import com.anishudupan.bajarpulsarn160.ui.DeviceAdapter
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class FirstFragment : Fragment() {

    private var _binding: FragmentFirstBinding? = null
    private val binding get() = _binding!!

    private lateinit var bleManager: PulsarBleManager
    private lateinit var deviceAdapter: DeviceAdapter

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            bleManager.startScan()
        } else {
            Toast.makeText(requireContext(), "BLE permissions are required", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFirstBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        bleManager = PulsarBleManager(requireContext().applicationContext)

        deviceAdapter = DeviceAdapter { discovered ->
            bleManager.connect(discovered.device)
        }

        binding.rvDevices.layoutManager = LinearLayoutManager(requireContext())
        binding.rvDevices.adapter = deviceAdapter

        binding.tvLogs.movementMethod = ScrollingMovementMethod()

        setupSpinnersAndControls()
        setupClickListeners()
        observeState()
    }

    private fun setupSpinnersAndControls() {
        // Battery Bars Spinner (0..4)
        val batteryOptions = arrayOf("0 Bars", "1 Bar", "2 Bars", "3 Bars", "4 Bars (Full)")
        val batteryAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, batteryOptions)
        binding.spinnerBatteryBars.adapter = batteryAdapter
        binding.spinnerBatteryBars.setSelection(4)

        // Signal Bars Spinner (0..4 Max for N160 LCD)
        val signalOptions = arrayOf("0 Bars", "1 Bar", "2 Bars", "3 Bars", "4 Bars (Full)")
        val signalAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, signalOptions)
        binding.spinnerSignalBars.adapter = signalAdapter
        binding.spinnerSignalBars.setSelection(4)

        // Alert Type Spinner
        val alertOptions = arrayOf("1 = SMS", "2 = WhatsApp", "3 = Social Alert")
        val alertAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, alertOptions)
        binding.spinnerAlertType.adapter = alertAdapter
        binding.spinnerAlertType.setSelection(1)

        // Nav Turn Spinner (Official PrimaryTurns Codes)
        val navTurnOptions = arrayOf(
            "Straight (71)", "Turn Left (73)", "Turn Right (74)",
            "Slight Left (67)", "Slight Right (68)", "U-Turn (79)", "Destination Reached (72)"
        )
        val navAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, navTurnOptions)
        binding.spinnerNavIcon.adapter = navAdapter
        binding.spinnerNavIcon.setSelection(1) // Default Left
    }

    private fun setupClickListeners() {
        binding.btnScan.setOnClickListener { checkPermissionsAndScan() }
        binding.btnConnectPaired.setOnClickListener { checkPermissionsAndConnectPaired() }
        binding.btnDisconnect.setOnClickListener { bleManager.disconnect() }

        // 1. Indicators Apply Button
        binding.btnApplyIndicators.setOnClickListener {
            val battery = binding.spinnerBatteryBars.selectedItemPosition.coerceIn(0, 4)
            val signal = binding.spinnerSignalBars.selectedItemPosition.coerceIn(0, 4)
            bleManager.updateIndicators(battery, signal)
        }

        // 2. Phone Call Buttons
        binding.btnTriggerIncomingCall.setOnClickListener {
            val name = binding.etCallerName.text?.toString()?.takeIf { it.isNotBlank() } ?: "Jane Smith"
            val phone = binding.etPhoneNumber.text?.toString()?.takeIf { it.isNotBlank() } ?: "+919876543210"
            bleManager.triggerDummyIncomingCall(name, phone)
        }

        binding.btnEndCall.setOnClickListener {
            bleManager.endDummyCall()
        }

        binding.btnSendMissedCall.setOnClickListener {
            val name = binding.etCallerName.text?.toString()?.takeIf { it.isNotBlank() } ?: "Jane Smith"
            val phone = binding.etPhoneNumber.text?.toString()?.takeIf { it.isNotBlank() } ?: "+919876543210"
            bleManager.sendDummyMissedCall(name, phone)
        }

        // 3. Alert Button
        binding.btnSendAlert.setOnClickListener {
            val alertType = binding.spinnerAlertType.selectedItemPosition + 1
            val msg = binding.etAlertMessage.text?.toString()?.takeIf { it.isNotBlank() } ?: "WhatsApp: Hello!"
            bleManager.sendDummyAlert(alertType, msg)
        }

        // 4. TBT Navigation Button
        binding.btnSendTbtInfo.setOnClickListener {
            val isNavActive = binding.switchNavActive.isChecked
            val turn = when (binding.spinnerNavIcon.selectedItemPosition) {
                0 -> PulsarProtocol.PrimaryTurn.STRAIGHT
                1 -> PulsarProtocol.PrimaryTurn.TURN_LEFT
                2 -> PulsarProtocol.PrimaryTurn.TURN_RIGHT
                3 -> PulsarProtocol.PrimaryTurn.SLIGHT_LEFT
                4 -> PulsarProtocol.PrimaryTurn.SLIGHT_RIGHT
                5 -> PulsarProtocol.PrimaryTurn.U_TURN
                6 -> PulsarProtocol.PrimaryTurn.DESTINATION_REACHED
                else -> PulsarProtocol.PrimaryTurn.TURN_LEFT
            }
            val distance = binding.etNavDistance.text?.toString()?.toIntOrNull() ?: 250
            val street = binding.etNavStreet.text?.toString()?.takeIf { it.isNotBlank() } ?: "Turn Left on MG Road"
            bleManager.sendTbtInfo(turn, distance, street, isNavActive)
        }

        // 5. Contacts Sync Buttons
        binding.btnSyncFavContacts.setOnClickListener {
            val contacts = listOf(
                PulsarProtocol.ContactSlot("Jane Smith", "9876543210", status = 1),
                PulsarProtocol.ContactSlot("Alex Brown", "9123456789", status = 1),
                PulsarProtocol.ContactSlot("John Doe", "1234567890", status = 3)
            )
            bleManager.syncFavContacts(contacts)
        }

        binding.btnSyncRecentCalls.setOnClickListener {
            val calls = listOf(
                PulsarProtocol.ContactSlot("Jane Smith", "9876543210", status = 1), // Incoming
                PulsarProtocol.ContactSlot("John Doe", "1234567890", status = 3),   // Missed
                PulsarProtocol.ContactSlot("Alex Brown", "9123456789", status = 2)  // Outgoing
            )
            bleManager.syncRecentCalls(calls)
        }
    }

    private fun checkPermissionsAndScan() {
        val permissions = listOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(requireContext(), it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isEmpty()) {
            bleManager.startScan()
        } else {
            requestPermissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun checkPermissionsAndConnectPaired() {
        val permissions = listOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(requireContext(), it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isEmpty()) {
            bleManager.connectFirstPairedPulsar()
        } else {
            requestPermissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            bleManager.connectionState.collectLatest { state ->
                binding.tvConnectionState.text = "Status: ${state.name}"
                binding.btnScan.isEnabled = (state == ConnectionState.DISCONNECTED)
                binding.btnConnectPaired.isEnabled = (state == ConnectionState.DISCONNECTED)
                binding.btnDisconnect.isEnabled = (state == ConnectionState.CONNECTED || state == ConnectionState.CONNECTING)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            bleManager.discoveredDevices.collectLatest { list ->
                deviceAdapter.submitList(list)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            bleManager.generalState.collectLatest { state ->
                binding.tvBatteryStatus.text = "Cluster Battery: ${state.batteryBars}/4 Bars | Signal: ${state.signalBars}/4 Bars"
                binding.tvCallState.text = "Call State: ${state.callState.name} ${if (state.callerName.isNotBlank()) "(${state.callerName})" else ""}"
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            bleManager.lastControlEvent.collectLatest { event ->
                if (event != null) {
                    val summary = "Accept=${event.callAcceptToggle} Reject=${event.callRejectToggle}"
                    binding.tvHandlebarEvent.text = "Last Handlebar Switch Event: $summary"
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            bleManager.logs.collectLatest { logList ->
                val text = logList.takeLast(40).joinToString("\n")
                binding.tvLogs.text = text
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        bleManager.disconnect()
        _binding = null
    }
}
