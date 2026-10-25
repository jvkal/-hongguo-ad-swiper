package com.hongguo.helper

import android.content.Context
import android.util.Log
import moe.shizuku.manager.adb.AdbClient
import moe.shizuku.manager.adb.AdbKey
import moe.shizuku.manager.adb.AdbMdns
import moe.shizuku.manager.adb.AdbPairingClient
import moe.shizuku.manager.adb.PreferenceAdbKeyStore
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * 内嵌 ADB 客户端: 不依赖 Shizuku, App 直连本机无线调试执行 shell 命令。
 * 首次: pair(端口, 6位码) 一次性登记密钥; 之后 discover+connect 即用。
 * (协议实现来自 Shizuku 项目, Apache-2.0)
 */
object AdbShell {

    private const val TAG = "HGHelper"
    private const val HOST = "127.0.0.1"

    val executor = Executors.newSingleThreadExecutor()

    private var key: AdbKey? = null
    private val clientRef = AtomicReference<AdbClient?>(null)

    @JvmStatic
    fun key(context: Context): AdbKey {
        if (key == null) {
            key = AdbKey(
                PreferenceAdbKeyStore(
                    context.getSharedPreferences("adb", Context.MODE_PRIVATE)
                ), "hongguo-helper"
            )
        }
        return key!!
    }

    /** 是否已完成过配对(有密钥登记记录) */
    @JvmStatic
    fun paired(context: Context): Boolean {
        return context.getSharedPreferences("adb", Context.MODE_PRIVATE)
            .getBoolean("paired", false)
    }

    /** 配对: 端口和6位码来自 配对码弹窗本身(不是无线调试主页面的端口!) */
    @JvmStatic
    fun pair(context: Context, port: Int, code: String): String {
        return try {
            AdbPairingClient(HOST, port, code, key(context)).use { client ->
                val ok = client.start()
                if (ok) {
                    context.getSharedPreferences("adb", Context.MODE_PRIVATE)
                        .edit().putBoolean("paired", true).apply()
                }
                Log.i(TAG, "ADB配对: $ok")
                if (ok) "ok" else "fail:服务器拒绝(配对码错误或弹窗已关闭)"
            }
        } catch (e: java.net.ConnectException) {
            "fail:连不上端口→端口不对或弹窗已关闭"
        } catch (e: javax.net.ssl.SSLHandshakeException) {
            "fail:握手被拒→这个端口不是配对端口(要用配对弹窗里的端口)"
        } catch (e: Exception) {
            "fail:${e.javaClass.simpleName}:${e.message}"
        }
    }

    /** 只填配对码: 通过 mDNS 自动发现配对端口(需配对弹窗开着) */
    @JvmStatic
    fun pairCodeOnly(context: Context, code: String): String {
        return try {
            var port = -1
            // 弹窗刚弹出时 mDNS 广播有延迟, 多试几轮(总共约 12 秒)
            repeat(2) {
                val latch = java.util.concurrent.CountDownLatch(1)
                val mdns = AdbMdns(context, AdbMdns.TLS_PAIRING) { p ->
                    if (p > 0) {
                        port = p
                        latch.countDown()
                    }
                }
                mdns.start()
                latch.await(6, java.util.concurrent.TimeUnit.SECONDS)
                mdns.stop()
                if (port > 0) return@repeat
            }
            if (port <= 0) {
                "fail:没找到配对端口→请先打开配对码弹窗再点配对(弹窗别关)"
            } else {
                Log.i(TAG, "mDNS 发现配对端口: $port")
                pair(context, port, code)
            }
        } catch (e: Exception) {
            "fail:${e.javaClass.simpleName}:${e.message}"
        }
    }

    /** 通过 mDNS 找到无线调试端口并连接; 成功返回 true */
    @JvmStatic
    fun reconnect(context: Context, onResult: java.util.function.Consumer<Boolean>) {
        executor.execute {
            var ok = tryConnectSaved(context)
            if (!ok) {
                // 尝试 mDNS 发现端口(无线调试开着才会广播)
                val latch = java.util.concurrent.CountDownLatch(1)
                var done = false
                val mdns = AdbMdns(context, AdbMdns.TLS_CONNECT) { port ->
                    if (port > 0 && !done) {
                        done = true
                        ok = tryConnect(context, port)
                        latch.countDown()
                    }
                }
                mdns.start()
                latch.await(8, java.util.concurrent.TimeUnit.SECONDS)
                mdns.stop()
            }
            Log.i(TAG, "ADB重连: $ok")
            onResult.accept(ok)
        }
    }

    private fun tryConnectSaved(context: Context): Boolean {
        val port = context.getSharedPreferences("adb", Context.MODE_PRIVATE)
            .getInt("port", -1)
        if (port <= 0) return false
        return tryConnect(context, port)
    }

    private fun tryConnect(context: Context, port: Int): Boolean {
        return try {
            val c = AdbClient(HOST, port, key(context))
            c.connect()
            clientRef.get()?.close()
            clientRef.set(c)
            context.getSharedPreferences("adb", Context.MODE_PRIVATE)
                .edit().putInt("port", port).apply()
            true
        } catch (e: Exception) {
            Log.w(TAG, "ADB连接失败 port=$port: $e")
            false
        }
    }

    @JvmStatic
    fun connected(): Boolean = clientRef.get() != null

    /**
     * 通过 ADB 一键开启本应用无障碍(shell uid 有 WRITE_SECURE_SETTINGS)。
     * 返回 "ok" 或 "fail:..."。调用方须在后台线程。
     */
    @JvmStatic
    fun enableAccessibility(context: Context): String {
        val svc = "${context.packageName}/com.hongguo.helper.SkipService"
        val cur = exec(context, "settings get secure enabled_accessibility_services")?.trim()
            ?: return "fail:ADB通道未连接,先完成配对/重连"
        val curClean = if (cur.isEmpty() || cur == "null") "" else cur
        val merged = when {
            curClean.contains(svc) -> curClean
            curClean.isEmpty() -> svc
            else -> "$curClean:$svc"
        }
        exec(context, "settings put secure enabled_accessibility_services $merged")
        exec(context, "settings put secure accessibility_enabled 1")
        val now = exec(context, "settings get secure enabled_accessibility_services")?.trim()
        return if (now != null && now.contains(svc)) "ok" else "fail:写入未生效($now)"
    }

    /** 执行命令; 断线时自动重连一次; 失败返回 null */
    @JvmStatic
    fun exec(context: Context, command: String): String? {
        var client = clientRef.get()
        if (client == null) {
            // 同步重连(在调用方已是后台线程)
            if (!tryConnectSaved(context)) return null
            client = clientRef.get() ?: return null
        }
        try {
            val sb = StringBuilder()
            client.shellCommand(command) { sb.append(String(it)) }
            return sb.toString()
        } catch (e: Exception) {
            Log.w(TAG, "ADB执行失败, 重连重试: $e")
            clientRef.getAndSet(null)?.close()
            if (!tryConnectSaved(context)) return null
            val c2 = clientRef.get() ?: return null
            return try {
                val sb = StringBuilder()
                c2.shellCommand(command) { sb.append(String(it)) }
                sb.toString()
            } catch (e2: Exception) {
                Log.w(TAG, "ADB重试仍失败: $e2")
                clientRef.getAndSet(null)?.close()
                null
            }
        }
    }
}
