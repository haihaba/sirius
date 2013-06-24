package de.unijena.bioinf.IsotopePatternAnalysis.extraction;

import de.unijena.bioinf.ChemistryBase.ms.Peak;
import de.unijena.bioinf.ChemistryBase.ms.Spectrum;
import de.unijena.bioinf.IsotopePatternAnalysis.IsotopePattern;

import java.util.List;

/**
 * Input: MS1 Spectrum with one or more isotopic patterns
 * Output: For each compound this algorithm should return a spectrum containing the isotopic peaks of this compound
 * For each nominal mass there have to be only ONE peak! So multiple peaks have to be merged
 */
public interface PatternExtractor {

    public List<IsotopePattern> extractPattern(Spectrum<Peak> spectrum);

}
