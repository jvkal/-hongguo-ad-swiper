package com.hongguo.helper

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import moe.shizuku.manager.adb.AdbKey
import moe.shizuku.manager.adb.AdbMdns
import moe.shizuku.manager.adb.AdbMessage
import moe.shizuku.manager.adb.AdbPairingClient
import moe.shizuku.manager.adb.AdbProtocol.A_CNXN
import moe.shizuku.manager.adb.AdbProtocol.A_MAXDATA
import moe.shizuku.manager.adb.AdbProtocol.A_STLS
import moe.shizuku.manager.adb.AdbProtocol.A_STLS_VERSION
import moe.shizuku.manager.adb.AdbProtocol.A_VERSION
import moe.shizuku.manager.adb.ConscryptCompat
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocket

/**
 * TLS exporter 自检(不打配对码也能验证修复)。
 *
 * 背景: 界面上的
 *   fail:异常 java.lang.NoSuchMethodError: ... Conscrypt.exportKeyingMaterial ...
 * 死在 AdbPairingClient.setupTlsConnection() 里的“导出密钥材料”这一步。
 * 修好的调用是 ConscryptCompat.exportKeyingMaterial()。
 *
 * 但配对流程还额外需要手机上弹出 6 位码, 不好自动化。这里改成:
 * 连本机已在广播的 adb-tls-connect 端口(无线调试开着就有), 走完 TLS1.3 握手,
 * 然后在真实会话上调用同一个 exportKeyingMaterial。
 * 这条路通了, 就证明配对时不会再抛 NoSuchMethodError。
 *
 * 触发: adb shell am start -n com.hongguo.helper/.AltTestActivity --es mode exporter
 */
object TlsSelfTest {

    private const val TAG = "HGHelper"
    private const val LABEL = "adb-label\u0000"
    private const val LEN = 64
    private const val HOST = "127.0.0.1"

    @JvmStatic
    fun run(context: Context): String {
        val out = try {
            doRun(context)
        } catch (t: Throwable) {
            "fail:${t.javaClass.simpleName}:${t.message}"
        }
        // ASCII 标记, 方便 PowerShell 过滤(中文日志会乱码)
        Log.i(TAG, "[EXPORT] $out")
        return out
    }

    @SuppressLint("NewApi")
    private fun doRun(context: Context): String {
        // ① mDNS 找无线调试连接端口
        val latch = CountDownLatch(1)
        val portRef = intArrayOf(-1)
        val mdns = AdbMdns(context, AdbMdns.TLS_CONNECT) { p ->
            if (p > 0 && portRef[0] <= 0) {
                portRef[0] = p
                latch.countDown()
            }
        }
        mdns.start()
        val found = latch.await(8, TimeUnit.SECONDS)
        mdns.stop()
        if (!found || portRef[0] <= 0) {
            return "fail:no-connect-port(无线调试没开? 开发者选项里打开开关)"
        }
        val port = portRef[0]

        // ② 明文 CNXN -> 期望 A_STLS -> 升级为 TLS
        val key = AdbShell.key(context)
        return Socket(HOST, port).use { plain ->
            plain.tcpNoDelay = true
            val din = DataInputStream(plain.inputStream)
            val dout = DataOutputStream(plain.outputStream)

            dout.write(AdbMessage(A_CNXN, A_VERSION, A_MAXDATA, "host::").toByteArray())
            dout.flush()

            val header = ByteArray(AdbMessage.HEADER_LENGTH)
            din.readFully(header)
            val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            val command = buf.int
            val arg0 = buf.int
            val arg1 = buf.int
            val len = buf.int
            val crc = buf.int
            val magic = buf.int
            val data = if (len > 0) ByteArray(len).also { din.readFully(it) } else null
            val first = AdbMessage(command, arg0, arg1, len, crc, magic, data)
            if (first.command != A_STLS) {
                return "fail:peer-not-tls cmd=${first.command.toString(16)} (这台机的无线调试未走 TLS, 检查是否旧版 adb)"
            }

            dout.write(AdbMessage(A_STLS, A_STLS_VERSION, 0, null as ByteArray?).toByteArray())
            dout.flush()

            val ssl = key.sslContext.socketFactory
                .createSocket(plain, HOST, port, true) as SSLSocket
            try {
                ssl.startHandshake()
                val proto = ssl.session.protocol
                // ③ 关键一步: 和配对时完全相同的调用
                val material = ConscryptCompat.exportKeyingMaterial(ssl, LABEL, null, LEN)
                val head = material.take(4).joinToString("") { "%02x".format(it) }
                "ok proto=$proto len=${material.size} head=$head port=$port path=${ConscryptCompat.activePath}"
            } catch (t: Throwable) {
                "fail:${t.javaClass.simpleName}:${t.message} fallback=${ConscryptCompat.lastError}"
            } finally {
                runCatching { ssl.close() }
            }
        }
    }

    /** 配对端口探测(顺便确认弹窗是否开着), 供界面提示用 */
    @JvmStatic
    fun probePairingPort(context: Context): Int {
        return try {
            val latch = CountDownLatch(1)
            val ref = intArrayOf(-1)
            val mdns = AdbMdns(context, AdbMdns.TLS_PAIRING) { p ->
                if (p > 0 && ref[0] <= 0) {
                    ref[0] = p
                    latch.countDown()
                }
            }
            mdns.start()
            latch.await(6, TimeUnit.SECONDS)
            mdns.stop()
            ref[0]
        } catch (t: Throwable) {
            Log.w(TAG, "[EXPORT] probePairingPort fail: $t")
            -1
        }
    }

    /** 直接读一下 native 侧是否可用(libadb.so 已加载) */
    @JvmStatic
    fun nativeAvailable(): Boolean = try {
        AdbPairingClient.available()
    } catch (t: Throwable) {
        false
    }
}
