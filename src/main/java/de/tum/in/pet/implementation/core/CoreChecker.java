package de.tum.in.pet.implementation.core;

import static picocli.CommandLine.Command;
import static picocli.CommandLine.Mixin;

import com.google.common.base.Stopwatch;
import de.tum.in.naturals.bitset.BitSets;
import de.tum.in.naturals.set.NatBitSet;
import de.tum.in.naturals.set.NatBitSets;
import de.tum.in.pet.Main;
import de.tum.in.pet.analyser.CollapsingAnalyser;
import de.tum.in.pet.analyser.CollapsingSampler;
import de.tum.in.pet.analyser.PartialSystem;
import de.tum.in.pet.analyser.PrefixSampler;
import de.tum.in.probmodels.cli.DefaultCli;
import de.tum.in.probmodels.explorer.DefaultExplorer;
import de.tum.in.probmodels.explorer.Explorer;
import de.tum.in.probmodels.explorer.SelfLoopHandling;
import de.tum.in.probmodels.impl.prism.PrismModelMixin;
import de.tum.in.probmodels.impl.prism.PrismWrappedException;
import de.tum.in.probmodels.impl.prism.model.PrismModels;
import de.tum.in.probmodels.output.DefaultStatistics;
import de.tum.in.probmodels.output.ModelStatistics;
import de.tum.in.probmodels.problem.ProblemInstance;
import de.tum.in.probmodels.problem.verdict.QuantitativeVerdict;
import de.tum.in.probmodels.util.Util;
import explicit.DTMC;
import explicit.DTMCModelChecker;
import explicit.MDP;
import explicit.MDPModelChecker;
import explicit.ModelCheckerResult;
import java.io.IOException;
import java.util.BitSet;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nullable;
import picocli.CommandLine.Option;
import prism.ModelType;
import prism.Prism;
import prism.PrismDevNullLog;
import prism.PrismException;
import prism.PrismSettings;

@SuppressWarnings("PMD.ImmutableField")
@Command(name = "core", mixinStandardHelpOptions = true)
public final class CoreChecker extends DefaultCli<CoreChecker.CoreStatistics> {
    private static final Logger logger = Logger.getLogger(CoreChecker.class.getName());

    @Mixin
    private PrismModelMixin modelOption;

    @Option(names = "--precision", description = "Precision (default: ${DEFAULT-VALUE})")
    private double precision = Main.DEFAULT_PRECISION;

    @Option(names = "--unbounded", description = "Build unbounded core")
    private boolean unboundedCore = false;

    @Option(names = "--bounded", description = "Build bounded core")
    @Nullable
    private Integer boundedCore = null;

    @Option(
            names = "--bounded-update",
            description = "Update mechanism for bounded core (dense or simple,n)",
            hidden = true)
    private String boundedCoreStorage = "dense";

    @Option(names = "--validate", description = "Validate core property")
    private boolean validateCoreProperty = false;

    @Option(names = "--components", description = "Analyse components")
    private boolean componentAnalysis = false;

    private CoreChecker() {
        // Empty
    }

    private static void checkCoreProperty(double precision, PartialSystem partialModel, int stepBound) {
        logger.log(Level.INFO, "Checking core property");
        int initialState = partialModel.system().onlyInitialState();

        NatBitSet fringeStates = NatBitSets.ensureModifiable(
                NatBitSets.copyOf(partialModel.system().states()));
        fringeStates.andNot(partialModel.exploredStates());
        BitSet target = BitSets.of(fringeStates);
        assert NatBitSets.asSet(target).intStream().noneMatch(partialModel.exploredStates()::contains);

        var prismModel = PrismModels.asPrismModel(
                partialModel.system(), partialModel.system().isDeterministic() ? ModelType.DTMC : ModelType.MDP);

        double validationPrecision = precision * 0.001;
        double reach;
        try {
            Prism mcPrism = new Prism(new PrismDevNullLog());
            PrismSettings settings = new PrismSettings();
            settings.set(PrismSettings.PRISM_TERM_CRIT, "Absolute");
            settings.set(PrismSettings.PRISM_TERM_CRIT_PARAM, validationPrecision);
            mcPrism.setSettings(settings);
            ModelCheckerResult result;
            if (prismModel instanceof DTMC chain) {
                DTMCModelChecker dtmcChecker = new DTMCModelChecker(mcPrism);
                result = stepBound < 0
                        ? dtmcChecker.computeReachProbs(chain, target)
                        : dtmcChecker.computeBoundedReachProbs(chain, target, stepBound);
            } else if (prismModel instanceof MDP mdp) {
                MDPModelChecker mdpChecker = new MDPModelChecker(mcPrism);
                result = stepBound < 0
                        ? mdpChecker.computeReachProbs(mdp, target, false)
                        : mdpChecker.computeBoundedReachProbs(mdp, target, stepBound, false);
            } else {
                throw new UnsupportedOperationException(prismModel.getClass().getName());
            }
            reach = result.soln[initialState];
        } catch (PrismException e) {
            throw new PrismWrappedException(e);
        }
        logger.log(Level.INFO, () -> String.format("Reachability: %f", reach));
        if (!Util.lessOrEqual(reach, precision + validationPrecision)) {
            throw new AssertionError("Core property violated!");
        }
    }

    private static Supplier<BoundedCoreValues> parseBoundedValues(String option) {
        String[] split = option.split(",");
        return switch (split[0]) {
            case "dense" -> BoundedCoreValues.Dense::new;
            case "simple" -> {
                int count = Integer.parseInt(split[1]);
                yield () -> new BoundedCoreValues.Simple(count);
            }
            default -> throw new IllegalArgumentException("Invalid state optimization");
        };
    }

    private <S> CoreStatistics solve(ProblemInstance<S> problemInstance) {
        CoreStatistics statistics = new CoreStatistics();
        Explorer<S> explorer = DefaultExplorer.of(problemInstance.model(), SelfLoopHandling.KEEP);
        QuantitativeVerdict verdict = new QuantitativeVerdict(precision, false);
        var boundedValues = parseBoundedValues(boundedCoreStorage);

        if (unboundedCore) {
            logger.log(Level.INFO, "Building unbounded core");

            Stopwatch timer = Stopwatch.createStarted();
            var values = new UnboundedCoreValues();
            var sampler = new CollapsingSampler<>(explorer, values, verdict).run();
            var duration = timer.elapsed();
            var core = sampler.model();

            statistics.unboundedStatistics =
                    DefaultStatistics.statistics(core.system(), core.exploredStates(), duration, componentAnalysis);
            statistics.analyserStatistics = sampler.statistics();
            if (validateCoreProperty) {
                checkCoreProperty(precision, core, -1);
            }
        }
        if (boundedCore != null) {
            logger.log(Level.INFO, "Building {0}-bounded core", new Object[] {boundedCore});
            Stopwatch timer = Stopwatch.createStarted();
            var sampler = new PrefixSampler<>(explorer, boundedCore + 1, boundedValues.get(), verdict).run();
            var duration = timer.elapsed();
            var core = sampler.model();

            statistics.boundedStatistics.put(
                    boundedCore,
                    DefaultStatistics.statistics(core.system(), core.exploredStates(), duration, componentAnalysis));

            if (validateCoreProperty) {
                checkCoreProperty(precision, core, boundedCore);
            }
        }
        return statistics;
    }

    @Override
    protected CoreStatistics run() throws IOException {
        return solve(modelOption.parse());
    }

    public static final class CoreStatistics {
        // CHECKSTYLE.OFF: VisibilityModifier
        @Nullable
        public ModelStatistics unboundedStatistics;

        @Nullable
        public CollapsingAnalyser.UnboundedStatistics analyserStatistics;

        public final Map<Integer, ModelStatistics> boundedStatistics = new TreeMap<>();
        // CHECKSTYLE.ON: VisibilityModifier
    }
}
