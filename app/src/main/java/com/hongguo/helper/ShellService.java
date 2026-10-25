package com.hongguo.helper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Shizuku UserService: 运行在 shell(uid 2000) 权限的独立进程里,
 * 代红果助手执行 input swipe(不带无障碍标记, 红果广告页不拦截)。
 */
public class ShellService extends IShellService.Stub {

    @Override
    public String exec(String command) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", command});
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            InputStream in = p.getInputStream();
            byte[] buf = new byte[1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            p.waitFor();
            return out.toString().trim();
        } catch (IOException e) {
            return "ERR:" + e.getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "ERR:interrupted";
        }
    }

    @Override
    public void destroy() {
    }
}
