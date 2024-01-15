package de.tum.in.pet.util;

import static picocli.CommandLine.Option;

import de.tum.in.pet.Main;
import de.tum.in.probmodels.util.Precision;

@SuppressWarnings("PMD.ImmutableField")
public class PrecisionMixin {
    @Option(names = "--precision", description = "Precision (default: ${DEFAULT-VALUE})")
    private double precision = Main.DEFAULT_PRECISION;

    @Option(names = "--relative", description = "Relative Error (default: ${DEFAULT-VALUE})", negatable = true)
    private boolean relativeError = false;

    public Precision parse() {
        assert precision >= 0.0;
        return new Precision(precision, relativeError);
    }
}
