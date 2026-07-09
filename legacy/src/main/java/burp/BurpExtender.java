package burp;

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
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LEGACY-API equivalent of the Montoya extension, for Burp Suite older than 2023.
 * Self-contained single file; uses the deprecated
 * {@code net.portswigger.burp.extender:burp-extender-api:2.3}.
 *
 * <p>Like Montoya, the legacy API has NO native hook for "a request was sent to Repeater" and
 * NO tab-rename method, so the same Swing technique is required: locate the Repeater
 * {@link JTabbedPane} and rename newly added default-numbered tabs.
 *
 * <p><b>API-only filter:</b> when enabled (default), a tab is renamed only if the originating
 * response is JSON (i.e. it looks like an API call). Toggle it from the right-click menu item
 * "Auto-rename: API (JSON) responses only"; the choice is persisted via Burp extension settings.
 */
public class BurpExtender implements IBurpExtender, IContextMenuFactory, IExtensionStateListener {

    private static final String KEY_ONLY_JSON = "repeaterRenamer.onlyJsonApi";

    private IBurpExtenderCallbacks callbacks;
    private IExtensionHelpers helpers;
    private boolean onlyJsonApi = false; // default: rename ALL endpoints; toggle on for JSON-only

    private final RepeaterTabWatcher watcher = new RepeaterTabWatcher();
    private final RequestCapture capture = new RequestCapture();

    @Override
    public void registerExtenderCallbacks(IBurpExtenderCallbacks callbacks) {
        this.callbacks = callbacks;
        this.helpers = callbacks.getHelpers();
        callbacks.setExtensionName("Repeater Auto Tab Renamer (legacy)");

        String saved = callbacks.loadExtensionSetting(KEY_ONLY_JSON);
        if (saved != null) {
            onlyJsonApi = Boolean.parseBoolean(saved);
        }

        callbacks.registerContextMenuFactory(this);
        callbacks.registerExtensionStateListener(this);
        watcher.start();
        callbacks.printOutput("Repeater Auto Tab Renamer (legacy) loaded. ALL endpoints are renamed: "
                + "REST as \"METHOD /clean/path\", GraphQL as the body operation name (e.g. "
                + "\"query GetUser\"). Optional API(JSON)-only filter = " + onlyJsonApi
                + " (toggle from the right-click menu). Open the Repeater tab once so it can attach. "
                + "(Note: Ctrl+R auto-naming is Montoya-only; this legacy build covers right-click.)");
    }

    @Override
    public void extensionUnloaded() {
        watcher.stop();
    }

    // ------------------------------------------------------------------ context menu

    @Override
    public List<JMenuItem> createMenuItems(IContextMenuInvocation invocation) {
        IHttpRequestResponse[] selected = invocation.getSelectedMessages();
        if (selected == null || selected.length == 0) {
            return null;
        }

        // Capture the current selection so the watcher can name the next native send.
        capture.replace(selected);

        List<JMenuItem> items = new ArrayList<>(2);

        // Approach B: a fully-supported named send (manual override, ignores the filter).
        JMenuItem named = new JMenuItem("Send to Repeater (auto-named)");
        named.addActionListener(e -> {
            for (IHttpRequestResponse m : selected) {
                IHttpService svc = m.getHttpService();
                if (svc == null || m.getRequest() == null) {
                    continue;
                }
                String name = nameFromRequest(m.getRequest());
                callbacks.sendToRepeater(svc.getHost(), svc.getPort(),
                        "https".equalsIgnoreCase(svc.getProtocol()), m.getRequest(), name);
            }
        });
        items.add(named);

        // Runtime toggle for the "API (JSON) only" filter.
        JCheckBoxMenuItem toggle =
                new JCheckBoxMenuItem("Auto-rename: API (JSON) responses only", onlyJsonApi);
        toggle.addActionListener(e -> {
            onlyJsonApi = toggle.isSelected();
            callbacks.saveExtensionSetting(KEY_ONLY_JSON, String.valueOf(onlyJsonApi));
            callbacks.printOutput("Repeater Auto Tab Renamer (legacy): API(JSON)-only = " + onlyJsonApi);
        });
        items.add(toggle);

        return items;
    }

    private static String nameFromRequest(byte[] request) {
        String text = new String(request, StandardCharsets.ISO_8859_1);
        String name = Names.fromRequestText(text);
        return name != null ? name : "Tab";
    }

    /** True if the response looks like JSON (i.e. the request is an API call). */
    private boolean isJsonResponse(byte[] response) {
        if (response == null || response.length == 0) {
            return false;
        }
        IResponseInfo ri = helpers.analyzeResponse(response);
        if ("JSON".equalsIgnoreCase(ri.getStatedMimeType())
                || "JSON".equalsIgnoreCase(ri.getInferredMimeType())) {
            return true;
        }
        for (String header : ri.getHeaders()) {
            String h = header.toLowerCase();
            if (h.startsWith("content-type:") && h.contains("json")) {
                return true;
            }
        }
        return false;
    }

    // ============================================================ Swing watcher (legacy)

    private final class RepeaterTabWatcher {
        private static final int DISCOVERY_INTERVAL_MS = 1_500;
        private static final int RENAME_DELAY_MS = 150;
        private static final int MAX_ATTEMPTS = 8;
        private static final boolean RENAME_EXISTING_ON_ATTACH = true;

        private Timer discoveryTimer;
        private JTabbedPane repeaterTabs;
        private boolean attached = false;

        private final ContainerListener containerListener = new ContainerAdapter() {
            @Override
            public void componentAdded(ContainerEvent e) {
                scheduleRename(e.getChild(), 0);
            }
        };

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
                return;
            }
            repeaterTabs = tabs;
            tabs.addContainerListener(containerListener);
            attached = true;
            if (discoveryTimer != null) {
                discoveryTimer.stop();
            }
            callbacks.printOutput("Repeater Auto Tab Renamer (legacy): attached to Repeater tab pane.");
            // Existing tabs have no captured response, so they can't pass the JSON filter.
            if (RENAME_EXISTING_ON_ATTACH && !onlyJsonApi) {
                renameExisting(tabs);
            }
        }

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
            String currentTitle = tabs.getTitleAt(idx);
            if (currentTitle == null || !Names.DEFAULT_TAB_TITLE.matcher(currentTitle.trim()).matches()) {
                return;
            }

            String name;
            String host;

            if (onlyJsonApi) {
                // API-only mode: require a captured response that is JSON.
                IHttpRequestResponse hrr = capture.poll();
                if (hrr == null || hrr.getRequest() == null) {
                    return;
                }
                if (!isJsonResponse(hrr.getResponse())) {
                    return; // not JSON -> not an API request -> keep the default number
                }
                String text = new String(hrr.getRequest(), StandardCharsets.ISO_8859_1);
                name = Names.fromRequestText(text);
                host = Names.hostFromRequestText(text);
            } else {
                Scan s = scan(child);
                if (s.requestText != null) {
                    name = Names.fromRequestText(s.requestText);
                    host = Names.hostFromRequestText(s.requestText);
                } else if (s.sawTextComponent && attempt < MAX_ATTEMPTS) {
                    scheduleRename(child, attempt + 1);
                    return;
                } else {
                    IHttpRequestResponse hrr = capture.poll();
                    if (hrr == null || hrr.getRequest() == null) {
                        return;
                    }
                    String text = new String(hrr.getRequest(), StandardCharsets.ISO_8859_1);
                    name = Names.fromRequestText(text);
                    host = Names.hostFromRequestText(text);
                }
            }

            if (name == null) {
                return;
            }
            applyTitle(tabs, idx, Names.makeUnique(name, host, visibleTitlesExcept(tabs, idx)));
        }

        private void renameExisting(JTabbedPane tabs) {
            for (int i = 0; i < tabs.getTabCount(); i++) {
                String title = tabs.getTitleAt(i);
                if (title == null || !Names.DEFAULT_TAB_TITLE.matcher(title.trim()).matches()) {
                    continue;
                }
                Scan s = scan(tabs.getComponentAt(i));
                if (s.requestText == null) {
                    continue;
                }
                String name = Names.fromRequestText(s.requestText);
                if (name == null) {
                    continue;
                }
                applyTitle(tabs, i, Names.makeUnique(name,
                        Names.hostFromRequestText(s.requestText), visibleTitlesExcept(tabs, i)));
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
            JLabel lbl = firstLabel(tabs.getTabComponentAt(idx));
            if (lbl != null) {
                lbl.setText(name);
            }
            callbacks.printOutput("Repeater Auto Tab Renamer (legacy): tab #" + (idx + 1) + " -> " + name);
        }
    }

    // ============================================================ shared Swing helpers

    private static JTabbedPane findTabbedPaneWithTab(Component root, String tabTitle) {
        for (JTabbedPane p : collectTabbedPanes(root)) {
            if (indexOfTab(p, tabTitle) >= 0) {
                return p;
            }
        }
        return null;
    }

    private static JTabbedPane pickNumberedTabsPane(Component repeaterPage) {
        List<JTabbedPane> panes = collectTabbedPanes(repeaterPage);
        for (JTabbedPane p : panes) {
            for (int i = 0; i < p.getTabCount(); i++) {
                String t = p.getTitleAt(i);
                if (t != null && Names.DEFAULT_TAB_TITLE.matcher(t.trim()).matches()) {
                    return p;
                }
            }
        }
        return panes.isEmpty() ? null : panes.get(0);
    }

    private static List<JTabbedPane> collectTabbedPanes(Component root) {
        List<JTabbedPane> out = new ArrayList<>();
        Deque<Component> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            Component c = queue.poll();
            if (c instanceof JTabbedPane) {
                out.add((JTabbedPane) c);
            }
            if (c instanceof Container) {
                for (Component child : ((Container) c).getComponents()) {
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
            if (c instanceof JLabel) {
                return (JLabel) c;
            }
            if (c instanceof Container) {
                for (Component child : ((Container) c).getComponents()) {
                    stack.push(child);
                }
            }
        }
        return null;
    }

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
            if (c instanceof JTextComponent) {
                s.sawTextComponent = true;
                if (s.requestText == null) {
                    String text = ((JTextComponent) c).getText();
                    if (text != null && !text.isEmpty()
                            && Names.looksLikeRequestLine(Names.firstLine(text))) {
                        s.requestText = text;
                    }
                }
            }
            if (c instanceof Container) {
                for (Component child : ((Container) c).getComponents()) {
                    stack.push(child);
                }
            }
        }
        return s;
    }

    // ============================================================ captured selection

    /** Remembers the most-recently context-selected request/responses (EDT-confined). */
    private static final class RequestCapture {
        private static final long FRESH_MS = 5_000;
        private final Deque<Object[]> recent = new ArrayDeque<>(); // {IHttpRequestResponse, Long ts}

        void replace(IHttpRequestResponse[] selected) {
            recent.clear();
            long now = System.currentTimeMillis();
            for (IHttpRequestResponse m : selected) {
                if (m != null && m.getRequest() != null) {
                    recent.addLast(new Object[]{m, now});
                }
            }
        }

        IHttpRequestResponse poll() {
            long now = System.currentTimeMillis();
            while (!recent.isEmpty()) {
                Object[] e = recent.pollFirst();
                if (now - (Long) e[1] <= FRESH_MS) {
                    return (IHttpRequestResponse) e[0];
                }
            }
            return null;
        }
    }

    // ============================================================ naming rules (self-contained)

    static final class Names {
        private Names() {
        }

        private static final Pattern VERSION_PREFIX =
                Pattern.compile("^/(?:api/)?(?:v\\d+/)?", Pattern.CASE_INSENSITIVE);
        private static final Pattern REQUEST_LINE =
                Pattern.compile("^([A-Za-z]+)\\s+(\\S+)\\s+HTTP/\\d(?:\\.\\d)?\\s*$");
        static final Pattern DEFAULT_TAB_TITLE = Pattern.compile("\\d+");

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
            String t = stripLeadingWs(text.replace("\r", ""));
            int nl = t.indexOf('\n');
            if (nl >= 0) {
                t = t.substring(0, nl);
            }
            return t.trim();
        }

        private static String stripLeadingWs(String s) {
            int i = 0;
            while (i < s.length() && s.charAt(i) != '\n' && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
            return s.substring(i);
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

        /**
         * Build a tab name. GraphQL endpoints are named after the operation in the body
         * (e.g. "query GetUser", "mutation CreateOrder"); otherwise "METHOD /clean/path".
         */
        static String fromRequest(String method, String target, String body) {
            if (isGraphQlEndpoint(target)) {
                String source = (body != null && !body.trim().isEmpty()) ? body : target;
                String label = graphQlLabel(source);
                if (label == null && body != null && !body.trim().isEmpty()) {
                    label = graphQlLabel(target);
                }
                if (label != null) {
                    return label;
                }
            }
            return method.toUpperCase() + " " + cleanPath(target);
        }

        static boolean isGraphQlEndpoint(String target) {
            return target != null && cleanPath(target).toLowerCase().contains("graphql");
        }

        static String graphQlLabel(String text) {
            if (text == null || text.isEmpty()) {
                return null;
            }
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
            String field = firstSelectionField(doc);
            if (field != null) {
                Matcher ma = GQL_ANON_TYPE.matcher(doc);
                String type = ma.find() ? ma.group(1) : "query";
                return type + " " + field;
            }
            return null;
        }

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
                        case 'n':
                        case 'r':
                        case 't':
                        case 'f':
                        case 'b':
                            b.append(' ');
                            break;
                        case '"':
                            b.append('"');
                            break;
                        case '\\':
                            b.append('\\');
                            break;
                        case '/':
                            b.append('/');
                            break;
                        case 'u':
                            if (i + 4 < s.length()) {
                                i += 4;
                            }
                            b.append(' ');
                            break;
                        default:
                            b.append(n);
                    }
                } else {
                    b.append(c);
                }
            }
            return b.toString();
        }

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
            int scheme = path.indexOf("://");
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
            for (String raw : requestText.replace("\r", "").split("\n")) {
                String line = raw.trim();
                if (line.isEmpty()) {
                    break;
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
            if (host != null && !host.trim().isEmpty()) {
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
