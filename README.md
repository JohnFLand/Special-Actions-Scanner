# RM/BC Rules Special Actions Scanner

A [Hubitat Elevation](https://hubitat.com/) app for Rule Machine (RM) and Button Controller (BC) rules.

The app scans RM/BC rules and builds a sortable table showing whether each rule appears to contain selected Rule Machine special actions. It is intended as a read-only audit/reporting tool. It does **not** modify rules, Private Booleans, triggers, actions, or rule settings.

---

## What It Detects

The app searches each rule's internal configuration JSON for these Rule Machine keywords:

| Display label | Internal keyword |
|---------------|------------------|
| **While** | `getWhile` |
| **Repeat** | `repeatActs` |
| **End Repeat** | `getEndRepeat` |
| **Stop Repeat** | `getStopRepeat` |
| **Wait for Expression** | `getWaitRule` |
| **Wait for Event** | `getWaitEvents` |

A detected keyword usually means that the rule contains the corresponding type of special action, wait, or repeat structure.

---

## Installation

1. In the Hubitat web UI, go to **Apps Code → + New App** and paste in the app's Groovy source.
2. Save the app code.
3. Go to **Apps → + Add User App** and select **RM-BC Special Actions Scanner**.
4. The app will attempt to create an OAuth token automatically on first open. If it does not, enable OAuth manually in Apps Code for this app and re-open it.
5. No scan runs automatically on install. Click **Scan All RM/BC Rules for Special Actions** to begin.

---

## Usage

### Scanning

Click **Scan All RM/BC Rules for Special Actions**.

The scan has two phases:

**Phase 1** reads each rule's runtime status JSON to collect basic report data, including **Last Run**.

**Phase 2** reads each rule's internal configuration JSON and searches the raw JSON text for the six special-action keywords listed above.

Phase 2 uses a queued `configure/json` pass with only a small number of simultaneous requests. This helps prevent one very large rule or dropped response from stopping the rest of the scan. If a rule's configuration JSON cannot be read, that rule is marked as unknown/skipped.

After a scan, the top summary line shows:

- **Rules scanned**
- **Rules with Special Actions**
- **Unknown/skipped**
- Counts for **While**, **Repeat**, **End Repeat**, **Stop Repeat**, **Wait for Expression**, and **Wait for Event**

The scan-time line shows Phase 1 time, Phase 2 time, and total scan time.

Clicking **Done** and reopening the app re-renders the table from cached data, so no rescan is needed for display-only changes. The cached table may be stale until a new scan is run.

---

## Special Actions Table

The table lists every discovered RM and BC rule with the following columns:

| Column | Description |
|--------|-------------|
| **Rule ID** | Hubitat internal app ID for the rule |
| **Rule** | Rule name, linked directly to its configuration page |
| **App Type** | `RM` for Rule Machine or `BC` for Button Controller |
| **While** | Checkmark when `getWhile` is detected |
| **Repeat** | Checkmark when `repeatActs` is detected |
| **End Repeat** | Checkmark when `getEndRepeat` is detected |
| **Stop Repeat** | Checkmark when `getStopRepeat` is detected |
| **Wait for Expression** | Checkmark when `getWaitRule` is detected |
| **Wait for Event** | Checkmark when `getWaitEvents` is detected |
| **Last Run** | Date and time of the most recent trigger event, formatted as `yyyy-MM-dd HH:mm` when available |

### Cell meanings

| Cell | Meaning |
|------|---------|
| Green **✓** | The keyword was detected in this rule's configuration JSON |
| Grey **—** | The keyword was not detected |
| Red **?** | The rule's configuration JSON could not be read, timed out, or was skipped |

### Sorting

Click any column header to sort by that column. Click the same header again to reverse the sort direction.

### Filtering

Use the rule-name filter above the table to show only matching rules. The filter supports plain substring matching and `*` / `?` wildcards.

The filter state is browser-side and does not require clicking **Done**.

### Hide rows with no Special Actions

Click **Hide rows with no Special Actions** to hide rows where all six keyword columns are known and none of the keywords were detected.

Rows marked with a red **?** remain visible, because the app could not determine whether those rules contain special actions.

Click **Show all rows** to restore the hidden rows.

### Hide columns

The hide-column buttons above the table let you show or hide:

- Rule ID
- App Type
- While
- Repeat
- End Repeat
- Stop Repeat
- Wait for Expression
- Wait for Event
- Last Run

Column visibility persists without clicking **Done**.

---

## Reports

In the **Controls** section, after a scan:

- **Open Printable Report** opens a formatted HTML report of the scanned rules.
- **Download CSV** downloads the same table data as a CSV file.

Reports use the cached data from the most recent scan.

The CSV export uses the friendly column labels:

```text
Rule ID, Rule, App Type, While, Repeat, End Repeat, Stop Repeat, Wait for Expression, Wait for Event, Last Run
```

---

## Controls Section

| Control | Description |
|---------|-------------|
| **App instance name** | Rename this app instance |
| **Open Printable Report** | Open a printable HTML report after a scan |
| **Download CSV** | Download a CSV export after a scan |
| **Enable debug logging** | Turns on verbose debug output to the Hubitat log; auto-disables after 30 minutes |

This app intentionally has no Private Boolean setters, bulk-apply controls, scheduled apply controls, or rule-modification controls.

---

## Debug Logging

When **Enable debug logging** is turned on in the Controls section, additional output appears in the Hubitat log, including:

- **Lifecycle events** — install, update, rename, and scan-cancel events
- **Rule discovery count** — number of RM/BC rules found from `/hub2/appsList`
- **Per-rule Phase 1 results** — rule name, ID, app type, and Last Run data as rules are scanned
- **Per-rule Phase 2 results** — keyword-detection results from each rule's `configure/json` response
- **Phase 2 queue progress** — active request count, completed count, and watchdog activity
- **Timeout/unknown handling** — rules whose configuration JSON could not be read within the timeout
- **Re-render-from-cache confirmations** — when the table is rebuilt from cached data on Done press

Debug logging auto-disables after 30 minutes to avoid filling the hub log.

---

## Technical Notes

The app uses the following Hubitat local/internal endpoints:

| Endpoint | Purpose |
|----------|---------|
| `/hub2/appsList` | Discover RM and BC rules |
| `/installedapp/statusJson/{appId}` | Read per-rule status data, including Last Run, during Phase 1 |
| `/installedapp/configure/json/{appId}` | Read per-rule configuration JSON for keyword detection during Phase 2 |
| `/apps/api/{appId}/setpref` | Persist column-hide preferences |
| `/apps/api/{appId}/report` | Printable HTML report |
| `/apps/api/{appId}/RM-BC_Special_Actions.csv` | CSV export |

> **Warning:** The `/hub2/appsList`, `/installedapp/statusJson/`, and `/installedapp/configure/json/` endpoints are internal Hubitat APIs and are not formal public APIs. They could change in a future Hubitat platform update.

---

## Limitations

- **Read-only keyword detection.** The app detects the presence of selected internal keyword strings. It does not parse or understand the full logical structure of the rule.
- **Internal Hubitat APIs.** The app relies on internal JSON endpoints whose structure could change in future Hubitat platform versions.
- **Large Phase 2 responses.** Very large rule configuration JSON may time out or be dropped. The affected rule will show a red **?**, and the scan will continue.
- **Unknown rows are preserved by the row filter.** Unknown/skipped rows remain visible when **Hide rows with no Special Actions** is active, because the app cannot safely classify them as having no special actions.
- **Basic Button Controller excluded.** Basic Button Controller rules are not included because they use a different internal structure.
- **Cached display can be stale.** Clicking **Done** and reopening can re-render cached data without a rescan; run a scan to refresh actual hub data.

---

## Credits

Designed initially by John Land. Built with AI assistance and adapted from the structure of the Private Boolean Manager app.
