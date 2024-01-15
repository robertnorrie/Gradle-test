package de.tum.in.pet.analyser;

import de.tum.in.probmodels.explorer.Explorer;
import de.tum.in.probmodels.model.distribution.Distribution;
import de.tum.in.probmodels.problem.verdict.BoundVerdict;
import de.tum.in.probmodels.util.Sample;
import de.tum.in.probmodels.util.Util;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import javax.annotation.Nullable;

@SuppressWarnings("PMD.TooManyFields")
public final class CollapsingSampler<S, C> extends CollapsingAnalyser<S, C> {
    private static final int MAX_BACKTRACK_PER_SAMPLE = 4;
    private static final int MAX_EXPLORES_PER_SAMPLE = 4;
    private static final int INITIAL_COLLAPSE_THRESHOLD = 10;

    private long collapseThreshold = INITIAL_COLLAPSE_THRESHOLD;
    private int loopCount = 0;

    private final IntSet visitedStateSet = new IntOpenHashSet(128);
    private final Deque<Pair<C>> samples = new ArrayDeque<>(128);

    private long backtrackCount = 0;
    private long backtrackToInitialCount = 0;

    public CollapsingSampler(Explorer<S> explorer, CollapsingValues<C> values, BoundVerdict verdict) {
        super(explorer, values, verdict);
    }

    @Override
    protected SamplingResult<C> getPairs(int initialState) {
        Deque<Pair<C>> samples = this.samples;
        IntSet visitedStateSet = this.visitedStateSet;
        CollapsingValues<C> values = this.values;
        Explorer<S> explorer = this.explorer;

        samples.clear();
        visitedStateSet.clear();

        int exploreCount = 0;
        int currentState = initialState;
        int sampleBacktraceCount = 0;
        int stateRevisit = 0;
        boolean checkForComponents = false;
        while (stateRevisit < 10) {
            assert explorer().isExploredState(currentState);

            if (!visitedStateSet.add(currentState)) {
                stateRevisit += 1;
            }

            int state = currentState;
            List<Distribution> distributions = distributions(currentState);
            List<? extends C> choices = values.choices(state, distributions);
            var optimal = Sample.getOptimal(choices, values.score(state, distributions, choices));

            int nextState;
            if (optimal == null) {
                nextState = -1;
            } else {
                samples.addFirst(new Pair<>(state, distributions, choices, optimal));
                @Nullable Distribution distribution = values.successors(state, distributions, optimal);
                if (distribution == null) {
                    nextState = -1;
                } else {
                    nextState = distribution.sampleWeightedExcept(
                            (s, p) -> p * values.difference(s), visitedStateSet::contains);
                }
            }

            if (nextState == -1) {
                if (sampleBacktraceCount == MAX_BACKTRACK_PER_SAMPLE) {
                    checkForComponents = true;
                    break;
                }

                // We won't find anything of value if we continue to follow this path, backtrace until we find an
                // interesting state again
                // Note: We might as well completely restart the sampling here, but then we potentially have to move to
                // this interesting "fringe" region again
                double difference;
                double updatedDifference;
                do {
                    backtrackCount += 1;
                    var backtrack = samples.removeFirst();
                    currentState = backtrack.state();
                    visitedStateSet.remove(currentState);
                    difference = bounds(currentState).difference();
                    updatedDifference = backtrack.update(values).difference();
                    loopCount += 1;
                } while (Util.isEqual(difference, updatedDifference) && currentState != initialState);
                if (currentState == initialState) {
                    backtrackToInitialCount += 1;
                    checkForComponents = true;
                    break;
                }
                sampleBacktraceCount += 1;
            } else {
                if (!explorer.isExploredState(nextState)) {
                    if (exploreCount == MAX_EXPLORES_PER_SAMPLE) {
                        break;
                    }
                    collapseThreshold -= 1;
                    exploreCount += 1;
                    explore(nextState);
                }
                currentState = nextState;
            }
        }

        if (stateRevisit > 5) {
            checkForComponents = true;
        }

        // Handle end components
        if (checkForComponents) {
            loopCount += 1;
            // We looped quite often - chances for this are high if there is a MEC, otherwise the sampling probabilities
            // would decrease
            if (loopCount > collapseThreshold) {
                loopCount = 0;
                if (collapseThreshold < 0) {
                    collapseThreshold = 0;
                }

                // TODO Only search on frequently visited states?
                if (handleComponents(false)) {
                    samples.clear();
                    //noinspection NumericCastThatLosesPrecision
                    collapseThreshold = (int) Math.sqrt(quotient.stateCount());
                } else {
                    collapseThreshold = quotient.stateCount();
                }
            }
        }

        return new SamplingResult<>(samples.stream());
    }

    @Override
    public UnboundedStatistics statistics() {
        return new UnboundedSamplerStatistics(this);
    }

    public static class UnboundedSamplerStatistics extends UnboundedStatistics {
        // CHECKSTYLE.OFF: VisibilityModifier
        public final long backtrackCount;
        public final long backtrackToInitialCount;
        // CHECKSTYLE.ON: VisibilityModifier

        public UnboundedSamplerStatistics(CollapsingSampler<?, ?> sampler) {
            super(sampler);
            this.backtrackCount = sampler.backtrackCount;
            this.backtrackToInitialCount = sampler.backtrackToInitialCount;
        }

        @Override
        public String toString() {
            return "%s%n%d backtracks, %d to initial"
                    .formatted(super.toString(), backtrackCount, backtrackToInitialCount);
        }
    }
}
