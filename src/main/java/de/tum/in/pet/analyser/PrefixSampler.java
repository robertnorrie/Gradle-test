package de.tum.in.pet.analyser;

import de.tum.in.probmodels.explorer.Explorer;
import de.tum.in.probmodels.model.Choice;
import de.tum.in.probmodels.model.distribution.Distribution;
import de.tum.in.probmodels.problem.verdict.BoundVerdict;
import de.tum.in.probmodels.util.Sample;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

public class PrefixSampler<S> extends PrefixAnalyser<S> {
    public PrefixSampler(Explorer<S> explorer, int stepBound, PrefixValues values, BoundVerdict verdict) {
        super(explorer, stepBound, values, verdict);
    }

    @Override
    protected SamplingResult getPairs(int initialState) {
        Deque<Pair> samples = new ArrayDeque<>();

        int exploreCount = 0;
        int currentState = initialState;
        int remainingSteps = stepBound;
        while (remainingSteps > 0) {
            assert explorer.isExploredState(currentState);
            assert remainingSteps == stepBound - samples.size();

            List<Choice> choices = explorer.choices(currentState);
            var choice = Sample.getOptimal(choices, values.score(currentState, remainingSteps, choices));
            assert choice != null;
            samples.addFirst(new Pair(currentState, remainingSteps, choices, choice));

            Distribution distribution = choice.distribution();
            int successorSteps = remainingSteps - 1;
            int nextState = distribution.sampleWeighted((s, p) -> p * values.difference(s, successorSteps));

            if (nextState == -1) {
                break;
            }
            if (!explorer.isExploredState(nextState)) {
                if (exploreCount == 5) {
                    break;
                }
                exploreCount += 1;
                explorer.exploreState(nextState);
            }

            currentState = nextState;
            remainingSteps -= 1;
        }
        return new SamplingResult(samples.stream());
    }
}
