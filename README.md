<div align="center">

# 🏷️ Repeater Auto Tab Renamer

**Stop hunting through `1 2 3 4 5`.** A Burp Suite extension that auto-names new
Repeater tabs after the request they hold — so you can actually *read* your tab bar.

[![Burp Suite](https://img.shields.io/badge/Burp%20Suite-Extension-FF6633?logo=burpsuite&logoColor=white)](https://portswigger.net/burp)
[![Montoya API](https://img.shields.io/badge/Montoya%20API-2026.4-orange)](https://portswigger.github.io/burp-extensions-montoya-api/)
[![Java](https://img.shields.io/badge/Java-17%2B-007396?logo=openjdk&logoColor=white)](https://openjdk.org/)
[![License: MIT](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)
![PRs Welcome](https://img.shields.io/badge/PRs-welcome-brightgreen.svg)

[![Download & Install](https://img.shields.io/badge/⬇%EF%B8%8F%20Download%20%26%20Install-FF6633?style=for-the-badge&logo=burpsuite&logoColor=white)](#install)

</div>

---

## 🎬 Demo

<div align="center">

![Repeater Auto Tab Renamer — auto-naming Repeater tabs in real time](docs/demo.gif)

**Before** — which tab was the login request again? 🤷

`[  1  ][  2  ][  3  ][  4  ][  5  ]`

**After** — obvious at a glance ✅

`[ GET /user/info ][ POST /login ][ query GetUser ][ mutation CreateOrder ][ DELETE /cart/items ]`

</div>

---

## 📖 Description

**Repeater Auto Tab Renamer** is a Burp Suite extension that gives every Repeater tab a
meaningful name — automatically, the moment the request arrives.

Out of the box, Burp labels each request you send to Repeater with a plain incrementing number
(`1`, `2`, `3`, …). After a busy session you're left with a row of identical numbered tabs and no
way to tell them apart without clicking through them one by one. This extension fixes that:

- **REST** requests become **`METHOD /clean/path`** (e.g. `GET /user/info`), with noisy version
  prefixes like `/api/v1/` stripped away.
- **GraphQL** requests are named after the operation in the body — **`query GetUser`**,
  **`mutation CreateOrder`** — so you're not staring at a dozen tabs all reading `POST /graphql`.

It hooks into the ways you already send requests — the native **`Ctrl+R`** shortcut and the
right-click **Send to Repeater** menu — so there's nothing new to learn. An optional filter limits
renaming to API (JSON) responses, duplicate names are disambiguated automatically, and any tab you
renamed by hand is never touched. Built for pentesters and bug bounty hunters who live in Repeater
and juggle a lot of endpoints at once.

---

## ✨ Features

- 🔤 **REST → `METHOD /clean/path`** — version prefixes like `/api/v1/` are stripped.
  `GET /api/v1/user/info` becomes the tab **`GET /user/info`**.
- 🧬 **GraphQL → the operation name** — for any endpoint whose path contains `graphql`, the tab
  is named from the request body: **`query GetUser`**, **`mutation CreateOrder`**, an anonymous op
  as `query <firstField>`, and a batched request as `op (+N)`.
- ⌨️ **Works with `Ctrl+R`** *and* right-click **Send to Repeater** — both paths get named.
- 🎯 **Optional "API (JSON) only" filter** — rename only requests whose response is JSON. Toggle
  it from the right-click menu; the choice is **persisted** across restarts.
- 🛡️ **Never clobbers your work** — only Burp's default *numbered* tabs are touched. Hand-renamed
  tabs and tabs it already named are left alone.
- 🔁 **Auto-dedupes** — duplicate names get ` @ host` or ` (2)` appended so every tab stays unique.
- 📦 **Two builds** — modern **Montoya** (Burp 2023+) and a **legacy** `IBurpExtender` build for
  older Burp.

---

<a id="install"></a>

## 🚀 Install (prebuilt jar)

1. **Download** the jar for your Burp:
   - Modern (Burp 2023+): [`dist/repeater-tab-renamer.jar`](dist/repeater-tab-renamer.jar)
   - Legacy (pre-2023): [`legacy/dist/repeater-tab-renamer-legacy.jar`](legacy/dist/repeater-tab-renamer-legacy.jar)
2. In Burp: **Extensions → Installed → Add**.
3. Set **Extension type** = `Java`, choose the downloaded jar, click **Next**.
4. Open the **Repeater** tab once so the extension can attach to its tab pane.
5. Send any request to Repeater (`Ctrl+R` or right-click) — watch the tab name itself. 🎉

---

## 🛠️ Build from source

> Requires **JDK 17+** (Burp 2023+ bundles a Java 17 runtime; the Montoya API needs 17).

<table>
<tr><th>Maven</th><th>Gradle</th></tr>
<tr>
<td>

```bash
mvn clean package
# → target/repeater-tab-renamer.jar
```

</td>
<td>

```bash
gradle clean jar
# → build/libs/repeater-tab-renamer.jar
```

</td>
</tr>
</table>

The Montoya API is declared `provided` / `compileOnly` — Burp supplies it at runtime, so it is
**not** bundled into the jar.

**Legacy build:**

```bash
cd legacy && mvn clean package
# → legacy/target/repeater-tab-renamer-legacy.jar
```

---

## ⚙️ Configuration

| Setting                       | Default | Where                                                    |
| ----------------------------- | ------- | -------------------------------------------------------- |
| **Rename all endpoints**      | ✅ On    | —                                                        |
| **API (JSON) responses only** | ❌ Off   | Right-click → *"Auto-rename: API (JSON) responses only"* |

When the JSON filter is on, a tab is renamed only if the originating response's `Content-Type` /
Burp-detected MIME type is JSON — i.e. it looks like a real API call. The setting is saved and
restored automatically.

---

## 🧠 How it works

The Montoya `Repeater` interface only exposes `sendToRepeater(request)` and
`sendToRepeater(request, name)` — there is **no event** for *"the user sent a request to Repeater"*
and **no API** to rename an existing tab. So to react to Burp's *native* **Send to Repeater**, the
extension locates the Repeater `JTabbedPane` in the Swing tree and renames newly added
default-numbered tabs via `setTitleAt`. (Same technique other real-world extensions use — and, yes,
inherently version-fragile.)

| Trigger                                           | How the tab gets named                                                                                                                                                               |
| ------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| ⌨️ **`Ctrl+R`**                                   | A `HotKeyHandler` sends the focused request itself and names the tab immediately — it has the request **and** response in hand, so the JSON filter works directly (no tree-walking). |
| 🖱️ **Right-click → Send to Repeater**            | A `ContainerListener` on the Repeater tab pane renames the new numbered tab; the request/response is captured when the context menu opens.                                           |
| ➕ **Right-click → Send to Repeater (auto-named)** | Explicit menu item that always names the tab, ignoring the filter.                                                                                                                   |

---

## ⚠️ Limitations

- 🧩 Tab renaming walks Burp's **Swing component tree**, which is _not_ a public API and can break
  when PortSwigger changes Burp's UI internals. If tabs stop being renamed after a Burp update,
  that's the likely cause.
- ⌨️ **`Ctrl+R` auto-naming is Montoya-only.** Burp's hotkey model is one-combo-one-command, so
  binding `Ctrl+R` here means this extension owns it. If `Ctrl+R` does nothing on your build, either
  the built-in kept the binding (use the right-click menu) or change `SEND_HOTKEY` to e.g.
  `Ctrl+Alt+R` and rebuild. The legacy build covers **right-click only**.
- 🔢 Only tabs still showing Burp's **default numeric title** are renamed — your hand-named tabs are
  always safe.

---

## 📄 License

Released under the [MIT License](LICENSE).


