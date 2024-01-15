package de.tum.in.pet.implementation.meanpayoff;

import static picocli.CommandLine.Command;
import static picocli.CommandLine.Mixin;
import static picocli.CommandLine.Option;

import de.tum.in.pet.analyser.CollapsingAnalyser;
import de.tum.in.pet.analyser.CollapsingGlobalAnalyser;
import de.tum.in.pet.analyser.CollapsingSampler;
import de.tum.in.pet.util.DefaultResult;
import de.tum.in.pet.util.PrecisionMixin;
import de.tum.in.probmodels.cli.DefaultCli;
import de.tum.in.probmodels.explorer.DefaultExplorer;
import de.tum.in.probmodels.explorer.Explorer;
import de.tum.in.probmodels.explorer.RewardExplorer;
import de.tum.in.probmodels.explorer.SelfLoopHandling;
import de.tum.in.probmodels.impl.prism.PrismModelMixin;
import de.tum.in.probmodels.problem.ProblemInstance;
import de.tum.in.probmodels.problem.query.Optimization;
import de.tum.in.probmodels.problem.verdict.QuantitativeVerdict;
import de.tum.in.probmodels.problem.verdict.Result;
import de.tum.in.probmodels.values.Bounds;
import java.io.IOException;

@SuppressWarnings("PMD.ImmutableField")
@Command(name = "mean-payoff", mixinStandardHelpOptions = true)
public final class MeanPayoffChecker extends DefaultCli<DefaultResult<?>> {
    @Mixin
    private PrecisionMixin precisionOption;

    @Mixin
    private PrismModelMixin modelOption;

    @Option(names = "--reward-min", required = true, description = "Minimum reward")
    private double rewardMin;

    @Option(names = "--reward-max", required = true, description = "Maximum reward")
    private double rewardMax;

    @Option(names = "--rewards", required = true, description = "Reward name / index to check")
    private String rewardName;

    @Option(names = "--optimization", description = "Optimization (min / max)")
    private Optimization optimization = Optimization.MAX_VALUE;

    @Option(names = "--global", hidden = true)
    private boolean global = false;

    private MeanPayoffChecker() {}

    private <S> DefaultResult<S> solve(ProblemInstance<S> problemInstance) {
        Explorer<S> explorer = DefaultExplorer.of(problemInstance.model(), SelfLoopHandling.KEEP);
        var rewardGenerator = problemInstance.reward(rewardName);
        var verdict = QuantitativeVerdict.of(precisionOption.parse());
        var values = new MeanPayoffValues(
                optimization, Bounds.of(rewardMin, rewardMax), new RewardExplorer<>(explorer, rewardGenerator));
        CollapsingAnalyser<S, ?> analyser = (global
                        ? new CollapsingGlobalAnalyser<>(explorer, values, verdict)
                        : new CollapsingSampler<>(explorer, values, verdict))
                .run();
        var results = Result.of(explorer.initialStates(), s -> analyser.bounds(explorer.getStateId(s)), verdict);
        return new DefaultResult<>(rewardName, analyser.statistics(), results.asMap());
    }

    @Override
    protected DefaultResult<?> run() throws IOException {
        return solve(modelOption.parse());
    }
}
