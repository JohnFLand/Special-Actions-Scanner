/*
 *  Rules Special Actions Scanner
 *
 *  Scans Rule Machine and Button Controller child apps, reports each rule's
 *  App Type and Last Run time, and checks each rule's internal configuration
 *  JSON for selected Rule Machine special-action keywords.
 *
 *  Based on John's Private Boolean Manager structure, but this is read-only:
 *  it does not read, set, toggle, schedule, or bulk-apply Private Booleans.
 *
 *  Keywords checked:
 *      getWhile
 *      repeatActs
 *      getEndRepeat
 *      getStopRepeat
 *      getWaitRule
 *      getWaitEvents
 *
 *  Notes:
 *  - Uses Hubitat local/internal JSON endpoints:
 *      /hub2/appsList
 *      /installedapp/statusJson/{appId}
 *      /installedapp/configure/json/{appId}
 *      /apps/api/{thisAppId}/setpref?key={prefKey}&value={prefValue}
 *      /apps/api/{thisAppId}/report
 *      /apps/api/{thisAppId}/RM-BC_Special_Actions.csv
 *  - Some of these endpoints are not a formal public API and could change in a
 *    future Hubitat platform release.
 */

import groovy.transform.Field
import groovy.transform.CompileStatic

@Field static final String RM_BASE_URL                    = "http://127.0.0.1:8080"
@Field static final int    SCAN_TIMEOUT_SECS              = 360   // max seconds before Phase 1 scan is force-finalized
@Field static final int    LOGS_OFF_DELAY_SECS            = 1800  // seconds before debug logging auto-disables
@Field static final int    CONFIGURE_MAX_IN_FLIGHT        = 3     // max simultaneous configure/json requests during keyword scan
@Field static final int    CONFIGURE_REQUEST_TIMEOUT_SECS = 20    // per-rule watchdog timeout for configure/json callbacks
@Field static final int    CONFIGURE_TOTAL_TIMEOUT_SECS   = 600   // max seconds before Phase 2 scan is force-finalized
@Field static final int    CONFIGURE_HEARTBEAT_SECS       = 30    // silence timeout for Phase 2 progress

@Field static final List<String> SPECIAL_ACTION_KEYS = [
    "getWhile",
    "repeatActs",
    "getEndRepeat",
    "getStopRepeat",
    "getWaitRule",
    "getWaitEvents"
]

@Field static final Map SPECIAL_ACTION_LABELS = [
    getWhile     : "While",
    repeatActs   : "Repeat",
    getEndRepeat : "End Repeat",
    getStopRepeat: "Stop Repeat",
    getWaitRule  : "Wait for Expression",
    getWaitEvents: "Wait for Event"
]

// Transient scan state lives in @Field static to avoid database writes during a scan.
// If the app class is reloaded during a scan (e.g., on code save or hub restart),
// the scan is abandoned and can be run again.
@Field static String    currentScanId           = null
@Field static Long      scanStartMs             = 0L
@Field static List<Map> scanRuleQueue           = null
@Field static Map       scanPartialResults      = null   // keyed by ruleId String; holds RM/BC rule scan rows
@Field static String    configureScanId         = null   // Phase 2 scan ID, separate from Phase 1
@Field static List<Map> configureQueue          = null   // rule list for Phase 2 configure/json queue
@Field static Map       configureResults        = [:]    // ruleId -> Map keyword -> Boolean, or null when unreadable
@Field static Integer   configureNextIdx        = 0      // next configureQueue index to launch
@Field static Integer   configureInFlight       = 0      // number of configure/json requests currently in flight
@Field static Integer   configureTotalRules     = 0      // total number of rules expected in Phase 2
@Field static Map       configureInflight       = [:]    // ruleId -> [startedMs, name], for dropped-response watchdog

definition(
    name:           "Rules Special Actions Scanner 1.03",
    namespace:      "John Land",
    author:         "John Land & AI",
    description:    "Scans RM/BC rules and reports selected special-action keywords found in rule configuration JSON.",
    category:       "Utility",
    singleInstance: false,
    installOnOpen:  true,
    oauth:          true,
    iconUrl:        '',
    iconX2Url:      '',
    importUrl:      "https://raw.githubusercontent.com/JohnFLand/Special-Actions-Scanner/refs/heads/main/Rules_Special_Actions_Scanner.groovy"
)

preferences {
    page(name: "mainPage")
}

mappings {
    path("/setpref")                 { action: [GET: "handleSetPrefEndpoint"] }
    path("/report")                  { action: [GET: "handleReportEndpoint"] }
    path("/RM-BC_Special_Actions.csv") { action: [GET: "handleRmCsvEndpoint"] }
}

// ============================================================
// Lifecycle
// ============================================================

void installed() {
    if (debugEnable) log.debug "SAS: installed — ${app.name}"
    checkOAuth()
    initialize()
}

void updated() {
    if (debugEnable) log.debug "SAS: updated — label: '${app.label}', scan active: ${currentScanId != null || configureScanId != null}"

    String newLabel = settings.vAppLabel?.trim()
    if (newLabel && newLabel != app.label) {
        app.updateLabel(newLabel)
        log.info "SAS: app label updated to '${newLabel}'"
    }

    boolean scanWasActive = (currentScanId != null || configureScanId != null)
    initialize()
    if (scanWasActive) {
        state.scanStatus = "<i>Scan was cancelled because app settings were saved. Click Scan again to run again.</i>"
    } else {
        reRenderReportIfCached()
    }
}

void initialize() {
    if (currentScanId != null) {
        log.warn "initialize: aborting in-progress scan (scanId: ${currentScanId}) — re-scan when ready"
    }

    currentScanId      = null
    scanStartMs        = 0L
    scanRuleQueue      = null
    scanPartialResults = null

    unschedule("finalizeScanTimeout")
    unschedule("finalizeUsageScan")
    unschedule("configurePump")
    unschedule("configureHeartbeat")

    state.remove("scanRuleQueue")
    configureScanId         = null
    configureQueue          = null
    configureResults        = [:]
    configureNextIdx        = 0
    configureInFlight       = 0
    configureTotalRules     = 0
    configureInflight       = [:]

    unsubscribe()

    if (debugEnable) {
        runIn(LOGS_OFF_DELAY_SECS, "logsOff")
    }
}

void logsOff() {
    app.updateSetting("debugEnable", [value: "false", type: "bool"])
}

// Re-render the report HTML using rows cached in state.scanRowsJson.
// Called from updated() so display-setting changes apply on Done without a rescan.
void reRenderReportIfCached() {
    if (!state.scanRowsJson) return
    try {
        List<Map> rows = new groovy.json.JsonSlurper().parseText(state.scanRowsJson) as List<Map>
        state.reportHtml = buildReportHtml(rows)
        if (debugEnable) log.debug "SAS: report re-rendered from cached scan data (${rows.size()} rules)"
    } catch (Exception e) {
        log.warn "reRenderReportIfCached: could not re-render — ${e.message}"
    }
}

// ============================================================
// OAuth token management
// ============================================================

private String getAppTypeId() {
    String typeId = null
    try {
        httpGet([uri: RM_BASE_URL, path: "/hub2/userAppTypes", timeout: 15]) { resp ->
            List apps = resp.data instanceof List ? (List) resp.data : []
            Map match = apps.find { it.name == app.name }
            if (match) typeId = match.id?.toString()
        }
    } catch (Exception e) {
        log.debug "getAppTypeId: could not fetch user app types — ${e.message}"
    }
    return typeId
}

private boolean autoEnableOAuth() {
    String typeId = getAppTypeId()
    if (!typeId) {
        log.warn "autoEnableOAuth: could not determine app type ID — OAuth must be enabled manually in Apps Code"
        return false
    }

    String internalVer = null
    try {
        httpGet([uri: RM_BASE_URL, path: "/app/ajax/code", query: [id: typeId], timeout: 15]) { resp ->
            internalVer = resp.data?.version?.toString()
        }
    } catch (Exception e) {
        log.error "autoEnableOAuth: could not fetch app code version — ${e.message}"
        return false
    }
    if (!internalVer) {
        log.error "autoEnableOAuth: app code version was null — cannot proceed"
        return false
    }

    boolean success = false
    try {
        httpPost([
            uri                : RM_BASE_URL,
            path               : "/app/edit/update",
            requestContentType : "application/x-www-form-urlencoded",
            body               : [id: typeId, version: internalVer, oauthEnabled: "true", _action_update: "Update"],
            timeout            : 20
        ]) { resp ->
            success = true
        }
        if (success) log.info "autoEnableOAuth: OAuth successfully enabled on app code (typeId: ${typeId})"
    } catch (Exception e) {
        log.error "autoEnableOAuth: POST to /app/edit/update failed — ${e.message}"
    }
    return success
}

boolean checkOAuth() {
    if (state.accessToken) return true
    try {
        createAccessToken()
        if (state.accessToken) {
            log.info "Special Actions Scanner: OAuth token created"
            return true
        }
    } catch (Exception e) {
        log.debug "checkOAuth: OAuth not yet enabled — attempting auto-enable via hub API..."
        if (autoEnableOAuth()) {
            try {
                createAccessToken()
                if (state.accessToken) {
                    log.info "Special Actions Scanner: OAuth auto-enabled and token created successfully"
                    return true
                }
            } catch (Exception e2) {
                log.error "checkOAuth: OAuth was enabled but token creation still failed — ${e2.message}"
            }
        }
    }
    return false
}

def renderJson(Map m) {
    return render(contentType: "application/json", data: groovy.json.JsonOutput.toJson(m))
}

// ============================================================
// UI
// ============================================================

def mainPage() {
    checkOAuth()

    int pollInterval = (currentScanId || configureScanId) ? 5 : 0

    dynamicPage(name: "mainPage", title: "<b>${app.name}</b>", install: true, uninstall: true, refreshInterval: pollInterval) {

        section("NOTE: Scanning may take a while, be patient!") {
            input name: "btnScan", type: "button", title: "Scan All RM/BC Rules for Special Actions", width: 12

            if (state.lastScan) {
                String scanTimeHtml = "Phase 1 scan time: ${state.phase1ScanDuration ?: state.scanDuration ?: '00:00'}; " +
                                      "Phase 2 scan time: ${state.phase2ScanDuration ?: '00:00'}; " +
                                      "total scan time: ${state.totalScanDuration ?: state.scanDuration ?: '00:00'}"
                paragraph "<b>Last scan:</b> ${state.lastScan} (${scanTimeHtml})"
            } else {
                paragraph "No scan has been run yet."
            }
            if (state.scanStatus) {
                paragraph state.scanStatus
            }
            if (state.lastError) {
                paragraph "<span style='color:red'><b>Last error:</b> ${htmlEncode(state.lastError.toString())}</span>"
            }
        }

        section("") {
            if (!state.accessToken) {
                paragraph "<span style='color:red;font-weight:bold;'>✗ Report links and UI preference persistence not active</span> — automatic OAuth setup failed.<br>" +
                          "Please enable it manually as a fallback:<br>" +
                          "1. Go to <b>Apps Code</b>, open this app, click the <b>three-dot menu</b>, select <b>OAuth</b>, and press <b>Enable OAuth in Smartapp</b>.<br>" +
                          "2. Return here and re-open the app — the token will be created automatically."
            }
        }

        section("Rule Machine and Button Controller Special Actions", hideable: true, hidden: false) {
            if (state.scannedCount != null) {
                String summaryHtml = buildSummaryHtml()
                paragraph summaryHtml
            }

            paragraph(state.reportHtml ?: "Click <b>Scan All RM/BC Rules for Special Actions</b> to begin.")
        }

        section("Controls", hideable: true, hidden: true) {
            input "vAppLabel", "text", title: "<b>App instance name</b>", defaultValue: app.label

            if (state.accessToken) {
                String base = "/apps/api/${app.id}/report?access_token=${state.accessToken}"
                if (state.scanRowsJson) {
                    String rmCsvUrl = "/apps/api/${app.id}/RM-BC_Special_Actions.csv?access_token=${state.accessToken}"
                    paragraph "<br><b>RM/BC Special Actions Table</b> &nbsp;" +
                        "<a href='${base}' target='_blank'>" +
                        "&#128196; Open Printable Report</a>" +
                        " &nbsp;|&nbsp; " +
                        "<a href='${rmCsvUrl}'>&#11015; Download CSV</a>"
                } else {
                    paragraph "<small>Run <b>Scan All RM/BC Rules for Special Actions</b> to enable RM/BC reports.</small>"
                }
            } else {
                paragraph "<small>OAuth setup required before reports are available.</small>"
            }

            paragraph "<br><br>"
            input "debugEnable", "bool",
                title: "<b>Enable debug logging</b>",
                defaultValue:   false,
                submitOnChange: true
        }

        section("Notes", hideable: true, hidden: true) {
            paragraph '''
                <b>Overview</b><br>
                This app scans Rule Machine (<b>RM</b>) and Button Controller (<b>BC</b>) rules and
                displays whether each rule's internal configuration JSON contains any of these
                special-action keywords:<br>
                <b>While</b> (<code>getWhile</code>), <b>Repeat</b> (<code>repeatActs</code>),
                <b>End Repeat</b> (<code>getEndRepeat</code>), <b>Stop Repeat</b> (<code>getStopRepeat</code>),
                <b>Wait for Expression</b> (<code>getWaitRule</code>), and <b>Wait for Event</b> (<code>getWaitEvents</code>).
                <br><br>
                <b>Scanning</b><br>
                The scan has two phases. Phase 1 reads each rule's runtime status JSON to get
                Last Run. Phase 2 reads each rule's configuration JSON and searches the raw JSON
                text for the six keywords. Phase 2 is queued with a small number of simultaneous
                requests so very large rules or dropped responses should mark only the affected
                rule as unknown instead of stopping the whole scan.
                <br><br>
                <b>Table</b><br>
                Shows Rule ID, Rule name (linked to its config page), App Type, one column for
                each keyword, and Last Run. Keyword cells show a green checkmark when the keyword
                is found, a dash when it is not found, or a red question mark when the rule's
                configuration JSON could not be read.
                <br><br>
                The <b>Hide rows with no Special Actions</b> button hides rows where all six
                keyword columns are known and none is present. Unknown/skipped rows remain visible.
                The <b>Show all rows</b> button restores them. Column headers are clickable to sort.
                The hide-column buttons persist without clicking <b>Done</b>.
                <br><br>
                <b>Controls section</b><br>
                App instance rename, printable HTML report, CSV export, and debug logging toggle.
                There are no Private Boolean setters, bulk-apply controls, or scheduled apply controls.
                <br><br>
                <b>WARNING</b><br>
                This app uses Hubitat local/internal JSON endpoints that are not a formal public
                API and could change in a future platform update.
                <br>
            '''
        }
    }
}

String buildSummaryHtml() {
    Map counts = getKeywordCountsFromState()
    StringBuilder sb = new StringBuilder()
    sb << "<div id='rm-summary' style='margin:0;padding:0;line-height:1.5;font-size:1em;'>"
    sb << "<b>Rules scanned:</b> ${state.scannedCount ?: 0}; "
    sb << "<b>Rules with Special Actions:</b> ${state.specialActionRuleCount ?: 0}; "
    sb << "<b>Unknown/skipped:</b> ${state.specialActionUnknownCount ?: 0}"
    SPECIAL_ACTION_KEYS.each { String key ->
        sb << "; <b>${htmlEncode(labelForKeyword(key))}:</b> ${counts[key] ?: 0}"
    }
    sb << "<br><br></div>"
    return sb.toString()
}

Map getKeywordCountsFromState() {
    try {
        return new groovy.json.JsonSlurper().parseText(state.specialActionCountsJson ?: "{}") as Map
    } catch (Exception ignored) {
        return [:]
    }
}

def appButtonHandler(String btn) {
    switch (btn) {
        case "btnScan":
            scanRules()
            break
        default:
            log.warn "Unknown button: ${btn}"
            break
    }
}

// ============================================================
// Scanning — async sequential statusJson chain + queued configure/json pass
// ============================================================

void scanRules() {
    state.lastError                 = null
    state.specialActionRuleCount    = null
    state.specialActionUnknownCount = null
    state.specialActionCountsJson   = null

    state.scanStartedMs             = null
    state.phase1EndedMs             = null
    state.phase1ScanDuration        = null
    state.phase2ScanDuration        = null
    state.totalScanDuration         = null
    state.scanDuration              = null

    state.scanStatus                = "<i>Scan in progress…</i>"
    state.reportHtml                = null
    state.scanRowsJson              = null

    unschedule("finalizeScanTimeout")
    runIn(SCAN_TIMEOUT_SECS, "finalizeScanTimeout")

    List<Map> ruleApps = getRuleMachineRuleApps()

    if (ruleApps.isEmpty()) {
        unschedule("finalizeScanTimeout")
        state.scannedCount              = 0
        state.specialActionRuleCount    = 0
        state.specialActionUnknownCount = 0
        state.specialActionCountsJson   = groovy.json.JsonOutput.toJson(emptyKeywordCounts())
        state.scanStartedMs             = null
        state.phase1EndedMs             = null
        state.lastScan                  = new Date().format("yyyy-MM-dd HH:mm:ss", location.timeZone)
        state.phase1ScanDuration        = "00:00"
        state.phase2ScanDuration        = null
        state.totalScanDuration         = "00:00"
        state.scanDuration              = "00:00"
        state.reportHtml                = "<p>No Rule Machine or Button Controller rules found.</p>"
        state.scanStatus                = null
        return
    }

    Long   nowMs         = now() as Long
    String scanId        = nowMs.toString()
    state.scanStartedMs  = nowMs
    String scanStartTime = new Date().format("yyyy-MM-dd HH:mm:ss", location.timeZone)

    List<Map> queue = ruleApps.collect { Map r ->
        [id       : r.id                 as String,
         name     : r.name               as String,
         appType  : (r.appType ?: "RM")  as String]
    }

    state.scanStatus = "<i>Scan started: ${scanStartTime} — scanning ${queue.size()} rules…</i>"

    scanRuleQueue      = queue
    scanPartialResults = [:]
    currentScanId      = scanId
    scanStartMs        = nowMs

    log.info "SAS: scan started — ${queue.size()} rules (scanId: ${scanId})"

    Map first = queue[0]
    asynchttpGet("handleStatusResponse",
        [uri: RM_BASE_URL, path: "/installedapp/statusJson/${first.id}", timeout: 60],
        [scanId     : scanId,
         ruleId     : first.id,
         ruleName   : first.name,
         appType    : first.appType,
         nextIdx    : 1,
         totalRules : queue.size()]
    )
}

void handleStatusResponse(resp, data) {
    String scanId = data.scanId as String
    if (currentScanId != scanId) return

    String ruleId = data.ruleId as String

    try {
        Map status = [:]
        try {
            int httpStatus = resp.getStatus() as int
            if (httpStatus == 200) {
                Object raw = resp.getData()
                if (raw instanceof Map) {
                    status = raw as Map
                } else if (raw != null) {
                    status = new groovy.json.JsonSlurper().parseText(raw.toString()) as Map ?: [:]
                }
            } else {
                log.warn "HTTP ${httpStatus} for rule ${ruleId} (${data.ruleName})"
            }
        } catch (Exception e) {
            log.warn "Error parsing statusJson for rule ${ruleId}: ${e.message}"
        }

        if (scanPartialResults == null) scanPartialResults = [:]

        if (debugEnable) {
            log.debug "Scanned status: ${data.ruleName} (${ruleId}, ${data.appType}) LastRun=${extractLastRun(status)}"
        }

        scanPartialResults[ruleId] = [
            id             : ruleId,
            name           : data.ruleName,
            appType        : data.appType,
            lastRun        : extractLastRun(status),
            specialActions : emptyKeywordCounts(),
            specialUnknown : true
        ]

    } catch (Exception e) {
        log.warn "handleStatusResponse error for rule ${ruleId} (${data.ruleName}): ${e.message}"
        if (scanPartialResults == null) scanPartialResults = [:]
        scanPartialResults[ruleId] = [
            id             : ruleId,
            name           : data.ruleName as String,
            appType        : (data.appType ?: "RM") as String,
            lastRun        : "",
            specialActions : emptyKeywordCounts(),
            specialUnknown : true
        ]
    } finally {
        if (currentScanId != scanId) return

        int nextIdx    = (data.nextIdx    ?: 0) as int
        int totalRules = (data.totalRules ?: 0) as int

        if (debugEnable) log.debug "Completed statusJson ${nextIdx}/${totalRules}: ${data.ruleName} (${ruleId})"

        if (nextIdx < totalRules) {
            Map nextRule = scanRuleQueue[nextIdx]
            asynchttpGet("handleStatusResponse",
                [uri: RM_BASE_URL, path: "/installedapp/statusJson/${nextRule.id}", timeout: 60],
                [scanId     : currentScanId,
                 ruleId     : nextRule.id                 as String,
                 ruleName   : nextRule.name               as String,
                 appType    : (nextRule.appType ?: "RM")  as String,
                 nextIdx    : nextIdx + 1,
                 totalRules : totalRules]
            )
        } else {
            finalizeScan()
        }
    }
}

void resetConfigureHeartbeat(String reason = "") {
    if (configureScanId == null) return

    unschedule("configureHeartbeat")
    runIn(CONFIGURE_HEARTBEAT_SECS, "configureHeartbeat")

    if (debugEnable && reason) {
        log.debug "SAS: Phase 2 heartbeat reset (${reason})"
    }
}

void configureHeartbeat() {
    if (configureScanId == null) return

    Integer done   = (configureResults?.size() ?: 0) as Integer
    Integer total  = (configureTotalRules ?: 0) as Integer
    Integer active = (configureInFlight ?: 0) as Integer
    Integer next   = (configureNextIdx ?: 0) as Integer

    log.warn "SAS: Phase 2 heartbeat timeout — no configure/json progress for ${CONFIGURE_HEARTBEAT_SECS}s; finalizing partial results. Done=${done}/${total}, active=${active}, nextIdx=${next}"

    finalizeUsageScan()
}

void configurePump() {
    if (configureScanId == null) return

    unschedule("configurePump")

    boolean madeProgress = false

    Long nowMs = now() as Long
    if (configureResults  == null) configureResults  = [:]
    if (configureInflight == null) configureInflight = [:]

    List<String> expiredIds = []
    configureInflight.each { String rid, Object infoObj ->
        Map info = (infoObj instanceof Map) ? (Map) infoObj : [:]
        Long startedMs = (info.startedMs ?: 0L) as Long
        if (startedMs && (nowMs - startedMs) > (CONFIGURE_REQUEST_TIMEOUT_SECS * 1000L)) {
            expiredIds << rid
        }
    }

    expiredIds.each { String rid ->
        Map info = (configureInflight[rid] instanceof Map) ? (Map) configureInflight[rid] : [:]
        log.warn "SAS: configure/json timeout for rule ${rid} (${info.name ?: ''}) — special actions marked unknown"
        configureResults[rid] = null
        configureInflight.remove(rid)
        configureInFlight = Math.max(0, (configureInFlight ?: 0) - 1)
        madeProgress = true
    }

    while ((configureInFlight ?: 0) < CONFIGURE_MAX_IN_FLIGHT &&
           (configureNextIdx  ?: 0) < (configureQueue?.size() ?: 0)) {

        Map rule = configureQueue[configureNextIdx] as Map
        configureNextIdx = (configureNextIdx ?: 0) + 1

        String rid   = rule.id as String
        String nm    = rule.name as String
        String cfgId = configureScanId

        configureInflight[rid] = [startedMs: nowMs, name: nm]
        configureInFlight = (configureInFlight ?: 0) + 1
        madeProgress = true

        try {
            asynchttpGet(
                "handleConfigureResponse",
                [
                    uri     : RM_BASE_URL,
                    path    : "/installedapp/configure/json/${rid}",
                    timeout : CONFIGURE_REQUEST_TIMEOUT_SECS
                ],
                [
                    cfgScanId : cfgId,
                    ruleId    : rid,
                    ruleName  : nm
                ]
            )
        } catch (Exception e) {
            log.warn "SAS: could not start configure/json for rule ${rid} (${nm}) — special actions marked unknown: ${e.message}"
            configureResults[rid] = null
            configureInflight.remove(rid)
            configureInFlight = Math.max(0, (configureInFlight ?: 0) - 1)
            madeProgress = true
        }
    }

    Integer done   = (configureResults?.size() ?: 0) as Integer
    Integer total  = (configureTotalRules ?: 0) as Integer
    Integer active = (configureInFlight ?: 0) as Integer

    if (total > 0) {
        state.scanStatus = "<i>Phase 2: checking configure/json for special-action keywords… ${done}/${total} complete, ${active} active</i>"
    }

    if (madeProgress) {
        resetConfigureHeartbeat("pump progress")
    }

    if (total <= 0 || done >= total) {
        finalizeUsageScan()
        return
    }

    runIn(5, "configurePump")
}

void handleConfigureResponse(resp, data) {
    String cfgScanId = data.cfgScanId as String
    if (configureScanId != cfgScanId) return

    String ruleId   = data.ruleId as String
    String ruleName = data.ruleName ?: ""

    if (configureResults?.containsKey(ruleId) && !configureInflight?.containsKey(ruleId)) {
        return
    }

    Map foundMap = null

    try {
        int httpStatus = resp.getStatus() as int
        if (httpStatus == 200) {
            Object raw = resp.getData()
            foundMap = detectSpecialActionsFromRaw(raw)
            if (debugEnable) log.debug "configure/json ${ruleId}: specialActions=${foundMap}"
        } else {
            log.warn "configure/json HTTP ${httpStatus} for rule ${ruleId} (${ruleName})"
        }
    } catch (Exception e) {
        log.warn "handleConfigureResponse ${ruleId} (${ruleName}): ${e.message}"
        foundMap = null
    }

    if (configureScanId != cfgScanId) return

    if (configureResults == null)  configureResults  = [:]
    if (configureInflight == null) configureInflight = [:]

    configureResults[ruleId] = foundMap
    configureInflight.remove(ruleId)
    configureInFlight = Math.max(0, (configureInFlight ?: 0) - 1)

    resetConfigureHeartbeat("callback")

    configurePump()
}

private Map detectSpecialActionsFromRaw(Object raw) {
    try {
        if (raw == null) return null

        String jsonText
        if (raw instanceof CharSequence) {
            jsonText = raw.toString()
        } else {
            jsonText = groovy.json.JsonOutput.toJson(raw)
        }

        Map found = [:]
        SPECIAL_ACTION_KEYS.each { String key ->
            found[key] = jsonText.contains(key)
        }
        return found
    } catch (Exception e) {
        log.warn "detectSpecialActionsFromRaw: ${e.message}"
        return null
    }
}

void finalizeUsageScan() {
    unschedule("finalizeUsageScan")
    unschedule("configurePump")
    unschedule("configureHeartbeat")

    state.scanStatus = null
    if (configureScanId == null) return
    log.info "SAS: Phase 2 finalizing"

    try {
        List<Map> rows = new groovy.json.JsonSlurper().parseText(state.scanRowsJson ?: "[]") as List<Map>
        Map cfgRes = configureResults ?: [:]
        rows = rows.collect { Map r ->
            String id = r.id?.toString()
            if (cfgRes.containsKey(id) && cfgRes[id] instanceof Map) {
                Map found = normalizeKeywordMap(cfgRes[id] as Map)
                r.specialActions = found
                r.specialUnknown = false
            } else {
                r.specialActions = emptyKeywordCounts()
                r.specialUnknown = true
            }
            return r
        }

        updateSpecialActionStats(rows)

        Long phase2EndMs         = now() as Long
        Long startedMs           = (state.scanStartedMs ?: scanStartMs ?: phase2EndMs) as Long
        Long phase1EndMs         = (state.phase1EndedMs ?: startedMs) as Long

        state.phase1ScanDuration = state.phase1ScanDuration ?: formatScanDuration(phase1EndMs - startedMs)
        state.phase2ScanDuration = formatScanDuration(phase2EndMs - phase1EndMs)
        state.totalScanDuration  = formatScanDuration(phase2EndMs - startedMs)
        state.scanDuration       = state.totalScanDuration
        state.lastScan           = new Date().format("yyyy-MM-dd HH:mm:ss", location.timeZone)

        state.scanRowsJson       = groovy.json.JsonOutput.toJson(rows)
        state.reportHtml         = buildReportHtml(rows)

        log.info "SAS: Phase 2 complete in ${state.phase2ScanDuration}; total scan time ${state.totalScanDuration} — ${state.specialActionRuleCount ?: 0} of ${rows.size()} rules with detected special actions; ${state.specialActionUnknownCount ?: 0} unknown/skipped"

    } catch (Exception e) {
        log.warn "finalizeUsageScan: ${e.message}"
    } finally {
        configureScanId         = null
        configureQueue          = null
        configureResults        = null
        configureNextIdx        = 0
        configureInFlight       = 0
        configureTotalRules     = 0
        configureInflight       = [:]
        state.scanStartedMs     = null
        state.phase1EndedMs     = null
    }
}

void finalizeScan() {
    unschedule("finalizeScanTimeout")

    List<Map> asyncRules     = scanRuleQueue      ?: []
    Map       partialResults = scanPartialResults ?: [:]

    List<Map> rmRows = asyncRules.collect { Map rule ->
        Map row = partialResults[rule.id as String] as Map
        if (row) return row
        log.warn "No statusJson response for ${rule.id} (${rule.name}) — Last Run is unknown"
        return [id: rule.id as String,
                name: rule.name as String,
                appType: (rule.appType ?: "RM") as String,
                lastRun: "",
                specialActions: emptyKeywordCounts(),
                specialUnknown: true]
    }

    Long phase1EndMs         = now() as Long
    Long startedMs           = (state.scanStartedMs ?: scanStartMs ?: phase1EndMs) as Long
    state.scannedCount       = rmRows.size()
    state.lastScan           = new Date().format("yyyy-MM-dd HH:mm:ss", location.timeZone)
    state.phase1EndedMs      = phase1EndMs
    state.phase1ScanDuration = formatScanDuration(phase1EndMs - startedMs)
    state.scanDuration       = state.phase1ScanDuration

    try {
        state.scanRowsJson = groovy.json.JsonOutput.toJson(rmRows)
    } catch (Exception e) {
        log.warn "finalizeScan: could not cache scan rows — ${e.message}"
        state.scanRowsJson = null
    }

    updateSpecialActionStats(rmRows)
    state.reportHtml = buildReportHtml(rmRows)
    state.scanStatus = "<i>Phase 2: checking configure/json for special-action keywords…</i>"

    if (!asyncRules.isEmpty()) {
        configureScanId     = (currentScanId ?: "scan") + "_cfg"
        configureQueue      = asyncRules
        configureResults    = [:]
        configureInflight   = [:]
        configureNextIdx    = 0
        configureInFlight   = 0
        configureTotalRules = asyncRules.size()

        runIn(CONFIGURE_TOTAL_TIMEOUT_SECS, "finalizeUsageScan")
        resetConfigureHeartbeat("start")
        configurePump()

        log.info "SAS: Phase 2 started — ${asyncRules.size()} configure/json requests queued; max in flight: ${CONFIGURE_MAX_IN_FLIGHT}; heartbeat: ${CONFIGURE_HEARTBEAT_SECS}s"
    }

    currentScanId      = null
    scanPartialResults = null
    scanRuleQueue      = null

    log.info "SAS: Phase 1 scan complete in ${state.phase1ScanDuration}: ${rmRows.size()} RM/BC rules"
}

void finalizeScanTimeout() {
    if (currentScanId != null) {
        int total = scanRuleQueue?.size() ?: 0
        log.warn "Scan timeout: finalizing with partial results (${total} rules in queue)"
        finalizeScan()
    }
}

// ============================================================
// Rule discovery
// ============================================================

List<Map> getRuleMachineRuleApps() {
    List<Map> rules = []
    Set<String> seenIds = [] as Set

    Map params = [
        uri         : RM_BASE_URL,
        path        : "/hub2/appsList",
        contentType : "application/json"
    ]

    try {
        httpGet(params) { resp ->
            resp.data?.apps?.each { parentApp ->
                def pd = parentApp?.data
                String parentType  = pd?.type?.toString()  ?: ""
                String parentName  = pd?.name?.toString()  ?: ""
                String parentLabel = pd?.label?.toString() ?: ""
                String appType     = getSupportedAutomationAppType(parentType, parentName, parentLabel)

                if (appType) {
                    parentApp?.children?.each { child ->
                        collectRmLeafRules(child, appType, rules, seenIds, 0)
                    }
                }
            }
        }
    } catch (Exception e) {
        state.lastError = "Unable to read /hub2/appsList. This may be temporary; try Scan again. Error: ${e.message}"
        log.warn state.lastError
    }

    if (debugEnable) log.debug "SAS: discovered ${rules.size()} RM/BC rules from /hub2/appsList"
    return rules.sort { it.name?.toLowerCase() ?: "" }
}

private void collectRmLeafRules(Object node, String parentAppType, List<Map> rules, Set<String> seenIds, int depth) {
    if (depth > 6) return
    List children = (node?.children ?: []) as List
    if (children.isEmpty()) {
        def d = node?.data
        if (d?.id && d?.name) {
            String id = d.id.toString()
            if (!seenIds.contains(id)) {
                String childType         = d?.type?.toString()    ?: ""
                String childAppName      = d?.appName?.toString() ?: ""
                String childDetectedType = getSupportedAutomationAppType(childType, childAppName)
                String finalAppType      = (parentAppType == "BC" || childDetectedType == "BC") ? "BC" : (childDetectedType ?: parentAppType)

                seenIds << id
                String ruleName = d.name.toString()
                rules << [
                    id       : id,
                    name     : ruleName,
                    appType  : finalAppType
                ]
            }
        }
    } else {
        children.each { child -> collectRmLeafRules(child, parentAppType, rules, seenIds, depth + 1) }
    }
}

String getSupportedAutomationAppType(String type, String name, String label = "") {
    String combined = [type, name, label].findAll { it }.join(" ").toLowerCase()

    if (!combined) return null

    if (combined.contains("basic button controller") || combined.contains("basicbuttoncontroller")) {
        return null
    }

    if (combined.contains("button controller") || combined.contains("buttoncontroller")) {
        return "BC"
    }

    if (combined.contains("rule machine") || combined.contains("rulemachine")) {
        return "RM"
    }

    return null
}

// ============================================================
// Preference persistence endpoint
// ============================================================

def handleSetPrefEndpoint() {
    if (!state.accessToken) return renderJson([status: "error", message: "OAuth not active"])

    String key   = params?.key?.toString()
    String value = params?.value?.toString()
    if (!key) return renderJson([status: "error", message: "missing key"])

    Set allowedKeys = (["hideColRuleId", "hideColAppType", "hideColLastRun"] + SPECIAL_ACTION_KEYS.collect { "hideCol_${it}" }) as Set

    if (!(key in allowedKeys)) {
        return renderJson([status: "error", message: "unsupported preference key"])
    }

    Map prefs = (state.userPrefs ?: [:]) as Map
    prefs[key] = value
    state.userPrefs = prefs
    return renderJson([status: "success"])
}

boolean getPref(String key, boolean defaultVal = false) {
    Map prefs = (state.userPrefs ?: [:]) as Map
    if (prefs.containsKey(key)) return prefs[key]?.toString() == "true"
    return defaultVal
}

// ============================================================
// Report endpoints — printable HTML and CSV export
// ============================================================

def handleReportEndpoint() {
    if (!state.accessToken) {
        render contentType: "text/plain", data: "OAuth not active — re-open the app to retry."
        return
    }
    render contentType: "text/html; charset=UTF-8", data: buildRmPrintHtml()
}

def handleRmCsvEndpoint() {
    if (!state.accessToken) { render contentType: "text/plain", data: "OAuth not active."; return }
    render contentType: "text/csv; charset=UTF-8", data: buildRmCsv()
}

private String printHtmlShell(String title, String subtitle, String tableHtml) {
    String safeTitle = htmlEncode(title)
    String safeSubtitle = htmlEncode(subtitle)
    return "<!DOCTYPE html>" +
        "<html lang='en'><head><meta charset='UTF-8'>" +
        "<title>${safeTitle}</title>" +
        "<style>" +
        "body{font-family:Arial,sans-serif;font-size:12px;margin:16px;}" +
        "h2{font-size:16px;margin-bottom:2px;}" +
        "p.sub{font-size:11px;color:#555;margin:0 0 12px;}" +
        "table{border-collapse:collapse;width:100%;}" +
        "th,td{border:1px solid #bbb;padding:4px 8px;text-align:left;vertical-align:top;}" +
        "th{background:#e8e8e8;font-weight:bold;}" +
        "tr:nth-child(even){background:#f7f7f7;}" +
        ".c{text-align:center;}" +
        "@media print{body{margin:6mm;font-size:11px;}a{text-decoration:none;color:inherit;}thead{display:table-header-group;}tr{page-break-inside:avoid;}}" +
        "</style></head><body>" +
        "<h2>${safeTitle}</h2>" +
        "<p class='sub'>${safeSubtitle}</p>" +
        tableHtml +
        "</body></html>"
}

String buildRmPrintHtml() {
    List<Map> rows = []
    try { rows = new groovy.json.JsonSlurper().parseText(state.scanRowsJson ?: "[]") as List<Map> } catch (Exception ignored) {}
    rows = rows.sort { it.name?.toString()?.toLowerCase() ?: "" }

    StringBuilder sb = new StringBuilder()
    sb << "<table><thead><tr>"
    (["Rule ID", "Rule", "App Type"] + SPECIAL_ACTION_KEYS.collect { String key -> labelForKeyword(key) } + ["Last Run"]).each { String h ->
        sb << "<th>${htmlEncode(h)}</th>"
    }
    sb << "</tr></thead><tbody>"
    rows.each { Map r ->
        Map actions = normalizeKeywordMap(r.specialActions instanceof Map ? (Map) r.specialActions : [:])
        boolean unknown = r.specialUnknown == true
        sb << "<tr>"
        sb << "<td class='c'>${htmlEncode(r.id)}</td>"
        sb << "<td>${htmlEncode(r.name)}</td>"
        sb << "<td class='c'>${htmlEncode(r.appType ?: '')}</td>"
        SPECIAL_ACTION_KEYS.each { String key ->
            String v = unknown ? "?" : (actions[key] == true ? "Yes" : "No")
            sb << "<td class='c'>${v}</td>"
        }
        sb << "<td class='c'>${htmlEncode(r.lastRun ?: '')}</td>"
        sb << "</tr>"
    }
    sb << "</tbody></table>"

    String subtitle = "Last scan: ${state.lastScan ?: 'never'} — ${rows.size()} rules"
    return printHtmlShell("Rule Machine and Button Controller Special Actions", subtitle, sb.toString())
}

String buildRmCsv() {
    List<Map> rows = []
    try { rows = new groovy.json.JsonSlurper().parseText(state.scanRowsJson ?: "[]") as List<Map> } catch (Exception ignored) {}
    rows = rows.sort { it.name?.toString()?.toLowerCase() ?: "" }

    StringBuilder sb = new StringBuilder()
    sb << (["Rule ID", "Rule", "App Type"] + SPECIAL_ACTION_KEYS.collect { String key -> labelForKeyword(key) } + ["Last Run"]).collect { escapeCsv(it) }.join(",") << "\n"
    rows.each { Map r ->
        Map actions = normalizeKeywordMap(r.specialActions instanceof Map ? (Map) r.specialActions : [:])
        boolean unknown = r.specialUnknown == true
        List vals = [r.id, r.name, r.appType]
        SPECIAL_ACTION_KEYS.each { String key ->
            vals << (unknown ? "?" : (actions[key] == true ? "Yes" : "No"))
        }
        vals << r.lastRun
        sb << vals.collect { escapeCsv(it) }.join(",") << "\n"
    }
    return sb.toString()
}

@CompileStatic
private String escapeCsv(Object v) {
    if (v == null) return ""
    String s = v.toString().replace('"', '""')
    return (s.contains(",") || s.contains('"') || s.contains("\n")) ? "\"${s}\"" : s
}

// ============================================================
// Last Run extraction
// ============================================================

String extractLastRun(Map status) {
    String lastEvtDate = ""
    String lastEvtTime = ""
    String timeFormat  = ""
    String dateFormat  = ""

    status?.appState?.each { item ->
        String n = item?.name?.toString() ?: ""
        if (n == "lastEvtDate") lastEvtDate = item?.value?.toString() ?: ""
        if (n == "lastEvtTime") lastEvtTime = item?.value?.toString() ?: ""
        if (n == "timeFormat")  timeFormat  = item?.value?.toString() ?: ""
        if (n == "dateFormat")  dateFormat  = item?.value?.toString() ?: ""
    }

    if (!lastEvtDate) return ""

    java.text.SimpleDateFormat outDateTimeFmt = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm")
    java.text.SimpleDateFormat outDateFmt     = new java.text.SimpleDateFormat("yyyy-MM-dd")
    java.text.SimpleDateFormat outTimeFmt     = new java.text.SimpleDateFormat("HH:mm")

    boolean hasTimeComponent = lastEvtDate.toUpperCase().contains("AM") ||
                               lastEvtDate.toUpperCase().contains("PM") ||
                               lastEvtDate.indexOf(":", 6) >= 0

    if (hasTimeComponent) {
        List<String> fullDateFmts = [
            "dd-MMM-yyyy hh:mm:ss a",
            "dd-MMM-yyyy HH:mm:ss",
            "dd-MMM-yyyy hh:mm a",
            "dd-MMM-yyyy HH:mm",
            "MM/dd/yyyy hh:mm:ss a",
            "MM/dd/yyyy HH:mm:ss",
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd hh:mm:ss a"
        ]
        for (String fmt : fullDateFmts) {
            try {
                return outDateTimeFmt.format(new java.text.SimpleDateFormat(fmt).parse(lastEvtDate))
            } catch (Exception ignored) {}
        }
        log.warn "extractLastRun: unrecognized full datetime '${lastEvtDate}' — add format to extractLastRun if needed"
        return "* ${lastEvtDate}"
    }

    if (!lastEvtDate.matches(/\d{4}-\d{2}-\d{2}/)) {
        List<String> dateFmts = (dateFormat ? [dateFormat] : []) + ["dd-MMM-yyyy", "MM/dd/yyyy", "dd/MM/yyyy", "MMM dd, yyyy"]
        String normalizedDate = null
        for (String fmt : dateFmts) {
            try {
                normalizedDate = outDateFmt.format(new java.text.SimpleDateFormat(fmt).parse(lastEvtDate))
                break
            } catch (Exception ignored) {}
        }
        if (normalizedDate) {
            lastEvtDate = normalizedDate
        } else {
            log.warn "extractLastRun: unrecognized date format '${lastEvtDate}' — add format to extractLastRun if needed"
            lastEvtDate = "* ${lastEvtDate}"
        }
    }

    if (!lastEvtTime) return lastEvtDate

    List<String> timeFmts = timeFormat ? [timeFormat] : []
    timeFmts += ["hh:mm:ss a", "h:mm:ss a", "HH:mm:ss", "hh:mm a", "h:mm a", "HH:mm", "h:mm"]
    for (String fmt : timeFmts) {
        try {
            return "${lastEvtDate} ${outTimeFmt.format(new java.text.SimpleDateFormat(fmt).parse(lastEvtTime))}"
        } catch (Exception ignored) {}
    }

    log.warn "extractLastRun: could not parse time '${lastEvtTime}' (timeFormat='${timeFormat}') — add format to extractLastRun if needed"
    return "* ${lastEvtDate} ${lastEvtTime}"
}

// ============================================================
// Shared report assets (CSS + JS)
// ============================================================

String buildSharedReportAssets(String prefEndpoint = "") {
    StringBuilder sb = new StringBuilder()
    sb << "<style>"
    sb << "table.rmlogcheck{border-collapse:collapse;width:100%;}"
    sb << "table.rmlogcheck th,table.rmlogcheck td{border:1px solid #ccc;padding:4px 7px;text-align:left;vertical-align:middle;}"
    sb << "table.rmlogcheck th{background-color:#FFD700;color:#000;cursor:pointer;font-weight:bold;user-select:none;white-space:nowrap;}"
    sb << "table.rmlogcheck th:hover{background-color:#FFC700;}"
    sb << "table.rmlogcheck th.sort-asc::after{content:' ▲';font-size:0.8em;}"
    sb << "table.rmlogcheck th.sort-desc::after{content:' ▼';font-size:0.8em;}"
    sb << "table.rmlogcheck td.center,table.rmlogcheck th.center{text-align:center;}"
    sb << "table.rmlogcheck td.rmcol-lastrun{white-space:nowrap;}"
    sb << ".rmcol-toggle-bar{margin:4px 0 8px;font-size:0.9em;line-height:1.9;}"
    sb << ".rmcol-btn{display:inline-block;cursor:pointer;padding:2px 8px;margin-right:6px;border:1px solid #aaa;border-radius:3px;background:#e8e8e8;user-select:none;}"
    sb << ".rmcol-btn.hidden-col{text-decoration:line-through;opacity:0.45;background:#ccc;}"
    sb << ".rmname-filter{padding:2px 6px;font-size:0.9em;border:1px solid #aaa;border-radius:3px;vertical-align:middle;}"
    sb << ".rmcheck-action-btn{display:inline-block;cursor:pointer;padding:2px 9px;margin-right:4px;border:1px solid #888;border-radius:3px;background:#f0f0f0;color:#333;font-weight:bold;user-select:none;}"
    sb << "table.rmlogcheck td.rmcol-special,table.rmlogcheck th.rmcol-special{width:94px;min-width:94px;}"
    sb << "</style>"
    sb << "<script>var rmPrefEndpoint = ${groovy.json.JsonOutput.toJson(prefEndpoint ?: null)};</script>"

    sb << '''<script>
function sortRmLogTable(tableId, columnIndex) {
    const table = document.getElementById(tableId);
    if (!table) return;
    const tbody = table.querySelector('tbody');
    if (!tbody) return;
    const rows = Array.from(tbody.querySelectorAll('tr'));
    const headers = table.querySelectorAll('th');
    if (!window.rmLogTableSorts) window.rmLogTableSorts = {};
    if (!window.rmLogTableSorts[tableId]) window.rmLogTableSorts[tableId] = {};
    const currentDirection = window.rmLogTableSorts[tableId][columnIndex] || 'asc';
    const newDirection = currentDirection === 'asc' ? 'desc' : 'asc';
    window.rmLogTableSorts[tableId][columnIndex] = newDirection;
    headers.forEach(header => { header.classList.remove('sort-asc', 'sort-desc'); });
    if (headers[columnIndex]) headers[columnIndex].classList.add('sort-' + newDirection);
    rows.sort((a, b) => {
        const aCell = a.querySelectorAll('td')[columnIndex];
        const bCell = b.querySelectorAll('td')[columnIndex];
        let aText = aCell ? (aCell.getAttribute('data-sort') || aCell.textContent || '').trim() : '';
        let bText = bCell ? (bCell.getAttribute('data-sort') || bCell.textContent || '').trim() : '';
        const aIsNumber = aText !== '' && isFinite(Number(aText));
        const bIsNumber = bText !== '' && isFinite(Number(bText));
        let comparison = 0;
        if (aIsNumber && bIsNumber) {
            comparison = Number(aText) - Number(bText);
        } else {
            comparison = aText.toLowerCase().localeCompare(bText.toLowerCase());
        }
        return newDirection === 'asc' ? comparison : -comparison;
    });
    rows.forEach(row => tbody.appendChild(row));
}

function persistPref(key, value) {
    if (!key || !rmPrefEndpoint) return;
    fetch(rmPrefEndpoint + '&key=' + encodeURIComponent(key) + '&value=' + encodeURIComponent(value))
        .catch(function(e) { console.warn('persistPref failed:', e.message); });
}

function toggleRmCol(cls, btn) {
    var hiding = btn.className.indexOf('hidden-col') === -1;
    document.querySelectorAll('.' + cls).forEach(function(el) { el.style.display = hiding ? 'none' : ''; });
    btn.className = hiding ? 'rmcol-btn hidden-col' : 'rmcol-btn';
    persistPref(btn.dataset.prefKey, String(hiding));
}

function wildcardToRegex(pattern) {
    var result = '';
    for (var i = 0; i < pattern.length; i++) {
        var ch = pattern[i];
        if (ch === '*') { result += '.*'; }
        else if (ch === '?') { result += '.'; }
        else if ('.+^$()|[]{}'.indexOf(ch) >= 0 || ch === String.fromCharCode(92)) { result += String.fromCharCode(92) + ch; }
        else { result += ch; }
    }
    return new RegExp('^' + result + '$', 'i');
}

var rmSpecialFilterActive = false;

function rmToggleSpecialFilter() {
    var btn = document.getElementById('rm-hide-no-special');
    rmSpecialFilterActive = !rmSpecialFilterActive;
    if (btn) btn.textContent = rmSpecialFilterActive ? 'Show all rows' : 'Hide rows with no Special Actions';
    applyRmRowFilters();
}

function applyRmRowFilters() {
    var filter = (document.getElementById('rmname-filter')?.value || '').trim();
    var useWildcard = filter.indexOf('*') >= 0 || filter.indexOf('?') >= 0;
    var regex = null;
    if (filter && useWildcard) {
        try { regex = wildcardToRegex(filter); } catch(e) { regex = null; }
    }

    document.querySelectorAll('#rmlog_table tbody tr').forEach(function(row) {
        var name = row.getAttribute('data-rule-name') || '';
        var showByName = true;
        if (filter) {
            showByName = regex ? regex.test(name) : name.toLowerCase().indexOf(filter.toLowerCase()) >= 0;
        }
        var showBySpecial = true;
        if (rmSpecialFilterActive) {
            // Hide only rows that are known to have no special actions.
            // Unknown/skipped rows remain visible so they can be reviewed.
            showBySpecial = row.getAttribute('data-special-any') !== 'false';
        }
        row.style.display = (showByName && showBySpecial) ? '' : 'none';
    });
}
</script>'''
    return sb.toString()
}

// ============================================================
// RM/BC table HTML
// ============================================================

String buildReportHtml(List<Map> rows) {
    String prefEndpoint = ""
    if (state.accessToken) {
        prefEndpoint = "/apps/api/${app.id}/setpref?access_token=${state.accessToken}"
    } else {
        log.warn "buildReportHtml: no access token — UI hide preferences will not persist until OAuth is active."
    }

    StringBuilder sb = new StringBuilder()
    sb << buildSharedReportAssets(prefEndpoint)

    if (!rows) {
        sb << "<p>No rules found. Click <b>Scan All RM/BC Rules for Special Actions</b> to begin.</p>"
        return sb.toString()
    }

    boolean cfgHideColRuleId  = getPref("hideColRuleId",  false)
    boolean cfgHideColAppType = getPref("hideColAppType", false)
    boolean cfgHideColLastRun = getPref("hideColLastRun", false)

    Map hideKeyword = [:]
    SPECIAL_ACTION_KEYS.each { String key -> hideKeyword[key] = getPref("hideCol_${key}", false) }

    String btnColRuleId  = cfgHideColRuleId  ? "rmcol-btn hidden-col" : "rmcol-btn"
    String btnColAppType = cfgHideColAppType ? "rmcol-btn hidden-col" : "rmcol-btn"
    String btnColLastRun = cfgHideColLastRun ? "rmcol-btn hidden-col" : "rmcol-btn"

    sb << "<div class='rmcol-toggle-bar'>"
    sb << "<span id='rm-hide-no-special' class='rmcheck-action-btn' onclick='rmToggleSpecialFilter()'>Hide rows with no Special Actions</span>"
    sb << "<span style='display:inline-block;width:0.35in;'></span>"
    sb << "<b>Hide columns:</b>&nbsp;"
    sb << "<span id='rmtoggle-rmcol-ruleid'  class='${btnColRuleId}'  data-pref-key='hideColRuleId'  onclick=\"toggleRmCol('rmcol-ruleid',this)\">Rule ID</span>"
    sb << "<span id='rmtoggle-rmcol-apptype' class='${btnColAppType}' data-pref-key='hideColAppType' onclick=\"toggleRmCol('rmcol-apptype',this)\">App Type</span>"
    SPECIAL_ACTION_KEYS.each { String key ->
        String cls = colClassForKeyword(key)
        String btnCls = hideKeyword[key] ? "rmcol-btn hidden-col" : "rmcol-btn"
        sb << "<span id='rmtoggle-${cls}' class='${btnCls}' data-pref-key='hideCol_${htmlEncode(key)}' onclick=\"toggleRmCol('${cls}',this)\">${htmlEncode(labelForKeyword(key))}</span>"
    }
    sb << "<span id='rmtoggle-rmcol-lastrun' class='${btnColLastRun}' data-pref-key='hideColLastRun' onclick=\"toggleRmCol('rmcol-lastrun',this)\">Last Run</span>"
    sb << "&nbsp;&nbsp;<b>Filter:</b>&nbsp;"
    sb << "<input id='rmname-filter' type='text' class='rmname-filter' placeholder='Filter rule name (substring or * ? wildcards)' oninput='applyRmRowFilters()' style='width:330px;'>"
    sb << "</div>"

    sb << "<table id='rmlog_table' class='rmlogcheck'><thead><tr>"
    sb << "<th onclick=\"sortRmLogTable('rmlog_table',0)\" class='center rmcol-ruleid'>Rule ID</th>"
    sb << "<th onclick=\"sortRmLogTable('rmlog_table',1)\" class='sort-asc'>Rule</th>"
    sb << "<th onclick=\"sortRmLogTable('rmlog_table',2)\" class='center rmcol-apptype'>App Type</th>"
    int colIndex = 3
    SPECIAL_ACTION_KEYS.each { String key ->
        String cls = colClassForKeyword(key)
        sb << "<th onclick=\"sortRmLogTable('rmlog_table',${colIndex})\" class='center rmcol-special ${cls}'>${htmlEncode(labelForKeyword(key))}</th>"
        colIndex++
    }
    sb << "<th onclick=\"sortRmLogTable('rmlog_table',${colIndex})\" class='center rmcol-lastrun'>Last Run</th>"
    sb << "</tr></thead><tbody>"

    rows.each { Map r ->
        String id       = htmlEncode(r.id)
        String nameSort = htmlEncode(r.name?.toString()?.replaceAll(/<[^>]+>/, '') ?: "")
        String nameHtml = renderNameHtml(r.name)
        String appType  = htmlEncode(r.appType ?: "RM")
        String lastRun  = htmlEncode(r.lastRun ?: "")

        Map actions = normalizeKeywordMap(r.specialActions instanceof Map ? (Map) r.specialActions : [:])
        boolean unknown = r.specialUnknown == true
        boolean anySpecial = !unknown && SPECIAL_ACTION_KEYS.any { String key -> actions[key] == true }
        String specialAny = unknown ? "unknown" : (anySpecial ? "true" : "false")

        sb << "<tr data-rule-name='${nameSort}' data-special-any='${specialAny}'>"
        sb << "<td class='center rmcol-ruleid' data-sort='${id}'>${id}</td>"
        sb << "<td data-sort='${nameSort}'><a href='/installedapp/configure/${id}' target='_blank'>${nameHtml}</a></td>"
        sb << "<td class='center rmcol-apptype' data-sort='${appType}'>${appType}</td>"
        SPECIAL_ACTION_KEYS.each { String key ->
            String cls = colClassForKeyword(key)
            boolean found = actions[key] == true
            String disp = unknown
                ? "<span title='configure/json unknown or skipped' style='color:#c00;font-weight:bold;'>?</span>"
                : (found ? "<span style='color:green;font-weight:bold;'>&#10003;</span>" : "<span style='color:#aaa;'>—</span>")
            String sortVal = unknown ? "1" : (found ? "2" : "0")
            sb << "<td class='center rmcol-special ${cls}' data-sort='${sortVal}'>${disp}</td>"
        }
        sb << "<td class='center rmcol-lastrun' data-sort='${lastRun}'>${lastRun}</td>"
        sb << "</tr>"
    }

    sb << "</tbody></table>"

    List<String> colClassesToHide = []
    if (cfgHideColRuleId)  colClassesToHide << "'rmcol-ruleid'"
    if (cfgHideColAppType) colClassesToHide << "'rmcol-apptype'"
    SPECIAL_ACTION_KEYS.each { String key ->
        if (hideKeyword[key]) colClassesToHide << "'${colClassForKeyword(key)}'"
    }
    if (cfgHideColLastRun) colClassesToHide << "'rmcol-lastrun'"
    if (colClassesToHide) {
        sb << "<script>setTimeout(function(){[${colClassesToHide.join(',')}].forEach(function(cls){document.querySelectorAll('.'+cls).forEach(function(el){el.style.display='none';});});},0);</script>"
    }

    return sb.toString()
}

// ============================================================
// Stats and helper functions
// ============================================================

Map emptyKeywordCounts() {
    Map m = [:]
    SPECIAL_ACTION_KEYS.each { String key -> m[key] = false }
    return m
}

Map normalizeKeywordMap(Map raw) {
    Map m = [:]
    SPECIAL_ACTION_KEYS.each { String key -> m[key] = (raw?.get(key) == true || raw?.get(key)?.toString() == "true") }
    return m
}

void updateSpecialActionStats(List<Map> rows) {
    Map counts = [:]
    SPECIAL_ACTION_KEYS.each { String key -> counts[key] = 0 }

    Integer ruleCount = 0
    Integer unknownCount = 0

    rows.each { Map r ->
        boolean unknown = r.specialUnknown == true
        if (unknown) {
            unknownCount++
        } else {
            Map actions = normalizeKeywordMap(r.specialActions instanceof Map ? (Map) r.specialActions : [:])
            boolean any = false
            SPECIAL_ACTION_KEYS.each { String key ->
                if (actions[key] == true) {
                    counts[key] = (counts[key] ?: 0) + 1
                    any = true
                }
            }
            if (any) ruleCount++
        }
    }

    state.specialActionRuleCount    = ruleCount
    state.specialActionUnknownCount = unknownCount
    state.specialActionCountsJson   = groovy.json.JsonOutput.toJson(counts)
}

String labelForKeyword(String key) {
    return (SPECIAL_ACTION_LABELS[key] ?: key)?.toString() ?: ""
}

String colClassForKeyword(String key) {
    return "rmcol-sa-" + (key ?: "").replaceAll(/[^A-Za-z0-9_-]/, "_")
}

@CompileStatic
String formatScanDuration(Long elapsedMs) {
    Long safeMs = elapsedMs ?: 0L
    if (safeMs < 0L) safeMs = 0L
    Long totalSeconds = Math.round(safeMs / 1000.0D) as Long
    Long minutes      = Math.floor(totalSeconds / 60.0D) as Long
    Long seconds      = totalSeconds % 60L
    return String.format("%02d:%02d", minutes, seconds)
}

@CompileStatic
String htmlEncode(Object value) {
    if (value == null) return ""
    return value.toString()
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace('"', "&quot;")
        .replace("'", "&#39;")
}

@CompileStatic
String renderNameHtml(Object value) {
    if (value == null) return ""
    String encoded = htmlEncode(value)
    return encoded.replaceAll(
        /&lt;span style=(?:&#39;|&quot;)color:([a-zA-Z#0-9]+)(?:&#39;|&quot;)&gt;(.*?)&lt;\/span&gt;/,
        "<span style='color:\$1'>\$2</span>"
    )
}
