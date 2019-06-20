package edu.illinois.cs.dt.tools.minimizer;

import com.reedoei.eunomia.collections.StreamUtil;
import edu.illinois.cs.dt.tools.detection.DetectorPathManager;
import edu.illinois.cs.dt.tools.runner.InstrumentingSmartRunner;
import edu.illinois.cs.dt.tools.runner.data.DependentTest;
import edu.illinois.cs.dt.tools.runner.data.DependentTestList;
import edu.illinois.cs.dt.tools.runner.data.TestRun;
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
import java.util.Collections;
import java.util.stream.Stream;

public class MinimizerPlugin extends TestPlugin {
    private TestMinimizerBuilder builder;
    private InstrumentingSmartRunner runner;

    private static final boolean VERIFY_DTS = Configuration.config().getProperty("dt.verify", true);

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

    private Stream<TestMinimizer> fromDtList(final Path path) {
        TestPluginPlugin.info("Creating minimizers for file: " + path);

        try {
            final DependentTestList dependentTestList = DependentTestList.fromFile(path);
            if (dependentTestList == null) {
                throw new IllegalArgumentException("Dependent test list file is empty");
            }

            return dependentTestList.dts().stream()
                    .flatMap(dt -> minimizers(dt, builder, runner));
        } catch (IOException e) {
            return Stream.empty();
        }
    }

    private Stream<TestMinimizer> minimizers(final DependentTest dependentTest,
            final TestMinimizerBuilder builder, final Runner runner) {
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

        if (!isolationResult.equals(Result.PASS)) { // Does not pass in isolation, needs setter, so need to minimize passing order
            return Stream.of(minimizerBuilder.testOrder(intended.order()).build());
        } else {    // Otherwise passes in isolation, needs polluter, so need to minimize failing order
            return Stream.of(minimizerBuilder.testOrder(revealed.order()).build());
        }
    }

    public Stream<MinimizeTestsResult> runDependentTestFolder(final Path dtFolder) throws IOException {
        return Files.walk(dtFolder)
                .filter(p -> Files.isRegularFile(p))
                .flatMap(this::runDependentTestFile);
    }

    public Stream<MinimizeTestsResult> runDependentTestFile(final Path dtFile) {
        return fromDtList(dtFile).flatMap(minimizer -> {
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

        StreamUtil.seq(runDependentTestFile(DetectorPathManager.detectionFile()));
    }
}
