package com.jpq.mobile;

import java.io.IOException;

/** ELF machine values, independent of Android's advertised translated ABIs. */
final class CollectorArchitecture {
    static int machine(byte[] header) throws IOException {
        if (header.length < 20 || header[0] != 0x7f || header[1] != 'E'
                || header[2] != 'L' || header[3] != 'F' || header[5] != 1)
            throw new IOException("无法识别采集程序ELF架构");
        return (header[18] & 255) | ((header[19] & 255) << 8);
    }

    static String realm(int collector, int game) throws IOException {
        if (collector == 183 && game == 183) return "native";
        if (collector == 62 && (game == 62 || game == 3)) return "native";
        if (collector == 62 && (game == 183 || game == 40)) return "emulated";
        throw new IOException("暂不支持此游戏架构：采集=" + collector + "，游戏=" + game);
    }

    static void verifyHost(String host, int collector) throws IOException {
        if ((host.equals("x86_64") && collector == 62)
                || ((host.equals("aarch64") || host.equals("arm64")) && collector == 183)) return;
        throw new IOException("采集程序与系统架构不匹配，请安装对应测试包：" + host);
    }
}
