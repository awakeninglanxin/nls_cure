package com.nls.handring

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.*

class TherapyEngine(private val ctx: Context) {
    private var port: UsbSerialPort? = null
    private var driver: UsbSerialDriver? = null
    private var job: Job? = null
    private var cmds: List<Cmd> = emptyList()
    private var index = 0
    private var interval = 500L
    private var loopMode = true

    var onStatus: ((String) -> Unit)? = null
    var onProgress: ((Int, Int) -> Unit)? = null
    var isPlaying = false
        private set
    var isConnected = false
        private set

    private val actionUsbPermission = "com.nls.handring.USB_PERMISSION"
    private var permCallback: ((Boolean) -> Unit)? = null

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (actionUsbPermission == intent.action) {
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                permCallback?.invoke(granted)
                permCallback = null
            }
        }
    }

    init {
        ctx.registerReceiver(usbReceiver, IntentFilter(actionUsbPermission), Context.RECEIVER_NOT_EXPORTED)
    }

    fun connect(callback: (Boolean, String) -> Unit) {
        val usbManager = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        if (availableDrivers.isEmpty()) {
            callback(false, "未检测到 FTDI 设备\n请确认 OTG 线已连接手环")
            return
        }
        driver = availableDrivers[0]
        val device = driver!!.device

        if (!usbManager.hasPermission(device)) {
            permCallback = { granted ->
                if (granted) openPort(callback) else callback(false, "USB 权限被拒绝")
            }
            val pi = PendingIntent.getBroadcast(ctx, 0, Intent(actionUsbPermission),
                PendingIntent.FLAG_IMMUTABLE)
            usbManager.requestPermission(device, pi)
            return
        }
        openPort(callback)
    }

    private fun openPort(callback: (Boolean, String) -> Unit) {
        try {
            val usbManager = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
            val conn = usbManager.openDevice(driver!!.device)
            port = driver!!.ports[0]
            port!!.open(conn)
            port!!.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            isConnected = true
            callback(true, "手环已连接")
        } catch (e: Exception) {
            isConnected = false
            callback(false, "连接失败: ${e.message}")
        }
    }

    fun disconnect() {
        stop()
        try { port?.close() } catch (_: Exception) {}
        port = null
        driver = null
        isConnected = false
        onStatus?.invoke("已断开")
    }

    fun play(programKey: String, powerLevel: Int?, loop: Boolean) {
        stop()
        val prog = TherapyData.programs[programKey] ?: return
        val filtered = if (powerLevel != null) {
            prog.cmds.filter { it.b11 == powerLevel || kotlin.math.abs(it.b11 - powerLevel) <= 2 }
                .ifEmpty { prog.cmds.filter { it.b11 == powerLevel } }
                .ifEmpty { prog.cmds }
        } else prog.cmds

        cmds = filtered
        index = 0
        loopMode = loop
        isPlaying = true

        job = CoroutineScope(Dispatchers.IO).launch {
            while (isPlaying && cmds.isNotEmpty()) {
                val cmd = cmds[index % cmds.size]
                sendRaw(cmd)
                index++
                onProgress?.invoke(index, cmds.size)
                onStatus?.invoke("${prog.organ} #$index/${cmds.size} CH1(${cmd.b9},${cmd.b11}) CH2(${cmd.b13},${cmd.b15})")
                if (!loopMode && index >= cmds.size) {
                    stop()
                    break
                }
                delay(interval)
            }
        }
    }

    private fun sendRaw(cmd: Cmd) {
        val buf = ByteArray(128)
        buf[9] = cmd.b9.toByte()
        buf[11] = cmd.b11.toByte()
        buf[13] = cmd.b13.toByte()
        buf[15] = cmd.b15.toByte()
        try {
            port?.write(buf, 500)
        } catch (_: Exception) {}
    }

    fun stop() {
        isPlaying = false
        job?.cancel()
        job = null
        index = 0
        cmds = emptyList()
    }

    fun setInterval(ms: Long) { interval = ms }

    fun destroy() {
        disconnect()
        try { ctx.unregisterReceiver(usbReceiver) } catch (_: Exception) {}
    }
}
