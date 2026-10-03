package com.xy2407.kingdom.client.watchdog;

import com.sighs.apricityui.init.Document;
import com.sighs.apricityui.screen.ApricityContainerScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 客户端看门狗：客户端线程卡死时，把证据留在磁盘上。
 *
 * <p>之所以要专门做这件事：上次卡死到连日志都停了——渲染线程一卡，写日志的那条线程也走不动，
 * 事后什么都看不到。所以这里刻意分成两套：</p>
 * <ul>
 *   <li><b>心跳</b>由客户端线程打（每帧、每 tick 各一次）；</li>
 *   <li><b>检查与写盘</b>全在独立守护线程 {@code kingdom-watchdog} 上，客户端线程再死也拦不住它。</li>
 * </ul>
 *
 * <p>报告里最关键的三条判据：</p>
 * <ul>
 *   <li><b>心跳增量</b>：{@code 0} = 客户端线程完全停住（死锁 / 死循环 / 驱动挂起）；
 *       大于 {@code 0} = 没死，只是极慢（例如每帧都在刷 GL 错误）。</li>
 *   <li><b>当前所在阶段</b>：最后进入却没退出的自定义绘制（例如 {@code kingdom-npc-model.drawEntity}），
 *       直接指出卡在谁身上。</li>
 *   <li><b>全线程栈 + 渲染线程 CPU 增量</b>：栈顶停在什么地方、CPU 有没有在涨，
 *       足以区分"自旋"和"等锁 / 等驱动"。</li>
 * </ul>
 *
 * <p>报告写到 {@code logs/kingdom-watchdog/}：每次卡死一份带时间戳的完整报告，另有一行摘要追加到
 * {@code summary.log}。卡死够久会开安全模式（跳过重绘型自定义元素），更久则排队关掉当前界面——
 * 目的都是让客户端有机会活过来。</p>
 *
 * <p>本类只在客户端侧加载，且所有对外方法都刻意做成"几百纳秒的字段读写"，
 * 可以毫无顾虑地挂在每帧调用的路径上。</p>
 */
@OnlyIn(Dist.CLIENT)
public final class ClientWatchdog {

    // ------------------------------------------------------------ 判定参数
    /** 看门狗线程的巡检间隔。 */
    private static final long POLL_INTERVAL_MS = 1000L;
    /** 心跳停多久判定为卡死。 */
    private static final long STALL_THRESHOLD_MS = 5000L;
    /** 两份报告之间至少间隔这么久，避免一次卡死刷出上百份文件。 */
    private static final long DUMP_COOLDOWN_MS = 5000L;
    /** 一次卡死最多出几份报告（用于观察"是否在推进"）。 */
    private static final int MAX_DUMPS = 4;
    /** 第几份报告时开安全模式。 */
    private static final int SAFE_MODE_AT_DUMP = 2;
    /** 第几份报告时排队关掉当前界面。 */
    private static final int CLOSE_SCREEN_AT_DUMP = 3;
    /** 面包屑环形缓冲容量。 */
    private static final int BREADCRUMB_CAPACITY = 64;
    /** 报告里附带 latest.log 末尾多少行 / 最多读多少字节。 */
    private static final int LOG_TAIL_LINES = 120;
    private static final int LOG_TAIL_BYTES = 128 * 1024;

    private static final String REPORT_DIRECTORY = "kingdom-watchdog";
    private static final String SUMMARY_FILE = "summary.log";
    private static final String RENDER_THREAD_NAME = "Render thread";

    private static final Logger LOGGER = LoggerFactory.getLogger("xy2407_kingdom/watchdog");
    private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    private static final AtomicBoolean INSTALLED = new AtomicBoolean();
    private static final AtomicLong BEATS = new AtomicLong();
    private static final Deque<Crumb> BREADCRUMBS = new ArrayDeque<>();

    private static volatile long lastBeatNanos = System.nanoTime();
    private static volatile String lastBeatPhase = "尚未起跳";
    private static volatile String activePhase;
    private static volatile long activePhaseNanos;
    private static volatile boolean safeMode;
    private static volatile long lastFrameMillis = -1L;
    private static volatile long lastDumpNanos;
    private static volatile long lastRenderThreadCpuNanos = -1L;

    private ClientWatchdog() {
    }

    /** 一条面包屑：值得事后回溯的稀有事件（开屏、改版式……）。 */
    public record Crumb(long nanos, String phase, String detail) {
    }

    // ------------------------------------------------------------ 对外接口

    /**
     * 挂载看门狗。由第一次心跳自举调用，可重复调用。
     *
     * <p>刻意不依赖任何初始化事件：第一次心跳必然发生在客户端线程开始干活之后，
     * 那个时刻一定已经能读到 {@link Minecraft#gameDirectory}。</p>
     */
    public static void install() {
        if (!INSTALLED.compareAndSet(false, true)) {
            return;
        }
        Thread watcher = new Thread(ClientWatchdog::loop, "kingdom-watchdog");
        watcher.setDaemon(true);
        watcher.start();
        LOGGER.info("看门狗已挂载：心跳超过 {}s 判定卡死，报告写入 logs/{}/",
                STALL_THRESHOLD_MS / 1000L, REPORT_DIRECTORY);
    }

    /**
     * 客户端线程每前进一次调一次。心跳停了就是卡死。
     *
     * @param phase 心跳来源，例如 {@code frame} / {@code tick}
     */
    public static void heartbeat(String phase) {
        if (!INSTALLED.get()) {
            install();
        }
        lastBeatNanos = System.nanoTime();
        lastBeatPhase = phase;
        BEATS.incrementAndGet();
    }

    /**
     * 进入一段"可能长时间不返回"的自定义绘制。
     *
     * <p>报告里的"当前所在阶段"就是它：只要某段代码进来了没出去，卡死时就指名道姓。
     * 每帧调用也很便宜（两次字段写）。</p>
     */
    public static void beginPhase(String phase) {
        activePhaseNanos = System.nanoTime();
        activePhase = phase;
    }

    public static void endPhase() {
        activePhase = null;
    }

    /** 记一条面包屑，只保留最近 {@value BREADCRUMB_CAPACITY} 条。 */
    public static void crumb(String phase, String detail) {
        long now = System.nanoTime();
        synchronized (BREADCRUMBS) {
            while (BREADCRUMBS.size() >= BREADCRUMB_CAPACITY) {
                BREADCRUMBS.removeFirst();
            }
            BREADCRUMBS.addLast(new Crumb(now, phase, detail));
        }
    }

    /**
     * 安全模式：跳过重绘型自定义元素（只保留底框），用于把客户端从卡死里救回来。
     *
     * <p>由看门狗在确认卡死后自动开启；也可由调试入口手动开关。</p>
     */
    public static boolean isSafeMode() {
        return safeMode;
    }

    public static void setSafeMode(boolean value) {
        if (safeMode == value) {
            return;
        }
        safeMode = value;
        LOGGER.warn("看门狗：安全模式{}（重绘型自定义元素此后只画底框）", value ? "已开启" : "已关闭");
        crumb("safe-mode", String.valueOf(value));
    }

    /** 记录上一帧耗时，报告里用来看"卡死前的帧有多慢"。 */
    public static void noteFrameMillis(long millis) {
        lastFrameMillis = millis;
    }

    /** 手动立刻出一份报告（留给以后接键位或指令）。 */
    public static void dumpNow(String reason) {
        try {
            Path file = writeReport(1, -1L, -1L, reason);
            if (file != null) {
                LOGGER.warn("看门狗：手动报告已生成 {}", file);
            }
        } catch (Throwable ignored) {
            // 手动触发失败不应影响游戏
        }
    }

    // ------------------------------------------------------------ 巡检

    private static void loop() {
        int dumpIndex = 0;
        long previousBeats = BEATS.get();
        while (true) {
            sleepQuietly();
            try {
                long now = System.nanoTime();
                long stallMs = (now - lastBeatNanos) / 1_000_000L;
                if (stallMs < STALL_THRESHOLD_MS) {
                    if (dumpIndex > 0) {
                        LOGGER.info("看门狗：客户端已恢复（此前卡死约 {}s，共出 {} 份报告）",
                                stallMs / 1000L, dumpIndex);
                        dumpIndex = 0;
                    }
                    continue;
                }
                if (dumpIndex >= MAX_DUMPS || now - lastDumpNanos < DUMP_COOLDOWN_MS * 1_000_000L) {
                    continue;
                }
                long beats = BEATS.get();
                long beatDelta = beats - previousBeats;
                previousBeats = beats;
                lastDumpNanos = now;
                dumpIndex++;

                Path file = writeReport(dumpIndex, stallMs, beatDelta, null);
                LOGGER.error("看门狗：客户端疑似卡死 {}ms（心跳增量 {}，最后阶段 {}，当前阶段 {}）→ {}",
                        stallMs, beatDelta, lastBeatPhase, activePhase, file);

                if (dumpIndex >= SAFE_MODE_AT_DUMP) {
                    setSafeMode(true);
                }
                if (dumpIndex == CLOSE_SCREEN_AT_DUMP) {
                    queueScreenClose();
                }
            } catch (Throwable ignored) {
                // 看门狗自己出错绝不能把客户端带走
            }
        }
    }

    /**
     * 卡死太久时排队关掉当前界面。
     *
     * <p>只在看门狗线程调用，且**不直接碰 MC**：只把任务排进客户端队列，等客户端线程真能动了由它执行
     * （同线程操作 MC 才安全）。客户端若一直动不了，这个任务就静静躺着，无害。</p>
     */
    private static void queueScreenClose() {
        LOGGER.warn("看门狗：卡死已出 {} 份报告，排队关闭当前界面", CLOSE_SCREEN_AT_DUMP);
        try {
            Minecraft.getInstance().execute(() -> {
                Minecraft minecraft = Minecraft.getInstance();
                Screen screen = minecraft.screen;
                if (screen != null) {
                    LOGGER.warn("看门狗：关闭界面 {}", screen.getClass().getName());
                    minecraft.setScreen(null);
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private static void sleepQuietly() {
        try {
            Thread.sleep(POLL_INTERVAL_MS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------ 报告

    private static Path writeReport(int index, long stallMs, long beatDelta, String note) {
        Path directory = reportDirectory();
        if (directory == null) {
            return null;
        }
        try {
            Files.createDirectories(directory);
            String name = FILE_STAMP.format(LocalDateTime.now())
                    + (stallMs >= 0 ? "-stall" + (stallMs / 1000L) + "s" : "-manual")
                    + "-" + index + ".txt";
            Path file = directory.resolve(name);
            Files.writeString(file, buildReport(index, stallMs, beatDelta, note), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
            Files.writeString(directory.resolve(SUMMARY_FILE),
                    "[" + Instant.now() + "] #" + index
                            + " stall=" + (stallMs < 0 ? "manual" : stallMs + "ms")
                            + " beatsDelta=" + beatDelta
                            + " lastPhase=" + lastBeatPhase
                            + " activePhase=" + activePhase
                            + " screen=" + screenName()
                            + " -> " + file.getFileName()
                            + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            return file;
        } catch (Throwable failure) {
            return null;
        }
    }

    private static String buildReport(int index, long stallMs, long beatDelta, String note) {
        long now = System.nanoTime();
        StringBuilder report = new StringBuilder(1 << 16);
        report.append("=== kingdom 客户端看门狗报告 #").append(index).append(" ===\n");
        report.append("生成时间        : ").append(Instant.now()).append('\n');
        if (note != null) {
            report.append("触发原因        : ").append(note).append('\n');
        }
        report.append("卡死时长        : ")
                .append(stallMs < 0 ? "（手动触发，未卡死）"
                        : stallMs / 1000.0 + "s（心跳最后一次更新于 " + stallMs + "ms 前）")
                .append('\n');
        report.append("心跳增量        : ")
                .append(beatDelta < 0 ? "（手动触发）"
                        : beatDelta + "（0 = 客户端线程完全停住；>0 = 没死锁，只是极慢）")
                .append('\n');
        report.append("心跳总数        : ").append(BEATS.get()).append('\n');
        report.append("最后心跳阶段    : ").append(lastBeatPhase).append('\n');
        report.append("当前所在阶段    : ").append(activePhase == null
                        ? "（不在自定义元素绘制中）"
                        : activePhase + "（已持续 " + ((now - activePhaseNanos) / 1_000_000L) + "ms）")
                .append('\n');
        report.append("安全模式        : ").append(safeMode ? "已开启" : "未开启").append('\n');
        report.append("上一帧耗时      : ").append(lastFrameMillis < 0 ? "未知" : lastFrameMillis + "ms").append('\n');
        report.append('\n');
        report.append("判读提示        : ").append(hint()).append('\n');
        appendClientState(report);
        report.append('\n');
        appendJvmState(report);
        report.append('\n');
        appendBreadcrumbs(report, now);
        report.append('\n');
        appendThreads(report);
        report.append('\n');
        appendLogTail(report);
        return report.toString();
    }

    /**
     * 按渲染线程栈顶给一句人话提示。
     *
     * <p>看门狗的价值在于"下一次不用靠人猜"：栈里出现 {@code apricityui.layout.*} 与
     * {@code apricityui.render.*} 含义完全不同（前者安全模式救不了）。</p>
     */
    private static String hint() {
        StackTraceElement[] stack = renderThreadStack();
        if (stack == null || stack.length == 0) {
            return "（拿不到渲染线程栈）";
        }
        for (StackTraceElement frame : stack) {
            if (frame.getClassName().contains("apricityui.layout")) {
                boolean viaHover = false;
                for (StackTraceElement f : stack) {
                    if (f.getClassName().contains("isBoundElementHovered") || f.getClassName().contains("containsSlotPoint")) {
                        viaHover = true;
                        break;
                    }
                }
                if (viaHover) {
                    return "卡在 AUI 的槽位悬停/命中检测（mouseDragged/findSlot → Position.of）："
                            + "确认 kingdom jar 已含 AuiMeasureCacheGateMixin（布局缓存闸门常开，§7.8）。栈顶=" + frame;
                }
                return "卡在 AUI 布局引擎（layout.*）：不是绘制问题，安全模式救不了。"
                        + "首要检查：kingdom jar 是否已含 AuiMeasureCacheGateMixin + AuiSlotSyncMeasureCacheMixin（§7.8）——"
                        + "绘制之外的布局求值（init 逐槽位求位、悬停命中）都是无缓存全树测量，页面改 CSS 只是换循环入口。"
                        + "若 mixin 都已生效仍卡，再查页面的 flex-wrap/baseline/em 尺寸（§7.6/§7.7）。栈顶=" + frame;
            }
        }
        for (StackTraceElement frame : stack) {
            if (frame.getClassName().contains("apricityui.render")) {
                return "卡在 AUI 绘制阶段（render.*）：先确认安全模式是否已开启。栈顶=" + frame;
            }
        }
        return "栈顶=" + stack[0];
    }

    private static StackTraceElement[] renderThreadStack() {
        for (Map.Entry<Thread, StackTraceElement[]> entry : Thread.getAllStackTraces().entrySet()) {
            if (RENDER_THREAD_NAME.equals(entry.getKey().getName())) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static void appendClientState(StringBuilder report) {
        report.append("--- 客户端状态 ---\n");
        try {
            Minecraft minecraft = Minecraft.getInstance();
            report.append("屏幕            : ").append(screenName()).append('\n');
            report.append("AUI 文档        : ").append(linkedDocumentName(minecraft)).append('\n');
            report.append("世界 / 玩家     : ").append(minecraft.level != null ? "有" : "无")
                    .append(" / ").append(minecraft.player != null ? "有" : "无").append('\n');
            report.append("FPS（上次统计） : ").append(minecraft.getFps()).append('\n');
            report.append("窗口物理尺寸    : ").append(minecraft.getWindow().getWidth()).append('x')
                    .append(minecraft.getWindow().getHeight()).append("，GUI Scale ")
                    .append(minecraft.getWindow().getGuiScale()).append('\n');
            report.append("窗口逻辑尺寸    : ").append(minecraft.getWindow().getGuiScaledWidth()).append('x')
                    .append(minecraft.getWindow().getGuiScaledHeight()).append('\n');
        } catch (Throwable failure) {
            report.append("（读取客户端状态失败：").append(failure).append("）\n");
        }
    }

    private static void appendJvmState(StringBuilder report) {
        report.append("--- JVM ---\n");
        Runtime runtime = Runtime.getRuntime();
        report.append("堆              : ")
                .append((runtime.totalMemory() - runtime.freeMemory()) / (1024L * 1024L)).append("MB / 上限 ")
                .append(runtime.maxMemory() / (1024L * 1024L)).append("MB\n");
        report.append("线程数（存活）  : ").append(Thread.activeCount()).append('\n');
        report.append("可用核数        : ").append(runtime.availableProcessors()).append('\n');
        report.append("Java            : ").append(System.getProperty("java.version")).append('\n');
        report.append("系统            : ").append(System.getProperty("os.name")).append(' ')
                .append(System.getProperty("os.version")).append('\n');
        report.append("本次启动时长    : ")
                .append(ManagementFactory.getRuntimeMXBean().getUptime() / 1000L).append("s\n");
        appendRenderThreadCpu(report);
    }

    /**
     * 渲染线程的累计 CPU 时间，以及"自上一份报告起涨了多少"。
     *
     * <p>这是区分自旋与阻塞的关键：CPU 猛涨 = 死循环；几乎不涨 = 卡在锁或驱动调用里。</p>
     */
    private static void appendRenderThreadCpu(StringBuilder report) {
        try {
            ThreadMXBean bean = ManagementFactory.getThreadMXBean();
            if (!bean.isThreadCpuTimeSupported() || !bean.isThreadCpuTimeEnabled()) {
                return;
            }
            long cpuNanos = renderThreadCpuNanos(bean);
            if (cpuNanos < 0) {
                return;
            }
            long previous = lastRenderThreadCpuNanos;
            lastRenderThreadCpuNanos = cpuNanos;
            report.append("渲染线程 CPU    : ").append(seconds(cpuNanos));
            if (previous >= 0) {
                report.append("（自上一份报告起 ").append(seconds(cpuNanos - previous))
                        .append("：猛涨=死循环自旋，不动=等锁或等驱动）");
            }
            report.append('\n');
        } catch (Throwable ignored) {
        }
    }

    private static long renderThreadCpuNanos(ThreadMXBean bean) {
        try {
            for (Thread thread : Thread.getAllStackTraces().keySet()) {
                if (RENDER_THREAD_NAME.equals(thread.getName())) {
                    return bean.getThreadCpuTime(thread.getId());
                }
            }
        } catch (Throwable ignored) {
        }
        return -1L;
    }

    private static String seconds(long nanos) {
        return String.format(Locale.ROOT, "%.2fs", nanos / 1.0E9D);
    }

    private static void appendBreadcrumbs(StringBuilder report, long now) {
        List<Crumb> crumbs;
        synchronized (BREADCRUMBS) {
            crumbs = new ArrayList<>(BREADCRUMBS);
        }
        report.append("--- 面包屑（最近 ").append(crumbs.size()).append(" 条，新→旧）---\n");
        for (int i = crumbs.size() - 1; i >= 0; i--) {
            Crumb crumb = crumbs.get(i);
            report.append(String.format(Locale.ROOT, "[-%8.3fs] %-24s %s%n",
                    (now - crumb.nanos()) / 1.0E9D, crumb.phase(), crumb.detail()));
        }
    }

    private static void appendThreads(StringBuilder report) {
        report.append("--- 线程栈（Render thread / Client thread 优先）---\n");
        ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        boolean cpuSupported = bean.isThreadCpuTimeSupported() && bean.isThreadCpuTimeEnabled();
        Map<Thread, StackTraceElement[]> stacks = Thread.getAllStackTraces();
        List<Thread> threads = new ArrayList<>(stacks.keySet());
        threads.sort(Comparator.comparingInt(ClientWatchdog::threadRank).thenComparing(Thread::getName));

        for (Thread thread : threads) {
            report.append('"').append(thread.getName()).append("\" id=").append(thread.getId())
                    .append(" state=").append(thread.getState())
                    .append(" daemon=").append(thread.isDaemon())
                    .append(" prio=").append(thread.getPriority());
            if (cpuSupported) {
                long cpuNanos = bean.getThreadCpuTime(thread.getId());
                if (cpuNanos >= 0) {
                    report.append(" cpu=").append(seconds(cpuNanos));
                }
            }
            report.append('\n');
            StackTraceElement[] stack = stacks.get(thread);
            if (stack == null || stack.length == 0) {
                report.append("    （无栈信息）\n");
            } else {
                for (StackTraceElement frame : stack) {
                    report.append("    at ").append(frame).append('\n');
                }
            }
            report.append('\n');
        }
        appendDeadlocks(report, bean);
    }

    private static void appendDeadlocks(StringBuilder report, ThreadMXBean bean) {
        report.append("--- 死锁 ---\n");
        try {
            long[] deadlocked = bean.findDeadlockedThreads();
            if (deadlocked == null || deadlocked.length == 0) {
                report.append("未检测到 Java 层死锁。注意：native / 驱动挂起与自旋不会体现为死锁，"
                        + "请看上面的渲染线程栈顶与 CPU 增量。\n");
                return;
            }
            ThreadInfo[] infos = bean.getThreadInfo(deadlocked, true, true);
            for (ThreadInfo info : infos) {
                report.append(info).append('\n');
            }
        } catch (Throwable failure) {
            report.append("（死锁检测失败：").append(failure).append("）\n");
        }
    }

    private static void appendLogTail(StringBuilder report) {
        report.append("--- latest.log 末尾最多 ").append(LOG_TAIL_LINES).append(" 行 ---\n");
        Path log = logFile();
        if (log == null) {
            report.append("（找不到日志文件）\n");
            return;
        }
        try {
            String text = new String(readTail(log, LOG_TAIL_BYTES), StandardCharsets.UTF_8);
            String[] lines = text.split("\r?\n");
            int from = Math.max(0, lines.length - LOG_TAIL_LINES);
            if (from > 0) {
                report.append("（前面的 ").append(from).append(" 行已省略）\n");
            }
            for (int i = from; i < lines.length; i++) {
                report.append(lines[i]).append('\n');
            }
        } catch (Throwable failure) {
            report.append("（读取日志失败：").append(failure).append("）\n");
        }
    }

    private static byte[] readTail(Path file, int maxBytes) throws IOException {
        try (SeekableByteChannel channel = Files.newByteChannel(file, StandardOpenOption.READ)) {
            long size = channel.size();
            int length = (int) Math.min(size, maxBytes);
            ByteBuffer buffer = ByteBuffer.allocate(length);
            channel.position(size - length);
            while (buffer.hasRemaining() && channel.read(buffer) > 0) {
                // 读满为止
            }
            return buffer.array();
        }
    }

    // ------------------------------------------------------------ 环境读取

    private static int threadRank(Thread thread) {
        String name = thread.getName();
        if (RENDER_THREAD_NAME.equals(name)) {
            return 0;
        }
        if (name.contains("Client thread")) {
            return 1;
        }
        if ("main".equals(name)) {
            return 2;
        }
        if (name.startsWith("Server thread")) {
            return 3;
        }
        if (name.startsWith("kingdom-watchdog")) {
            return 4;
        }
        return thread.isDaemon() ? 6 : 5;
    }

    private static String screenName() {
        try {
            Minecraft minecraft = Minecraft.getInstance();
            Screen screen = minecraft == null ? null : minecraft.screen;
            return screen == null ? "（无，游戏中）" : screen.getClass().getName();
        } catch (Throwable failure) {
            return "（未知）";
        }
    }

    private static String linkedDocumentName(Minecraft minecraft) {
        try {
            if (minecraft != null && minecraft.screen instanceof ApricityContainerScreen container) {
                Document document = container.getLinkedDocument();
                return document == null ? "（容器屏，但文档为空）" : "path=" + document.getPath();
            }
        } catch (Throwable failure) {
            return "（读取失败：" + failure + "）";
        }
        return "（当前屏幕不是 AUI 容器屏）";
    }

    private static Path reportDirectory() {
        try {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft == null || minecraft.gameDirectory == null) {
                return null;
            }
            return minecraft.gameDirectory.toPath().resolve("logs").resolve(REPORT_DIRECTORY);
        } catch (Throwable failure) {
            return null;
        }
    }

    private static Path logFile() {
        Path directory = reportDirectory();
        return directory == null || directory.getParent() == null
                ? null
                : directory.getParent().resolve("latest.log");
    }
}
