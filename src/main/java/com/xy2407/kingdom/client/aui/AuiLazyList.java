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
 * 长列表分批渲染：首屏只建一批 DOM，滚动到底再追加下一批。
 */
@OnlyIn(Dist.CLIENT)
public final class AuiLazyList {

    private static final int FIRST_PAGE = 20;
    private static final int PAGE_STEP = 20;
    private static final double BOTTOM_PX = 64.0D;
    private static final double LOAD_RATIO = 0.75D;
    private static final double ROW_ESTIMATE = 30.0D;
    private static final int FILL_PASSES = 6;

    private static final Map<Element, State> STATES = new WeakHashMap<>();

    private AuiLazyList() {
    }

    public static void render(Element host, int total, IntFunction<String> rowHtml, IntConsumer bindRow) {
        if (host == null) {
            return;
        }
        State state = STATES.computeIfAbsent(host, key -> new State());
        state.total = Math.max(0, total);
        state.rowHtml = rowHtml;
        state.bindRow = bindRow;
        state.rendered = 0;
        host.setInnerHTML("");
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
            if (!shouldLoad(host)) {
                return;
            }
            appendBatch(host, current);
            fillViewport(host, current);
        });
    }

    private static void appendBatch(Element host, State state) {
        int from = state.rendered;
        int to = Math.min(state.total, from + (from == 0 ? FIRST_PAGE : PAGE_STEP));
        if (to <= from) {
            return;
        }
        StringBuilder html = new StringBuilder();
        for (int index = from; index < to; index++) {
            String row = state.rowHtml == null ? null : state.rowHtml.apply(index);
            if (row != null) {
                html.append(row);
            }
        }
        state.rendered = to;
        appendHtml(host, html.toString());
        if (state.bindRow != null) {
            for (int index = from; index < to; index++) {
                state.bindRow.accept(index);
            }
        }
    }

    private static void fillViewport(Element host, State state) {
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
            double content = host.scrollHeight;
            if (content > view + BOTTOM_PX) {
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

    private static boolean shouldLoad(Element host) {
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
        private IntFunction<String> rowHtml;
        private IntConsumer bindRow;
    }
}
