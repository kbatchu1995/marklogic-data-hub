package com.marklogic.hub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.marklogic.client.DatabaseClient;
import com.marklogic.client.FailedRequestException;
import com.marklogic.client.datamovement.DataMovementManager;
import com.marklogic.client.datamovement.JobTicket;
import com.marklogic.client.datamovement.WriteBatcher;
import com.marklogic.client.document.GenericDocumentManager;
import com.marklogic.client.document.ServerTransform;
import com.marklogic.client.io.*;
import com.marklogic.hub.flow.*;
import com.marklogic.hub.scaffold.Scaffolding;
import com.marklogic.hub.util.MlcpRunner;
import org.apache.commons.io.FileUtils;
import org.custommonkey.xmlunit.XMLUnit;
import org.json.JSONException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.platform.runner.JUnitPlatform;
import org.junit.runner.RunWith;
import org.skyscreamer.jsonassert.JSONAssert;
import org.w3c.dom.Document;
import org.xml.sax.SAXException;

import javax.xml.parsers.ParserConfigurationException;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.custommonkey.xmlunit.XMLAssert.assertXMLEqual;
import static org.junit.jupiter.api.Assertions.*;

interface CreateFlowListener {
    void onFlowCreated(CodeFormat codeFormat, DataFormat dataFormat, FlowType flowType, String srcDir, Path flowDir) throws IOException;
}

interface ComboListener {
    void onCombo(CodeFormat codeFormat, DataFormat dataFormat, FlowType flowType) throws IOException, InterruptedException, ParserConfigurationException, SAXException, JSONException;
}

class Tuple<X, Y> {
    public final X x;
    public final Y y;
    public Tuple(X x, Y y) {
        this.x = x;
        this.y = y;
    }
}

class FinalCounts {
    public int stagingCount;
    public int finalCount;
    public int tracingCount;
    public int jobCount;
    public int completedCount;
    public int failedCount;
    public int jobSuccessfulEvents;
    public int jobFailedEvents;
    public int jobSuccessfulBatches;
    public int jobFailedBatches;
    public String jobStatus;
    public String optionsFile = "options-test";

    public FinalCounts(
        int stagingCount, int finalCount, int tracingCount, int jobCount, int completedCount, int failedCount,
        int jobSuccessfulEvents, int jobFailedEvents, int jobSuccessfulBatches, int jobFailedBatches, String jobStatus)
    {
        this.stagingCount = stagingCount;
        this.finalCount = finalCount;
        this.tracingCount = tracingCount;
        this.jobCount = jobCount;
        this.completedCount = completedCount;
        this.failedCount = failedCount;
        this.jobSuccessfulEvents = jobSuccessfulEvents;
        this.jobFailedEvents = jobFailedEvents;
        this.jobSuccessfulBatches = jobSuccessfulBatches;
        this.jobFailedBatches = jobFailedBatches;
        this.jobStatus = jobStatus;
    }
}

@RunWith(JUnitPlatform.class)
public class EndToEndFlowTests extends HubTestBase {
    private static final String ENTITY = "e2eentity";
    private static Path projectDir = Paths.get(".", "ye-olde-project");
    private static final int TEST_SIZE = 500;
    private static final int BATCH_SIZE = 10;
    private static FlowManager flowManager;
    private static DataMovementManager stagingDataMovementManager;
    private static DataMovementManager finalDataMovementManager;

    private boolean installDocsFinished = false;
    private boolean installDocsFailed = false;
    private String installDocError;

    private static Scaffolding scaffolding;

    @BeforeAll
    public static void setup() throws IOException, InterruptedException, ParserConfigurationException, SAXException, JSONException {
        XMLUnit.setIgnoreWhitespace(true);

        File projectDirFile = projectDir.toFile();
        if (projectDirFile.isDirectory() && projectDirFile.exists()) {
            FileUtils.deleteDirectory(projectDirFile);
        }

        installHub();
        enableTracing();

        scaffolding = new Scaffolding(projectDir.toString(), stagingClient);
        scaffolding.createEntity(ENTITY);

        scaffoldFlows("scaffolded");

        createFlows("with-error", (codeFormat, dataFormat, flowType, srcDir, flowDir) -> {
            copyFile(srcDir + "main-" + flowType.toString() + "." + codeFormat.toString(), flowDir.resolve("main." + codeFormat.toString()));
            copyFile(srcDir + "extra-plugin." + codeFormat.toString(), flowDir.resolve("extra-plugin." + codeFormat.toString()));
        });

        createFlows("extra-plugin", (codeFormat, dataFormat, flowType, srcDir, flowDir) -> {
            copyFile(srcDir + "main-" + flowType.toString() + "." + codeFormat.toString(), flowDir.resolve("main." + codeFormat.toString()));
            copyFile(srcDir + "extra-plugin." + codeFormat.toString(), flowDir.resolve("extra-plugin." + codeFormat.toString()));
        });

        allCombos((codeFormat, dataFormat, flowType) -> {
            if (codeFormat.equals(CodeFormat.XQUERY)) {
                createFlow("triples-array", codeFormat, dataFormat, flowType, (codeFormat1, dataFormat1, flowType1, srcDir, flowDir) -> {
                    copyFile(srcDir + "triples-json-array.xqy", flowDir.resolve("triples.xqy"));
                });
            }
        });

        allCombos((codeFormat, dataFormat, flowType) -> {
            createLegacyFlow(codeFormat, dataFormat, flowType);
        });

        flowManager = new FlowManager(getHubConfig());
        List<String> legacyFlows = flowManager.getLegacyFlows();
        assertEquals(8, legacyFlows.size(), String.join("\n", legacyFlows));
        assertEquals(8, flowManager.updateLegacyFlows().size());
        assertEquals(0, flowManager.getLegacyFlows().size());

        getDataHub().installUserModules();

        stagingDataMovementManager = stagingClient.newDataMovementManager();
        finalDataMovementManager = finalClient.newDataMovementManager();
    }

    @AfterAll
    public static void teardown() throws IOException {
        uninstallHub();
    }

    @TestFactory
    public List<DynamicTest> generateTests() throws InterruptedException, ParserConfigurationException, SAXException, JSONException, IOException {
        List<DynamicTest> tests = new ArrayList<>();
        allCombos((codeFormat, dataFormat, flowType) -> {
            String prefix = "legacy";
            String flowName = getFlowName(prefix, codeFormat, dataFormat, flowType);
            if (flowType.equals(FlowType.INPUT)) {
                tests.add(DynamicTest.dynamicTest(flowName + " MLCP", () -> {
                    Map<String, Object> options = new HashMap<>();
                    FinalCounts finalCounts = new FinalCounts(1, 0, 2, 1, 0, 0, 1, 0, 0, 0, "FINISHED");
                    testInputFlowViaMlcp(prefix, "", stagingClient, codeFormat, dataFormat, options, finalCounts);
                }));
                tests.add(DynamicTest.dynamicTest(flowName + " MLCP", () -> {
                    Map<String, Object> options = new HashMap<>();
                    FinalCounts finalCounts = new FinalCounts(0, 1, 2, 1, 0, 0, 1, 0, 0, 0, "FINISHED");
                    testInputFlowViaMlcp(prefix, "", finalClient, codeFormat, dataFormat, options, finalCounts);
                }));
                tests.add(DynamicTest.dynamicTest(flowName + " REST", () -> {
                    Map<String, Object> options = new HashMap<>();
                    FinalCounts finalCounts = new FinalCounts(1, 0, 2, 0, 0, 0, 0, 0, 0, 0, "FINISHED");
                    testInputFlowViaREST(prefix, "", codeFormat, dataFormat, options, finalCounts);
                }));
            }
            else {
                Map<String, Object> options = new HashMap<>();
                tests.add(DynamicTest.dynamicTest(flowName + " wait", () -> {
                    FinalCounts finalCounts = new FinalCounts(TEST_SIZE, TEST_SIZE + 1, TEST_SIZE + 1, 1, TEST_SIZE, 0, TEST_SIZE, 0, TEST_SIZE/BATCH_SIZE, 0, "FINISHED");
                    testHarmonizeFlow(prefix, codeFormat, dataFormat, options, stagingClient, HubConfig.DEFAULT_FINAL_NAME, finalCounts, true);
                }));
                tests.add(DynamicTest.dynamicTest(flowName + " wait Reverse Dbs", () -> {
                    FinalCounts finalCounts = new FinalCounts(TEST_SIZE + 1, TEST_SIZE, TEST_SIZE + 1, 1, TEST_SIZE, 0, TEST_SIZE, 0, TEST_SIZE/BATCH_SIZE, 0, "FINISHED");
                    testHarmonizeFlow(prefix, codeFormat, dataFormat, options, finalClient, HubConfig.DEFAULT_STAGING_NAME, finalCounts, true);
                }));
                tests.add(DynamicTest.dynamicTest(flowName + " no-wait", () -> {
                    FinalCounts finalCounts = new FinalCounts(TEST_SIZE, TEST_SIZE + 1, TEST_SIZE + 1, 1, TEST_SIZE, 0, TEST_SIZE, 0, TEST_SIZE/BATCH_SIZE, 0, "FINISHED");
                    testHarmonizeFlow(prefix, codeFormat, dataFormat, options, stagingClient, HubConfig.DEFAULT_FINAL_NAME, finalCounts, false);
                }));
            }
        });

        allCombos((codeFormat, dataFormat, flowType) -> {
            String prefix = "extra-plugin";
            String flowName = getFlowName(prefix, codeFormat, dataFormat, flowType);
            if (flowType.equals(FlowType.INPUT)) {
                tests.add(DynamicTest.dynamicTest(flowName + " MLCP", () -> {
                    Map<String, Object> options = new HashMap<>();
                    options.put("extraPlugin", true);
                    FinalCounts finalCounts = new FinalCounts(1, 0, 2, 1, 0, 0, 1, 0, 0, 0, "FINISHED");
                    testInputFlowViaMlcp(prefix, "", stagingClient, codeFormat, dataFormat, options, finalCounts);
                }));
                tests.add(DynamicTest.dynamicTest(flowName + " REST", () -> {
                    Map<String, Object> options = new HashMap<>();
                    options.put("extraPlugin", true);
                    FinalCounts finalCounts = new FinalCounts(1, 0, 2, 0, 0, 0, 0, 0, 0, 0, "FINISHED");
                    testInputFlowViaREST(prefix, "", codeFormat, dataFormat, options, finalCounts);
                }));
            }
            else {
                tests.add(DynamicTest.dynamicTest(flowName + " wait", () -> {
                    Map<String, Object> options = new HashMap<>();
                    options.put("extraPlugin", true);
                    FinalCounts finalCounts = new FinalCounts(TEST_SIZE, TEST_SIZE + 1, TEST_SIZE + 1, 1, TEST_SIZE, 0, TEST_SIZE, 0, TEST_SIZE/BATCH_SIZE, 0, "FINISHED");
                    finalCounts.optionsFile = "options-extra";
                    testHarmonizeFlow(prefix, codeFormat, dataFormat, options, stagingClient, HubConfig.DEFAULT_FINAL_NAME, finalCounts, true);
                }));

                tests.add(DynamicTest.dynamicTest(flowName + " extra error", () -> {
                    Map<String, Object> options = new HashMap<>();
                    options.put("extraPlugin", true);
                    options.put("extraGoBoom", true);
                    FinalCounts finalCounts = new FinalCounts(TEST_SIZE, TEST_SIZE, TEST_SIZE + 1, 1, TEST_SIZE - 1, 1, TEST_SIZE - 1, 1, TEST_SIZE/BATCH_SIZE, 0, "FINISHED_WITH_ERRORS");
                    testHarmonizeFlowWithFailedMain(prefix, codeFormat, dataFormat, options, stagingClient, HubConfig.DEFAULT_FINAL_NAME, finalCounts);
                }));
            }
        });

        allCombos((codeFormat, dataFormat, flowType) -> {
            String prefix = "scaffolded";
            String flowName = getFlowName(prefix, codeFormat, dataFormat, flowType);
            if (flowType.equals(FlowType.INPUT)) {
                tests.add(DynamicTest.dynamicTest(flowName + " MLCP", () -> {
                    Map<String, Object> options = new HashMap<>();
                    FinalCounts finalCounts = new FinalCounts(1, 0, 2, 1, 0, 0, 1, 0, 0, 0, "FINISHED");
                    testInputFlowViaMlcp(prefix, "", stagingClient, codeFormat, dataFormat, options, finalCounts);
                }));
                tests.add(DynamicTest.dynamicTest(flowName + " REST", () -> {
                    Map<String, Object> options = new HashMap<>();
                    FinalCounts finalCounts = new FinalCounts(1, 0, 2, 0, 0, 0, 0, 0, 0, 0, "FINISHED");
                    testInputFlowViaREST(prefix, "", codeFormat, dataFormat, options, finalCounts);
                }));
            }
            else {
                Map<String, Object> options = new HashMap<>();
                tests.add(DynamicTest.dynamicTest(flowName + " wait", () -> {
                    testScaffoldedHarmonizeFlow(prefix, codeFormat, dataFormat, options, stagingClient, HubConfig.DEFAULT_FINAL_NAME, true);
                }));
                tests.add(DynamicTest.dynamicTest(flowName + " wait Reverse DBs", () -> {
                    testScaffoldedHarmonizeFlow(prefix, codeFormat, dataFormat, options, finalClient, HubConfig.DEFAULT_STAGING_NAME, true);
                }));
                tests.add(DynamicTest.dynamicTest(flowName + " no-wait", () -> {
                    testScaffoldedHarmonizeFlow(prefix, codeFormat, dataFormat, options, stagingClient, HubConfig.DEFAULT_FINAL_NAME, false);
                }));
            }
        });

        allCombos((codeFormat, dataFormat, flowType) -> {
            String prefix = "triples-array";
            String flowName = getFlowName(prefix, codeFormat, dataFormat, flowType);
            if (codeFormat.equals(CodeFormat.XQUERY)) {
                if (flowType.equals(FlowType.INPUT)) {
                    tests.add(DynamicTest.dynamicTest(flowName + " MLCP", () -> {
                        Map<String, Object> options = new HashMap<>();
                        FinalCounts finalCounts = new FinalCounts(1, 0, 2, 1, 0, 0, 1, 0, 0, 0, "FINISHED");
                        testInputFlowViaMlcp(prefix, "", stagingClient, codeFormat, dataFormat, options, finalCounts);
                    }));

                    tests.add(DynamicTest.dynamicTest(flowName + " REST", () -> {
                        Map<String, Object> options = new HashMap<>();
                        FinalCounts finalCounts = new FinalCounts(1, 0, 2, 0, 0, 0, 1, 0, 0, 0, "FINISHED");
                        testInputFlowViaREST(prefix, "", codeFormat, dataFormat, options, finalCounts);
                    }));
                } else {
                    Map<String, Object> options = new HashMap<>();
                    FinalCounts finalCounts = new FinalCounts(TEST_SIZE, TEST_SIZE + 1, TEST_SIZE + 1, 1, TEST_SIZE, 0, TEST_SIZE, 0, TEST_SIZE / BATCH_SIZE, 0, "FINISHED");
                    tests.add(DynamicTest.dynamicTest(flowName, () -> {
                        testHarmonizeFlow(prefix, codeFormat, dataFormat, options, stagingClient, HubConfig.DEFAULT_FINAL_NAME, finalCounts, true);
                    }));
                }
            }
        });

        allCombos(((codeFormat, dataFormat, flowType) -> {
            String prefix = "with-error";
            String flowName = getFlowName(prefix, codeFormat, dataFormat, flowType);
            if (flowType.equals(FlowType.INPUT)) {
                for (String plugin : new String[] { "main", "content", "headers", "triples"}) {
                    Map<String, Object> options = new HashMap<>();
                    options.put(plugin + "GoBoom", true);
                    tests.add(DynamicTest.dynamicTest(flowName + ": " + plugin + " error MLCP", () -> {
                        FinalCounts finalCounts = new FinalCounts(0, 0, 1, 1, 0, 0, 0, 0, 0, 0, "FAILED");
                        testInputFlowViaMlcp(prefix, "-2", stagingClient, codeFormat, dataFormat, options, finalCounts);
                    }));

                    tests.add(DynamicTest.dynamicTest(flowName + ": " + plugin + " error REST", () -> {
                        FinalCounts finalCounts = new FinalCounts(0, 0, 1, 0, 0, 0, 0, 0, 0, 0, "FAILED");
                        testInputFlowViaREST(prefix, "-2", codeFormat, dataFormat, options, finalCounts);
                    }));
                }
            }
            else {
                tests.add(DynamicTest.dynamicTest(flowName + ": collector error", () -> {
                    Map<String, Object> options = new HashMap<>();
                    options.put("collectorGoBoom", true);
                    FinalCounts finalCounts = new FinalCounts(TEST_SIZE, 0, 1, 1, 0, 0, 0, 0, 0, 0, "FAILED");
                    testHarmonizeFlowWithFailedMain(prefix, codeFormat, dataFormat, options, stagingClient, HubConfig.DEFAULT_FINAL_NAME, finalCounts);
                }));

                FinalCounts finalCounts = new FinalCounts(TEST_SIZE, TEST_SIZE, TEST_SIZE + 1, 1, TEST_SIZE - 1, 1, TEST_SIZE - 1, 1, TEST_SIZE/BATCH_SIZE, 0, "FINISHED_WITH_ERRORS");
                for (String plugin : new String[] { "main", "content", "headers", "triples", "writer"}) {
                    tests.add(DynamicTest.dynamicTest(flowName + ": " + plugin + " error", () -> {
                        Map<String, Object> options = new HashMap<>();
                        options.put(plugin + "GoBoom", true);
                        testHarmonizeFlowWithFailedMain(prefix, codeFormat, dataFormat, options, stagingClient, HubConfig.DEFAULT_FINAL_NAME, finalCounts);
                    }));
                }
            }
        }));

        return tests;
    }

    private static String getFlowName(String prefix, CodeFormat codeFormat, DataFormat dataFormat, FlowType flowType) {
        return prefix + "-" + flowType.toString() + "-" + codeFormat.toString() + "-" + dataFormat.toString();
    }

    private static void createLegacyFlow(CodeFormat codeFormat, DataFormat dataFormat, FlowType flowType) throws IOException {

        String flowName = getFlowName("legacy", codeFormat, dataFormat, flowType);
        Path flowDir = projectDir.resolve("plugins").resolve("entities").resolve(ENTITY).resolve(flowType.toString()).resolve(flowName);

        if (flowType.equals(FlowType.HARMONIZE)) {
            flowDir.resolve("collector").toFile().mkdirs();
            flowDir.resolve("writer").toFile().mkdirs();
        }
        flowDir.resolve("content").toFile().mkdirs();
        flowDir.resolve("headers").toFile().mkdirs();
        flowDir.resolve("triples").toFile().mkdirs();

        String srcDir = "e2e-test/" + codeFormat.toString() + "-flow/";
        if (flowType.equals(FlowType.HARMONIZE)) {
            copyFile(srcDir + "collector." + codeFormat.toString(), flowDir.resolve("collector/collector." + codeFormat.toString()));
            copyFile(srcDir + "writer." + codeFormat.toString(), flowDir.resolve("writer/writer." + codeFormat.toString()));
        }

        if (codeFormat.equals(CodeFormat.JAVASCRIPT)) {
            copyFile(srcDir + "headers." + codeFormat.toString(), flowDir.resolve("headers/headers." + codeFormat.toString()));
        }
        else {
            copyFile(srcDir + "headers-" + dataFormat.toString() + "." + codeFormat.toString(), flowDir.resolve("headers/headers." + codeFormat.toString()));
        }
        copyFile(srcDir + "content-" + flowType.toString() + "." + codeFormat.toString(), flowDir.resolve("content/content." + codeFormat.toString()));
        copyFile(srcDir + "triples." + codeFormat.toString(), flowDir.resolve("triples/triples." + codeFormat.toString()));

        copyFile("e2e-test/legacy-" + dataFormat.toString() + ".xml", flowDir.resolve("" + flowName + ".xml"));
    }

    private static void scaffoldFlows(String prefix) throws IOException, InterruptedException, ParserConfigurationException, SAXException, JSONException {
        allCombos(((codeFormat, dataFormat, flowType) -> {
            scaffoldFlow(prefix, codeFormat, dataFormat, flowType);
        }));
    }

    private static void scaffoldFlow(String prefix, CodeFormat codeFormat, DataFormat dataFormat, FlowType flowType) throws IOException {
        String flowName = getFlowName(prefix, codeFormat, dataFormat, flowType);
        scaffolding.createFlow(ENTITY, flowName, flowType, codeFormat, dataFormat);
    }

    private static void allCombos(ComboListener listener) throws IOException, InterruptedException, ParserConfigurationException, SAXException, JSONException {
        CodeFormat[] codeFormats = new CodeFormat[] { CodeFormat.JAVASCRIPT, CodeFormat.XQUERY };
        DataFormat[] dataFormats = new DataFormat[] { DataFormat.JSON, DataFormat.XML };
        FlowType[] flowTypes = new FlowType[] { FlowType.INPUT, FlowType.HARMONIZE };
        for (CodeFormat codeFormat : codeFormats) {
            for (DataFormat dataFormat : dataFormats) {
                for (FlowType flowType : flowTypes) {
                    listener.onCombo(codeFormat, dataFormat, flowType);
                }
            }
        }
    }

    private static void createFlows(String prefix, CreateFlowListener listener) throws IOException, InterruptedException, ParserConfigurationException, SAXException, JSONException {
        allCombos(((codeFormat, dataFormat, flowType) -> {
            createFlow(prefix, codeFormat, dataFormat, flowType, listener);
        }));
    }

    private static void createFlow(String prefix, CodeFormat codeFormat, DataFormat dataFormat, FlowType flowType, CreateFlowListener listener) throws IOException {
        String flowName = getFlowName(prefix, codeFormat, dataFormat, flowType);
        Path flowDir = projectDir.resolve("plugins").resolve("entities").resolve(ENTITY).resolve(flowType.toString()).resolve(flowName);

        scaffolding.createFlow(ENTITY, flowName, flowType, codeFormat, dataFormat);

        String srcDir = "e2e-test/" + codeFormat.toString() + "-flow/";
        if (flowType.equals(FlowType.HARMONIZE)) {
            copyFile(srcDir + "collector." + codeFormat.toString(), flowDir.resolve("collector." + codeFormat.toString()));
            copyFile(srcDir + "writer." + codeFormat.toString(), flowDir.resolve("writer." + codeFormat.toString()));
        }

        if (codeFormat.equals(CodeFormat.JAVASCRIPT)) {
            copyFile(srcDir + "headers." + codeFormat.toString(), flowDir.resolve("headers." + codeFormat.toString()));
        }
        else {
            copyFile(srcDir + "headers-" + dataFormat.toString() + "." + codeFormat.toString(), flowDir.resolve("headers." + codeFormat.toString()));
        }

        copyFile(srcDir + "content-" + flowType.toString() + "." + codeFormat.toString(), flowDir.resolve("content." + codeFormat.toString()));
        copyFile(srcDir + "triples." + codeFormat.toString(), flowDir.resolve("triples." + codeFormat.toString()));

        if (listener != null) {
            listener.onFlowCreated(codeFormat, dataFormat, flowType, srcDir, flowDir);
        }
    }

    private static void copyFile(String srcDir, Path dstDir) throws IOException {
        FileUtils.copyFile(getResourceFile(srcDir), dstDir.toFile());
    }

    private void installDocs(DataFormat dataFormat, String collection, DatabaseClient srcClient) throws IOException {
        DataMovementManager mgr = stagingDataMovementManager;
        if (srcClient.getDatabase().equals(HubConfig.DEFAULT_FINAL_NAME)) {
            mgr = finalDataMovementManager;
        }

        WriteBatcher writeBatcher = mgr.newWriteBatcher()
            .withBatchSize(100)
            .withThreadCount(4)
            .onBatchSuccess(batch -> installDocsFinished = true)
            .onBatchFailure((batch, failure) -> {
                failure.printStackTrace();
                installDocError = failure.getMessage();
                installDocsFailed = true;
            });

        installDocsFinished = false;
        installDocsFailed = false;
        mgr.startJob(writeBatcher);

        DocumentMetadataHandle metadataHandle = new DocumentMetadataHandle();
        metadataHandle.getCollections().add(collection);
        StringHandle handle = new StringHandle(getResource("e2e-test/staged." + dataFormat.toString()));
        String dataFormatString = dataFormat.toString();
        for (int i = 0; i < TEST_SIZE; i++) {
            writeBatcher.add("/input-" + i + "." + dataFormatString, metadataHandle, handle);
        }

        writeBatcher.flushAndWait();
        assertTrue(installDocsFinished, "Doc install not finished");
        assertFalse(installDocsFailed, "Doc install failed: " + installDocError);

        if (srcClient.getDatabase().equals(HubConfig.DEFAULT_STAGING_NAME)) {
            assertEquals(TEST_SIZE, getStagingDocCount(collection));
            assertEquals(0, getFinalDocCount(collection));
        }
        else {
            assertEquals(TEST_SIZE, getFinalDocCount(collection));
            assertEquals(0, getStagingDocCount(collection));
        }
    }

    private void testInputFlowViaMlcp(String prefix, String fileSuffix, DatabaseClient databaseClient, CodeFormat codeFormat, DataFormat dataFormat, Map<String, Object> options, FinalCounts finalCounts) throws IOException, ParserConfigurationException, SAXException, JSONException, InterruptedException {
        clearDatabases(HubConfig.DEFAULT_STAGING_NAME, HubConfig.DEFAULT_FINAL_NAME, HubConfig.DEFAULT_TRACE_NAME, HubConfig.DEFAULT_JOB_NAME);

        String flowName = getFlowName(prefix, codeFormat, dataFormat, FlowType.INPUT);

        assertEquals(0, getStagingDocCount());
        assertEquals(0, getFinalDocCount());
        assertEquals(0, getTracingDocCount());
        assertEquals(0, getJobDocCount());

        Flow flow = flowManager.getFlow(ENTITY, flowName, FlowType.INPUT);
        String inputPath = getResourceFile("e2e-test/input/input" + fileSuffix + "." + dataFormat.toString()).getAbsolutePath();
        String basePath = getResourceFile("e2e-test/input").getAbsolutePath();
        String optionString = new ObjectMapper().writeValueAsString(options).replace("\"", "\\\"\\\"");
        JsonNode mlcpOptions = new ObjectMapper().readTree(
            "{" +
                "\"input_file_path\":\"" + inputPath + "\"," +
                "\"input_file_type\":\"\\\"documents\\\"\"," +
                "\"output_collections\":\"\\\"" + ENTITY + "\\\"\"," +
                "\"output_permissions\":\"\\\"rest-reader,read,rest-writer,update\\\"\"," +
                "\"output_uri_replace\":\"\\\"" + basePath + ",''\\\"\"," +
                "\"document_type\":\"\\\"" + dataFormat.toString() + "\\\"\"," +
                "\"transform_module\":\"\\\"/com.marklogic.hub/mlcp-flow-transform.xqy\\\"\"," +
                "\"transform_namespace\":\"\\\"http://marklogic.com/data-hub/mlcp-flow-transform\\\"\"," +
                "\"transform_param\":\"\\\"entity=" + ENTITY + ",flow=" + flowName + ",flowType=input,options=" + optionString + "\\\"\"" +
                "}");
        MlcpRunner mlcpRunner = new MlcpRunner("com.marklogic.hub.util.MlcpMain", getHubConfig(), flow, databaseClient, mlcpOptions, null);
        mlcpRunner.start();
        mlcpRunner.join();

        assertEquals(finalCounts.stagingCount, getStagingDocCount());
        assertEquals(finalCounts.finalCount, getFinalDocCount());
        assertEquals(finalCounts.tracingCount, getTracingDocCount());
        assertEquals(finalCounts.jobCount, getJobDocCount());

        // inspect the job json
        JsonNode node = jobDocMgr.read("/jobs/" + mlcpRunner.getJobId() + ".json").next().getContent(new JacksonHandle()).get();
        assertEquals(mlcpRunner.getJobId(), node.get("jobId").asText());
        assertEquals(finalCounts.jobSuccessfulEvents, node.get("successfulEvents").asInt());
        assertEquals(finalCounts.jobFailedEvents, node.get("failedEvents").asInt());
        assertEquals(finalCounts.jobSuccessfulBatches, node.get("successfulBatches").asInt());
        assertEquals(finalCounts.jobFailedBatches, node.get("failedBatches").asInt());
        assertEquals(finalCounts.jobStatus, node.get("status").asText());
    }

    private void testInputFlowViaREST(String prefix, String fileSuffix, CodeFormat codeFormat, DataFormat dataFormat, Map<String, Object> options, FinalCounts finalCounts) throws IOException, ParserConfigurationException, SAXException, JSONException, InterruptedException {
        clearDatabases(HubConfig.DEFAULT_STAGING_NAME, HubConfig.DEFAULT_FINAL_NAME, HubConfig.DEFAULT_TRACE_NAME, HubConfig.DEFAULT_JOB_NAME);

        String flowName = getFlowName(prefix, codeFormat, dataFormat, FlowType.INPUT);

        assertEquals(0, getStagingDocCount());
        assertEquals(0, getFinalDocCount());
        assertEquals(0, getTracingDocCount());
        assertEquals(0, getJobDocCount());

        ServerTransform serverTransform = new ServerTransform("run-flow");
        serverTransform.addParameter("job-id", UUID.randomUUID().toString());
        serverTransform.addParameter("entity", ENTITY);
        serverTransform.addParameter("flow", flowName);
        String optionString = new ObjectMapper().writeValueAsString(options);
        serverTransform.addParameter("options", optionString);
        FileHandle handle = new FileHandle(getResourceFile("e2e-test/input/input." + dataFormat.toString()));
        Format format = null;
        switch (dataFormat) {
            case XML:
                format = Format.XML;
                break;

            case JSON:
                format = Format.JSON;
                break;
        }
        handle.setFormat(format);

        try {
            stagingDocMgr.write("/input" + fileSuffix + "." + dataFormat.toString(), handle, serverTransform);
            if (finalCounts.stagingCount == 0) {
                fail("Should have thrown an exception.");
            }
        }
        catch(FailedRequestException e) {

        }

        assertEquals(finalCounts.stagingCount, getStagingDocCount());
        assertEquals(finalCounts.finalCount, getFinalDocCount());
        assertEquals(finalCounts.tracingCount, getTracingDocCount());
        assertEquals(finalCounts.jobCount, getJobDocCount());

        if (finalCounts.stagingCount == 1) {
            String filename = "final";
            if (prefix.equals("scaffolded")) {
                filename = "staged";
            }
            if (dataFormat.equals(DataFormat.JSON)) {
                String expected = getResource("e2e-test/" + filename + "." + dataFormat.toString());
                String actual = stagingDocMgr.read("/input" + fileSuffix + "." + dataFormat.toString()).next().getContent(new StringHandle()).get();
                JSONAssert.assertEquals(expected, actual, false);
            } else {
                Document expected = getXmlFromResource("e2e-test/" + filename + "." + dataFormat.toString());
                Document actual = stagingDocMgr.read("/input" + fileSuffix + "." + dataFormat.toString()).next().getContent(new DOMHandle()).get();
                assertXMLEqual(expected, actual);
            }
        }
    }

    private Tuple<FlowRunner, JobTicket> runHarmonizeFlow(
        String flowName, DataFormat dataFormat,
        Vector<String> completed, Vector<String> failed,
        Map<String, Object> options,
        DatabaseClient srcClient, String destDb,
        boolean waitForCompletion) throws IOException, InterruptedException
    {
        clearDatabases(HubConfig.DEFAULT_STAGING_NAME, HubConfig.DEFAULT_FINAL_NAME, HubConfig.DEFAULT_TRACE_NAME, HubConfig.DEFAULT_JOB_NAME);

        assertEquals(0, getStagingDocCount());
        assertEquals(0, getFinalDocCount());
        assertEquals(0, getTracingDocCount());
        assertEquals(0, getJobDocCount());

        installDocs(dataFormat, ENTITY, srcClient);

        Flow harmonizeFlow = flowManager.getFlow(ENTITY, flowName, FlowType.HARMONIZE);

        FlowRunner flowRunner = flowManager.newFlowRunner()
            .withFlow(harmonizeFlow)
            .withBatchSize(BATCH_SIZE)
            .withThreadCount(4)
            .withOptions(options)
            .withSourceClient(srcClient)
            .withDestinationDatabase(destDb)
            .onItemComplete((String jobId, String itemId) -> {
               completed.add(itemId);
            })
            .onItemFailed((String jobId, String itemId) -> {
               failed.add(itemId);
            });

        JobTicket jobTicket = flowRunner.run();
        if (waitForCompletion) {
            flowRunner.awaitCompletion();
        }
        else {
            flowRunner.awaitCompletion(2, TimeUnit.MILLISECONDS);
        }
        return new Tuple<>(flowRunner, jobTicket);
    }

    private void testScaffoldedHarmonizeFlow(
        String prefix, CodeFormat codeFormat, DataFormat dataFormat,
        Map<String, Object> options, DatabaseClient srcClient, String destDb,
        boolean waitForCompletion) throws IOException, ParserConfigurationException, SAXException, JSONException, InterruptedException
    {
        String flowName = getFlowName(prefix, codeFormat, dataFormat, FlowType.HARMONIZE);

        Vector<String> completed = new Vector<>();
        Vector<String> failed = new Vector<>();

        Tuple<FlowRunner, JobTicket> tuple = runHarmonizeFlow(flowName, dataFormat, completed, failed, options, srcClient, destDb, waitForCompletion);

        GenericDocumentManager mgr = finalDocMgr;
        if (destDb.equals(HubConfig.DEFAULT_STAGING_NAME)) {
            mgr = stagingDocMgr;
        }

        if (waitForCompletion) {
            assertEquals(TEST_SIZE, getStagingDocCount());
            assertEquals(TEST_SIZE, getFinalDocCount());
            assertEquals(TEST_SIZE + 1, getTracingDocCount());
            assertEquals(1, getJobDocCount());

            assertEquals(TEST_SIZE, completed.size());
            assertEquals(0, failed.size());

            if (dataFormat.equals(DataFormat.XML)) {
                Document expected = getXmlFromResource("e2e-test/staged.xml");
                for (int i = 0; i < TEST_SIZE; i+=10) {
                    Document actual = mgr.read("/input-" + i + ".xml").next().getContent(new DOMHandle()).get();
                    assertXMLEqual(expected, actual);
                }
            } else {
                String expected = getResource("e2e-test/staged." + dataFormat.toString());
                for (int i = 0; i < TEST_SIZE; i+=10) {
                    String actual = mgr.read("/input-" + i + "." + dataFormat.toString()).next().getContent(new StringHandle()).get();
                    JSONAssert.assertEquals(expected, actual, false);
                }
            }

            // inspect the job json
            JsonNode node = jobDocMgr.read("/jobs/" + tuple.y.getJobId() + ".json").next().getContent(new JacksonHandle()).get();
            assertEquals(tuple.y.getJobId(), node.get("jobId").asText());
            assertEquals(TEST_SIZE, node.get("successfulEvents").asInt());
            assertEquals(0, node.get("failedEvents").asInt());
            assertEquals(TEST_SIZE / BATCH_SIZE, node.get("successfulBatches").asInt());
            assertEquals(0, node.get("failedBatches").asInt());
            assertEquals("FINISHED", node.get("status").asText());
        }
        else {
            assertNotEquals(TEST_SIZE, getFinalDocCount());
            tuple.x.awaitCompletion();
        }
    }

    private void testHarmonizeFlow(
        String prefix, CodeFormat codeFormat, DataFormat dataFormat,
        Map<String, Object> options, DatabaseClient srcClient, String destDb,
        FinalCounts finalCounts, boolean waitForCompletion) throws IOException, ParserConfigurationException, SAXException, JSONException, InterruptedException
    {
        String flowName = getFlowName(prefix, codeFormat, dataFormat, FlowType.HARMONIZE);

        Vector<String> completed = new Vector<>();
        Vector<String> failed = new Vector<>();

        Tuple<FlowRunner, JobTicket> tuple = runHarmonizeFlow(flowName, dataFormat, completed, failed, options, srcClient, destDb, waitForCompletion);

        if (waitForCompletion) {
            assertEquals(finalCounts.stagingCount, getStagingDocCount());
            assertEquals(finalCounts.finalCount, getFinalDocCount());
            assertEquals(finalCounts.tracingCount, getTracingDocCount());
            assertEquals(finalCounts.jobCount, getJobDocCount());

            assertEquals(finalCounts.completedCount, completed.size());
            assertEquals(finalCounts.failedCount, failed.size());

            GenericDocumentManager mgr = finalDocMgr;
            if (destDb.equals(HubConfig.DEFAULT_STAGING_NAME)) {
                mgr = stagingDocMgr;
            }

            if (dataFormat.equals(DataFormat.XML)) {
                Document expected = getXmlFromResource("e2e-test/final.xml");
                for (int i = 0; i < TEST_SIZE; i+=10) {
                    Document actual = mgr.read("/input-" + i + ".xml").next().getContent(new DOMHandle()).get();
                    assertXMLEqual(expected, actual);
                }
            } else {
                String expected = getResource("e2e-test/final." + dataFormat.toString());
                for (int i = 0; i < TEST_SIZE; i+=10) {
                    String actual = mgr.read("/input-" + i + "." + dataFormat.toString()).next().getContent(new StringHandle()).get();
                    JSONAssert.assertEquals(expected, actual, false);
                }
            }

            // inspect the job json
            JsonNode node = jobDocMgr.read("/jobs/" + tuple.y.getJobId() + ".json").next().getContent(new JacksonHandle()).get();
            assertEquals(tuple.y.getJobId(), node.get("jobId").asText());
            assertEquals(finalCounts.jobSuccessfulEvents, node.get("successfulEvents").asInt());
            assertEquals(finalCounts.jobFailedEvents, node.get("failedEvents").asInt());
            assertEquals(finalCounts.jobSuccessfulBatches, node.get("successfulBatches").asInt());
            assertEquals(finalCounts.jobFailedBatches, node.get("failedBatches").asInt());
            assertEquals(finalCounts.jobStatus, node.get("status").asText());

            if (codeFormat.equals(CodeFormat.XQUERY)) {
                Document optionsActual = mgr.read("/options-test.xml").next().getContent(new DOMHandle()).get();
                Document optionsExpected = getXmlFromResource("e2e-test/" + finalCounts.optionsFile + ".xml");
                assertXMLEqual(optionsExpected, optionsActual);
            } else {
                String optionsExpected = getResource("e2e-test/" + finalCounts.optionsFile + ".json");
                String optionsActual = mgr.read("/options-test.json").next().getContent(new StringHandle()).get();
                JSONAssert.assertEquals(optionsExpected, optionsActual, false);
            }
        }
        else {
            assertNotEquals(TEST_SIZE, getFinalDocCount());
            tuple.x.awaitCompletion();
        }
    }

    private void testHarmonizeFlowWithFailedMain(
        String prefix, CodeFormat codeFormat, DataFormat dataFormat,
        Map<String, Object> options, DatabaseClient srcClient, String destDb,
        FinalCounts finalCounts) throws IOException, InterruptedException
    {
        String flowName = getFlowName(prefix, codeFormat, dataFormat, FlowType.HARMONIZE);

        Vector<String> completed = new Vector<>();
        Vector<String> failed = new Vector<>();

        Tuple<FlowRunner, JobTicket> tuple = runHarmonizeFlow(flowName, dataFormat, completed, failed, options, srcClient, destDb, true);

        assertEquals(finalCounts.stagingCount, getStagingDocCount());
        assertEquals(finalCounts.finalCount, getFinalDocCount());
        assertEquals(finalCounts.tracingCount, getTracingDocCount());
        assertEquals(finalCounts.jobCount, getJobDocCount());

        assertEquals(finalCounts.completedCount, completed.size());
        assertEquals(finalCounts.failedCount, failed.size());

        // inspect the job json
        JsonNode node = jobDocMgr.read("/jobs/" + tuple.y.getJobId() + ".json").next().getContent(new JacksonHandle()).get();
        assertEquals(tuple.y.getJobId(), node.get("jobId").asText());
        assertEquals(finalCounts.jobSuccessfulEvents, node.get("successfulEvents").asInt());
        assertEquals(finalCounts.jobFailedEvents, node.get("failedEvents").asInt());
        assertEquals(finalCounts.jobSuccessfulBatches, node.get("successfulBatches").asInt());
        assertEquals(finalCounts.jobFailedBatches, node.get("failedBatches").asInt());
        assertEquals(finalCounts.jobStatus, node.get("status").asText());
   }
}
