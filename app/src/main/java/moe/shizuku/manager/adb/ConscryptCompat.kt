package moe.shizuku.manager.adb

import android.os.Build
import android.util.Log
import java.lang.reflect.Method
import javax.net.ssl.SSLSocket

/**
 * TLS exporter 兼容层。
 *
 * Android 16 起 com.android.org.conscrypt.Conscrypt.exportKeyingMaterial 被
 * 隐藏 API 限制挡在链接期(NoSuchMethodError), 必须改用系统公开 API:
 *   android.net.ssl.SSLSockets.exportKeyingMaterial (API 29+)
 * 老系统(28-)再退回反射调用 Conscrypt。
 */
object ConscryptCompat {

    private const val TAG = "ConscryptCompat"

    @JvmStatic
    var lastError: String? = null

    /** 最近一次成功走的是哪条路: SSLSockets / Conscrypt(reflect) */
    @JvmStatic
    var activePath: String? = null
        private set

    private val reflectMethod: Method? by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        try {
            val cls = Class.forName("com.android.org.conscrypt.Conscrypt")
            cls.methods.firstOrNull {
                it.name == "exportKeyingMaterial" && it.parameterTypes.size == 4
            }?.also { it.isAccessible = true }
        } catch (e: Throwable) {
            lastError = "反射失败: $e"
            null
        }
    }

    @JvmStatic
    fun exportKeyingMaterial(socket: SSLSocket, label: String, context: ByteArray?, length: Int): ByteArray {
        lastError = null

        // ① 公开 API (API 29+): 这是 Android 16 上唯一可行的路径。
        //    注意: 必须整段(调用+取返回值)包在 try 里。本方法编译进同一个 dex,
        //    系统若拒绝链接 android.net.ssl.SSLSockets, NoSuchMethodError 会在
        //    求值这一行时才抛出; 只 catch 异常不接住 Error 的话整个调用直接崩掉,
        //    后面的反射回退永远执行不到 —— 这正是界面上 "fail:异常 NoSuchMethodError"
        //    的来源。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val r = android.net.ssl.SSLSockets.exportKeyingMaterial(socket, label, context, length)
                if (r != null && r.size == length) {
                    activePath = "SSLSockets"
                    return r
                }
                lastError = "SSLSockets 返回 ${if (r == null) "null" else "长度${r.size}"}"
                Log.w(TAG, lastError!!)
            } catch (e: Throwable) {   // Throwable: 同时兜住 NoSuchMethodError / LinkageError
                lastError = "SSLSockets 失败: ${e.javaClass.simpleName}:${e.message}"
                Log.w(TAG, lastError!!)
            }
        }

        // ② 老系统: 反射调用 Conscrypt
        val m = reflectMethod
                ?: throw IllegalStateException("TLS exporter不可用: ${lastError ?: "无可用实现"}")
        return try {
            (m.invoke(null, socket, label, context, length) as ByteArray).also {
                activePath = "Conscrypt(reflect)"
            }
        } catch (e: Throwable) {
            throw IllegalStateException("反射调用失败: ${e.cause ?: e}")
        }
    }
}
