package edu.illinois.cs.dt.tools.minimizer;

import com.reedoei.eunomia.collections.StreamUtil;
import edu.illinois.cs.dt.tools.detection.DetectorPathManager;
import edu.illinois.cs.dt.tools.detection.DetectorPlugin;
import edu.illinois.cs.dt.tools.detection.detectors.Detector;
import edu.illinois.cs.dt.tools.detection.detectors.RandomDetector;
import edu.illinois.cs.dt.tools.runner.InstrumentingSmartRunner;
import edu.illinois.cs.dt.tools.runner.data.DependentTest;
import edu.illinois.cs.dt.tools.runner.data.DependentTestList;
import edu.illinois.cs.dt.tools.runner.data.TestRun;
import edu.illinois.cs.dt.tools.utility.ErrorLogger;
import edu.illinois.cs.testrunner.configuration.Configuration;
import edu.illinois.cs.testrunner.data.results.Result;
import edu.illinois.cs.testrunner.mavenplugin.TestPlugin;
import edu.illinois.cs.testrunner.mavenplugin.TestPluginPlugin;
import edu.illinois.cs.testrunner.runner.Runner;
import edu.illinois.cs.testrunner.runner.RunnerFactory;
import org.apache.maven.project.MavenProject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

public class MinimizerPlugin extends TestPlugin {
    private TestMinimizerBuilder builder;
    private InstrumentingSmartRunner runner;
    private final String TEST_TO_MINIMIZE = Configuration.config().getProperty("dt.minimizer.dependent.test", null);
    private final boolean GENERATE_FLAKIES = Configuration.config().getProperty("dt.minimizer.generate.list.flakies", false);
    private final boolean USE_ORIGINAL_ORDER = Configuration.config().getProperty("dt.minimizer.use.original.order", false);
    private static final boolean VERIFY_DTS = Configuration.config().getProperty("dt.verify", true);
    private final String FLAKY_LIST = Configuration.config().getProperty("dt.minimizer.flaky.list", null);
    private final String ORIGINAL_ORDER = Configuration.config().getProperty("dt.minimizer.original.order", null);

    /**
     * This will clear all the cached test runs!
     * Be careful when calling!
     */
    public MinimizerPlugin() {
    }

    public MinimizerPlugin(final InstrumentingSmartRunner runner) {
        super();
        this.runner = runner;
        this.builder = new TestMinimizerBuilder(runner);
    }

    private Stream<TestMinimizer> fromDtList(Path path, MavenProject project) {
        if (FLAKY_LIST != null) {
            path = Paths.get(FLAKY_LIST);
            TestPluginPlugin.info("dt.minimizer.flaky.list argument specified: " + FLAKY_LIST);
        }
        TestPluginPlugin.info("Creating minimizers for file: " + path);

        try {
            Path originalOrderPath = DetectorPathManager.originalOrderPath();
            List<String> originalOrder;
            if (Files.exists(originalOrderPath)) {
                originalOrder = Files.readAllLines(originalOrderPath);
            } else {
                originalOrder = new ArrayList<>();
            }

            if (ORIGINAL_ORDER != null) {
                TestPluginPlugin.info("Using specified original order. dt.minimizer.original.order argument specified: " + ORIGINAL_ORDER);
                List<String> originalOrderSpecified = Files.readAllLines(Paths.get(ORIGINAL_ORDER));

                if (originalOrderSpecified.equals(originalOrder)) {
                    TestPluginPlugin.info("Custom original order specified and found to be matching the existing original order at: "
                                          + originalOrderPath);
                } else {
                    TestPluginPlugin.info("Copying custom original order specified since it is different than the (non)existing original order at: "
                                          + originalOrderPath);

                    if (Files.exists(originalOrderPath)) {
                        String originalOrderRename = "original-order-" + System.currentTimeMillis();
                        Files.move(originalOrderPath, originalOrderPath.resolveSibling(originalOrderRename));
                        TestPluginPlugin.info("Original order before copying is now moved to: " + originalOrderRename);
                    }

                    // Copy the original order file to where we expect it to be since other parts of the tool still expects it to be there
                    // Future versions of iDFlakies should allow us to set the DetectorPathManager.originalOrderPath directly
                    Files.createDirectories(originalOrderPath.getParent());
                    Files.write(originalOrderPath, originalOrderSpecified);

                    TestPluginPlugin.info("Specified original order copied to: " + originalOrderSpecified);
                }
            }

            if (!Files.exists(originalOrderPath) || originalOrder.isEmpty()) {
                TestPluginPlugin.info("Original order file not found or is empty. Creating original-order file now at: "
                                      + originalOrderPath);
                originalOrder = DetectorPlugin.getOriginalOrder(project, true);
                Files.write(originalOrderPath, originalOrder);
            }

            DependentTestList dependentTestList = DependentTestList.fromFile(path);
            if (dependentTestList == null && !GENERATE_FLAKIES) {
                throw new IllegalArgumentException("Dependent test list file is empty. " +
                                                   "If you would like iFixFlakies to try generating passing/failing orders using random-class-method, try running iFixFlakies with -Ddt.minimizer.generate.list.flakies=true. " +
                                                   "You can control the rounds for iFixFlakies to generate passing/failing orders with -Ddt.randomize.rounds=X where X is an integer. The default is 20.");
            } else if (dependentTestList == null) {
                // Run random class method a few times to see if test would be OD
                Detector detector = new RandomDetector("random", runner,
                                                       DetectorPlugin.moduleRounds(new ErrorLogger(project).coordinates()),
                                                       originalOrder);
                dependentTestList = new DependentTestList(detector.detect());
            }

            if (TEST_TO_MINIMIZE != null) {
                TestPluginPlugin.info("Filtering dependent test list to run only for: " + TEST_TO_MINIMIZE);
                Optional<DependentTest> dependentTest = dependentTestList.dts().stream().
                        filter(dt -> dt.name().equalsIgnoreCase(TEST_TO_MINIMIZE)).findFirst();

                if (!dependentTest.isPresent()) {
                    throw new IllegalArgumentException("Dependent test name is specificed but could not find matching dependent test.");
                } else {
                    return minimizers(dependentTest.get(), builder, runner, USE_ORIGINAL_ORDER ? originalOrder : null);
                }
            } else {
                List<String> finalOriginalOrder = originalOrder;
                if (dependentTestList.dts().size() > 1) {
                    TestPluginPlugin.debug("More than one dependent test list detected. Original order cannot be trusted.");
                }
                return dependentTestList.dts().stream()
		    .flatMap(dt -> minimizers(dt, builder, runner, USE_ORIGINAL_ORDER ? finalOriginalOrder : null));
            }
        } catch (IOException e) {
            return Stream.empty();
        }
    }

    public Stream<TestMinimizer> minimizers(final DependentTest dependentTest,
					    final TestMinimizerBuilder builder,
					    final Runner runner,
					    List<String> originalOrder) {
        final TestRun intended = dependentTest.intended();
        final TestRun revealed = dependentTest.revealed();
        final String name = dependentTest.name();
        final TestMinimizerBuilder minimizerBuilder = builder.dependentTest(name);

        if (VERIFY_DTS) {
            if (!intended.verify(name, runner, null) || !revealed.verify(name, runner, null)) {
                return Stream.of(minimizerBuilder.buildNOD());
            }
        }

        // Try running dependent test in isolation to determine which order to minimize
        // Also run it 10 times to be more confident that test is deterministic in its result
        final Result isolationResult = runner.runList(Collections.singletonList(name)).get().results().get(name).result();
        for (int i = 0; i < 9; i++) {
            Result rerunIsolationResult = runner.runList(Collections.singletonList(name)).get().results().get(name).result();
            // If ever get different result, then not confident in result, return
            if (!rerunIsolationResult.equals(isolationResult)) {
                System.out.println("Test " + name + " does not have consistent result in isolation, not order-dependent!");
                return Stream.of(minimizerBuilder.buildNOD());
            }
        }

        TestMinimizer tm;
        if (originalOrder != null) {
            TestPluginPlugin.info("Using original order to run Minimizer instead of intended or revealed order.");
            if (!isolationResult.equals(Result.PASS)) {
                tm = minimizerBuilder.testOrder(reorderOriginalOrder(intended.order(), originalOrder)).build();
            } else {
                tm = minimizerBuilder.testOrder(reorderOriginalOrder(revealed.order(), originalOrder)).build();
            }
        } else if (!isolationResult.equals(Result.PASS)) { // Does not pass in isolation, needs setter, so need to minimize passing order
            tm = minimizerBuilder.testOrder(intended.order()).build();
            
        } else {    // Otherwise passes in isolation, needs polluter, so need to minimize failing order
            tm = minimizerBuilder.testOrder(revealed.order()).build();
        }
        String victimBrittleStr = !isolationResult.equals(Result.PASS) 
                ? "Test is brittle. Result of running test in isolation is: " + isolationResult
                : "Test is victim. Result of running test in isolation is: " + isolationResult;
        TestPluginPlugin.info(victimBrittleStr);
        return Stream.of(tm);
    }

    private List<String> reorderOriginalOrder(List<String> intended, List<String> originalOrder) {
        List<String> retList = new ArrayList<>(intended);

        for (String test : originalOrder) {
            if (!intended.contains(test)) {
                retList.add(test);
            }
        }
        try {
            Files.write(DetectorPathManager.originalOrderPath(), retList);
        } catch (IOException e) {
            TestPluginPlugin.error("Created new original order but could not write to "
                                           + DetectorPathManager.originalOrderPath());
            return retList;
        }
        TestPluginPlugin.info("Reordered original order to have some tests come first. For tests: " + intended);

        return retList;
    }

    public Stream<MinimizeTestsResult> runDependentTestFile(final Path dtFile, MavenProject project) {
        return fromDtList(dtFile, project).flatMap(minimizer -> {
            try {
                final MinimizeTestsResult result = minimizer.get();
                result.save();
                return Stream.of(result);
            } catch (Exception e) {
                e.printStackTrace();
            }

            return Stream.empty();
        });
    }

    @Override
    public void execute(final MavenProject project) {
        this.runner = InstrumentingSmartRunner.fromRunner(RunnerFactory.from(project).get());
        this.builder = new TestMinimizerBuilder(runner);

        StreamUtil.seq(runDependentTestFile(DetectorPathManager.detectionFile(), project));
    }
}
