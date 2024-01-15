package de.tum.in.pet.implementation.reachability;

import static com.google.common.base.Preconditions.checkArgument;
import static picocli.CommandLine.Command;
import static picocli.CommandLine.Mixin;

import de.tum.in.pet.analyser.CollapsingAnalyser;
import de.tum.in.pet.analyser.CollapsingGlobalAnalyser;
import de.tum.in.pet.analyser.CollapsingSampler;
import de.tum.in.pet.analyser.PrefixSampler;
import de.tum.in.pet.util.DefaultResult;
import de.tum.in.pet.util.PrecisionMixin;
import de.tum.in.probmodels.cli.DefaultCli;
import de.tum.in.probmodels.explorer.DefaultExplorer;
import de.tum.in.probmodels.explorer.Explorer;
import de.tum.in.probmodels.explorer.SelfLoopHandling;
import de.tum.in.probmodels.generator.Generator;
import de.tum.in.probmodels.generator.SafetyGenerator;
import de.tum.in.probmodels.impl.prism.PrismModelMixin;
import de.tum.in.probmodels.problem.Problem;
import de.tum.in.probmodels.problem.ProblemInstance;
import de.tum.in.probmodels.problem.property.ReachType;
import de.tum.in.probmodels.problem.property.ReachabilityProperty;
import de.tum.in.probmodels.problem.query.QualitativeQuery;
import de.tum.in.probmodels.problem.query.QuantitativeQuery;
import de.tum.in.probmodels.problem.verdict.BoundHandler;
import de.tum.in.probmodels.problem.verdict.QualitativeVerdict;
import de.tum.in.probmodels.problem.verdict.QuantitativeVerdict;
import de.tum.in.probmodels.problem.verdict.Result;
import de.tum.in.probmodels.util.NatCacheFunction;
import java.io.IOException;
import java.util.Map;
import java.util.function.IntPredicate;
import java.util.logging.Level;
import java.util.logging.Logger;
import picocli.CommandLine.Option;

@SuppressWarnings("PMD.ImmutableField")
@Command(name = "reachability", mixinStandardHelpOptions = true)
public final class ReachChecker extends DefaultCli<DefaultResult<?>> {
    private static final Logger logger = Logger.getLogger(ReachChecker.class.getName());

    @Mixin
    private PrecisionMixin precisionOption;

    @Mixin
    private PrismModelMixin modelOption;

    @Option(names = "--property", required = true, description = "Property name / index to check")
    private String expressionName;

    @Option(names = "--global", hidden = true)
    private boolean global = false;

    private ReachChecker() {
        // Empty
    }

    private <S> DefaultResult<S> solve(ProblemInstance<S> instance) {
        Problem<S> expression = instance.problem(expressionName);
        logger.log(Level.INFO, "Checking expression {0}", new Object[] {expression});

        var query = expression.query();
        checkArgument(expression.property() instanceof ReachabilityProperty<S>);
        var property = (ReachabilityProperty<S>) expression.property();

        BoundHandler<?> verdict;
        if (query instanceof QualitativeQuery qualitative) {
            verdict = new QualitativeVerdict(qualitative.comparison(), qualitative.threshold());
        } else {
            assert query instanceof QuantitativeQuery;
            var precision = precisionOption.parse();
            verdict = new QuantitativeVerdict(precision.bound(), precision.relativeError());
        }

        Explorer<S> explorer;
        Result<S, ?> result;
        Object statistics;
        if (property.upperBound().isPresent()) {
            explorer = DefaultExplorer.of(instance.model(), SelfLoopHandling.KEEP);
            var target = new NatCacheFunction<>(property.reachability(), explorer::getState);
            var values = new BoundedReachValues(target, query.optimization(), true);
            int stepBound = property.upperBound().getAsInt();
            new PrefixSampler<>(explorer, stepBound, values, verdict).run();
            statistics = explorer.exploredStateCount();
            result =
                    Result.of(explorer.initialStates(), s -> values.bounds(explorer.getStateId(s), stepBound), verdict);
        } else {
            Generator<S> propertyGenerator = property.hasSafety()
                    ? new SafetyGenerator<>(
                            instance.model(), s -> property.reachability().apply(s) != ReachType.SINK)
                    : instance.model();
            explorer = DefaultExplorer.of(propertyGenerator, SelfLoopHandling.KEEP);
            // var target = new ReachabilityCache<>(property.reachability(), explorer::getState);

            IntPredicate goal = s -> property.reachability().apply(explorer.getState(s)) == ReachType.GOAL;
            var values = new UnboundedReachValues(goal, query.optimization());
            CollapsingAnalyser<S, ?> analyser = (global
                            ? new CollapsingGlobalAnalyser<>(explorer, values, verdict)
                            : new CollapsingSampler<>(explorer, values, verdict))
                    .run();
            statistics = analyser.statistics();
            result = Result.of(explorer.initialStates(), s -> analyser.bounds(explorer.getStateId(s)), verdict);
        }

        return new DefaultResult<>(expressionName, statistics, Map.copyOf(result.asMap()));
    }

    @Override
    protected DefaultResult<?> run() throws IOException {
        return solve((ProblemInstance<?>) modelOption.parse());
    }
}
