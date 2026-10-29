package com.xy2407.kingdom.common.network.clientbound;

/**
 * 队伍操作结果的客户端落点：common 侧只留槽位，实际提示由客户端注册
 */
public final class RtsTeamResultBridge {

    public interface Sink {
        void accept(boolean success, String message);
    }

    private static volatile Sink sink;

    private RtsTeamResultBridge() {
    }

    public static void setSink(Sink value) {
        sink = value;
    }

    public static void accept(boolean success, String message) {
        Sink current = sink;
        if (current != null) {
            current.accept(success, message);
        }
    }
}
