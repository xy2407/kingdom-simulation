package com.xy2407.kingdom.client.aui;

import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;

import com.sighs.apricityui.init.Document;
import com.sighs.apricityui.init.Element;
import com.sighs.apricityui.init.Node;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * 长列表分批渲染：首屏一批、滚动追加，并校验 DOM 行数与账面一致以防重复。
 */
@OnlyIn(Dist.CLIENT)
public final class AuiLazyList {

    private static final int FIRST_PAGE = 200;
    private static final int PAGE_STEP = 200;
    private static final int H_FIRST_PAGE = 24;
    private static final int H_PAGE_STEP = 24;
    private static final double BOTTOM_PX = 64.0D;
    private static final double LOAD_RATIO = 0.75D;
    private static final double ROW_ESTIMATE = 30.0D;
    private static final double CARD_ESTIMATE = 168.0D;
    private static final int FILL_PASSES = 6;

    private static final Map<Element, State> STATES = new WeakHashMap<>();

    private AuiLazyList() {
    }

    public static void render(Element host, int total, IntFunction<String> rowHtml, IntConsumer bindRow) {
        render(host, total, rowHtml, bindRow, false);
    }

    public static void renderHorizontal(Element host, int total, IntFunction<String> rowHtml, IntConsumer bindRow) {
        render(host, total, rowHtml, bindRow, true);
    }

    private static void render(Element host, int total, IntFunction<String> rowHtml, IntConsumer bindRow,
                               boolean horizontal) {
        if (host == null) {
            return;
        }
        State state = STATES.computeIfAbsent(host, key -> new State());
        state.total = Math.max(0, total);
        state.rowHtml = rowHtml;
        state.bindRow = bindRow;
        state.horizontal = horizontal;
        state.rendered = 0;
        clearHost(host);
        listen(host, state);
        appendBatch(host, state);
        fillViewport(host, state);
    }

    public static void clear(Element host) {
        if (host != null) {
            STATES.remove(host);
        }
    }

    private static void listen(Element host, State state) {
        if (state.listening) {
            return;
        }
        state.listening = true;
        host.addEventListener("scroll", event -> {
            State current = STATES.get(host);
            if (current == null || current.rendered >= current.total) {
                return;
            }
            if (!shouldLoad(host, current)) {
                return;
            }
            appendBatch(host, current);
            fillViewport(host, current);
        });
    }

    private static void appendBatch(Element host, State state) {
        if (host.childNodes.size() != state.rendered) {
            rebuild(host, state);
            return;
        }
        if (state.rendered >= state.total) {
            return;
        }
        int from = state.rendered;
        int step;
        if (state.horizontal) {
            step = from == 0 ? H_FIRST_PAGE : H_PAGE_STEP;
        } else {
            step = from == 0 ? FIRST_PAGE : PAGE_STEP;
        }
        int to = Math.min(state.total, from + step);
        if (to <= from) {
            return;
        }
        state.rendered = to;
        appendHtml(host, rows(state, from, to));
        bind(state, from, to);
    }

    private static void rebuild(Element host, State state) {
        int keep = Math.min(state.rendered, state.total);
        String html = rows(state, 0, keep);
        state.rendered = 0;
        clearHost(host);
        state.rendered = keep;
        appendHtml(host, html);
        bind(state, 0, keep);
    }

    private static String rows(State state, int from, int to) {
        StringBuilder html = new StringBuilder();
        for (int index = from; index < to; index++) {
            String row = state.rowHtml == null ? null : state.rowHtml.apply(index);
            if (row != null) {
                html.append(row);
            }
        }
        return html.toString();
    }

    private static void bind(State state, int from, int to) {
        if (state.bindRow == null) {
            return;
        }
        for (int index = from; index < to; index++) {
            state.bindRow.accept(index);
        }
    }

    private static void fillViewport(Element host, State state) {
        if (state.horizontal) {
            double width = host.getClientWidth();
            if (width <= 0.0D) {
                return;
            }
            int enough = (int) Math.ceil(width * 1.25D / CARD_ESTIMATE);
            for (int pass = 0; pass < FILL_PASSES && state.rendered < state.total && state.rendered < enough; pass++) {
                appendBatch(host, state);
            }
            return;
        }
        double view = host.getClientHeight();
        if (view <= 0.0D) {
            return;
        }
        if (host.scrollHeight <= 0.0D) {
            int enough = (int) Math.ceil(view * 1.25D / ROW_ESTIMATE);
            for (int pass = 0; pass < FILL_PASSES && state.rendered < state.total && state.rendered < enough; pass++) {
                appendBatch(host, state);
            }
            return;
        }
        for (int pass = 0; pass < FILL_PASSES && state.rendered < state.total; pass++) {
            if (host.scrollHeight > view + BOTTOM_PX) {
                return;
            }
            appendBatch(host, state);
        }
    }

    private static void appendHtml(Element host, String html) {
        if (html == null || html.isEmpty()) {
            return;
        }
        Document document = host.document;
        if (document == null) {
            return;
        }
        Element wrapper = document.createHTML("<div>" + html + "</div>");
        if (wrapper == null) {
            return;
        }
        ArrayList<Node> children = new ArrayList<>(wrapper.childNodes);
        for (Node child : children) {
            host.appendChild(child);
        }
    }

    private static void clearHost(Element host) {
        ArrayList<Node> children = new ArrayList<>(host.childNodes);
        for (Node child : children) {
            host.removeChild(child);
        }
    }

    private static boolean shouldLoad(Element host, State state) {
        if (state.horizontal) {
            double width = host.getClientWidth();
            return width <= 0.0D || host.getScrollLeft() >= width * LOAD_RATIO;
        }
        double top = host.getScrollTop();
        double view = host.getClientHeight();
        double content = host.scrollHeight;
        if (view <= 0.0D) {
            return true;
        }
        if (content > view && top + view >= content - BOTTOM_PX) {
            return true;
        }
        return top >= view * LOAD_RATIO;
    }

    private static final class State {
        private int total;
        private int rendered;
        private boolean listening;
        private boolean horizontal;
        private IntFunction<String> rowHtml;
        private IntConsumer bindRow;
    }
}
