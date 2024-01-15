package de.tum.in.pet.analyser;

import de.tum.in.probmodels.explorer.Explorer;
import de.tum.in.probmodels.model.Choice;
import de.tum.in.probmodels.problem.verdict.BoundVerdict;
import de.tum.in.probmodels.values.Bounds;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public abstract class PrefixAnalyser<S> implements Analyser<S> {
    private static final Logger logger = Logger.getLogger(PrefixAnalyser.class.getName());

    // CHECKSTYLE.OFF: VisibilityModifier
    protected final Explorer<S> explorer;
    protected final PrefixValues values;
    protected final int stepBound;
    protected final BoundVerdict verdict;
    // CHECKSTYLE.ON: VisibilityModifier

    private int iterations = 0;
    private long time;

    public PrefixAnalyser(Explorer<S> explorer, int stepBound, PrefixValues values, BoundVerdict verdict) {
        this.values = values;
        this.explorer = explorer;
        this.stepBound = stepBound;
        this.verdict = verdict;
    }

    @Override
    public Explorer<S> explorer() {
        return explorer;
    }

    public PrefixValues values() {
        return values;
    }

    @Override
    public PartialSystem model() {
        return new PartialSystem(explorer.partialSystem(), explorer.exploredStates());
    }

    @Override
    public Bounds bounds(int state) {
        return values.bounds(state, stepBound);
    }

    @Override
    public PrefixAnalyser<S> run() {
        time = System.currentTimeMillis();
        for (int initialState : explorer.initialStateIds()) {
            while (!verdict.isSolved(values.bounds(initialState, stepBound))) {
                getPairs(initialState).pairs.forEach(p -> p.update(values));
                iterations += 1;
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
                "Progress after %d rounds: %s%nExplored: %d states"
                        .formatted(
                                iterations,
                                explorer.initialStateIds()
                                        .intStream()
                                        .mapToObj(s -> s + ": " + bounds(s))
                                        .collect(Collectors.joining(", ")),
                                explorer.exploredStateCount()));
    }

    protected abstract SamplingResult getPairs(int initialState);

    public record SamplingResult(Stream<Pair> pairs) {}

    public record Pair(int state, int remaining, List<Choice> choices, Choice choice) {
        public Bounds update(PrefixValues values) {
            return values.update(state, remaining, choices, choice);
        }
    }
}
