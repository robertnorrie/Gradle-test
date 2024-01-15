package de.tum.in.pet;

import static picocli.CommandLine.Command;
import static picocli.CommandLine.Model;
import static picocli.CommandLine.ParameterException;
import static picocli.CommandLine.Spec;

import de.tum.in.pet.implementation.core.CoreChecker;
import de.tum.in.pet.implementation.meanpayoff.MeanPayoffChecker;
import de.tum.in.pet.implementation.reachability.ReachChecker;
import de.tum.in.probmodels.cli.Statistics;
import de.tum.in.probmodels.cli.Uniformization;
import java.io.IOException;
import java.io.InputStream;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;
import picocli.CommandLine;

@SuppressWarnings("PMD.ImmutableField")
@Command(
        name = "pet",
        synopsisSubcommandLabel = "COMMAND",
        version = "1.0",
        mixinStandardHelpOptions = true,
        subcommands = {
            ReachChecker.class,
            CoreChecker.class,
            MeanPayoffChecker.class,
            Statistics.class,
            Uniformization.class
        })
public final class Main implements Runnable {
    static {
        String name = System.getenv().containsKey("PET_SILENT") ? "logging-quiet.properties" : "logging.properties";
        try (InputStream stream = Thread.currentThread().getContextClassLoader().getResourceAsStream(name)) {
            if (stream != null) {
                LogManager.getLogManager().readConfiguration(stream);
            }
        } catch (IOException | NullPointerException ignored) { // NOPMD
        }
    }

    private static final Logger logger = Logger.getLogger(Main.class.getName());
    public static final double DEFAULT_PRECISION = 1.0e-6;

    @Spec
    private Model.CommandSpec spec;

    @Override
    public void run() {
        throw new ParameterException(spec.commandLine(), "Missing required subcommand");
    }

    public static void main(String... args) {
        // NatBitSets.setFactory(new RoaringNatBitSetFactory());
        logger.log(Level.INFO, "Invocation:\n{0}", String.join(" ", args));
        System.exit(new CommandLine(new Main()).execute(args));
    }
}
