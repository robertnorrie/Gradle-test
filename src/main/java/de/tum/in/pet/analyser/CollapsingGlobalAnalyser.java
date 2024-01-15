package de.tum.in.pet.analyser;

import de.tum.in.probmodels.explorer.Explorer;
import de.tum.in.probmodels.model.distribution.Distribution;
import de.tum.in.probmodels.problem.verdict.BoundVerdict;
import java.util.List;
import java.util.function.Function;

@SuppressWarnings("PMD.TooManyFields")
public final class CollapsingGlobalAnalyser<S, C> extends CollapsingAnalyser<S, C> {
    private boolean explored = false;

    public CollapsingGlobalAnalyser(Explorer<S> explorer, CollapsingValues<C> values, BoundVerdict verdict) {
        super(explorer, values, verdict);
    }

    @Override
    protected SamplingResult<C> getPairs(int initialState) {
        if (!explored) {
            explored = true;
            exploreReachable(initialState);
            handleComponents(true);
        }
        return new SamplingResult<>(quotient.states()
                .intStream()
                .mapToObj(currentState -> {
                    List<Distribution> distributions = distributions(currentState);
                    List<? extends C> choices = values.choices(currentState, distributions);
                    return choices.stream().map(c -> new Pair<>(currentState, distributions, choices, c));
                })
                .flatMap(Function.identity()));
    }
}
