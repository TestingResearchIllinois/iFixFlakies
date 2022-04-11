package edu.illinois.cs.dt.tools.utility;

import com.google.gson.Gson;
import com.reedoei.eunomia.io.files.FileUtil;
import edu.illinois.cs.dt.tools.minimizer.MinimizeTestsResult;
import edu.illinois.cs.dt.tools.minimizer.PolluterData;
import edu.illinois.cs.dt.tools.runner.InstrumentingSmartRunner;
import edu.illinois.cs.dt.tools.runner.data.DependentTest;
import edu.illinois.cs.dt.tools.runner.data.DependentTestList;
import edu.illinois.cs.testrunner.configuration.Configuration;
import edu.illinois.cs.testrunner.data.results.TestRunResult;
import edu.illinois.cs.testrunner.mavenplugin.TestPlugin;
import edu.illinois.cs.testrunner.mavenplugin.TestPluginPlugin;
import edu.illinois.cs.testrunner.runner.Runner;
import edu.illinois.cs.testrunner.runner.RunnerFactory;
import org.apache.commons.io.FileUtils;
import org.apache.maven.project.MavenProject;
import org.w3c.dom.Node;
import scala.util.Try;

import java.io.File;
import java.io.FileReader;
import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.FileNotFoundException;
import java.io.UnsupportedEncodingException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Set;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.HashSet;

import org.xmlunit.builder.DiffBuilder;
import org.xmlunit.diff.Comparison.Detail;
import org.xmlunit.diff.DefaultNodeMatcher;
import org.xmlunit.diff.Diff;
import org.xmlunit.diff.Difference;
import org.xmlunit.diff.ElementSelectors;

public class iFixPlusPlugin extends TestPlugin {
    private Path replayPath;
    private Path replayPath2;
    private String dtname;
    private String subxmlFold;
    private String rootFile;
    private String module;
    private String output;
    private String diffFieldsFile;
    private String subdiffsFold;
    private String reflectionFile;
    private String eagerloadfile;

    private Set<String> diffFields_filtered = new HashSet<String> ();

    @Override
    public void execute(final MavenProject mavenProject) {
        Configuration.config().properties().setProperty("statecapture.phase", "initial");

        long startTime = System.currentTimeMillis();

        // Currently there could two runners, one for JUnit 4 and one for JUnit 5
        // If the maven project has both JUnit 4 and JUnit 5 tests, two runners will
        // be returned
        List<Runner> runners = RunnerFactory.allFrom(mavenProject);

        if (runners.size() != 1) {
            // HACK: Always force JUnit 4
            boolean forceJUnit4 = true;
            if (forceJUnit4) {
                Runner nrunner = null;
                for (Runner runner : runners) {
                    if (runner.framework().toString() == "JUnit") {
                        nrunner = runner;
                        break;
                    }
                }
                if (nrunner != null) {
                    runners = new ArrayList<>(Arrays.asList(nrunner));
                } else {
                    String errorMsg;
                    if (runners.size() == 0) {
                        errorMsg =
                            "Module is not using a supported test framework (probably not JUnit), " +
                            "or there is no test.";
                    } else {
                        errorMsg = "dt.detector.forceJUnit4 is true but no JUnit 4 runners found. Perhaps the project only contains JUnit 5 tests.";
                    }
                    TestPluginPlugin.mojo().getLog().info(errorMsg);
                    return;
                }
            } else {
                String errorMsg;
                if (runners.size() == 0) {
                    errorMsg =
                        "Module is not using a supported test framework (probably not JUnit), " +
                        "or there is no test.";
                } else {
                    // more than one runner, currently is not supported.
                    errorMsg =
                        "This project contains both JUnit 4 and JUnit 5 tests, which currently"
                        + " is not supported by iDFlakies";
                }
                TestPluginPlugin.mojo().getLog().info(errorMsg);
                return;
            }
        }
        final Runner runner = InstrumentingSmartRunner.fromRunner(runners.get(0));

        replayPath = Paths.get(Configuration.config().getProperty("replay.path"));
        replayPath2 = Paths.get(Configuration.config().getProperty("replay.path2"));
        dtname= Configuration.config().getProperty("statecapture.testname");
        output= Configuration.config().getProperty("replay.output");
        subxmlFold = Configuration.config().getProperty("statecapture.subxmlFold");
        rootFile = Configuration.config().getProperty("statecapture.rootFile");
        module = Configuration.config().getProperty("replay.module");
        diffFieldsFile = Configuration.config().getProperty("replay.diffFieldsFile");
        subdiffsFold = Configuration.config().getProperty("replay.subdiffsFold");
        reflectionFile = Configuration.config().getProperty("statecapture.reflectionFile");
        eagerloadfile = Configuration.config().getProperty("statecapture.eagerloadfile");

        if (runner != null && module.equals(PathManager.modulePath().toString())) {
            System.out.println("replyPath: " + replayPath);
            System.out.println("module: " + module);

            try {
                Files.write(Paths.get(output),
                        (lastPolluter() + ",").getBytes(),
                        StandardOpenOption.APPEND);
                // phase 0 check json file
                System.out.println("~~~~~~ Begin to check if this test is a true order-dependent test.");
                Configuration.config().properties().setProperty("statecapture.phase", "check");
                if (testFailOrder()==null) {
                    timing(startTime);
                    Files.write(Paths.get(output),
                            "0,0,0,0,0,0,0,wrongjsonfail,".getBytes(),
                            StandardOpenOption.APPEND);
                    System.out.println("## Original json file is wrong!");
                    return;
                }

                if (testPassOrder_full()==null) {
                    timing(startTime);
                    Files.write(Paths.get(output),
                            "0,0,0,0,0,0,0,wrongjsonpass,".getBytes(),
                            StandardOpenOption.APPEND);
                    System.out.println("## Original json file is wrong!");
                    return;
                }

                for(int i=0; i<10; i++) {
                    Try<TestRunResult> phase0ResultFail = null;
                    try {
                        phase0ResultFail = runner.runList(testFailOrder());
                    }
                    catch (Exception ex) {
                        System.out.println("## Encountering error when checking the failing order: " + ex);
                    }

                    System.out.println("Failing order results: " +
                            phase0ResultFail.get().results().get(dtname).result().toString());

                    if (phase0ResultFail.get().results().get(dtname).result().toString().equals("PASS")) {
                        System.out.println("## Failing order json file is wrong!");
                        timing(startTime);
                        Files.write(Paths.get(output),
                                "0,0,0,0,0,0,0,wrongjsonfail2,".getBytes(),
                                StandardOpenOption.APPEND);
                        return;
                    }
                }

                for(int i=0; i<10; i++) {
                    Try<TestRunResult> phase0ResultPass = null;
                    try {
                        phase0ResultPass = runner.runList(testPassOrder_full());
                    }
                    catch (Exception ex) {
                        System.out.println("## Encountering error when checking the passing order: " + ex);
                    }
                    System.out.println("Passing order results: " +
                            phase0ResultPass.get().results().get(dtname).result().toString());

                    if (!phase0ResultPass.get().results().get(dtname).result().toString().equals("PASS")) {
                        System.out.println("## Passing order json file is wrong!");
                        timing(startTime);
                        Files.write(Paths.get(output),
                                "0,0,0,0,0,0,0,wrongjsonpass2,".getBytes(),
                                StandardOpenOption.APPEND);
                        return;
                    }
                }

                timing(startTime);
                startTime = System.currentTimeMillis();
                System.out.println("~~~~~~ Finish checking if this test is a true order-dependent test.");

                //phase 1: run doublevictim order
                Try<TestRunResult> phase1Result = null;
                try {
                    System.out.println("~~~~~~ Begin running double victim order to check if it is a double victim!");

                    Configuration.config().properties().
                            setProperty("testplugin.runner.idempotent.num.runs", "2");
                    phase1Result = runner.runList(victim());
                    System.out.println(phase1Result.get().results().get(dtname+":1").result());
                    Configuration.config().properties().
                            setProperty("testplugin.runner.idempotent.num.runs", "-1");
                    System.out.println("## Running double victim order results: " + phase1Result.get().results());
                }
                catch (Exception e) {
                    System.out.println("## Encountering error when running in double victim order: " + e);
                }
                timing(startTime);
                startTime = System.currentTimeMillis();
                System.out.println("~~~~~~ Finished running double victim order!");

                if (phase1Result.get().results().get(dtname+":1").result().toString().equals("PASS")) {
                    System.out.println("~~~~~~ Begin capturing the state in passing order!");
                    // phase 2: run doublevictim order state capture
                    Configuration.config().properties().
                            setProperty("statecapture.phase", "capture_after");
                    Configuration.config().properties().
                            setProperty("statecapture.rootFile", rootFile.substring(0, rootFile.lastIndexOf("/")) + "/passing_order.txt");
                    String allFieldsFold = Configuration.config().getProperty("statecapture.allFieldsFile").substring(0,
                            Configuration.config().getProperty("statecapture.allFieldsFile").lastIndexOf("/"));
                    Configuration.config().properties().
                            setProperty("statecapture.allFieldsFile", allFieldsFold + "/passing_order.txt");
                    Configuration.config().properties().
                            setProperty("statecapture.subxmlFold", subxmlFold + "/passing_order_xml");
                    System.out.println(Configuration.config().getProperty("statecapture.rootFile") + ";"
                            + Configuration.config().getProperty("statecapture.allFieldsFile"));
                    try {
                        runner.runList(victim());
                    }
                    catch (Exception e) {
                        System.out.println("## Encountering error in capturing the state in passing order: " + e);
                    }
                    System.out.println("~~~~~~ Finished capturing the state in passing order!");
                    timing(startTime);
                    startTime = System.currentTimeMillis();
                    Files.write(Paths.get(output),
                            "0,".getBytes(),
                            StandardOpenOption.APPEND);
                }
                else {
                    System.out.println("~~~~~~ Begin loading the classes when the test is a double victim!");
                    //phase 2tmp: run doublevictim order state capture
                    Configuration.config().properties().
                            setProperty("statecapture.phase", "capture_after");
                    Configuration.config().properties().
                            setProperty("statecapture.state", "eagerload");
                    Configuration.config().properties().
                            setProperty("statecapture.eagerloadfile", eagerloadfile);
                    try {
                        runner.runList(victim());
                    }
                    catch (Exception e) {
                        System.out.println("## Encounter error in loading the classes when the test is a double victim: " + e);
                    }
                    System.out.println("~~~~~~ Finish loading the classes when the test is a double victim!");
                    System.out.println("~~~~~~ Begin capturing the state in passing order(double victim)!!!");
                    Configuration.config().properties().
                            setProperty("statecapture.state", "normal");
                    // phase 3: run passorder (indicated in the json) state capture;
                    Configuration.config().properties().
                            setProperty("statecapture.phase", "capture_before");
                    Configuration.config().properties().
                            setProperty("statecapture.rootFile", rootFile.substring(0, rootFile.lastIndexOf("/")) + "/passing_order.txt");
                    String allFieldsFold = Configuration.config().getProperty("statecapture.allFieldsFile").substring(0,
                            Configuration.config().getProperty("statecapture.allFieldsFile").lastIndexOf("/"));
                    Configuration.config().properties().
                            setProperty("statecapture.allFieldsFile", allFieldsFold + "/passing_order.txt");
                    Configuration.config().properties().
                            setProperty("statecapture.subxmlFold", subxmlFold + "/passing_order_xml");
                    System.out.println(Configuration.config().getProperty("statecapture.rootFile") + ";"
                            + Configuration.config().getProperty("statecapture.allFieldsFile"));
                    try {
                        runner.runList(testPassOrder_full());
                    }
                    catch (Exception e) {
                        System.out.println("## Encounter error in capturing the state in passing order(double victim)!");
                    }

                    Files.write(Paths.get(output),
                            "0,".getBytes(),
                            StandardOpenOption.APPEND);
                    timing(startTime);
                    startTime = System.currentTimeMillis();
                    System.out.println("passOrder: " + testPassOrder_full());
                    System.out.println("~~~~~~ Finish phase capturing the state in passing order(double victim)!!");
                }

                // phase 4: failing order before state capture;
                System.out.println("~~~~~~ Begin capturing the state in failing order!!");
                Configuration.config().properties().
                        setProperty("statecapture.phase", "capture_before");
                Configuration.config().properties().
                        setProperty("statecapture.rootFile", rootFile.substring(0, rootFile.lastIndexOf("/")) + "/failing_order.txt");
                String allFieldsFold = Configuration.config().getProperty("statecapture.allFieldsFile").substring(0,
                        Configuration.config().getProperty("statecapture.allFieldsFile").lastIndexOf("/"));
                Configuration.config().properties().
                        setProperty("statecapture.allFieldsFile", allFieldsFold + "/failing_order.txt");
                Configuration.config().properties().
                        setProperty("statecapture.subxmlFold", subxmlFold + "/failing_order_xml");
                System.out.println(Configuration.config().getProperty("statecapture.rootFile") + ";"
                        + Configuration.config().getProperty("statecapture.allFieldsFile"));
                try {
                    runner.runList(testFailOrder());
                }
                catch (Exception e) {
                    System.out.println("## Encounter error in capturing the state in failing order!! " + e);

                }

                timing(startTime);
                startTime = System.currentTimeMillis();
                System.out.println("~~~~~~ Finish phase capturing the state in failing order!!");

                File failingSubXmlFolder = new File(subxmlFold + "/failing_order_xml");
                if (!failingSubXmlFolder.exists()) {
                    // phase 4: failing order after state capture;
                    Configuration.config().properties().
                            setProperty("statecapture.phase", "capture_after");
                    Configuration.config().properties().
                            setProperty("statecapture.rootFile", rootFile.substring(0, rootFile.lastIndexOf("/")) + "/failing_order.txt");
                    allFieldsFold = Configuration.config().getProperty("statecapture.allFieldsFile").substring(0,
                            Configuration.config().getProperty("statecapture.allFieldsFile").lastIndexOf("/"));
                    Configuration.config().properties().
                            setProperty("statecapture.allFieldsFile", allFieldsFold + "/failing_order.txt");
                    Configuration.config().properties().
                            setProperty("statecapture.subxmlFold", subxmlFold + "/failing_order_xml");
                    System.out.println(Configuration.config().getProperty("statecapture.rootFile") + ";"
                            + Configuration.config().getProperty("statecapture.allFieldsFile"));
                    System.out.println("~~~~~~ Begin capturing the state in failing order!!");
                    try {
                        runner.runList(testFailOrder());
                    }
                    catch (Exception e) {
                        System.out.println("## Encounter error in phase capturing the state in failing order!! " + e);

                    }
                    timing(startTime);
                    startTime = System.currentTimeMillis();
                    System.out.println("~~~~~~ Finish phase capturing the state in failing order!!");
                } else {
                    Files.write(Paths.get(output),
                            "0,".getBytes(),
                            StandardOpenOption.APPEND);
                }

                // phase 5: do the diff
                System.out.println("~~~~~~ Begin diffing between the passing order and failing order!!!");

                File passingSubXmlFolder = new File(subxmlFold + "/passing_order_xml");
                if (passingSubXmlFolder.exists() && failingSubXmlFolder.exists()) {
                    try {
                        diffing();
                    }
                    catch (Exception e) {
                        System.out.println("## Encounter error in doing diffing: " + e);
                    }
                }
                else {
                    System.out.println("## Cannot do diff, xml files are not complete!");
                }

                timing(startTime);
                startTime = System.currentTimeMillis();
                System.out.println("~~~~~~ Finish diffing between the passing order and failing order!!!");

                // output of phase 5
                String diffFile = diffFieldsFile;

                //create the reflection file
                File reflectFile = new File(reflectionFile);
                reflectFile.createNewFile();

                System.out.println("~~~~~~ Begin loading!");

                // reflect at the after state
                Configuration.config().properties().
                        setProperty("statecapture.subxmlFold", subxmlFold + "/passing_order_xml");
                boolean reflectAfterOneSuccess = reflectEachField(diffFile, reflectFile, runner, lastPolluter());
                if (reflectAfterOneSuccess) {
                    String successfulField = "";
                    try (BufferedReader br = new BufferedReader(new FileReader(reflectFile))) {
                        String line;
                        while ((line = br.readLine()) != null) {
                            if (line.contains(" made test success#######")) {
                                successfulField = line.substring(8, line.lastIndexOf(" made test success#######"));
                                break;
                            }
                        }
                    }
                    catch (Exception e) {
                        return;
                    }
                    timing(startTime);
                    if (!successfulField.equals("")) {
                        Files.write(Paths.get(output), (successfulField + ",").getBytes(),
                                StandardOpenOption.APPEND);
                    } else {
                        Files.write(Paths.get(output), "FAIL,".getBytes(),
                                StandardOpenOption.APPEND);
                    }
                }
                else {
                    timing(startTime);
                    Files.write(Paths.get(output), "FAIL,".getBytes(),
                            StandardOpenOption.APPEND);
                }

                System.out.println("~~~~~~ Finish loading!");
            } catch (Exception e) {
                TestPluginPlugin.mojo().getLog().error(e);
            }
        } else {
            TestPluginPlugin.mojo().getLog().info("Module is not using a supported test framework (probably not JUnit).");
        }
    }

    private void timing(long startTime) {
        long endTime = System.currentTimeMillis();
        double duration = (endTime - startTime)/1000.0;

        String time = duration + ",";
        try {
            Files.write(Paths.get(output), time.getBytes(),
                    StandardOpenOption.APPEND);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private boolean reflectEachField(String diffFile, File reflectionFile, Runner runner, String polluter) throws IOException {
        boolean reflectSuccess = false;
        String header = "*************************do reflection************************\n";
        Files.write(Paths.get(reflectionFile.getAbsolutePath()), header.getBytes(),
                StandardOpenOption.APPEND);
        try {
            try (BufferedReader br = new BufferedReader(new FileReader(diffFile))) {
                String diffField;
                while ((diffField = br.readLine()) != null) {
                    Configuration.config().properties().
                            setProperty("statecapture.phase", "load");
                    Configuration.config().properties().
                            setProperty("statecapture.fieldName", diffField);
                    Configuration.config().properties().
                            setProperty("statecapture.testname", polluter);
                    try {
                        System.out.println("## doing reflection");
                        Try<TestRunResult> result = runner.runList(testFailOrder());
                        if (result.get().results().get(dtname).result().toString().equals("PASS")) {
                            System.out.println("## reflection on diffField: " + diffField + " is success!!");
                            String output = "########" + diffField + " made test success#######\n";
                            Files.write(Paths.get(reflectionFile.getAbsolutePath()), output.getBytes(),
                                    StandardOpenOption.APPEND);
                            reflectSuccess = true;
                        } else {
                            String output = "########" + diffField + " made test fail######\n";
                            Files.write(Paths.get(reflectionFile.getAbsolutePath()), output.getBytes(),
                                    StandardOpenOption.APPEND);
                        }
                    } catch (Exception e) {
                        System.out.println("## Encounter error in reflection for field: "
                                + diffField + " " + e);
                    }
                }
            }
        } catch (FileNotFoundException e) {
            e.printStackTrace();
            System.out.println("FileNotFoundException");
        }

        return reflectSuccess;

    }

    private List<String> victim() {
        List<String> partialOrder = new ArrayList<>();
        partialOrder.add(dtname);
        return partialOrder;
    }

    private List<String> testPassOrder_full() throws IOException {
        try {
            System.out.println("------ testPassOrder_full: " + PathManager.modulePath());
            List<DependentTest> dtl = new Gson().fromJson(FileUtil.readFile(replayPath), DependentTestList.class).dts();
            List<String> partialOrder = new ArrayList<String>();
            for(int i = 0; i< dtl.size(); i++ ) {
                DependentTest dt = dtl.get(i);
                if (dt.name().equals(dtname)) {
                    for(String s: dt.intended().order()) {
                        partialOrder.add(s);
                        if (s.equals(dt.name()))
                            break;
                    }
                    if (!partialOrder.contains(dtname)) {
                        partialOrder.add(dtname);
                    }
                    return partialOrder;
                }
            }
            return null;

        } catch (Exception e) {
            System.out.println("## Encounter exception in reading json!");
            return null;
        }
    }

    private List<String> testFailOrder() throws IOException {
        if (replayPath2.toString().equals("")) {
            return testFailOrder_full();
        }
        else {
            return testFailOrder_minimized();
        }
    }

    private List<String> testFailOrder_full() throws IOException {
        try {
            System.out.println("------ testFailOrder_full: " + PathManager.modulePath());
            List<DependentTest> dtl = new Gson().fromJson(FileUtil.readFile(replayPath), DependentTestList.class).dts();
            List<String> partialOrder = new ArrayList<String>();
            for(int i = 0; i< dtl.size(); i++ ) {
                DependentTest dt = dtl.get(i);
                if (dt.name().equals(dtname)) {
                    for(String s: dt.revealed().order()) {
                        partialOrder.add(s);
                        if (s.equals(dt.name()))
                            break;
                    }
                    if (!partialOrder.contains(dtname)) {
                        partialOrder.add(dtname);
                    }
                    return partialOrder;
                }
            }
            return null;

        } catch (Exception e) {
            System.out.println("## Encounter exception in reading json!");
            return null;
        }
    }

    private List<String> testFailOrder_minimized() throws IOException {
        List<String> failingTests = new ArrayList<String>();
        try {
            List<PolluterData> polluters = new Gson().fromJson(FileUtil.readFile(replayPath2), MinimizeTestsResult.class).polluters();
            for(PolluterData pd: polluters) {
                if (pd.deps().size() >=1) {
                    failingTests.addAll(pd.deps());
                    failingTests.add(dtname);
                    return failingTests;
                }
            }
            return null;
        } catch (Exception e) {
            System.out.println("## Encounter exception in reading json for failing order!");
            return null;
        }
    }

    private String lastPolluter() {
        if (replayPath2.toString().equals("")) {
            return lastPolluter_full();
        }
        else {
            return lastPolluter_minimized();
        }
    }

    private String lastPolluter_full() {
        try {
            List<String> failorder = testFailOrder_full();
            return failorder.get(failorder.size()-2);
        } catch (Exception e) {
            System.out.println("Encounter exception in lastPolluter_full!");
            return null;
        }
    }

    private String lastPolluter_minimized() {
        try {
            List<PolluterData> polluters = new Gson().fromJson(FileUtil.readFile(replayPath2), MinimizeTestsResult.class).polluters();
            for(PolluterData pd: polluters) {
                if (pd.deps().size() >=1) {
                    return pd.deps().get(pd.deps().size()-1);
                }
            }
            return null;
        } catch (Exception e) {
            System.out.println("Encounter exception in reading json for failing order!");
            return null;
        }
    }

    int countDirNums(String path) {
        File [] list = new File(path).listFiles();
        int num = 0;
        for (File file : list) {
            if (file.isDirectory()) {
                num ++;
            }
        }
        return num;
    }

    public void diffing() {
        try {
            diffSub();
        }
        catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void recordsubDiff(String beforeState,
                               String afterState, String fileName) {
        try {
            // create a string builder
            StringBuilder sb = new StringBuilder();
            Diff diff = DiffBuilder.compare(beforeState).withTest(afterState).
                    withNodeMatcher(new DefaultNodeMatcher(
                            ElementSelectors.byName
                    ))
                    .checkForSimilar()
                    .build();
            Iterable<Difference> differences = diff.getDifferences();
            for (Object object : differences) {
                Difference difference = (Difference)object;

                sb.append("***********************\n");
                sb.append(difference);
                sb.append("\n~~~~\n");
                makeSubDifferenceReport(difference, sb);
                sb.append("***********************\n");
            }
            writeToFile(fileName, sb.toString(), true);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private String readFile(String path) throws IOException {
        File file = new File(path);
        return FileUtils.readFileToString(file, "UTF-8");
    }

    private Set<String> readFileContentsAsSet(String path) {
        File file = new File(path);
        Set<String> keys = new HashSet<>();
        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = br.readLine()) != null) {
                keys.add(line);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return keys;
    }

    private void diffSub() throws FileNotFoundException, UnsupportedEncodingException {
        String subxmlFoldPrefix = subxmlFold;
        String subxml0 = subxmlFoldPrefix + "/passing_order_xml";
        String subxml1 = subxmlFoldPrefix + "/failing_order_xml";
        String afterRootPath = Configuration.config().getProperty("statecapture.rootFile"); // ***
        Set<String> afterRoots = readFileContentsAsSet(afterRootPath);

        for(String s: afterRoots) {

            String path0 = subxml0 + "/" + s + ".xml";
            String path1 = subxml1 + "/" + s + ".xml";
            String state0 = ""; String state1 = "";
            File file0 = new File(path0);
            if (!file0.exists()) {
                continue;
            }
            else {
                try {
                    state0 = readFile(path0);
                    state1 = readFile(path1);
                }
                catch (IOException e) {
                    e.printStackTrace();
                }

                if (!state0.equals(state1)) {
                    diffFields_filtered.add(s);
                    String subdiffFile = subdiffsFold + "/" + s + ".txt";
                    recordsubDiff(state0, state1, subdiffFile);
                }
            }
        }

        PrintWriter writer = new PrintWriter(diffFieldsFile, "UTF-8");

        for(String ff: diffFields_filtered) {

            writer.println(ff);
        }
        writer.close();
    }


    /**
     * Writes content into a file.
     *
     * @param  fn       name of the destination file
     * @param  content  string representing the data to be written
     * @param  append   boolean indicating whether to append to destination file or rewrite it
     */
    protected void writeToFile(String fn, String content, boolean append) {
        try {
            File f = new File(fn);
            f.createNewFile();

            FileWriter fw = new FileWriter(f.getAbsoluteFile(), append);
            BufferedWriter w = new BufferedWriter(fw);
            w.write(content);
            w.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void makeSubDifferenceReport(Difference difference, StringBuilder sb) {
        Detail controlNode = difference.getComparison().getControlDetails();
        Detail afterNode = difference.getComparison().getTestDetails();

        String diffXpath = controlNode.getXPath();
        if (diffXpath == null) {
            diffXpath = afterNode.getXPath();
            if (diffXpath == null) {
                sb.append("NULL xpath\n");
                return;
            }
        }
        sb.append(controlNode.getXPath());
        sb.append("\n");
        sb.append(afterNode.getXPath());
        sb.append("\n");

        sb.append(difference.getComparison().getType() + "\n");
        sb.append("--------\n");

        // Deal specifically with <entry> if in map
        if (controlNode != null) {
            Node target = controlNode.getTarget();
            if (target != null && target.getNodeName().equals("entry")) {   // Tag name "entry" matches some map structure we want to explore
                for (int i = 0; i < target.getChildNodes().getLength(); i++) {
                    sb.append(target.getChildNodes().item(i).getTextContent());
                    sb.append("\n");
                }
            }
        }
    }

}
