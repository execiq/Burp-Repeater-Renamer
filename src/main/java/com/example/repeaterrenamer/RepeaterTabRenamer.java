package com.example.repeaterrenamer;

import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.MimeType;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.ui.contextmenu.ContextMenuEvent;
import burp.api.montoya.ui.contextmenu.ContextMenuItemsProvider;
import burp.api.montoya.ui.contextmenu.MessageEditorHttpRequestResponse;
import burp.api.montoya.ui.hotkey.HotKey;
import burp.api.montoya.ui.hotkey.HotKeyHandler;

import javax.swing.JCheckBoxMenuItem;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.text.JTextComponent;
import java.awt.Component;
import java.awt.Container;
import java.awt.Window;
import java.awt.event.ContainerAdapter;
import java.awt.event.ContainerEvent;
import java.awt.event.ContainerListener;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Burp Suite (Montoya API) extension that auto-renames new Repeater tabs from the default
 * number (1, 2, 3, ...) to "METHOD /clean/path", stripping version prefixes like
 * {@code /api/v1/}. Example: {@code GET /api/v1/user/info} becomes the tab {@code GET /user/info}.
 *
 * <h2>GraphQL</h2>
 * For GraphQL endpoints (path contains {@code graphql}) the tab is named after the operation in the
 * request body instead of the path: {@code query GetUser}, {@code mutation CreateOrder}, an anonymous
 * op as {@code query <firstField>}, and a batched request as {@code op (+N)}. The operation name comes
 * from the JSON {@code operationName} field, else from a named {@code query/mutation/subscription}
 * declaration in the document, else a best-effort first field.
 *
 * <h2>Optional "API requests only" filter</h2>
 * By default ALL endpoints sent to Repeater are renamed. Optionally, the right-click toggle
 * <i>"Auto-rename: API (JSON) responses only"</i> restricts renaming to requests whose response is
 * JSON (Content-Type / Burp-detected MIME type) — i.e. it looks like an API call. The response is
 * taken from the item you sent (e.g. the Proxy-history entry). The toggle is persisted across restarts.
 *
 * <h2>Why the Swing code is necessary</h2>
 * The Montoya {@code Repeater} interface only exposes {@code sendToRepeater(request)} and
 * {@code sendToRepeater(request, name)} — there is NO event for "the user sent a request to
 * Repeater" and NO API to rename an existing tab. So to react to Burp's <i>native</i>
 * "Send to Repeater" (and Ctrl+R) we locate the Repeater {@link JTabbedPane} in the Swing tree
 * and rename newly added default-numbered tabs via {@code setTitleAt}. This is the same
 * technique real extensions use; it is inherently version-fragile (see README).
 *
 * <h2>Mechanisms</h2>
 * <ul>
 *   <li><b>Ctrl+R (direct):</b> a {@code HotKeyHandler} bound to Ctrl+R sends the focused/selected
 *       request to Repeater itself and names the tab immediately when the response is JSON
 *       (or the filter is off). Because the handler has the request AND response in hand, the JSON
 *       filter works directly on Ctrl+R — no Swing tree-walking involved. This is the
 *       PortSwigger-sanctioned pattern (their hotkey tutorial calls {@code sendToRepeater} the same
 *       way). See {@link #SEND_HOTKEY}.</li>
 *   <li><b>Right-click "Send to Repeater" (automatic):</b> {@link Watcher} renames the new
 *       default-numbered tab, applying the JSON filter. The originating request/response is
 *       captured by {@link Capture} when the context menu opens.</li>
 *   <li><b>"Send to Repeater (auto-named)" menu item (manual override):</b> always names the tab,
 *       ignoring the filter.</li>
 * </ul>
 */
public class RepeaterTabRenamer implements BurpExtension {

    /**
     * Hotkey that drives the auto-naming send. Burp's default "Send to Repeater" shortcut is Ctrl+R,
     * so binding it here makes this extension own Ctrl+R (Burp's hotkey model is one-combo-one-command,
     * so the native send does not also fire). If on your build Ctrl+R does nothing, either the built-in
     * kept the binding (use the right-click menu instead) — or change this to e.g. "Ctrl+Alt+R".
     */
    private static final String SEND_HOTKEY = "Ctrl+R";
    private static final boolean BIND_SEND_HOTKEY = true;

    @Override
    public void initialize(MontoyaApi api) {
        api.extension().setName("Repeater Auto Tab Renamer");

        Settings settings = new Settings(api);
        Capture capture = new Capture(api, settings);
        api.userInterface().registerContextMenuItemsProvider(capture);

        Watcher watcher = new Watcher(api, capture, settings);
        watcher.start();
        api.extension().registerUnloadingHandler(watcher::stop);

        // Ctrl+R: send the focused/selected request to Repeater ourselves, naming the tab when the
        // response is JSON (or the filter is off). Non-JSON requests are still sent, just un-renamed.
        if (BIND_SEND_HOTKEY) {
            HotKeyHandler hotKeyHandler = event -> {
                capture.clear(); // don't let a stale right-click selection bleed into this hotkey send
                for (HttpRequestResponse hrr : extractPairs(
                        event.messageEditorRequestResponse(), event.selectedRequestResponses())) {
                    sendToRepeater(api, settings, hrr, true);
                }
            };
            api.userInterface().registerHotKeyHandler(
                    HotKey.hotKey("Send to Repeater (auto-rename)", SEND_HOTKEY), hotKeyHandler);
        }

        api.logging().logToOutput(
                "Repeater Auto Tab Renamer loaded. ALL endpoints sent to Repeater are auto-named: "
                        + "REST as \"METHOD /clean/path\" (version prefixes like /api/v1/ stripped), "
                        + "GraphQL as the operation name from the body (e.g. \"query GetUser\"). "
                        + "Ctrl+R names the tab directly; right-click 'Send to Repeater' is also handled. "
                        + "Optional API(JSON)-only filter = " + settings.onlyJsonApi()
                        + " (toggle from the right-click menu). "
                        + "Open the Repeater tab once so the right-click path can attach.");
    }

    /**
     * Send a request to Repeater and name its tab "METHOD /clean/path". When {@code respectFilter}
     * is true and the API-only filter is on, the tab is named only if the response is JSON; otherwise
     * the request is still sent but keeps Burp's default number.
     */
    static void sendToRepeater(MontoyaApi api, Settings settings, HttpRequestResponse hrr,
                               boolean respectFilter) {
        HttpRequest req = hrr.request();
        if (req == null) {
            return;
        }
        boolean nameIt = !respectFilter
                || !settings.onlyJsonApi()
                || isJsonResponse(hrr.hasResponse() ? hrr.response() : null);
        if (nameIt) {
            String name = TabNames.fromRequest(req.method(), req.path(), req.bodyToString());
            api.repeater().sendToRepeater(req, name);
        } else {
            api.repeater().sendToRepeater(req);
        }
    }

    /** Extract request/response pairs from a context-menu or hot-key event (editor first, else table). */
    static List<HttpRequestResponse> extractPairs(
            Optional<MessageEditorHttpRequestResponse> editor, List<HttpRequestResponse> selected) {
        List<HttpRequestResponse> out = new ArrayList<>();
        if (editor.isPresent()) {
            HttpRequestResponse hrr = editor.get().requestResponse();
            if (hrr != null && hrr.request() != null) {
                out.add(hrr);
            }
        }
        if (out.isEmpty() && selected != null) {
            for (HttpRequestResponse rr : selected) {
                if (rr.request() != null) {
                    out.add(rr);
                }
            }
        }
        return out;
    }

    // ====================================================================================
    //  Settings — the runtime-toggleable, persisted "API (JSON) only" flag.
    // ====================================================================================

    static final class Settings {
        private static final String KEY_ONLY_JSON = "repeaterRenamer.onlyJsonApi";

        private final MontoyaApi api;
        // Default: rename ALL endpoints sent to Repeater. Turn this on (via the menu) to restrict
        // auto-renaming to requests whose response is JSON.
        private boolean onlyJsonApi = false;

        Settings(MontoyaApi api) {
            this.api = api;
            Boolean saved = api.persistence().preferences().getBoolean(KEY_ONLY_JSON);
            if (saved != null) {
                onlyJsonApi = saved;
            }
        }

        boolean onlyJsonApi() {
            return onlyJsonApi;
        }

        void setOnlyJsonApi(boolean value) {
            onlyJsonApi = value;
            api.persistence().preferences().setBoolean(KEY_ONLY_JSON, value);
            api.logging().logToOutput("Repeater Auto Tab Renamer: API(JSON)-only renaming = " + value);
        }
    }

    /** True if the response looks like JSON (i.e. the request is an API call). */
    static boolean isJsonResponse(HttpResponse resp) {
        if (resp == null) {
            return false;
        }
        if (resp.mimeType() == MimeType.JSON
                || resp.statedMimeType() == MimeType.JSON
                || resp.inferredMimeType() == MimeType.JSON) {
            return true;
        }
        // Catch variants Burp may not classify as JSON: text/json, application/vnd.api+json, ...
        String contentType = resp.headerValue("Content-Type");
        return contentType != null && contentType.toLowerCase().contains("json");
    }

    // ====================================================================================
    //  Capture — records the right-click selection (request + response) and adds the menu.
    // ====================================================================================

    static final class Capture implements ContextMenuItemsProvider {

        /** Ignore captures older than this; avoids binding a stale selection to an unrelated tab. */
        private static final long FRESH_MS = 5_000;

        private final MontoyaApi api;
        private final Settings settings;
        private final Deque<Captured> recent = new ArrayDeque<>(); // EDT-confined

        Capture(MontoyaApi api, Settings settings) {
            this.api = api;
            this.settings = settings;
        }

        private record Captured(HttpRequestResponse pair, long timestamp) {
        }

        @Override
        public List<Component> provideMenuItems(ContextMenuEvent event) {
            List<HttpRequestResponse> pairs =
                    extractPairs(event.messageEditorRequestResponse(), event.selectedRequestResponses());

            // A fresh right-click always supersedes any previous selection.
            recent.clear();
            long now = System.currentTimeMillis();
            for (HttpRequestResponse hrr : pairs) {
                recent.addLast(new Captured(hrr, now));
            }

            if (pairs.isEmpty()) {
                return Collections.emptyList();
            }

            // Manual override: explicit named send that ignores the JSON filter.
            JMenuItem named = new JMenuItem("Send to Repeater (auto-named)");
            named.addActionListener(e -> {
                for (HttpRequestResponse hrr : pairs) {
                    sendToRepeater(api, settings, hrr, false);
                }
            });

            // Runtime toggle for the "API (JSON) only" filter.
            JCheckBoxMenuItem toggle =
                    new JCheckBoxMenuItem("Auto-rename: API (JSON) responses only", settings.onlyJsonApi());
            toggle.addActionListener(e -> settings.setOnlyJsonApi(toggle.isSelected()));

            List<Component> items = new ArrayList<>(2);
            items.add(named);
            items.add(toggle);
            return items;
        }

        /** Consume the oldest still-fresh captured pair, or null if none. */
        HttpRequestResponse pollRecent() {
            long now = System.currentTimeMillis();
            while (!recent.isEmpty()) {
                Captured c = recent.pollFirst();
                if (now - c.timestamp() <= FRESH_MS) {
                    return c.pair();
                }
            }
            return null;
        }

        /** Drop any buffered selection (used before a hotkey send to avoid stale cross-binding). */
        void clear() {
            recent.clear();
        }
    }

    // ====================================================================================
    //  Watcher — the Swing half: find the Repeater tab pane and rename new numbered tabs.
    // ====================================================================================

    static final class Watcher {
        private static final int DISCOVERY_INTERVAL_MS = 1_500;
        private static final int RENAME_DELAY_MS = 150;
        private static final int MAX_ATTEMPTS = 8;
        private static final boolean RENAME_EXISTING_ON_ATTACH = true;

        private final MontoyaApi api;
        private final Capture capture;
        private final Settings settings;

        private Timer discoveryTimer;
        private JTabbedPane repeaterTabs;
        private boolean attached = false;

        private final ContainerListener containerListener = new ContainerAdapter() {
            @Override
            public void componentAdded(ContainerEvent e) {
                scheduleRename(e.getChild(), 0);
            }
        };

        Watcher(MontoyaApi api, Capture capture, Settings settings) {
            this.api = api;
            this.capture = capture;
            this.settings = settings;
        }

        void start() {
            SwingUtilities.invokeLater(() -> {
                tryAttach();
                if (!attached) {
                    discoveryTimer = new Timer(DISCOVERY_INTERVAL_MS, e -> tryAttach());
                    discoveryTimer.start();
                }
            });
        }

        void stop() {
            SwingUtilities.invokeLater(() -> {
                if (discoveryTimer != null) {
                    discoveryTimer.stop();
                }
                if (repeaterTabs != null) {
                    repeaterTabs.removeContainerListener(containerListener);
                }
                attached = false;
            });
        }

        private void tryAttach() {
            if (attached) {
                return;
            }
            JTabbedPane tabs = findRepeaterTabs();
            if (tabs == null) {
                return; // Repeater not realised yet; retry on the next tick
            }
            repeaterTabs = tabs;
            tabs.addContainerListener(containerListener);
            attached = true;
            if (discoveryTimer != null) {
                discoveryTimer.stop();
            }
            api.logging().logToOutput("Repeater Auto Tab Renamer: attached to the Repeater tab pane.");
            // Existing tabs have no captured response, so they can't pass the JSON filter;
            // only rename pre-existing tabs when the filter is off.
            if (RENAME_EXISTING_ON_ATTACH && !settings.onlyJsonApi()) {
                renameExisting(tabs);
            }
        }

        // ---- discovery ----------------------------------------------------------------

        private JTabbedPane findRepeaterTabs() {
            for (Window w : Window.getWindows()) {
                if (!w.isShowing()) {
                    continue;
                }
                JTabbedPane mainTabs = findTabbedPaneWithTab(w, "Repeater");
                if (mainTabs == null) {
                    continue;
                }
                int idx = indexOfTab(mainTabs, "Repeater");
                if (idx < 0) {
                    continue;
                }
                JTabbedPane inner = pickNumberedTabsPane(mainTabs.getComponentAt(idx));
                if (inner != null) {
                    return inner;
                }
            }
            return null;
        }

        // ---- renaming -----------------------------------------------------------------

        private void scheduleRename(Component child, int attempt) {
            Timer t = new Timer(RENAME_DELAY_MS, e -> attemptRename(child, attempt));
            t.setRepeats(false);
            t.start();
        }

        private void attemptRename(Component child, int attempt) {
            JTabbedPane tabs = repeaterTabs;
            if (tabs == null) {
                return;
            }

            int idx = tabs.indexOfComponent(child);
            if (idx < 0) {
                if (attempt < MAX_ATTEMPTS) {
                    scheduleRename(child, attempt + 1);
                }
                return;
            }

            // Only touch Burp's default numeric tabs: never clobber hand-renamed tabs,
            // tabs we already named, or the trailing "new tab" control.
            String currentTitle = tabs.getTitleAt(idx);
            if (currentTitle == null
                    || !TabNames.DEFAULT_TAB_TITLE.matcher(currentTitle.trim()).matches()) {
                return;
            }

            String name;
            String host;

            if (settings.onlyJsonApi()) {
                // API-only mode: require a captured response that is JSON.
                HttpRequestResponse captured = capture.pollRecent();
                if (captured == null) {
                    return; // no originating response to inspect -> can't confirm API -> skip
                }
                HttpResponse resp = captured.hasResponse() ? captured.response() : null;
                if (!isJsonResponse(resp)) {
                    return; // not JSON -> not an API request -> leave the default number
                }
                HttpRequest req = captured.request();
                name = TabNames.fromRequest(req.method(), req.path(), req.bodyToString());
                host = (req.httpService() != null) ? req.httpService().host() : null;
            } else {
                // Unfiltered: name everything. Read the request from the new tab's editor,
                // falling back to the captured selection if the editor can't be read.
                Scan s = scan(child);
                if (s.requestText != null) {
                    name = TabNames.fromRequestText(s.requestText);
                    host = TabNames.hostFromRequestText(s.requestText);
                } else if (s.sawTextComponent && attempt < MAX_ATTEMPTS) {
                    scheduleRename(child, attempt + 1); // editor present but not populated yet
                    return;
                } else {
                    HttpRequestResponse captured = capture.pollRecent();
                    if (captured == null) {
                        return; // genuinely empty tab (e.g. user clicked "+"): keep its number
                    }
                    HttpRequest req = captured.request();
                    name = TabNames.fromMethodAndTarget(req.method(), req.path());
                    host = (req.httpService() != null) ? req.httpService().host() : null;
                }
            }

            if (name == null) {
                return;
            }
            applyTitle(tabs, idx, TabNames.makeUnique(name, host, visibleTitlesExcept(tabs, idx)));
        }

        private void renameExisting(JTabbedPane tabs) {
            for (int i = 0; i < tabs.getTabCount(); i++) {
                String title = tabs.getTitleAt(i);
                if (title == null
                        || !TabNames.DEFAULT_TAB_TITLE.matcher(title.trim()).matches()) {
                    continue;
                }
                Scan s = scan(tabs.getComponentAt(i));
                if (s.requestText == null) {
                    continue;
                }
                String name = TabNames.fromRequestText(s.requestText);
                if (name == null) {
                    continue;
                }
                applyTitle(tabs, i, TabNames.makeUnique(name,
                        TabNames.hostFromRequestText(s.requestText), visibleTitlesExcept(tabs, i)));
            }
        }

        private Set<String> visibleTitlesExcept(JTabbedPane tabs, int except) {
            Set<String> titles = new HashSet<>();
            for (int i = 0; i < tabs.getTabCount(); i++) {
                if (i == except) {
                    continue;
                }
                String t = tabs.getTitleAt(i);
                if (t != null) {
                    titles.add(t);
                }
                JLabel lbl = firstLabel(tabs.getTabComponentAt(i));
                if (lbl != null && lbl.getText() != null) {
                    titles.add(lbl.getText());
                }
            }
            return titles;
        }

        private void applyTitle(JTabbedPane tabs, int idx, String name) {
            tabs.setTitleAt(idx, name);
            tabs.setToolTipTextAt(idx, name);
            // Modern Burp renders a custom component (label + close button) per tab, which ignores
            // the model title, so update the label text directly as well.
            JLabel lbl = firstLabel(tabs.getTabComponentAt(idx));
            if (lbl != null) {
                lbl.setText(name);
            }
            api.logging().logToOutput("Repeater Auto Tab Renamer: tab #" + (idx + 1) + " -> " + name);
        }
    }

    // ====================================================================================
    //  Shared Swing helpers
    // ====================================================================================

    private static JTabbedPane findTabbedPaneWithTab(Component root, String tabTitle) {
        for (JTabbedPane p : collectTabbedPanes(root)) {
            if (indexOfTab(p, tabTitle) >= 0) {
                return p;
            }
        }
        return null;
    }

    /** Inside the Repeater page, the numbered-tabs pane is the shallowest tabbed pane. */
    private static JTabbedPane pickNumberedTabsPane(Component repeaterPage) {
        List<JTabbedPane> panes = collectTabbedPanes(repeaterPage);
        for (JTabbedPane p : panes) {
            for (int i = 0; i < p.getTabCount(); i++) {
                String t = p.getTitleAt(i);
                if (t != null && TabNames.DEFAULT_TAB_TITLE.matcher(t.trim()).matches()) {
                    return p;
                }
            }
        }
        return panes.isEmpty() ? null : panes.get(0);
    }

    /** Breadth-first collection, so index 0 is the shallowest pane. */
    private static List<JTabbedPane> collectTabbedPanes(Component root) {
        List<JTabbedPane> out = new ArrayList<>();
        Deque<Component> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            Component c = queue.poll();
            if (c instanceof JTabbedPane p) {
                out.add(p);
            }
            if (c instanceof Container con) {
                for (Component child : con.getComponents()) {
                    queue.add(child);
                }
            }
        }
        return out;
    }

    private static int indexOfTab(JTabbedPane pane, String tabTitle) {
        for (int i = 0; i < pane.getTabCount(); i++) {
            String t = pane.getTitleAt(i);
            if (t != null && t.trim().equalsIgnoreCase(tabTitle)) {
                return i;
            }
        }
        return -1;
    }

    private static JLabel firstLabel(Component root) {
        if (root == null) {
            return null;
        }
        Deque<Component> stack = new ArrayDeque<>();
        stack.push(root);
        while (!stack.isEmpty()) {
            Component c = stack.pop();
            if (c instanceof JLabel l) {
                return l;
            }
            if (c instanceof Container con) {
                for (Component child : con.getComponents()) {
                    stack.push(child);
                }
            }
        }
        return null;
    }

    /** Result of scanning a tab's component tree for the request editor. */
    private static final class Scan {
        boolean sawTextComponent = false;
        String requestText = null;
    }

    private static Scan scan(Component root) {
        Scan s = new Scan();
        if (root == null) {
            return s;
        }
        Deque<Component> stack = new ArrayDeque<>();
        stack.push(root);
        while (!stack.isEmpty()) {
            Component c = stack.pop();
            if (c instanceof JTextComponent tc) {
                s.sawTextComponent = true;
                if (s.requestText == null) {
                    String text = tc.getText();
                    if (text != null && !text.isEmpty()
                            && TabNames.looksLikeRequestLine(TabNames.firstLine(text))) {
                        s.requestText = text;
                    }
                }
            }
            if (c instanceof Container con) {
                for (Component child : con.getComponents()) {
                    stack.push(child);
                }
            }
        }
        return s;
    }

    // ====================================================================================
    //  TabNames — pure naming rules (strip version prefix, dedupe, etc.)
    // ====================================================================================

    static final class TabNames {
        private TabNames() {
        }

        /** Optional leading {@code /api/} and/or {@code /vN/} segment, anchored at the start. */
        private static final Pattern VERSION_PREFIX =
                Pattern.compile("^/(?:api/)?(?:v\\d+/)?", Pattern.CASE_INSENSITIVE);
        /** First line of an HTTP request, e.g. {@code "GET /a/b?x=1 HTTP/1.1"}. */
        private static final Pattern REQUEST_LINE =
                Pattern.compile("^([A-Za-z]+)\\s+(\\S+)\\s+HTTP/\\d(?:\\.\\d)?\\s*$");
        /** Burp's default tab caption is a bare number: 1, 2, 3, ... */
        static final Pattern DEFAULT_TAB_TITLE = Pattern.compile("\\d+");

        // GraphQL detection / operation-name extraction.
        private static final Pattern GQL_OP_NAME_JSON =
                Pattern.compile("\"operationName\"\\s*:\\s*\"([^\"\\\\]+)\"");
        private static final Pattern GQL_OP_NAME_PARAM =
                Pattern.compile("[?&]operationName=([A-Za-z0-9_]+)");
        private static final Pattern GQL_QUERY_VALUE =
                Pattern.compile("\"query\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");
        private static final Pattern GQL_OP_DECL =
                Pattern.compile("\\b(query|mutation|subscription)\\s+([A-Za-z_][A-Za-z0-9_]*)");
        private static final Pattern GQL_ANON_TYPE =
                Pattern.compile("\\b(query|mutation|subscription)\\s*[(\\{]");

        static boolean looksLikeRequestLine(String line) {
            return line != null && REQUEST_LINE.matcher(line.trim()).matches();
        }

        static String firstLine(String text) {
            if (text == null) {
                return "";
            }
            String t = text.stripLeading();
            int nl = t.indexOf('\n');
            if (nl >= 0) {
                t = t.substring(0, nl);
            }
            return t.stripTrailing();
        }

        static String fromRequestText(String requestText) {
            String line = firstLine(requestText);
            Matcher m = REQUEST_LINE.matcher(line.trim());
            if (!m.matches()) {
                return null;
            }
            return fromRequest(m.group(1), m.group(2), bodyOf(requestText));
        }

        static String fromRequestLine(String line) {
            if (line == null) {
                return null;
            }
            Matcher m = REQUEST_LINE.matcher(line.trim());
            if (!m.matches()) {
                return null;
            }
            return fromRequest(m.group(1), m.group(2), null);
        }

        static String fromMethodAndTarget(String method, String target) {
            return fromRequest(method, target, null);
        }

        /**
         * Build a tab name. For GraphQL endpoints the name is the operation read from the request
         * body (e.g. "query GetUser", "mutation CreateOrder"); otherwise it is "METHOD /clean/path".
         */
        static String fromRequest(String method, String target, String body) {
            if (isGraphQlEndpoint(target)) {
                String source = (body != null && !body.isBlank()) ? body : target;
                String label = graphQlLabel(source);
                if (label == null && body != null && !body.isBlank()) {
                    label = graphQlLabel(target); // GET ?operationName=... fallback
                }
                if (label != null) {
                    return label;
                }
            }
            return method.toUpperCase() + " " + cleanPath(target);
        }

        // ---- GraphQL helpers --------------------------------------------------------------

        static boolean isGraphQlEndpoint(String target) {
            return target != null && cleanPath(target).toLowerCase().contains("graphql");
        }

        /** Derive a label like "query GetUser" / "mutation X (+2)" from a GraphQL request, or null. */
        static String graphQlLabel(String text) {
            if (text == null || text.isEmpty()) {
                return null;
            }
            // 1. Explicit operationName (JSON body, then GET query param).
            List<String> ops = new ArrayList<>();
            Matcher mj = GQL_OP_NAME_JSON.matcher(text);
            while (mj.find()) {
                addUnique(ops, mj.group(1).trim());
            }
            if (ops.isEmpty()) {
                Matcher mp = GQL_OP_NAME_PARAM.matcher(text);
                while (mp.find()) {
                    addUnique(ops, mp.group(1).trim());
                }
            }
            String doc = graphQlDocument(text);
            if (!ops.isEmpty()) {
                return formatOps(ops, typeFor(doc, ops.get(0)));
            }
            // 2. Named operations declared in the document.
            List<String> named = new ArrayList<>();
            String firstType = null;
            Matcher md = GQL_OP_DECL.matcher(doc);
            while (md.find()) {
                if (firstType == null) {
                    firstType = md.group(1);
                }
                addUnique(named, md.group(2));
            }
            if (!named.isEmpty()) {
                return formatOps(named, firstType);
            }
            // 3. Anonymous operation: operation type + first selected field.
            String field = firstSelectionField(doc);
            if (field != null) {
                Matcher ma = GQL_ANON_TYPE.matcher(doc);
                String type = ma.find() ? ma.group(1) : "query";
                return type + " " + field;
            }
            return null;
        }

        /** Pull and JSON-unescape the GraphQL document from {@code "query":"..."}, else return text. */
        private static String graphQlDocument(String text) {
            Matcher m = GQL_QUERY_VALUE.matcher(text);
            return m.find() ? unescapeJson(m.group(1)) : text;
        }

        private static String typeFor(String doc, String name) {
            Matcher m = Pattern.compile(
                    "\\b(query|mutation|subscription)\\s+" + Pattern.quote(name) + "\\b").matcher(doc);
            return m.find() ? m.group(1) : null;
        }

        private static String formatOps(List<String> ops, String type) {
            String head = (type != null) ? type + " " + ops.get(0) : ops.get(0);
            if (ops.size() > 1) {
                head += " (+" + (ops.size() - 1) + ")";
            }
            return head;
        }

        private static void addUnique(List<String> list, String value) {
            if (value != null && !value.isEmpty() && !list.contains(value)) {
                list.add(value);
            }
        }

        /** Best-effort first field name inside the outermost selection set of an anonymous op. */
        private static String firstSelectionField(String doc) {
            int brace = doc.indexOf('{');
            if (brace < 0) {
                return null;
            }
            int i = brace + 1;
            int guard = 0;
            while (i < doc.length() && guard++ < 80) {
                char c = doc.charAt(i);
                if (Character.isLetter(c) || c == '_') {
                    break;
                }
                i++;
            }
            int start = i;
            while (i < doc.length()
                    && (Character.isLetterOrDigit(doc.charAt(i)) || doc.charAt(i) == '_')) {
                i++;
            }
            return i > start ? doc.substring(start, i) : null;
        }

        private static String unescapeJson(String s) {
            StringBuilder b = new StringBuilder(s.length());
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '\\' && i + 1 < s.length()) {
                    char n = s.charAt(++i);
                    switch (n) {
                        case 'n', 'r', 't', 'f', 'b' -> b.append(' ');
                        case '"' -> b.append('"');
                        case '\\' -> b.append('\\');
                        case '/' -> b.append('/');
                        case 'u' -> {
                            if (i + 4 < s.length()) {
                                i += 4;
                            }
                            b.append(' ');
                        }
                        default -> b.append(n);
                    }
                } else {
                    b.append(c);
                }
            }
            return b.toString();
        }

        /** Request body: everything after the first blank line, or null. */
        static String bodyOf(String text) {
            if (text == null) {
                return null;
            }
            int idx = text.indexOf("\r\n\r\n");
            int sep = 4;
            if (idx < 0) {
                idx = text.indexOf("\n\n");
                sep = 2;
            }
            return idx < 0 ? null : text.substring(idx + sep);
        }

        /** Strip query/fragment, unwrap absolute-URI targets, and remove a leading version prefix. */
        static String cleanPath(String target) {
            if (target == null || target.isEmpty()) {
                return "/";
            }
            String path = target;
            int q = path.indexOf('?');
            if (q >= 0) {
                path = path.substring(0, q);
            }
            int h = path.indexOf('#');
            if (h >= 0) {
                path = path.substring(0, h);
            }
            int scheme = path.indexOf("://"); // proxy-style absolute URI
            if (scheme >= 0) {
                int slash = path.indexOf('/', scheme + 3);
                path = (slash >= 0) ? path.substring(slash) : "/";
            }
            if (path.isEmpty()) {
                path = "/";
            }
            if (path.charAt(0) != '/') {
                path = "/" + path;
            }
            String stripped = VERSION_PREFIX.matcher(path).replaceFirst("/");
            if (stripped.isEmpty()) {
                stripped = "/";
            }
            if (stripped.charAt(0) != '/') {
                stripped = "/" + stripped;
            }
            return stripped;
        }

        static String hostFromRequestText(String requestText) {
            if (requestText == null) {
                return null;
            }
            for (String raw : requestText.split("\n")) {
                String line = raw.trim();
                if (line.isEmpty()) {
                    break; // end of headers
                }
                int c = line.indexOf(':');
                if (c > 0 && line.substring(0, c).trim().equalsIgnoreCase("Host")) {
                    String host = line.substring(c + 1).trim();
                    int colon = host.indexOf(':');
                    if (colon >= 0) {
                        host = host.substring(0, colon);
                    }
                    return host.isEmpty() ? null : host;
                }
            }
            return null;
        }

        static String makeUnique(String base, String host, Set<String> existing) {
            if (!existing.contains(base)) {
                return base;
            }
            if (host != null && !host.isBlank()) {
                String withHost = base + " @ " + host;
                if (!existing.contains(withHost)) {
                    return withHost;
                }
            }
            int n = 2;
            while (true) {
                String candidate = base + " (" + n + ")";
                if (!existing.contains(candidate)) {
                    return candidate;
                }
                n++;
            }
        }
    }
}
