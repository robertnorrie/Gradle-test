package de.tum.in.pet.analyser;

import de.tum.in.naturals.set.NatBitSet;
import de.tum.in.naturals.set.NatBitSets;
import de.tum.in.probmodels.explorer.Explorer;
import de.tum.in.probmodels.explorer.SelfLoopHandling;
import de.tum.in.probmodels.graph.Component;
import de.tum.in.probmodels.model.TransitionSystem;
import de.tum.in.probmodels.model.distribution.Distribution;
import de.tum.in.probmodels.model.impl.DynamicQuotient;
import de.tum.in.probmodels.problem.verdict.BoundVerdict;
import de.tum.in.probmodels.values.Bounds;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.IntIterator;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@SuppressWarnings("PMD.TooManyFields")
public abstract class CollapsingAnalyser<S, C> implements Analyser<S> {
    private static final Logger logger = Logger.getLogger(CollapsingAnalyser.class.getName());

    // CHECKSTYLE.OFF: VisibilityModifier
    protected final Explorer<S> explorer;
    protected final CollapsingValues<C> values;
    protected final DynamicQuotient<TransitionSystem> quotient;
    protected final BoundVerdict verdict;
    // CHECKSTYLE.ON: VisibilityModifier

    private final IntSet exploredSinceLastCollapse = new IntOpenHashSet();
    private int iterationsSinceExplore = 0;
    private int expandThreshold = 10;
    private int exploresBeforeCollapse = 100;
    private int exploresBeforeCollapseReset = 100;

    private long iterations = 0;
    private long time = 0L;
    private int componentSearches = 0;
    private int successfulComponentSearches = 0;

    public CollapsingAnalyser(Explorer<S> explorer, CollapsingValues<C> values, BoundVerdict verdict) {
        this.explorer = explorer;
        this.quotient = new DynamicQuotient<>(explorer.partialSystem(), SelfLoopHandling.INLINE);
        this.values = values;
        this.verdict = verdict;
    }

    @Override
    public Explorer<S> explorer() {
        return explorer;
    }

    public CollapsingValues<C> values() {
        return values;
    }

    @Override
    public PartialSystem model() {
        IntSet exploredStates = new IntOpenHashSet(explorer.exploredStates());
        exploredStates.removeIf((int state) -> values.isUnknown(quotient.representative(state)));
        return new PartialSystem(explorer.partialSystem(), exploredStates);
    }

    @Override
    public Bounds bounds(int state) {
        return values.bounds(quotient.representative(state));
    }

    @Override
    public CollapsingAnalyser<S, C> run() {
        time = System.currentTimeMillis();
        for (int initialState : explorer.initialStateIds()) {
            // The representative of the initial states might be a different state
            int representative = quotient.representative(initialState);
            while (!verdict.isSolved(values.bounds(representative))) {
                var sampling = getPairs(representative);
                sampling.pairs().forEach(pair -> pair.update(values));
                iterations += 1;
                iterationsSinceExplore += 1;

                if (iterationsSinceExplore > expandThreshold) {
                    var states = NatBitSets.ensureModifiable(NatBitSets.copyOf(quotient.states()));
                    states.andNot(explorer.exploredStates());
                    IntIterator iterator = states.iterator();
                    int count = 0;
                    while (iterator.hasNext() && count < expandThreshold) {
                        explore(iterator.nextInt());
                        count += 1;
                    }
                    //noinspection NumericCastThatLosesPrecision
                    expandThreshold = (int) Math.sqrt(quotient.stateCount());
                    logger.log(Level.FINE, "Updating components after expansion");
                    handleComponents(count == 0);
                }

                representative = quotient.representative(representative);
                logUpdate(false);
            }
        }
        logUpdate(true);
        return this;
    }

    private void logUpdate(boolean force) {
        if (!logger.isLoggable(Level.INFO)) {
            return;
        }
        if (!force && System.currentTimeMillis() - time < 5000) {
            return;
        }

        time = System.currentTimeMillis();
        logger.log(
                Level.INFO,
                "Progress after %d rounds: %s%n%s"
                        .formatted(
                                iterations,
                                explorer.initialStateIds()
                                        .intStream()
                                        .mapToObj(s -> s + ": " + bounds(s))
                                        .collect(Collectors.joining(", ")),
                                statistics()));
    }

    protected abstract SamplingResult<C> getPairs(int initialState);

    protected void explore(int state) {
        assert !explorer.isExploredState(state);
        exploredSinceLastCollapse.add(state);
        iterationsSinceExplore = 0;
        explorer.exploreState(state);
    }

    protected void exploreReachable(int state) {
        NatBitSet explored = NatBitSets.copyOf(explorer.exploredStates());
        explorer.exploreReachable(IntSet.of(state));
        NatBitSet exploredAfter = NatBitSets.ensureModifiable(NatBitSets.copyOf(explorer.exploredStates()));
        exploredAfter.andNot(explored);
        if (exploredAfter.isEmpty()) {
            return;
        }
        exploredSinceLastCollapse.addAll(exploredAfter);
        iterationsSinceExplore = 0;
    }

    protected boolean handleComponents(boolean force) {
        if (!force && exploredSinceLastCollapse.size() < exploresBeforeCollapse) {
            exploresBeforeCollapse = (int) Math.sqrt(exploresBeforeCollapse);
            return false;
        }
        componentSearches += 1;

        assert exploredSinceLastCollapse.intStream().noneMatch(quotient::isRemoved);
        assert explorer.exploredStates().containsAll(exploredSinceLastCollapse);
        Int2ObjectMap<Component> newComponents =
                quotient.updateComponents(exploredSinceLastCollapse, explorer::isExploredState);
        exploredSinceLastCollapse.clear();
        assert newComponents.values().stream()
                .allMatch(c -> explorer.exploredStates().containsAll(c.states()));

        if (newComponents.isEmpty()) {
            exploresBeforeCollapseReset = (int) Math.sqrt(quotient.stateCount());
            exploresBeforeCollapse = exploresBeforeCollapseReset;
            return false;
        }
        exploresBeforeCollapse = exploresBeforeCollapseReset;
        successfulComponentSearches += 1;
        for (Int2ObjectMap.Entry<Component> entry : newComponents.int2ObjectEntrySet()) {
            int representative = entry.getIntKey();
            values.collapse(representative, distributions(representative), entry.getValue());
        }
        return true;
    }

    protected List<Distribution> distributions(int state) {
        assert explorer.isExploredState(state);
        return quotient.distributions(state);
    }

    public UnboundedStatistics statistics() {
        return new UnboundedStatistics(this);
    }

    public record Pair<C>(int state, List<Distribution> distributions, List<? extends C> choices, C choice) {
        public Bounds update(CollapsingValues<C> values) {
            return values.update(state, distributions, choices, choice);
        }
    }

    public record SamplingResult<C>(Stream<Pair<C>> pairs) {}

    public static class UnboundedStatistics {
        // CHECKSTYLE.OFF: VisibilityModifier
        public final int exploredStates;
        public final long statesInQuotient;
        public final int componentSearches;
        public final int successfulComponentSearches;
        public final long iterations;
        // CHECKSTYLE.ON: VisibilityModifier

        public UnboundedStatistics(CollapsingAnalyser<?, ?> analyser) {
            this.exploredStates = analyser.explorer().exploredStateCount();
            this.componentSearches = analyser.componentSearches;
            this.successfulComponentSearches = analyser.successfulComponentSearches;
            this.iterations = analyser.iterations;
            this.statesInQuotient = analyser.quotient
                    .statesStream()
                    .filter(analyser.explorer::isExploredState)
                    .count();
        }

        @Override
        public String toString() {
            return "%d explored states (%d in quotient) in %d iterations%n%d component searches (%d successful)"
                    .formatted(
                            exploredStates,
                            statesInQuotient,
                            iterations,
                            componentSearches,
                            successfulComponentSearches);
        }
    }
}
