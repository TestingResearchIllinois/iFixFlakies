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
    private String rootFold;
    private String module;
    private String output;
    private String slug;
    private String tmpfile;
    private String diffFieldsFold;
    private String subdiffsFold;
    private String reflectionFold;

    private Set<String> diffFields_filtered = new HashSet<String> ();

    @Override
    public void execute(final MavenProject mavenProject) {
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
        dtname= Configuration.config().getProperty("replay.dtname");
        output= Configuration.config().getProperty("replay.output");
        slug= Configuration.config().getProperty("replay.slug");
        subxmlFold = Configuration.config().getProperty("replay.subxmlFold");
        rootFold = Configuration.config().getProperty("replay.rootFold");
        tmpfile = Configuration.config().getProperty("replay.tmpfile");
        module = Configuration.config().getProperty("replay.module");
        diffFieldsFold = Configuration.config().getProperty("replay.diffFieldsFold");
        subdiffsFold = Configuration.config().getProperty("replay.subdiffsFold");
        reflectionFold = Configuration.config().getProperty("replay.reflectionFold");

        int xmlFileNum = countDirNums(subxmlFold);
        System.out.println("xmlFileName: " + xmlFileNum);

        if (runner != null && module.equals(PathManager.modulePath().toString())) {
            System.out.println("replyPath: " + replayPath);
            System.out.println("module: " + module);

            try {
                //final Runner runner = runnerOption.get(); // safe because we checked above
                System.out.println("tests!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!" +
                        "!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!");

                Files.write(Paths.get(output),
                        (lastPolluter() + ",").getBytes(),
                        StandardOpenOption.APPEND);
                // phase 0 check json file
                System.out.println("phase 0 begin");
                write2tmp("0");
                if(testFailOrder()==null) {
                    timing(startTime);
                    Files.write(Paths.get(output),
                            "0,0,0,0,0,0,0,wrongjsonfail,".getBytes(),
                            StandardOpenOption.APPEND);
                    System.out.println("original json file wrong!!");
                    return;
                }

                if(testPassOrder_full()==null) {
                    timing(startTime);
                    Files.write(Paths.get(output),
                            "0,0,0,0,0,0,0,wrongjsonpass,".getBytes(),
                            StandardOpenOption.APPEND);
                    System.out.println("original json file wrong!!");
                    return;
                }

                for(int i=0; i<10; i++) {
                    Try<TestRunResult> phase0ResultFail = null;
                    try{
                        phase0ResultFail = runner.runList(testFailOrder());
                    }
                    catch(Exception ex) {
                        System.out.println("error in phase 0 failing order: " + ex);
                    }

                    System.out.println("Failing order results: " +
                            phase0ResultFail.get().results().get(dtname).result().toString());

                    if(phase0ResultFail.get().results().get(dtname).result().toString().equals("PASS")) {
                        System.out.println("json file wrong!!");
                        timing(startTime);
                        Files.write(Paths.get(output),
                                "0,0,0,0,0,0,0,wrongjsonfail2,".getBytes(),
                                StandardOpenOption.APPEND);
                        return;
                    }
                }

                for(int i=0; i<10; i++) {
                    Try<TestRunResult> phase0ResultPass = null;
                    try{
                        phase0ResultPass = runner.runList(testPassOrder_full());
                    }
                    catch(Exception ex) {
                        System.out.println("error in phase 0 pass order: " + ex);
                    }
                    System.out.println("passing order results: " +
                            phase0ResultPass.get().results().get(dtname).result().toString());

                    if(!phase0ResultPass.get().results().get(dtname).result().toString().equals("PASS")) {
                        System.out.println("json file wrong!!");
                        timing(startTime);
                        Files.write(Paths.get(output),
                                "0,0,0,0,0,0,0,wrongjsonpass2,".getBytes(),
                                StandardOpenOption.APPEND);
                        return;
                    }
                }

                timing(startTime);
                startTime = System.currentTimeMillis();
                System.out.println("phase 0 ends");

                 //phase 1: run doublevictim order
                Try<TestRunResult> phase1Result = null;
                try{
                    System.out.println("phase 1!!!");
                    write2tmp("1");

                    Configuration.config().properties().
                            setProperty("testplugin.runner.idempotent.num.runs", "2");
                    phase1Result = runner.runList(victim());
                    System.out.println(phase1Result.get().results().get(dtname+":1").result());
                    Configuration.config().properties().
                            setProperty("testplugin.runner.idempotent.num.runs", "-1");
                    System.out.println("phase 1 results: " + phase1Result.get().results());
                }
                catch(Exception e) {
                    System.out.println("error in phase 1: " + e);
                }
                timing(startTime);
                startTime = System.currentTimeMillis();
                System.out.println("finished phase 1!!");

                boolean doublevictim = false;
                if(phase1Result.get().results().get(dtname+":1").result().toString().equals("PASS")) {
                    System.out.println("enter phase 2!!!");
                    // phase 2: run doublevictim order state capture
                    write2tmp("2");
                    doublevictim = true;
                    try {
                        runner.runList(victim());
                    }
                    catch (Exception e){
                        System.out.println("error in phase 2: " + e);
                    }
                    System.out.println("finished phase 2!!");
                    timing(startTime);
                    startTime = System.currentTimeMillis();
                    Files.write(Paths.get(output),
                            "0,".getBytes(),
                            StandardOpenOption.APPEND);
                }
                else {
                    System.out.println("enter phase 2tmp!!!");
                    //phase 2tmp: run doublevictim order state capture
                    write2tmp("2tmp");
                    try {
                        runner.runList(victim());
                    }
                    catch (Exception e){
                        System.out.println("error in phase 2tmp: " + e);
                    }
                    System.out.println("enter phase 3!!!");
                    // phase 3: run passorder (indicated in the json) state capture;
                    write2tmp("3");
                    try{
                        runner.runList(testPassOrder_full());
                    }
                    catch(Exception e) {
                        System.out.println("error in running passing order!");
                    }

                    Files.write(Paths.get(output),
                            "0,".getBytes(),
                            StandardOpenOption.APPEND);
                    timing(startTime);
                    startTime = System.currentTimeMillis();
                    System.out.println("finished passing order state capturing!!");
                    System.out.println("passOrder: " + testPassOrder_full());
                    System.out.println("finished phase 3!!");
                }

                xmlFileNum = countDirNums(subxmlFold);
                System.out.println("xmlFileName: " + xmlFileNum);

                // phase 4: failing order before state capture;
                System.out.println("enter phase 4 before!!");
                write2tmp("4");
                try {
                    runner.runList(testFailOrder());
                }
                catch(Exception e) {
                    System.out.println("error in phase 4 before!! " + e);

                }

                timing(startTime);
                startTime = System.currentTimeMillis();
                System.out.println("finish phase 4 before!!");

                xmlFileNum = countDirNums(subxmlFold);
                if(xmlFileNum != 2) {
                    // phase 4: failing order after state capture;
                    write2tmp("4 " + lastPolluter());
                    System.out.println("enter phase 4 after!!");
                    try {
                        runner.runList(testFailOrder());
                    }
                    catch(Exception e) {
                        System.out.println("error in phase 4 after!! " + e);

                    }
                    timing(startTime);
                    startTime = System.currentTimeMillis();
                    System.out.println("finish phase 4 after!!");
                } else {
                    Files.write(Paths.get(output),
                            "0,".getBytes(),
                            StandardOpenOption.APPEND);
                }

                // phase 5: do the diff
                System.out.println("enter phase 5!!!");

                xmlFileNum = countDirNums(subxmlFold);
                System.out.println("xmlFileNum: " + xmlFileNum);
                if(xmlFileNum == 2) {
                    System.out.println("beginning diff!!!!!!!!!!");
                    if(doublevictim) {
                        System.out.println("doublevictim!!");
                        // write2tmp("5doublevic");
                        try {
                            System.out.println("doing diff%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%");
                            // runner.runList(victim());
                            diffing();
                        }
                        catch (Exception e){
                            System.out.println("error in phase 5(doing diffing): " + e);
                        }
                    }
                    else{
                        System.out.println("passorder!!");
                        // write2tmp("5");
                        try {
                            System.out.println("doing diff%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%");
                            // runner.runList(testPassOrder_full());
                            diffing();
                        } catch (Exception e) {
                            System.out.println("error in failing failing order!" + e);
                        }
                    }
                }
                else {
                    System.out.println("cannot do diff, the number of xml files is not 2!!");
                }

                timing(startTime);
                startTime = System.currentTimeMillis();

                // output of phase 5
                String diffFile = diffFieldsFold + "/0.txt";

                //create the reflection file
                File reflectionFile = new File(reflectionFold+"/0.txt");
                reflectionFile.createNewFile();

                System.out.println("reflection begin!!\n");

                // reflect at the after state
                String prefix = "diffFieldAfter " + lastPolluter() + " ";
                boolean reflectAfterOneSuccess = reflectEachField(diffFile, reflectionFile, runner, prefix);
                if(reflectAfterOneSuccess) {
                    String successfulField = "";
                    try (BufferedReader br = new BufferedReader(new FileReader(reflectionFile))) {
                        String line;
                        while ((line = br.readLine()) != null) {
                            if(line.contains(" made test success#######")) {
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

    private boolean reflectEachField(String diffFile, File reflectionFile, Runner runner, String prefix) throws IOException {
        boolean reflectSuccess = false;
        String header = "*************************reflection on " + prefix.split(" ")[0] + "************************\n";
        Files.write(Paths.get(reflectionFile.getAbsolutePath()), header.getBytes(),
                StandardOpenOption.APPEND);
        try {
            try (BufferedReader br = new BufferedReader(new FileReader(diffFile))) {
                String diffField;
                while ((diffField = br.readLine()) != null) {
                    System.out.println(prefix + diffField);
                    String s = prefix + diffField;
                    write2tmp(s);
                    try {
                        System.out.println("doing reflection%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%");
                        Try<TestRunResult> result = runner.runList(testFailOrder());
                        if (result.get().results().get(dtname).result().toString().equals("PASS")) {
                            System.out.println("reflection on diffField: " + diffField + " is success!!");
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
                        System.out.println("error in reflection for field: "
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

        /* arr[]  ---> Input Array
        data[] ---> Temporary array to store current combination
        start & end ---> Staring and Ending indexes in arr[]
        index  ---> Current index in data[]
        r ---> Size of a combination to be printed */
        static void combinationUtil(List<String> arr, List<String> data, int start,
                                    int end, int index, int r, List<List<String>> results)
        {
            // Current combination is ready to be printed, print it
            if (index == r)
            {
                for (int j=0; j<r; j++)
                    System.out.print(data.get(j) +" ");
                System.out.println("");

                List<String> subresults = new ArrayList<>();
                for (int j=0; j<r; j++) {
                    subresults.add(data.get(j));
                }

                results.add(subresults);
                return;
            }

            // replace index with all possible elements. The condition
            // "end-i+1 >= r-index" makes sure that including one element
            // at index will make a combination with remaining elements
            // at remaining positions
            for (int i=start; i<=end && end-i+1 >= r-index; i++)
            {
                data.set(index, arr.get(i));
                combinationUtil(arr, data, i+1, end, index+1, r, results);
            }
        }

        // The main function that prints all combinations of size r
        // in arr[] of size n. This function mainly uses combinationUtil()
        static List<List<String>>  printCombination(List<String> arr, int r)
        {
            // A temporary array to store all combination one by one

            int n = arr.size();
            List<String> data = new ArrayList<String>();
            for(int i=0; i<n; i++) {
                data.add("");
            }
            // Print all combination using temporary array 'data[]'
            List<List<String>> results = new ArrayList<>();
            combinationUtil(arr, data, 0, n-1, 0, r, results);
            return results;
        }


    private void write2tmp(String s) throws FileNotFoundException, UnsupportedEncodingException {
        PrintWriter writer = new PrintWriter(tmpfile, "UTF-8");
        writer.print(s);
        writer.close();
    }

    private List<String> victim() {
        List<String> partialOrder = new ArrayList<>();
        partialOrder.add(dtname);
        return partialOrder;
    }

    private List<String> testPassOrder_full() throws IOException {
        try {
            System.out.println("$$$$$$$$$$$testPassOrder_full: " + PathManager.modulePath());
            List<DependentTest> dtl = new Gson().fromJson(FileUtil.readFile(replayPath), DependentTestList.class).dts();
            System.out.println("dtl!!!!!!!!!!!");
            //must have one dt in dtl
            List<String> partialOrder = new ArrayList<String>();
            for(int i = 0; i< dtl.size(); i++ ) {
                DependentTest dt = dtl.get(i);
                if(dt.name().equals(dtname)) {
                    for(String s: dt.intended().order()) {
                        partialOrder.add(s);
                        if(s.equals(dt.name()))
                            break;
                    }
                    if(!partialOrder.contains(dtname)) {
                        partialOrder.add(dtname);
                    }
                    System.out.println("testFailOrder_full1 : " + dtname);
                    return partialOrder;
                }
            }
            System.out.println("testFailOrder_full2: " + dtname);
            return null;

        } catch (Exception e) {
            System.out.println("exception in reading json!!!!!");
            return null;
        }
    }

    private List<String> testFailOrder() throws IOException {
        if(replayPath2.toString().equals("")) {
            return testFailOrder_full();
        }
        else {
            return testFailOrder_minimized();
        }
    }

    private List<String> testFailOrder_full() throws IOException {
        try {
            System.out.println("$$$$$$$$$$$testFailOrder_full: " + PathManager.modulePath());
            List<DependentTest> dtl = new Gson().fromJson(FileUtil.readFile(replayPath), DependentTestList.class).dts();
            System.out.println("dtl!!!!!!!!!!!");
            //must have one dt in dtl
            List<String> partialOrder = new ArrayList<String>();
            for(int i = 0; i< dtl.size(); i++ ) {
                DependentTest dt = dtl.get(i);
                if(dt.name().equals(dtname)) {
                    for(String s: dt.revealed().order()) {
                        partialOrder.add(s);
                        if(s.equals(dt.name()))
                            break;
                    }
                    if(!partialOrder.contains(dtname)) {
                        partialOrder.add(dtname);
                    }
                    System.out.println("testFailOrder_full1 : " + dtname);
                    return partialOrder;
                }
            }
            System.out.println("testFailOrder_full2: " + dtname);
            return null;

        } catch (Exception e) {
            System.out.println("exception in reading json!!!!!");
            return null;
        }
    }

    private List<String> testFailOrder_minimized() throws IOException {
        List<String> failingTests = new ArrayList<String>();
        try {
            System.out.println("$$$$$$$$$$$replayPath2: " + replayPath2);
            List<PolluterData> polluters = new Gson().fromJson(FileUtil.readFile(replayPath2), MinimizeTestsResult.class).polluters();
            System.out.println("polluters!!!!!!!!!!!");
            for(PolluterData pd: polluters) {
                if(pd.deps().size() >=1) {
                    failingTests.addAll(pd.deps());
                    failingTests.add(dtname);
                    return failingTests;
                }
            }
            return null;
        } catch (Exception e) {
            System.out.println("exception in reading json for failing order!!!!!");
            return null;
        }
    }

    private String lastPolluter() {
        if(replayPath2.toString().equals("")) {
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
            System.out.println("exception in lastPolluter_full!!!!!");
            return null;
        }
    }

    private String lastPolluter_minimized() {
        try {
            System.out.println("$$$$$$$$$$$replayPath2: " + replayPath2);
            List<PolluterData> polluters = new Gson().fromJson(FileUtil.readFile(replayPath2), MinimizeTestsResult.class).polluters();
            System.out.println("polluters!!!!!!!!!!!");
            for(PolluterData pd: polluters) {
                if(pd.deps().size() >=1) {
                    return pd.deps().get(pd.deps().size()-1);
                }
            }
            return null;
        } catch (Exception e) {
            System.out.println("exception in reading json for failing order!!!!!");
            return null;
        }
    }

    int countDirNums(String path) {
        File [] list = new File(path).listFiles();
        int num = 0;
        for (File file : list){
            if (file.isDirectory()){
                num ++;
            }
        }
        return num;
    }

    public void diffing() {
        try {
            diffSub();
        }
        catch (Exception e){
            e.printStackTrace();
        }
    }

    private void recordsubDiff(String beforeState,
                               String afterState, String fileName) {
        try {
            // create a string builder
            StringBuilder sb = new StringBuilder();
            System.out.println("REACH 0");
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

    public String readFile(String path) throws IOException {
        File file = new File(path);
        return FileUtils.readFileToString(file, "UTF-8");
    }

    Set<String> File2SetString(String path) {
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
        String subxml0 = subxmlFold + "/0xml";
        String subxml1 = subxmlFold + "/1xml";
        String afterRootPath= rootFold + "/1.txt";
        System.out.println(subxml0 + "; " + subxml1 + "; " + afterRootPath + "; ");
        Set<String> afterRoots = File2SetString(afterRootPath);

        System.out.println(diffFieldsFold + "; ");
        for(String s: afterRoots) {

            String path0 = subxml0 + "/" + s + ".xml";
            String path1 = subxml1 + "/" + s + ".xml";
            String state0 = ""; String state1 = "";
            File file0 = new File(path0);
            if(!file0.exists()){
                continue;
            }
            else {
                try{
                    state0 = readFile(path0);
                    state1 = readFile(path1);
                }
                catch(IOException e) {
                    e.printStackTrace();
                }

                if (!state0.equals(state1)) {
                    diffFields_filtered.add(s);
                    String subdiffFile = subdiffsFold + "/" + s + ".txt";
                    System.out.println(subdiffFile + "; ");
                    recordsubDiff(state0, state1, subdiffFile);
                }
            }
        }
        System.out.println(diffFields_filtered + "; ");
        int num = new File(diffFieldsFold).listFiles().length;
        System.out.println(diffFieldsFold + "; ");
        PrintWriter writer = new PrintWriter(diffFieldsFold + "/" + num+ ".txt", "UTF-8");

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
