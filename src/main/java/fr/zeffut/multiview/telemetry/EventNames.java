package fr.zeffut.multiview.telemetry;

/** Event names and property keys for PostHog. No magic strings elsewhere. */
public final class EventNames {
    private EventNames() {}

    // Lifecycle / session
    public static final String EVT_MOD_LOADED = "mod_loaded";
    public static final String EVT_SESSION_HEARTBEAT = "session_heartbeat";

    // Merge
    public static final String EVT_MERGE_STARTED = "merge_started";
    public static final String EVT_MERGE_PHASE_COMPLETED = "merge_phase_completed";
    public static final String EVT_MERGE_COMPLETED = "merge_completed";
    public static final String EVT_MERGE_FAILED = "merge_failed";
    public static final String EVT_MERGE_CANCELLED = "merge_cancelled";
    public static final String EVT_OVERLAP_VALIDATION_FAILED = "overlap_validation_failed";

    // UI
    public static final String EVT_UI_OPENED = "ui_opened";
    public static final String EVT_UI_CLOSED = "ui_closed";
    public static final String EVT_UI_FILE_SELECTED = "ui_file_selected";
    public static final String EVT_UI_MERGE_CLICKED = "ui_merge_clicked";

    // Commands & tools
    public static final String EVT_COMMAND_USED = "command_used";
    public static final String EVT_INSPECT_PERFORMED = "inspect_performed";

    // Errors
    public static final String EVT_MOD_ERROR = "mod_error";
    public static final String EVT_TELEMETRY_OPTOUT = "telemetry_optout";

    // Common property keys
    public static final String PROP_APP = "app";
    public static final String PROP_MOD_VERSION = "mod_version";
    public static final String PROP_MC_VERSION = "mc_version";
    public static final String PROP_FLASHBACK_VERSION = "flashback_version";
    public static final String PROP_FABRIC_LOADER_VERSION = "fabric_loader_version";
    public static final String PROP_JAVA_VERSION = "java_version";
    public static final String PROP_OS_NAME = "os_name";
    public static final String PROP_OS_ARCH = "os_arch";
    public static final String PROP_LOCALE = "locale";
    public static final String PROP_UI_CAPABILITY = "ui_capability";
    public static final String PROP_SESSION_ID = "session_id";
}
