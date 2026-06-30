package com.nls.handring

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.*
import kotlinx.coroutines.*

class TherapyEngine(private val ctx: Context) {
    private var connection: UsbDeviceConnection? = null
    private var epOut: UsbEndpoint? = null
    private var device: UsbDevice? = null
    private var job: Job? = null
    private var cmds: List<Cmd> = emptyList()
    private var index = 0
    private var interval = 500L
    private var repeatCount = 1
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
        val devices = usbManager.deviceList.values
        device = devices.find { it.vendorId == 0x0403 && it.productId == 0x6001 }
            ?: devices.firstOrNull { it.vendorId == 0x0403 }
        if (device == null) { callback(false, "未检测到 FTDI 设备\n请确认 OTG 线已连接手环"); return }
        if (!usbManager.hasPermission(device)) {
            permCallback = { granted -> if (granted) openDevice(callback) else callback(false, "USB 权限被拒绝") }
            val pi = PendingIntent.getBroadcast(ctx, 0, Intent(actionUsbPermission), PendingIntent.FLAG_IMMUTABLE)
            usbManager.requestPermission(device!!, pi)
            return
        }
        openDevice(callback)
    }

    private fun openDevice(callback: (Boolean, String) -> Unit) {
        try {
            val usbManager = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
            connection = usbManager.openDevice(device!!) ?: run { callback(false, "无法打开 USB 设备"); return }
            for (i in 0 until device!!.interfaceCount) {
                val iface = device!!.getInterface(i)
                if (!connection!!.claimInterface(iface, true)) continue
                for (j in 0 until iface.endpointCount) {
                    val ep = iface.getEndpoint(j)
                    if (ep.direction == UsbConstants.USB_DIR_OUT && ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                        epOut = ep; break
                    }
                }
                if (epOut != null) break
            }
            if (epOut == null) { connection!!.close(); connection = null; callback(false, "未找到 USB 输出端点"); return }
            configureFtdi()
            isConnected = true
            callback(true, "手环已连接")
        } catch (e: Exception) {
            isConnected = false; try { connection?.close() } catch (_: Exception) {}; connection = null
            callback(false, "连接失败: ${e.message}")
        }
    }

    private fun configureFtdi() {
        connection?.controlTransfer(0x40, 0, 0, 1, null, 0, 1000)
        connection?.controlTransfer(0x40, 0, 0, 0, null, 0, 1000)
        val divisor = 3000000 / 115200
        val buf = byteArrayOf((divisor and 0xFF).toByte(), ((divisor shr 8) and 0xFF).toByte(), 0x00, 0x00, 0x08)
        connection?.controlTransfer(0x40, 0x03, 0x4138, 0, buf, buf.size, 1000)
    }

    fun disconnect() { stop(); try { connection?.close() } catch (_: Exception) {}; connection = null; device = null; epOut = null; isConnected = false; onStatus?.invoke("已断开") }

    fun playProgram(programKey: String, powerLevel: Int?, loop: Boolean) {
        stop()
        val prog = TherapyData.programs[programKey] ?: return
        val filtered = if (powerLevel != null) {
            prog.cmds.filter { it.b11 == powerLevel || kotlin.math.abs(it.b11 - powerLevel) <= 2 }.ifEmpty { prog.cmds }
        } else prog.cmds
        startPlaying(prog.organ, filtered, loop)
    }

    fun playAllPrograms(loop: Boolean) {
        stop()
        val all = mutableListOf<Cmd>()
        for (key in TherapyData.programs.keys.sortedBy { TherapyData.programs[it]!!.b9 }) {
            all.addAll(TherapyData.programs[key]!!.cmds)
        }
        startPlaying("全部器官", all, loop)
    }

    private fun startPlaying(label: String, cmdList: List<Cmd>, loop: Boolean) {
        cmds = cmdList; index = 0; loopMode = loop; isPlaying = true
        job = CoroutineScope(Dispatchers.IO).launch {
            while (isPlaying && cmds.isNotEmpty()) {
                val cmd = cmds[index % cmds.size]
                // 每条命令重复 repeatCount 次
                for (r in 0 until repeatCount) {
                    if (!isPlaying) break
                    sendRaw(cmd)
                    if (r < repeatCount - 1) delay(interval)
                }
                index++
                onProgress?.invoke(index, cmds.size)
                onStatus?.invoke("$label #$index/${cmds.size} CH1(${cmd.b9},${cmd.b11}) CH2(${cmd.b13},${cmd.b15})")
                if (!loop && index >= cmds.size) { stop(); break }
                delay(interval)
            }
        }
    }

    private fun sendRaw(cmd: Cmd) {
        val buf = ByteArray(128)
        buf[9] = cmd.b9.toByte(); buf[11] = cmd.b11.toByte(); buf[13] = cmd.b13.toByte(); buf[15] = cmd.b15.toByte()
        try { connection?.bulkTransfer(epOut, buf, buf.size, 1000) } catch (_: Exception) {}
    }

    fun stop() { isPlaying = false; job?.cancel(); job = null; index = 0; cmds = emptyList() }
    fun setInterval(ms: Long) { interval = ms }
    fun setRepeat(n: Int) { repeatCount = n.coerceIn(1, 20) }
    fun destroy() { disconnect(); try { ctx.unregisterReceiver(usbReceiver) } catch (_: Exception) {} }
}
