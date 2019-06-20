package edu.illinois.cs.dt.tools.minimizer;

import edu.illinois.cs.dt.tools.utility.PathManager;
import edu.illinois.cs.testrunner.data.results.Result;

import java.nio.file.Path;
import java.nio.file.Paths;

public class MinimizerPathManager extends PathManager {
    public static final Path MINIMIZED = Paths.get("minimized");

    public static Path minimized() {
        return path(MINIMIZED);
    }

    public static Path minimized(final Path relative) {
        return path(MINIMIZED.resolve(relative));
    }

    public static Path minimized(final String dependentTest, final String hash, final Result expected) {
        return minimized(Paths.get(String.format("%s-%s-%s-dependencies.json", dependentTest, hash, expected)));
    }

    public static Path minimizeResultsPath(final MinimizeTestsResult minimized) {
        return minimizeResultsPath(minimized, "");
    }

    /**
     * @param modifier A string to add to the end of the path. Can be null or blank to specify there is no modifier.
     * @return A (relative) path to be used whenever storing results relevant to this particular dependent test,
     *         usually inside of some other folder.
     */
    public static Path minimizeResultsPath(final MinimizeTestsResult minimized, final String modifier) {
        if (modifier == null || modifier.isEmpty()) {
            return Paths.get(String.format("%s-%s-%s", minimized.dependentTest(), minimized.hash(), minimized.expected()));
        } else {
            return Paths.get(String.format("%s-%s-%s-%s", minimized.dependentTest(), minimized.hash(), minimized.expected(), modifier));
        }
    }
}
