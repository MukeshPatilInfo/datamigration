package ext.kla;

import wt.clients.vc.CheckInOutTaskLogic;
import wt.epm.EPMDocument;
import wt.epm.EPMDocumentMaster;
import wt.epm.structure.EPMStructureHelper;
import wt.fc.Persistable;
import wt.fc.PersistenceHelper;
import wt.fc.QueryResult;
import wt.folder.Folder;
import wt.iba.definition.litedefinition.AttributeDefDefaultView;
import wt.iba.definition.litedefinition.BooleanDefView;
import wt.iba.definition.litedefinition.FloatDefView;
import wt.iba.definition.litedefinition.IntegerDefView;
import wt.iba.definition.litedefinition.StringDefView;
import wt.iba.definition.service.IBADefinitionHelper;
import wt.iba.value.DefaultAttributeContainer;
import wt.iba.value.IBAHolder;
import wt.iba.value.litevalue.AbstractValueView;
import wt.iba.value.litevalue.BooleanValueDefaultView;
import wt.iba.value.litevalue.FloatValueDefaultView;
import wt.iba.value.litevalue.IntegerValueDefaultView;
import wt.iba.value.litevalue.StringValueDefaultView;
import wt.iba.value.service.IBAValueDBService;
import wt.iba.value.service.IBAValueHelper;
import wt.method.RemoteAccess;
import wt.method.RemoteMethodServer;
import wt.org.WTPrincipal;
import wt.pom.Transaction;
import wt.query.QuerySpec;
import wt.query.SearchCondition;
import wt.session.SessionHelper;
import wt.util.WTException;
import wt.vc.VersionControlHelper;
import wt.vc.Versioned;
import wt.vc.wip.CheckoutLink;
import wt.vc.wip.WorkInProgressHelper;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.*;

/**
 * Processor:
 * - Reads ALL CSV files from EpmAttributeUpdateConfig.INPUT_DIR
 * - For each CSV, finds EPM by pdm.epmDocNumber
 * - If CAD is instance:
 *      checkout Generic -> checkout Instance -> update ONLY Instance -> checkin Instance -> checkin Generic
 * - If CAD is standalone:
 *      checkout Model -> update Model -> checkin Model
 * - Always resolves latest revision+iteration before checkout to avoid NonLatestCheckoutException
 * - Optionally updates associated drawing as standalone checkout/update/checkin
 * - Logs FULL original CSV row with prepended status columns
 */
public class EpmAttributeUpdateProcessor implements RemoteAccess {

    public static void main(String[] args) {
        try {
            RemoteMethodServer rms = RemoteMethodServer.getDefault();
            // TODO: Use secure credential mechanism; avoid hardcoding.
            rms.setUserName("munkuma");
            rms.setPassword("ShreyVerma_@05092017");

            rms.invoke(
                    "run",
                    EpmAttributeUpdateProcessor.class.getName(),
                    null,
                    new Class[]{},
                    new Object[]{}
            );
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void run() throws Exception {
        File inDir = new File(EpmAttributeUpdateConfig.INPUT_DIR);
        if (!inDir.exists() || !inDir.isDirectory()) {
            throw new IOException("Input directory not found: " + inDir.getAbsolutePath());
        }

        File outDir = new File(EpmAttributeUpdateConfig.OUTPUT_DIR);
        if (!outDir.exists() && !outDir.mkdirs()) {
            throw new IOException("Unable to create output directory: " + outDir.getAbsolutePath());
        }

        File[] csvFiles = inDir.listFiles((dir, name) -> name.toLowerCase(Locale.ENGLISH).endsWith(".csv"));
        if (csvFiles == null || csvFiles.length == 0) {
            System.out.println("No CSV files found in: " + inDir.getAbsolutePath());
            return;
        }

        for (File csvFile : csvFiles) {
            try {
                processSingleCsv(csvFile, outDir);
            } catch (Exception e) {
                System.out.println("ERROR processing file: " + csvFile.getName());
                e.printStackTrace();
            }
        }
    }

    private static void processSingleCsv(File csvFile, File outDir) throws Exception {
        long startTime = System.currentTimeMillis();
        String baseName = stripExtension(csvFile.getName());

        File processLog = new File(outDir, baseName + EpmAttributeUpdateConfig.PROCESS_LOG_SUFFIX);
        File successCsv = new File(outDir, baseName + EpmAttributeUpdateConfig.SUCCESS_CSV_SUFFIX);
        File failureCsv = new File(outDir, baseName + EpmAttributeUpdateConfig.FAILURE_CSV_SUFFIX);
        File notFoundCsv = new File(outDir, baseName + EpmAttributeUpdateConfig.NOT_FOUND_CSV_SUFFIX);
        File checkedOutCsv = new File(outDir, baseName + EpmAttributeUpdateConfig.CHECKED_OUT_CSV_SUFFIX);
        File summaryCsv = new File(outDir, baseName + EpmAttributeUpdateConfig.SUMMARY_CSV_SUFFIX);

        try (BufferedWriter processWriter = writer(processLog);
             BufferedWriter successWriter = writer(successCsv);
             BufferedWriter failureWriter = writer(failureCsv);
             BufferedWriter notFoundWriter = writer(notFoundCsv);
             BufferedWriter checkedOutWriter = writer(checkedOutCsv);
             BufferedWriter summaryWriter = writer(summaryCsv)) {

            log(processWriter, "===== Processing file: " + csvFile.getName() + " =====");

            List<String> headers = new ArrayList<>();
            List<List<String>> rows = new ArrayList<>();
            readCsv(csvFile, headers, rows);

            Map<String, Integer> idx = indexMap(headers);
            validateRequiredColumns(idx);

            List<String> outputHeaders = prepend(headers, "Update Status", "Error Message", "Drawing Number");
            String outputHeaderLine = buildCsvLine(outputHeaders);

            successWriter.write(outputHeaderLine); successWriter.newLine();
            failureWriter.write(outputHeaderLine); failureWriter.newLine();
            notFoundWriter.write(outputHeaderLine); notFoundWriter.newLine();
            checkedOutWriter.write(outputHeaderLine); checkedOutWriter.newLine();

            Summary summary = new Summary();

            for (List<String> row : rows) {
                summary.total++;

                Map<String, String> rowMap = toRowMap(headers, row);
                String epmDocNumber = safe(rowMap.get(EpmAttributeUpdateConfig.EPM_LOOKUP_COLUMN));

                if (epmDocNumber.isEmpty()) {
                    summary.fail++;
                    log(processWriter, "FAILURE | (no epmDocNumber) | Missing pdm.epmDocNumber");
                    writeOutputRow(failureWriter, headers, row, "FAILURE", "Missing pdm.epmDocNumber", "");
                    continue;
                }

                EPMDocument epm = findLatestEPMDocument(epmDocNumber);
                if (epm == null) {
                    summary.notFound++;
                    log(processWriter, "NOT_FOUND | " + epmDocNumber + " | EPMDocument not found");
                    writeOutputRow(notFoundWriter, headers, row, "NOT_FOUND", "EPMDocument not found", "");
                    continue;
                }

                EPMDocument drawing = null;
                String drwName = "";
                if (EpmAttributeUpdateConfig.UPDATE_REFERENCE_DRAWING) {
                    try {
                        drawing = findAssociatedDrawing(epm);
                        if (drawing != null) {
                            drawing = resolveToLatest(drawing);
                            drwName = drawing.getNumber();
                        }
                    } catch (Exception ex) {
                        log(processWriter, "WARN | " + epmDocNumber + " | Drawing lookup failed: " + ex.getMessage());
                    }
                }

                UpdateResult result = updateModelWithInstanceAwareFlow(epm, rowMap);

                if (drawing != null) {
                    try {
                        UpdateResult drwResult = updateSingleObjectWithCheckout(
                                drawing,
                                rowMap,
                                EpmAttributeUpdateConfig.CHECKOUT_COMMENT_DRAWING,
                                EpmAttributeUpdateConfig.CHECKIN_COMMENT_DRAWING
                        );

                        if (drwResult.success) {
                            summary.drwUpdated++;
                            result.message += " | Drawing (" + drwName + ") updated: " + String.join(", ", drwResult.updatedIbas);
                        } else {
                            result.message += " | Drawing (" + drwName + ") update FAILED: " + drwResult.message;
                        }
                    } catch (Exception ex) {
                        result.message += " | Drawing (" + drwName + ") update EXCEPTION: " + ex.getMessage();
                    }
                }

                if (result.success) {
                    summary.success++;
                    log(processWriter, "SUCCESS | " + epmDocNumber + " | " + result.message);
                    writeOutputRow(successWriter, headers, row, "SUCCESS", result.message, drwName);
                } else if (result.checkedOutConflict) {
                    summary.checkedOut++;
                    log(processWriter, "CHECKED_OUT | " + epmDocNumber + " | " + result.message);
                    writeOutputRow(checkedOutWriter, headers, row, "CHECKED_OUT", result.message, drwName);
                } else {
                    summary.fail++;
                    log(processWriter, "FAILURE | " + epmDocNumber + " | " + result.message);
                    writeOutputRow(failureWriter, headers, row, "FAILURE", result.message, drwName);
                }
            }

            long elapsedMs = System.currentTimeMillis() - startTime;
            writeSummary(summaryWriter, summary, csvFile.getName(), elapsedMs);

            log(processWriter, "===== Completed file: " + csvFile.getName()
                    + " | Total=" + summary.total
                    + " Success=" + summary.success
                    + " Failure=" + summary.fail
                    + " NotFound=" + summary.notFound
                    + " CheckedOut=" + summary.checkedOut
                    + " DrwUpdated=" + summary.drwUpdated
                    + " TimeMs=" + elapsedMs + " =====");
        }
    }

    // =====================================================================
    // MAIN UPDATE FLOW
    // =====================================================================

    private static UpdateResult updateModelWithInstanceAwareFlow(EPMDocument model, Map<String, String> rowMap) {
        UpdateResult result = new UpdateResult();
        Transaction trx = new Transaction();

        EPMDocument genericWc = null;
        EPMDocument instanceWc = null;
        EPMDocument modelWc = null;

        try {
            trx.start();

            model = resolveToLatest(model);

            EPMDocument generic = findGenericForInstance(model);
            if (generic != null) {
                // INSTANCE FLOW
                generic = resolveToLatest(generic);
                model = resolveToLatest(model);

                if (isCheckedOutByOtherUser(generic)) {
                    result.success = false;
                    result.checkedOutConflict = true;
                    result.message = "Generic is checked out by other user: " + getCheckedOutBy(generic);
                    trx.rollback();
                    return result;
                }

                if (isCheckedOutByOtherUser(model)) {
                    result.success = false;
                    result.checkedOutConflict = true;
                    result.message = "Instance is checked out by other user: " + getCheckedOutBy(model);
                    trx.rollback();
                    return result;
                }

                // Required sequence
                genericWc = checkout(generic, EpmAttributeUpdateConfig.CHECKOUT_COMMENT_GENERIC);
                instanceWc = checkout(model, EpmAttributeUpdateConfig.CHECKOUT_COMMENT_INSTANCE);

                // Update ONLY instance
                UpdateResult updRes = updateMappedAttributesOnWorkingCopy(instanceWc, rowMap);
                if (!updRes.success) {
                    trx.rollback();
                    return updRes;
                }

                checkin(instanceWc, EpmAttributeUpdateConfig.CHECKIN_COMMENT_INSTANCE);
                checkin(genericWc, EpmAttributeUpdateConfig.CHECKIN_COMMENT_GENERIC);

                trx.commit();

                result.success = true;
                result.updatedIbas = updRes.updatedIbas;
                result.message = "Instance updated (generic not updated). Updated IBA(s): "
                        + String.join(", ", updRes.updatedIbas);
                return result;
            } else {
                // STANDALONE FLOW
                model = resolveToLatest(model);

                if (isCheckedOutByOtherUser(model)) {
                    result.success = false;
                    result.checkedOutConflict = true;
                    result.message = "Model is checked out by other user: " + getCheckedOutBy(model);
                    trx.rollback();
                    return result;
                }

                modelWc = checkout(model, EpmAttributeUpdateConfig.CHECKOUT_COMMENT_MODEL);

                UpdateResult updRes = updateMappedAttributesOnWorkingCopy(modelWc, rowMap);
                if (!updRes.success) {
                    trx.rollback();
                    return updRes;
                }

                checkin(modelWc, EpmAttributeUpdateConfig.CHECKIN_COMMENT_MODEL);

                trx.commit();

                result.success = true;
                result.updatedIbas = updRes.updatedIbas;
                result.message = "Standalone model updated. Updated IBA(s): " + String.join(", ", updRes.updatedIbas);
                return result;
            }
        } catch (Exception e) {
            safeRollback(trx);
            result.success = false;
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            result.message = sw.toString();
            return result;
        }
    }

    private static UpdateResult updateSingleObjectWithCheckout(EPMDocument obj,
                                                               Map<String, String> rowMap,
                                                               String checkoutComment,
                                                               String checkinComment) {
        UpdateResult result = new UpdateResult();
        Transaction trx = new Transaction();

        try {
            trx.start();

            obj = resolveToLatest(obj);

            if (isCheckedOutByOtherUser(obj)) {
                result.success = false;
                result.checkedOutConflict = true;
                result.message = "Object is checked out by other user: " + getCheckedOutBy(obj);
                trx.rollback();
                return result;
            }

            EPMDocument wc = checkout(obj, checkoutComment);

            UpdateResult updRes = updateMappedAttributesOnWorkingCopy(wc, rowMap);
            if (!updRes.success) {
                trx.rollback();
                return updRes;
            }

            checkin(wc, checkinComment);

            trx.commit();

            result.success = true;
            result.updatedIbas = updRes.updatedIbas;
            result.message = "Updated IBA(s): " + String.join(", ", updRes.updatedIbas);
            return result;
        } catch (Exception e) {
            safeRollback(trx);
            result.success = false;
            result.message = e.getMessage();
            return result;
        }
    }

    // =====================================================================
    // FAMILY TABLE / DRAWING
    // =====================================================================

    private static EPMDocument findGenericForInstance(EPMDocument instance) throws WTException {
        QueryResult qr = EPMStructureHelper.service.navigateGenericToIteration(instance, null, true, null);
        while (qr != null && qr.hasMoreElements()) {
            Object obj = qr.nextElement();
            if (obj instanceof EPMDocument) {
                return (EPMDocument) obj;
            }
        }
        return null;
    }

    private static EPMDocument findAssociatedDrawing(EPMDocument model) throws WTException {
        if (model == null) return null;

        EPMDocumentMaster master = (EPMDocumentMaster) model.getMaster();
        QueryResult qr = EPMStructureHelper.service.navigateReferencedBy(master, null, true);

        List<EPMDocument> drawingCandidates = new ArrayList<>();
        while (qr.hasMoreElements()) {
            Object nextObj = qr.nextElement();
            if (nextObj instanceof EPMDocument) {
                EPMDocument candidate = (EPMDocument) nextObj;
                String cadName = candidate.getCADName() == null ? "" : candidate.getCADName();
                if (cadName.toUpperCase(Locale.ENGLISH).endsWith(".DRW")) {
                    drawingCandidates.add(candidate);
                }
            }
        }

        if (drawingCandidates.isEmpty()) return null;
        if (drawingCandidates.size() == 1) return drawingCandidates.get(0);

        String modelBase = removeExtension(model.getNumber()).toUpperCase(Locale.ENGLISH);
        for (EPMDocument drawing : drawingCandidates) {
            String drawingBase = removeExtension(drawing.getNumber()).toUpperCase(Locale.ENGLISH);
            if (modelBase.equals(drawingBase)) return drawing;
        }

        return drawingCandidates.get(0);
    }

    private static String removeExtension(String number) {
        if (number == null) return "";
        int index = number.lastIndexOf('.');
        return index > 0 ? number.substring(0, index) : number;
    }

    // =====================================================================
    // LATEST RESOLUTION (Fix for NonLatestCheckoutException)
    // =====================================================================

    private static EPMDocument findLatestEPMDocument(String number) throws WTException {
        QuerySpec qs = new QuerySpec(EPMDocument.class);
        qs.appendWhere(
                new SearchCondition(EPMDocument.class, EPMDocument.NUMBER, SearchCondition.EQUAL, number),
                new int[]{0}
        );

        QueryResult qr = PersistenceHelper.manager.find(qs);
        EPMDocument best = null;

        while (qr.hasMoreElements()) {
            Object o = qr.nextElement();
            if (o instanceof EPMDocument) {
                EPMDocument candidate = (EPMDocument) o;
                if (best == null || isLaterVersionOrIteration(candidate, best)) {
                    best = candidate;
                }
            }
        }

        return best;
    }

    private static EPMDocument resolveToLatest(EPMDocument epm) throws WTException {
        if (epm == null) return null;

        QueryResult all = VersionControlHelper.service.allVersionsOf((Versioned) epm.getMaster());
        EPMDocument best = null;

        while (all.hasMoreElements()) {
            Object o = all.nextElement();
            if (o instanceof EPMDocument) {
                EPMDocument c = (EPMDocument) o;
                if (best == null || isLaterVersionOrIteration(c, best)) {
                    best = c;
                }
            }
        }

        return best == null ? epm : best;
    }

    private static boolean isLaterVersionOrIteration(EPMDocument a, EPMDocument b) {
        String aRev = a.getVersionIdentifier().getValue();
        String bRev = b.getVersionIdentifier().getValue();

        int revCmp = aRev.compareTo(bRev);
        if (revCmp != 0) return revCmp > 0;

        String aIt = a.getIterationIdentifier().getValue();
        String bIt = b.getIterationIdentifier().getValue();

        try {
            return Integer.parseInt(aIt) > Integer.parseInt(bIt);
        } catch (Exception ignore) {
            return aIt.compareTo(bIt) > 0;
        }
    }

    // =====================================================================
    // WIP HELPERS
    // =====================================================================

    private static EPMDocument checkout(EPMDocument obj, String comment) throws WTException {
        obj = resolveToLatest(obj);

        if (WorkInProgressHelper.isWorkingCopy(obj)) {
            return obj;
        }

        if (WorkInProgressHelper.isCheckedOut(obj)) {
            Persistable wc = WorkInProgressHelper.service.workingCopyOf(obj);
            return (EPMDocument) wc;
        }

        Folder coFolder = WorkInProgressHelper.service.getCheckoutFolder();
        CheckoutLink link = WorkInProgressHelper.service.checkout(obj, coFolder, comment);
        return (EPMDocument) link.getWorkingCopy();
    }

    private static void checkin(EPMDocument wc, String comment) throws WTException {
        if (wc != null && WorkInProgressHelper.isWorkingCopy(wc)) {
            WorkInProgressHelper.service.checkin(wc, comment);
        }
    }

    private static boolean isCheckedOutByOtherUser(EPMDocument epm) {
        try {
            if (!WorkInProgressHelper.isCheckedOut(epm)) return false;
            WTPrincipal checkedOutBy = CheckInOutTaskLogic.getCheckedOutBy(epm);
            WTPrincipal current = SessionHelper.manager.getPrincipal();
            if (checkedOutBy == null || current == null) return true;
            return !checkedOutBy.getName().equalsIgnoreCase(current.getName());
        } catch (Exception e) {
            return true;
        }
    }

    private static String getCheckedOutBy(EPMDocument epm) {
        try {
            WTPrincipal p = CheckInOutTaskLogic.getCheckedOutBy(epm);
            return p == null ? "Unknown" : p.getName();
        } catch (Exception e) {
            return "Unknown";
        }
    }

    // =====================================================================
    // IBA UPDATE
    // =====================================================================

    private static UpdateResult updateMappedAttributesOnWorkingCopy(EPMDocument epmWc, Map<String, String> rowMap) {
        UpdateResult result = new UpdateResult();
        result.success = true;
        result.message = "No mapped values to update";

        try {
            if (!(epmWc instanceof IBAHolder)) {
                result.success = false;
                result.message = "Object is not IBAHolder";
                return result;
            }

            IBAHolder holder = (IBAHolder) epmWc;
            holder = IBAValueHelper.service.refreshAttributeContainer(holder, null, null, null);
            DefaultAttributeContainer container = (DefaultAttributeContainer) holder.getAttributeContainer();

            if (container == null) {
                result.success = false;
                result.message = "Attribute container is null";
                return result;
            }

            boolean anyUpdated = false;
            List<String> updated = new ArrayList<>();

            for (Map.Entry<String, String> mapEntry : EpmAttributeUpdateConfig.CSV_TO_IBA_MAPPING.entrySet()) {
                String csvCol = mapEntry.getKey();
                String ibaName = mapEntry.getValue();
                String newValue = rowMap.get(csvCol);

                if ("SECONDARY".equals(ibaName) && EpmAttributeUpdateConfig.isSkipValue(newValue)) {
                    String sourceVault = rowMap.get("pdm.sourceVault");
                    if (!EpmAttributeUpdateConfig.isSkipValue(sourceVault)) {
                        newValue = sourceVault;
                    }
                }

                if (EpmAttributeUpdateConfig.isSkipValue(newValue)) {
                    continue;
                }

                AttributeDefDefaultView attrDef = IBADefinitionHelper.service.getAttributeDefDefaultViewByPath(ibaName);
                if (attrDef == null) {
                    result.success = false;
                    result.failedIba = ibaName;
                    result.message = "IBA definition not found: " + ibaName;
                    return result;
                }

                AbstractValueView existing = findIBAValue(container, ibaName);
                if (existing != null) {
                    container.deleteAttributeValue(existing);
                }

                AbstractValueView newVal = buildValue(attrDef, newValue);
                if (newVal == null) {
                    result.success = false;
                    result.failedIba = ibaName;
                    result.message = "Unsupported/invalid value for IBA: " + ibaName + " value: " + newValue;
                    return result;
                }

                container.addAttributeValue(newVal);
                anyUpdated = true;
                updated.add(ibaName);
            }

            if (anyUpdated) {
                IBAValueDBService ibaDBService = new IBAValueDBService();
                ibaDBService.updateAttributeContainer(holder, "Bulk EPM IBA update from CSV", Locale.getDefault(), null);
                PersistenceHelper.manager.modify((Persistable) holder);

                result.success = true;
                result.updatedIbas = updated;
                result.message = "Updated IBA(s): " + String.join(", ", updated);
            } else {
                result.success = true;
                result.updatedIbas = Collections.emptyList();
                result.message = "No update values present for mapped columns";
            }

            return result;
        } catch (Exception e) {
            result.success = false;
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            result.message = sw.toString();
            return result;
        }
    }

    private static AbstractValueView buildValue(AttributeDefDefaultView attrDef, String rawValue) {
        String newValue = rawValue == null ? "" : rawValue.trim();

        try {
            if (attrDef instanceof StringDefView) {
                return new StringValueDefaultView((StringDefView) attrDef, newValue);
            } else if (attrDef instanceof IntegerDefView) {
                Double d = Double.parseDouble(newValue);
                return new IntegerValueDefaultView((IntegerDefView) attrDef, d.intValue());
            } else if (attrDef instanceof FloatDefView) {
                double floatValue;
                int precision = 0;

                if (newValue.contains("|")) {
                    String[] split = newValue.split("\\|");
                    floatValue = Double.parseDouble(split[0].trim());
                    precision = Integer.parseInt(split[1].trim());
                } else {
                    floatValue = Double.parseDouble(newValue);
                    int dot = newValue.indexOf('.');
                    if (dot >= 0) precision = newValue.length() - dot - 1;
                }
                return new FloatValueDefaultView((FloatDefView) attrDef, floatValue, precision);
            } else if (attrDef instanceof BooleanDefView) {
                String v = newValue.toLowerCase(Locale.ENGLISH);
                Boolean b;
                if ("1".equals(v) || "true".equals(v) || "yes".equals(v) || "y".equals(v)) {
                    b = Boolean.TRUE;
                } else if ("0".equals(v) || "false".equals(v) || "no".equals(v) || "n".equals(v)) {
                    b = Boolean.FALSE;
                } else {
                    return null;
                }
                return new BooleanValueDefaultView((BooleanDefView) attrDef, b);
            }
        } catch (Exception e) {
            return null;
        }

        return null;
    }

    private static AbstractValueView findIBAValue(DefaultAttributeContainer container, String ibaName) {
        AbstractValueView[] values = container.getAttributeValues();
        if (values == null) return null;

        for (AbstractValueView v : values) {
            if (v != null && v.getDefinition() != null && ibaName.equals(v.getDefinition().getName())) {
                return v;
            }
        }
        return null;
    }

    // =====================================================================
    // CSV / FILE HELPERS
    // =====================================================================

    private static void readCsv(File file, List<String> headersOut, List<List<String>> rowsOut) throws IOException {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String headerLine = br.readLine();
            if (headerLine == null) throw new IOException("CSV is empty: " + file.getName());
            headersOut.addAll(parseCsvLine(headerLine));

            String rowLine;
            while ((rowLine = br.readLine()) != null) {
                if (rowLine.trim().isEmpty()) continue;
                rowsOut.add(parseCsvLine(rowLine));
            }
        }
    }

    private static void validateRequiredColumns(Map<String, Integer> idx) {
        if (!idx.containsKey(EpmAttributeUpdateConfig.EPM_LOOKUP_COLUMN)) {
            throw new IllegalArgumentException("Required column missing: " + EpmAttributeUpdateConfig.EPM_LOOKUP_COLUMN);
        }

        for (String col : EpmAttributeUpdateConfig.CSV_TO_IBA_MAPPING.keySet()) {
            if (!idx.containsKey(col)) {
                throw new IllegalArgumentException("Mapped CSV column missing: " + col);
            }
        }
    }

    private static Map<String, Integer> indexMap(List<String> headers) {
        Map<String, Integer> m = new HashMap<>();
        for (int i = 0; i < headers.size(); i++) {
            m.put(headers.get(i), i);
        }
        return m;
    }

    private static Map<String, String> toRowMap(List<String> headers, List<String> row) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < headers.size(); i++) {
            String val = i < row.size() ? row.get(i) : "";
            m.put(headers.get(i), val);
        }
        return m;
    }

    private static List<String> prepend(List<String> headers, String... newCols) {
        List<String> out = new ArrayList<>();
        Collections.addAll(out, newCols);
        out.addAll(headers);
        return out;
    }

    private static void writeOutputRow(BufferedWriter bw, List<String> headers, List<String> row,
                                       String status, String message, String drwName) throws IOException {
        List<String> outRow = new ArrayList<>();
        outRow.add(status);
        outRow.add(message == null ? "" : message);
        outRow.add(drwName == null ? "" : drwName);
        for (int i = 0; i < headers.size(); i++) {
            outRow.add(i < row.size() ? row.get(i) : "");
        }
        bw.write(buildCsvLine(outRow));
        bw.newLine();
        bw.flush();
    }

    private static List<String> parseCsvLine(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);

            if (ch == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    sb.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (ch == EpmAttributeUpdateConfig.CSV_DELIMITER && !inQuotes) {
                out.add(sb.toString());
                sb.setLength(0);
            } else {
                sb.append(ch);
            }
        }
        out.add(sb.toString());
        return out;
    }

    private static String buildCsvLine(List<String> values) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(csvEscape(values.get(i)));
        }
        return sb.toString();
    }

    private static String safe(String s) {
        return s == null ? "" : s.trim();
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static void log(BufferedWriter bw, String message) throws IOException {
        bw.write("[" + new Timestamp(System.currentTimeMillis()) + "] " + message);
        bw.newLine();
        bw.flush();
    }

    private static void writeSummary(BufferedWriter bw, Summary s, String inputCsvName, long elapsedMs) throws IOException {
        bw.write("metric,value");
        bw.newLine();

        bw.write(csvEscape("generatedOn") + "," + csvEscape(new Timestamp(System.currentTimeMillis()).toString())); bw.newLine();
        bw.write(csvEscape("inputCsvFile") + "," + csvEscape(inputCsvName)); bw.newLine();

        bw.write(csvEscape("total") + "," + s.total); bw.newLine();
        bw.write(csvEscape("drw_updated") + "," + s.drwUpdated); bw.newLine();
        bw.write(csvEscape("success") + "," + s.success); bw.newLine();
        bw.write(csvEscape("fail") + "," + s.fail); bw.newLine();
        bw.write(csvEscape("not_found") + "," + s.notFound); bw.newLine();
        bw.write(csvEscape("checked_out") + "," + s.checkedOut); bw.newLine();

        double successPct = s.total == 0 ? 0.0 : (s.success * 100.0 / s.total);
        bw.write(csvEscape("success_percent") + "," + csvEscape(String.format(Locale.ENGLISH, "%.2f", successPct)));
        bw.newLine();

        bw.write(csvEscape("executionTimeMs") + "," + elapsedMs); bw.newLine();
        bw.write(csvEscape("executionTimeSec") + "," + csvEscape(String.format(Locale.ENGLISH, "%.2f", elapsedMs / 1000.0)));
        bw.newLine();

        bw.flush();
    }

    private static String csvEscape(String s) {
        if (s == null) return "\"\"";
        String v = s.replace("\"", "\"\"");
        return "\"" + v + "\"";
    }

    private static BufferedWriter writer(File f) throws IOException {
        return new BufferedWriter(new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8));
    }

    private static void safeRollback(Transaction trx) {
        if (trx != null) {
            try {
                trx.rollback();
            } catch (Exception ignore) { }
        }
    }

    private static class UpdateResult {
        boolean success;
        boolean checkedOutConflict;
        String message;
        String failedIba;
        List<String> updatedIbas = Collections.emptyList();
    }

    private static class Summary {
        int total = 0;
        int success = 0;
        int fail = 0;
        int notFound = 0;
        int checkedOut = 0;
        int drwUpdated = 0;
    }
}