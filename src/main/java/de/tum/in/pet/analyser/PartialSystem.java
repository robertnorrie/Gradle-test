package de.tum.in.pet.analyser;

import de.tum.in.probmodels.model.TransitionSystem;
import it.unimi.dsi.fastutil.ints.IntSet;

public record PartialSystem(TransitionSystem system, IntSet exploredStates) {}
