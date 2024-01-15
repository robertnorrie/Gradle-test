package de.tum.in.pet.analyser;

import de.tum.in.probmodels.explorer.Explorer;
import de.tum.in.probmodels.values.Bounds;

public interface Analyser<S> {
    /**
     * The explorer underlying this analyser.
     */
    Explorer<S> explorer();

    /**
     * The already explored subsystem.
     */
    PartialSystem model();

    /**
     * Bounds for the given state. For prefix-dependent properties, this refers to the initial bounds.
     */
    Bounds bounds(int state);

    /**
     * Run the analysis and return {@code this} for chaining.
     */
    Analyser<S> run();
}
