package com.anishudupan.bajarpulsarn160.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.util.Log
import com.anishudupan.bajarpulsarn160.battery.BatteryMonitor
import com.anishudupan.bajarpulsarn160.protocol.PulsarProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.LinkedList
import java.util.Queue

enum class ConnectionState {
    DISCONNECTED,
    SCANNING,
    CONNECTING,
    CONNECTED,
    DISCONNECTING
}

enum class OpType {
    WRITE,
    READ
}

data class DiscoveredDevice(
    val device: BluetoothDevice,
    val name: String,
    val address: String,
    val rssi: Int,
    val isBonded: Boolean = false
)

data class PendingOperation(
    val type: OpType,
    val characteristic: BluetoothGattCharacteristic,
    val payload: ByteArray? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as PendingOperation
        if (type != other.type) return false
        if (characteristic != other.characteristic) return false
        if (payload != null) {
            if (other.payload == null) return false
            if (!payload.contentEquals(other.payload)) return false
        } else if (other.payload != null) return false
        return true
    }

    override fun hashCode(): Int {
        var result = type.hashCode()
        result = 31 * result + characteristic.hashCode()
        result = 31 * result + (payload?.contentHashCode() ?: 0)
        return result
    }
}

@SuppressLint("MissingPermission")
class PulsarBleManager(private val context: Context) {

    companion object {
        private const val TAG = "PulsarBleManager"
    }

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter

    private val batteryMonitor = BatteryMonitor(context)

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var heartbeatJob: Job? = null
    private var controlPollingJob: Job? = null

    private var bluetoothGatt: BluetoothGatt? = null
    private var generalChar: BluetoothGattCharacteristic? = null
    private var controlChar: BluetoothGattCharacteristic? = null
    private var missedCallChar: BluetoothGattCharacteristic? = null
    private var favContactsChar: BluetoothGattCharacteristic? = null
    private var recentCallsChar: BluetoothGattCharacteristic? = null
    private var alertsChar: BluetoothGattCharacteristic? = null

    private var lastControlPacket: ByteArray? = null
    private val gattMutex = Mutex()
    private var isServicesDiscovered = false
    private var isMTUIncreased = false

    private var isControlBootstrap = true
    private var lastCallAcceptToggle: Byte? = null
    private var lastCallRejectToggle: Byte? = null
    private var lastCallRejectSmsToggle: Byte? = null

    private val gattOperationQueue: Queue<PendingOperation> = LinkedList()
    private var isQueueProcessing = false

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _discoveredDevices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<DiscoveredDevice>> = _discoveredDevices.asStateFlow()

    private val _generalState = MutableStateFlow(PulsarProtocol.GeneralState())
    val generalState: StateFlow<PulsarProtocol.GeneralState> = _generalState.asStateFlow()

    private val _lastControlEvent = MutableStateFlow<PulsarProtocol.ControlEvent?>(null)
    val lastControlEvent: StateFlow<PulsarProtocol.ControlEvent?> = _lastControlEvent.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    init {
        scope.launch {
            batteryMonitor.batteryInfo.collect { info ->
                addLog("DEBUG System Battery Level: ${info.percentage}%")
            }
        }
    }

    fun addLog(msg: String) {
        val debugMsg = if (msg.startsWith("DEBUG")) msg else "DEBUG $msg"
        Log.d(TAG, debugMsg)
        val current = _logs.value.toMutableList()
        if (current.size > 100) current.removeAt(0)
        current.add(debugMsg)
        _logs.value = current
    }

    fun startScan() {
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            addLog("DEBUG Error: Bluetooth is disabled or unavailable")
            return
        }

        val scanner = adapter.bluetoothLeScanner
        val currentList = mutableListOf<DiscoveredDevice>()

        try {
            val bonded = adapter.bondedDevices
            if (!bonded.isNullOrEmpty()) {
                for (device in bonded) {
                    val dName = device.name ?: "Paired Device"
                    currentList.add(DiscoveredDevice(device, dName, device.address, 0, isBonded = true))
                    addLog("DEBUG Found Paired Bluetooth Device: $dName [${device.address}]")
                }
            }
        } catch (e: Exception) {
            addLog("DEBUG Error checking bonded devices: ${e.message}")
        }

        _discoveredDevices.value = currentList
        _connectionState.value = ConnectionState.SCANNING

        if (scanner == null) {
            addLog("DEBUG Warning: BLE Scanner unavailable, showing paired devices only")
            return
        }

        addLog("DEBUG Starting BLE Scan for active advertisements...")

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            scanner.startScan(null, settings, scanCallback)
        } catch (ex: Exception) {
            addLog("DEBUG Scan error: ${ex.message}")
        }
    }

    fun stopScan() {
        val scanner = bluetoothAdapter?.bluetoothLeScanner
        if (scanner != null) {
            try {
                scanner.stopScan(scanCallback)
            } catch (e: Exception) {
                addLog("DEBUG Error stopping scan: ${e.message}")
            }
        }
        if (_connectionState.value == ConnectionState.SCANNING) {
            _connectionState.value = ConnectionState.DISCONNECTED
        }
        addLog("DEBUG Stopped BLE Scan")
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result?.device?.let { device ->
                val deviceName = result.scanRecord?.deviceName ?: device.name ?: "Unknown"
                val address = device.address
                val rssi = result.rssi

                val current = _discoveredDevices.value.toMutableList()
                val existingIndex = current.indexOfFirst { it.address == address }
                if (existingIndex >= 0) {
                    val existing = current[existingIndex]
                    current[existingIndex] = existing.copy(
                        name = if (deviceName != "Unknown") deviceName else existing.name,
                        rssi = rssi
                    )
                } else {
                    val isBonded = (device.bondState == BluetoothDevice.BOND_BONDED)
                    current.add(DiscoveredDevice(device, deviceName, address, rssi, isBonded))
                    addLog("DEBUG Discovered BLE Device: $deviceName [$address] RSSI=$rssi")
                }
                _discoveredDevices.value = current
            }
        }

        override fun onScanFailed(errorCode: Int) {
            addLog("DEBUG Scan failed with error code: $errorCode")
            if (_discoveredDevices.value.isEmpty()) {
                _connectionState.value = ConnectionState.DISCONNECTED
            }
        }
    }

    fun connectFirstPairedPulsar(): Boolean {
        val adapter = bluetoothAdapter ?: return false
        try {
            val bonded = adapter.bondedDevices
            if (!bonded.isNullOrEmpty()) {
                val pulsarDevice = bonded.find { device ->
                    val name = device.name ?: ""
                    name.contains("PULSAR", ignoreCase = true) || name.contains("N160", ignoreCase = true)
                } ?: bonded.firstOrNull()

                if (pulsarDevice != null) {
                    addLog("DEBUG Found paired device '${pulsarDevice.name}' [${pulsarDevice.address}]. Connecting...")
                    connect(pulsarDevice)
                    return true
                }
            }
        } catch (e: Exception) {
            addLog("DEBUG Error finding paired device: ${e.message}")
        }
        addLog("DEBUG No paired Pulsar device found in system Bluetooth settings.")
        return false
    }

    fun connect(device: BluetoothDevice) {
        if (_connectionState.value == ConnectionState.CONNECTED) {
            addLog("DEBUG Already connected to ${device.name ?: device.address}")
            return
        }
        if (_connectionState.value == ConnectionState.CONNECTING) {
            addLog("DEBUG Connection already in progress to ${device.name ?: device.address}")
            return
        }

        stopScan()
        disconnect()
        _connectionState.value = ConnectionState.CONNECTING
        addLog("DEBUG Connecting to ${device.name ?: device.address} via BLE GATT (TRANSPORT_LE)...")

        @Suppress("DEPRECATION")
        bluetoothGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        stopHeartbeat()
        stopControlPolling()
        batteryMonitor.stop()
        val gatt = bluetoothGatt
        bluetoothGatt = null
        if (gatt != null) {
            _connectionState.value = ConnectionState.DISCONNECTING
            addLog("DEBUG Disconnecting GATT...")
            try {
                gatt.disconnect()
                gatt.close()
            } catch (e: Exception) {
                addLog("DEBUG Error closing GATT: ${e.message}")
            }
        }
        synchronized(gattOperationQueue) {
            gattOperationQueue.clear()
            isQueueProcessing = false
        }
        isServicesDiscovered = false
        isMTUIncreased = false
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                isServicesDiscovered = false
                isMTUIncreased = false
                addLog("DEBUG GATT Connected (Status: $status). Requesting MTU 256...")
                val mtuReq = gatt.requestMtu(256)
                if (!mtuReq) {
                    addLog("DEBUG requestMtu returned false, discovering services directly...")
                    gatt.discoverServices()
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                addLog("DEBUG GATT Disconnected. Status: $status")
                stopHeartbeat()
                stopControlPolling()
                batteryMonitor.stop()
                isServicesDiscovered = false
                isMTUIncreased = false
                _connectionState.value = ConnectionState.DISCONNECTED
                try {
                    bluetoothGatt?.close()
                } catch (_: Exception) {}
                bluetoothGatt = null
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            addLog("DEBUG MTU changed to $mtu (Status: $status).")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                isMTUIncreased = true
            }
            if (!isServicesDiscovered) {
                addLog("DEBUG Discovering services...")
                gatt.discoverServices()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                addLog("DEBUG Service discovery failed: $status")
                disconnect()
                return
            }

            if (isServicesDiscovered) {
                addLog("DEBUG Services already discovered, skipping duplicate trigger.")
                return
            }
            isServicesDiscovered = true

            addLog("DEBUG --- GATT SERVICES DISCOVERED ---")
            for (srv in gatt.services) {
                addLog("DEBUG Service: ${srv.uuid}")
                for (ch in srv.characteristics) {
                    val descList = ch.descriptors.map { desc -> desc.uuid.toString() }.joinToString(", ")
                    addLog("DEBUG   Char: ${ch.uuid} | Props: ${ch.properties} | Descriptors: [$descList]")
                }
            }
            addLog("DEBUG --------------------------------")

            val service = gatt.getService(PulsarProtocol.SERVICE_UUID)
            if (service == null) {
                addLog("DEBUG Error: Pulsar Primary Service not found (${PulsarProtocol.SERVICE_UUID})")
                disconnect()
                return
            }

            generalChar = service.getCharacteristic(PulsarProtocol.CHAR_GENERAL_UUID)
            controlChar = service.getCharacteristic(PulsarProtocol.CHAR_CONTROL_UUID)
            missedCallChar = service.getCharacteristic(PulsarProtocol.CHAR_MISSED_CALL_UUID)
            favContactsChar = service.getCharacteristic(PulsarProtocol.CHAR_FAV_CONTACTS_UUID)
            recentCallsChar = service.getCharacteristic(PulsarProtocol.CHAR_RECENT_CALLS_UUID)
            alertsChar = service.getCharacteristic(PulsarProtocol.CHAR_ALERTS_INFO_UUID)

            if (generalChar == null) {
                addLog("DEBUG Error: GENERAL characteristic missing")
            }
            if (controlChar == null) {
                addLog("DEBUG Error: CONTROL characteristic missing")
            }

            scope.launch {
                delay(2500)
                if (!isMTUIncreased && _connectionState.value == ConnectionState.CONNECTING) {
                    isMTUIncreased = true
                    addLog("DEBUG MTU callback fallback timeout — enabling writes")
                }
            }

            onHandshakeComplete()
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            Log.d(TAG, "DEBUG onCharacteristicWrite UUID=${characteristic.uuid} status=$status")
            synchronized(gattOperationQueue) {
                if (gattOperationQueue.isNotEmpty()) {
                    gattOperationQueue.poll()
                }
            }
            processQueueHead()
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            Log.d(TAG, "DEBUG onCharacteristicRead UUID=${characteristic.uuid} status=$status")
            synchronized(gattOperationQueue) {
                if (gattOperationQueue.isNotEmpty()) {
                    gattOperationQueue.poll()
                }
            }
            if (status == BluetoothGatt.GATT_SUCCESS && characteristic.uuid == PulsarProtocol.CHAR_CONTROL_UUID) {
                @Suppress("DEPRECATION")
                val data = characteristic.value ?: return
                handleControlData(data)
            }
            processQueueHead()
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            Log.d(TAG, "DEBUG onCharacteristicRead UUID=${characteristic.uuid} status=$status")
            synchronized(gattOperationQueue) {
                if (gattOperationQueue.isNotEmpty()) {
                    gattOperationQueue.poll()
                }
            }
            if (status == BluetoothGatt.GATT_SUCCESS && characteristic.uuid == PulsarProtocol.CHAR_CONTROL_UUID) {
                handleControlData(value)
            }
            processQueueHead()
        }
    }

    private fun queueWrite(char: BluetoothGattCharacteristic?, data: ByteArray) {
        val gatt = bluetoothGatt ?: return
        if (char == null) return

        val pending = PendingOperation(OpType.WRITE, char, data.copyOf())

        if (char.uuid == PulsarProtocol.CHAR_GENERAL_UUID) {
            synchronized(gattOperationQueue) {
                val iterator = gattOperationQueue.iterator()
                var isFirst = true
                while (iterator.hasNext()) {
                    val next = iterator.next()
                    if (isFirst) {
                        isFirst = false
                    } else if (next.type == OpType.WRITE && next.characteristic.uuid == PulsarProtocol.CHAR_GENERAL_UUID) {
                        iterator.remove()
                    }
                }
            }
        }

        synchronized(gattOperationQueue) {
            gattOperationQueue.add(pending)
            if (gattOperationQueue.size == 1 && !isQueueProcessing) {
                processQueueHead()
            }
        }
    }

    private fun queueRead(char: BluetoothGattCharacteristic?) {
        val gatt = bluetoothGatt ?: return
        if (char == null) return

        val pending = PendingOperation(OpType.READ, char)

        synchronized(gattOperationQueue) {
            if (!gattOperationQueue.any { it.type == OpType.READ && it.characteristic.uuid == char.uuid }) {
                gattOperationQueue.add(pending)
                if (gattOperationQueue.size == 1 && !isQueueProcessing) {
                    processQueueHead()
                }
            }
        }
    }

    private fun processQueueHead() {
        val gatt = bluetoothGatt
        if (gatt == null) {
            isQueueProcessing = false
            return
        }

        val pending = synchronized(gattOperationQueue) {
            if (gattOperationQueue.isEmpty()) null else gattOperationQueue.peek()
        }

        if (pending == null) {
            isQueueProcessing = false
            return
        }
        isQueueProcessing = true

        val char = pending.characteristic

        if (pending.type == OpType.WRITE && pending.payload != null) {
            val data = pending.payload
            val writeType = if ((char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            } else {
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            }

            @Suppress("DEPRECATION")
            char.value = data
            @Suppress("DEPRECATION")
            char.writeType = writeType

            val resCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(char, data, writeType)
            } else {
                @Suppress("DEPRECATION")
                if (gatt.writeCharacteristic(char)) BluetoothStatusCodes.SUCCESS else -1
            }

            Log.d(TAG, "DEBUG Write characteristic ${char.uuid} len=${data.size} type=$writeType resCode=$resCode")

            if (resCode == BluetoothStatusCodes.SUCCESS && writeType == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) {
                scope.launch {
                    delay(40)
                    synchronized(gattOperationQueue) {
                        if (gattOperationQueue.isNotEmpty() && gattOperationQueue.peek() == pending) {
                            gattOperationQueue.poll()
                        }
                    }
                    processQueueHead()
                }
            }
        } else if (pending.type == OpType.READ) {
            val resCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.readCharacteristic(char)
            } else {
                @Suppress("DEPRECATION")
                if (gatt.readCharacteristic(char)) BluetoothStatusCodes.SUCCESS else -1
            }

            Log.d(TAG, "DEBUG Read characteristic ${char.uuid} resCode=$resCode")
            if (resCode != BluetoothStatusCodes.SUCCESS && resCode != 0) {
                synchronized(gattOperationQueue) {
                    if (gattOperationQueue.isNotEmpty() && gattOperationQueue.peek() == pending) {
                        gattOperationQueue.poll()
                    }
                }
                processQueueHead()
            }
        }
    }

    private fun handleControlData(data: ByteArray) {
        lastControlPacket = data.copyOf()
        val event = PulsarProtocol.parseControlPacket(data)
        if (event != null) {
            _lastControlEvent.value = event

            if (isControlBootstrap) {
                lastCallAcceptToggle = event.callAcceptToggle
                lastCallRejectToggle = event.callRejectToggle
                lastCallRejectSmsToggle = event.callRejectWithSmsToggle
                isControlBootstrap = false
                addLog("DEBUG Control Bootstrapped: Accept=${event.callAcceptToggle} Reject=${event.callRejectToggle} RejectSms=${event.callRejectWithSmsToggle}")
                return
            }

            val acceptChanged = (lastCallAcceptToggle != null && event.callAcceptToggle != lastCallAcceptToggle)
            val rejectChanged = (lastCallRejectToggle != null && event.callRejectToggle != lastCallRejectToggle)
            val rejectSmsChanged = (lastCallRejectSmsToggle != null && event.callRejectWithSmsToggle != lastCallRejectSmsToggle)

            lastCallAcceptToggle = event.callAcceptToggle
            lastCallRejectToggle = event.callRejectToggle
            lastCallRejectSmsToggle = event.callRejectWithSmsToggle

            val currentCallState = _generalState.value.callState

            addLog("DEBUG CONTROL Read Payload: Accept=${event.callAcceptToggle} Reject=${event.callRejectToggle} RejectSms=${event.callRejectWithSmsToggle} (State=${currentCallState.name})")

            // Exact match for Controls.java:
            // 1. callAccept (bArr[1]) -> Answers ringing call only (aCallP)
            if (acceptChanged && currentCallState == PulsarProtocol.CallState.INCOMING_CALL) {
                addLog("DEBUG Call Accept Toggle Changed -> Answering Ringing Call (ACTIVE_CALL)")
                _generalState.value = _generalState.value.copy(callState = PulsarProtocol.CallState.ACTIVE_CALL)
                generalChar?.let { gen ->
                    val packet = PulsarProtocol.buildGeneralPacket(_generalState.value, lastControlPacket)
                    queueWrite(gen, packet)
                }
            }

            // 2. callReject / callRejectWithSms (bArr[2] / bArr[3] - Long Press 3-4s) -> Rejects ringing call or ends active call (rejectCallP)
            if (rejectChanged || rejectSmsChanged) {
                if (currentCallState == PulsarProtocol.CallState.INCOMING_CALL || currentCallState == PulsarProtocol.CallState.ACTIVE_CALL) {
                    addLog("DEBUG Call Reject Toggle Changed -> Ending/Rejecting Call")
                    endDummyCall()
                }
            }
        }
    }

    private fun startControlPolling() {
        if (controlPollingJob?.isActive == true) return
        val ctrl = controlChar ?: return

        addLog("DEBUG Targeted BLE CONTROL Polling Started (Incoming/Active Call)")
        controlPollingJob = scope.launch {
            while (isActive && _connectionState.value == ConnectionState.CONNECTED) {
                val state = _generalState.value.callState
                if (state == PulsarProtocol.CallState.INCOMING_CALL || state == PulsarProtocol.CallState.ACTIVE_CALL) {
                    queueRead(ctrl)
                } else {
                    addLog("DEBUG Call inactive -> Stopping CONTROL Polling")
                    break
                }
                delay(300)
            }
        }
    }

    private fun stopControlPolling() {
        controlPollingJob?.cancel()
        controlPollingJob = null
    }

    private fun onHandshakeComplete() {
        if (_connectionState.value == ConnectionState.CONNECTED) return
        _connectionState.value = ConnectionState.CONNECTED
        addLog("DEBUG BLE Handshake Complete! Connected to Pulsar N160 Cluster.")

        batteryMonitor.start()

        scope.launch {
            val dummyContacts = listOf(
                PulsarProtocol.ContactSlot("Jane Smith", "9876543210", status = 1),
                PulsarProtocol.ContactSlot("John Doe", "1234567890", status = 3)
            )

            favContactsChar?.let { char ->
                val favPacket = PulsarProtocol.buildFavContactsPacket(dummyContacts)
                queueWrite(char, favPacket)
                addLog("DEBUG Sent initial FAV_CONTACTS frame (151 bytes)")
            }

            delay(400)

            recentCallsChar?.let { char ->
                val recentPacket = PulsarProtocol.buildRecentCallsPacket(dummyContacts)
                queueWrite(char, recentPacket)
                addLog("DEBUG Sent initial RECENT_CALLS frame (151 bytes)")
            }

            delay(400)

            startHeartbeat()
        }
    }

    private fun startHeartbeat() {
        stopHeartbeat()
        addLog("DEBUG Starting 1Hz Heartbeat loop...")
        heartbeatJob = scope.launch {
            while (isActive && _connectionState.value == ConnectionState.CONNECTED) {
                sendHeartbeatPacket()
                delay(1000)
            }
        }
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    private fun sendHeartbeatPacket() {
        val general = generalChar ?: return

        val current = _generalState.value
        val nextProgress = if (current.callState == PulsarProtocol.CallState.INCOMING_CALL ||
            current.callState == PulsarProtocol.CallState.ACTIVE_CALL ||
            current.callState == PulsarProtocol.CallState.OUTGOING_CALL
        ) {
            current.callProgressSeconds + 1
        } else {
            0
        }

        val updated = current.copy(
            callProgressSeconds = nextProgress
        )
        _generalState.value = updated

        val packet = PulsarProtocol.buildGeneralPacket(updated, lastControlPacket)
        queueWrite(general, packet)
    }

    // --- MANUAL CONTROL API METHODS ---

    fun updateIndicators(batteryBars: Int, signalBars: Int) {
        _generalState.value = _generalState.value.copy(
            batteryBars = batteryBars.coerceIn(0, 4),
            signalBars = signalBars.coerceIn(0, 4)
        )
        addLog("DEBUG Manual Indicators Update: Bat=$batteryBars Signal=$signalBars")
        generalChar?.let { gen ->
            val packet = PulsarProtocol.buildGeneralPacket(_generalState.value, lastControlPacket)
            queueWrite(gen, packet)
        }
    }

    fun updateCallState(callState: PulsarProtocol.CallState, callerName: String, callerNumber: String) {
        _generalState.value = _generalState.value.copy(
            callState = callState,
            callerName = callerName,
            callerNumber = callerNumber,
            callProgressSeconds = 0
        )
        addLog("DEBUG Manual Call State Update: State=${callState.name} Name='$callerName' Num='$callerNumber'")

        if (callState == PulsarProtocol.CallState.INCOMING_CALL || callState == PulsarProtocol.CallState.ACTIVE_CALL) {
            isControlBootstrap = true
            startControlPolling()
        } else {
            stopControlPolling()
        }

        generalChar?.let { gen ->
            val packet = PulsarProtocol.buildGeneralPacket(_generalState.value, lastControlPacket)
            queueWrite(gen, packet)
        }
    }

    fun sendDummyMissedCall(callerName: String, phoneNumber: String) {
        val char = missedCallChar
        val packet = PulsarProtocol.buildMissedCallPacket(callerName, phoneNumber)

        if (char != null && _connectionState.value == ConnectionState.CONNECTED) {
            queueWrite(char, packet)
            addLog("DEBUG Queued Missed Call (V1 74-byte): '$callerName' ($phoneNumber)")
        } else {
            addLog("DEBUG Simulated Missed Call (V1 74-byte): '$callerName' ($phoneNumber)")
        }

        _generalState.value = _generalState.value.copy(
            missedCallCount = _generalState.value.missedCallCount + 1
        )

        recentCallsChar?.let { recent ->
            val callList = listOf(
                PulsarProtocol.ContactSlot(callerName, phoneNumber, status = 3)
            )
            queueWrite(recent, PulsarProtocol.buildRecentCallsPacket(callList))
        }

        generalChar?.let { gen ->
            val updatedPacket = PulsarProtocol.buildGeneralPacket(_generalState.value, lastControlPacket)
            queueWrite(gen, updatedPacket)
        }
    }

    fun triggerDummyIncomingCall(callerName: String, phoneNumber: String = "+919876543210") {
        updateCallState(PulsarProtocol.CallState.INCOMING_CALL, callerName, phoneNumber)

        recentCallsChar?.let { recent ->
            val callList = listOf(
                PulsarProtocol.ContactSlot(callerName, phoneNumber, status = 1)
            )
            queueWrite(recent, PulsarProtocol.buildRecentCallsPacket(callList))
        }
    }

    fun endDummyCall() {
        updateCallState(PulsarProtocol.CallState.END_CALL, "", "")

        scope.launch {
            delay(2000)
            if (_generalState.value.callState == PulsarProtocol.CallState.END_CALL) {
                updateCallState(PulsarProtocol.CallState.NO_CALL, "", "")
            }
        }
    }

    fun sendDummyAlert(alertType: Int, message: String) {
        val char = alertsChar
        val packet = PulsarProtocol.buildAlertsPacket(alertType, message)
        if (char != null && _connectionState.value == ConnectionState.CONNECTED) {
            queueWrite(char, packet)
            addLog("DEBUG Queued Alert (V1 40-byte): type=$alertType msg='$message'")
        } else {
            addLog("DEBUG Simulated Alert (V1 40-byte): type=$alertType msg='$message'")
        }
        _generalState.value = _generalState.value.copy(
            smsCount = _generalState.value.smsCount + 1
        )
        generalChar?.let { gen ->
            val updatedPacket = PulsarProtocol.buildGeneralPacket(_generalState.value, lastControlPacket)
            queueWrite(gen, updatedPacket)
        }
    }

    fun sendTbtInfo(turn: PulsarProtocol.PrimaryTurn, distanceMeters: Int, streetName: String, isNavActive: Boolean) {
        val gatt = bluetoothGatt
        val tbtChar = gatt?.getService(PulsarProtocol.SERVICE_UUID)?.getCharacteristic(PulsarProtocol.CHAR_TBT_UUID)
        val packet = PulsarProtocol.buildTbtPacket(turn = turn, distanceMeters = distanceMeters, streetName = streetName, isNavActive = isNavActive)
        if (tbtChar != null && _connectionState.value == ConnectionState.CONNECTED) {
            queueWrite(tbtChar, packet)
            addLog("DEBUG Queued TBT Nav (V1 48-byte): Turn=${turn.name} Dist=${distanceMeters}m Street='$streetName'")
        } else {
            addLog("DEBUG Simulated TBT Nav (V1 48-byte): Turn=${turn.name} Dist=${distanceMeters}m Street='$streetName'")
        }
    }

    fun syncFavContacts(contacts: List<PulsarProtocol.ContactSlot>) {
        val char = favContactsChar
        val packet = PulsarProtocol.buildFavContactsPacket(contacts)
        if (char != null && _connectionState.value == ConnectionState.CONNECTED) {
            queueWrite(char, packet)
            addLog("DEBUG Queued Fav Contacts (151-byte): ${contacts.size} slots")
        } else {
            addLog("DEBUG Simulated Fav Contacts (151-byte): ${contacts.size} slots")
        }
    }

    fun syncRecentCalls(calls: List<PulsarProtocol.ContactSlot>) {
        val char = recentCallsChar
        val packet = PulsarProtocol.buildRecentCallsPacket(calls)
        if (char != null && _connectionState.value == ConnectionState.CONNECTED) {
            queueWrite(char, packet)
            addLog("DEBUG Queued Recent Calls (151-byte): ${calls.size} slots")
        } else {
            addLog("DEBUG Simulated Recent Calls (151-byte): ${calls.size} slots")
        }
    }
}
