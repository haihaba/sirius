package de.unijena.bioinf.FragmentationTreeConstruction.computation.scoring;

import de.unijena.bioinf.ChemistryBase.algorithm.Parameterized;
import de.unijena.bioinf.FragmentationTreeConstruction.model.Loss;
import de.unijena.bioinf.FragmentationTreeConstruction.model.ProcessedInput;

/**
 * @author Kai Dührkop
 */
public interface LossScorer extends Parameterized {

    public Object prepare(ProcessedInput input);

    public double score(Loss loss, ProcessedInput input, Object precomputed);

}
