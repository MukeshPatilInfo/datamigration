package ext.kla;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Central configuration for CSV -> Windchill IBA mapping and runtime behavior.
 */
public final class EpmAttributeUpdateConfig {

    private EpmAttributeUpdateConfig() {}

    /** Column used to locate EPMDocument in Windchill. */
    public static final String EPM_LOOKUP_COLUMN = "pdm.epmDocNumber";

    /**
     * CSV column -> Windchill IBA internal name mapping.
     * Keep LinkedHashMap to preserve update order.
     */
    public static final Map<String, String> CSV_TO_IBA_MAPPING;
    static {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("pdm.sourceVault", "PRIMARY");
        map.put("enovia.cadSecondary", "SECONDARY");
        map.put("enovia.project", "ENOVIA_PROJECT");
        map.put("enovia.eol", "EOL");
        map.put("enovia.lastDash", "LAST_ENOVIA_REV_DASH");
        map.put("enovia.partNumber", "KLAT_PN");
        CSV_TO_IBA_MAPPING = Collections.unmodifiableMap(map);
    }

    /** Treat these as empty/skip values. */
    public static boolean isSkipValue(String value) {
        if (value == null) return true;
        String v = value.trim();
        return v.isEmpty() || "NULL".equalsIgnoreCase(v);
    }

    // =====================================================================
    // INPUT / OUTPUT FOLDER CONFIG
    // =====================================================================

    /** Folder containing input CSV files. ALL *.csv files in this folder will be processed. */
    public static final String INPUT_DIR = "D:\\ptc\\Windchill_12.1\\Windchill\\src\\ext\\kla\\input";

    /** Output folder for logs/reports (adjust as needed). */
    public static final String OUTPUT_DIR = "D:\\ptc\\Windchill_12.1\\Windchill\\src\\ext\\kla\\logs";

    // =====================================================================
    // REFERENCE DRAWING CONFIG
    // =====================================================================

    /**
     * If TRUE, the processor will also look up the associated drawing
     * (via EPMStructureHelper navigateReferencedBy) and update the SAME
     * mapped attributes on that drawing object as well.
     */
    public static final boolean UPDATE_REFERENCE_DRAWING = true;

    // Output filename suffixes (actual file name = <inputCsvBaseName>_<suffix>)
    public static final String PROCESS_LOG_SUFFIX = "_process.log";
    public static final String SUCCESS_CSV_SUFFIX = "_success.csv";
    public static final String FAILURE_CSV_SUFFIX = "_failure.csv";
    public static final String NOT_FOUND_CSV_SUFFIX = "_not_found.csv";
    public static final String CHECKED_OUT_CSV_SUFFIX = "_checkedout.csv";
    public static final String SUMMARY_CSV_SUFFIX = "_summary.csv";

    /** CSV parsing delimiter. */
    public static final char CSV_DELIMITER = ',';

    /**
     * Windchill checkout/checkin comments.
     */
    public static final String CHECKOUT_COMMENT_GENERIC = "Auto checkout generic for instance attribute update";
    public static final String CHECKOUT_COMMENT_INSTANCE = "Auto checkout instance for attribute update";
    public static final String CHECKOUT_COMMENT_MODEL = "Auto checkout model for attribute update";
    public static final String CHECKOUT_COMMENT_DRAWING = "Auto checkout drawing for attribute update";
    public static final String CHECKIN_COMMENT_GENERIC = "Generic checked in after instance attribute update";
    public static final String CHECKIN_COMMENT_INSTANCE = "Instance attributes updated from CSV";
    public static final String CHECKIN_COMMENT_MODEL = "Model attributes updated from CSV";
    public static final String CHECKIN_COMMENT_DRAWING = "Drawing attributes updated from CSV";
}