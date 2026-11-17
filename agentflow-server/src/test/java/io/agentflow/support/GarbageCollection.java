package io.agentflow.support;

import java.lang.ref.WeakReference;
import static org.assertj.core.api.Assertions.fail;

/**
 * 断言对象已无强引用：多轮请求 GC 并制造分配压力，弱引用被清空即证明可回收。
 * 仅用于验证测试夹具不会把已关闭的 Spring 上下文钉在堆里。
 * @author owlzhangfq@gmail.com
 */
public final class GarbageCollection {
    private static final int ATTEMPTS = 50;

    private GarbageCollection() { }

    public static void assertCollected(WeakReference<?> reference, String what) {
        for (int attempt = 0; attempt < ATTEMPTS && reference.get() != null; attempt++) {
            System.gc();
            byte[][] pressure = new byte[16][];
            for (int index = 0; index < pressure.length; index++) pressure[index] = new byte[1 << 20];
            Thread.onSpinWait();
        }
        if (reference.get() != null) fail(what + " is still strongly reachable after repeated GC");
    }
}
