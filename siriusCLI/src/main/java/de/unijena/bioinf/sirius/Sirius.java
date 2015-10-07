/*
 *  This file is part of the SIRIUS library for analyzing MS and MS/MS data
 *
 *  Copyright (C) 2013-2015 Kai Dührkop
 *
 *  This library is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU Lesser General Public
 *  License as published by the Free Software Foundation; either
 *  version 2.1 of the License, or (at your option) any later version.
 *
 *  This library is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *  Lesser General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License along with SIRIUS.  If not, see <http://www.gnu.org/licenses/>.
 */
package de.unijena.bioinf.sirius;

import de.unijena.bioinf.ChemistryBase.chem.*;
import de.unijena.bioinf.ChemistryBase.chem.utils.ScoredMolecularFormula;
import de.unijena.bioinf.ChemistryBase.ms.*;
import de.unijena.bioinf.ChemistryBase.ms.ft.FTree;
import de.unijena.bioinf.ChemistryBase.ms.ft.TreeScoring;
import de.unijena.bioinf.ChemistryBase.ms.utils.Spectrums;
import de.unijena.bioinf.FragmentationTreeConstruction.computation.FragmentationPatternAnalysis;
import de.unijena.bioinf.FragmentationTreeConstruction.computation.MultipleTreeComputation;
import de.unijena.bioinf.FragmentationTreeConstruction.computation.TreeIterator;
import de.unijena.bioinf.FragmentationTreeConstruction.computation.scoring.TreeSizeScorer;
import de.unijena.bioinf.FragmentationTreeConstruction.model.DecompositionList;
import de.unijena.bioinf.FragmentationTreeConstruction.model.ProcessedInput;
import de.unijena.bioinf.IsotopePatternAnalysis.IsotopePattern;
import de.unijena.bioinf.IsotopePatternAnalysis.IsotopePatternAnalysis;
import de.unijena.bioinf.sirius.elementpred.ElementPrediction;

import java.io.IOException;
import java.util.*;

public class Sirius {

    public final static String VERSION_STRING = "Sirius 3.0.3";

    public final static String CITATION = "Sebastian Böcker and Florian Rasche\n" +
            "Towards de novo identification of metabolites by analyzing tandem mass spectra.\n" +
            "Bioinformatics, 24:I49-I55, 2008. Proc. of European Conference on Computational Biology (ECCB 2008).\n" +
            "\n" +
            "Kerstin Scheubert, Franziska Hufsky, Florian Rasche and Sebastian Böcker\n" +
            "Computing fragmentation trees from metabolite multiple mass spectrometry data.\n" +
            "In Proc. of Research in Computational Molecular Biology (RECOMB 2011), volume 6577 of Lect Notes Comput Sci, pages 377-391. Springer, Berlin, 2011.\n\n" +
            "Kai Dührkop and Sebastian Böcker\n" +
            "Fragmentation trees reloaded.\n" +
            "In Proc. of Research in Computational Molecular Biology (RECOMB 2015), volume 9029 of Lect Notes Comput Sci, pages 65-79. 2015.";

    private static final double MAX_TREESIZE_INCREASE = 3d;
    private static final double TREE_SIZE_INCREASE = 1d;
    private static final int MIN_NUMBER_OF_EXPLAINED_PEAKS = 15;
    private static final double MIN_EXPLAINED_INTENSITY = 0.7d;

    protected Profile profile;
    protected ElementPrediction elementPrediction;
    protected Progress progress;
    protected PeriodicTable table;
    protected boolean autoIonMode;


    public final static String ISOTOPE_SCORE = "isotope";

    public Sirius(String profileName) throws IOException {
        profile = new Profile(profileName);
        loadMeasurementProfile();
    }

    public Sirius() {
        try {
            profile = new Profile("default");
            loadMeasurementProfile();
            this.progress = new Progress.Quiet();
        } catch (IOException e) { // should be in classpath
            throw new RuntimeException(e);
        }
    }

    public Progress getProgress() {
        return progress;
    }

    public void setProgress(Progress progress) {
        this.progress = progress;
    }

    public FragmentationPatternAnalysis getMs2Analyzer() {
        return profile.fragmentationPatternAnalysis;
    }

    public IsotopePatternAnalysis getMs1Analyzer() {
        return profile.isotopePatternAnalysis;
    }

    private void loadMeasurementProfile() {
        this.table = PeriodicTable.getInstance();
        // make mutable
        profile.fragmentationPatternAnalysis.setDefaultProfile(new MutableMeasurementProfile(profile.fragmentationPatternAnalysis.getDefaultProfile()));
        profile.isotopePatternAnalysis.setDefaultProfile(new MutableMeasurementProfile(profile.isotopePatternAnalysis.getDefaultProfile()));
        this.elementPrediction = null;
        this.autoIonMode = false;
    }

    public ElementPrediction getElementPrediction() {
        return elementPrediction;
    }

    public void setElementPrediction(ElementPrediction elementPrediction) {
        this.elementPrediction = elementPrediction;
    }

    public boolean isAutoIonMode() {
        return autoIonMode;
    }

    public void setAutoIonMode(boolean autoIonMode) {
        this.autoIonMode = autoIonMode;
    }

    /**
     *
     * Identify the molecular formula of the measured compound using the provided MS and MSMS data
     *
     * @param uexperiment input data
     *
     * @return a list of identified molecular formulas together with their tree
     */
    public List<IdentificationResult> identify(Ms2Experiment uexperiment) {
        return identify(uexperiment, 5, true, IsotopePatternHandling.score, Collections.<MolecularFormula>emptySet());
    }

    /**
     *
     * Identify the molecular formula of the measured compound by combining an isotope pattern analysis on MS data with a fragmentation pattern analysis on MS/MS data
     *
     * @param uexperiment input data
     * @param numberOfCandidates number of candidates to output
     * @param recalibrating true if spectra should be recalibrated during tree computation
     * @param deisotope set this to 'omit' to ignore isotope pattern, 'filter' to use it for selecting molecular formula candidates or 'score' to rerank the candidates according to their isotope pattern
     * @param whiteList restrict the analysis to this subset of molecular formulas. If this set is empty, consider all possible molecular formulas
     * @return a list of identified molecular formulas together with their tree
     */
    public List<IdentificationResult> identify(Ms2Experiment uexperiment, int numberOfCandidates, boolean recalibrating, IsotopePatternHandling deisotope, Set<MolecularFormula> whiteList) {
        ProcessedInput pinput = profile.fragmentationPatternAnalysis.performValidation(uexperiment);
        final MutableMs2Experiment experiment = pinput.getExperimentInformation();
        // first check if MS data is present;
        final List<IsotopePattern> candidates = lookAtMs1(experiment, deisotope!=IsotopePatternHandling.omit);
        int maxNumberOfFormulas = 0;
        final HashMap<MolecularFormula, Double> isoFormulas = new HashMap<MolecularFormula, Double>();
        final double optIsoScore;
        if (candidates.size() > 0) {
            Collections.sort(candidates, new Comparator<IsotopePattern>() {
                @Override
                public int compare(IsotopePattern o1, IsotopePattern o2) {
                    return Double.compare(o2.getBestScore(),o1.getBestScore());
                }
            });
            final IsotopePattern pattern = candidates.get(0);
            optIsoScore = filterCandidateList(pattern, isoFormulas);
        } else optIsoScore = 0d;

        pinput = profile.fragmentationPatternAnalysis.preprocessing(pinput.getOriginalInput());

        if (isoFormulas.size() > 0 && optIsoScore>10) {
            maxNumberOfFormulas = isoFormulas.size();
        } else {
            maxNumberOfFormulas = pinput.getPeakAnnotationOrThrow(DecompositionList.class).get(pinput.getParentPeak()).getDecompositions().size();
        }

        if (whiteList!=null && !whiteList.isEmpty()) maxNumberOfFormulas = Math.min(maxNumberOfFormulas, whiteList.size());

        final int outputSize = Math.min(maxNumberOfFormulas, numberOfCandidates);
        final int computeNTrees = Math.max(5, outputSize);

        final TreeSizeScorer treeSizeScorer = FragmentationPatternAnalysis.getByClassName(TreeSizeScorer.class, profile.fragmentationPatternAnalysis.getFragmentPeakScorers());
        final double originalTreeSize = (treeSizeScorer!=null ? treeSizeScorer.getTreeSizeScore() : 0d);
        double modifiedTreeSizeScore = originalTreeSize;
        final double MAX_TREESIZE_SCORE = originalTreeSize+MAX_TREESIZE_INCREASE;

        final ArrayList<FTree> computedTrees = new ArrayList<FTree>();

        try {
            while (true) {
                MultipleTreeComputation trees = profile.fragmentationPatternAnalysis.computeTrees(pinput);
                trees = trees.inParallel(3);
                if (isoFormulas.size() > 0 && optIsoScore>10) {
                    trees = trees.onlyWith(isoFormulas.keySet());
                }
                if (whiteList.size() > 0) {
                    trees = trees.onlyWith(whiteList);
                }
                trees = trees.computeMaximal(computeNTrees).withoutRecalibration();

                final TreeSet<FTree> treeSet = new TreeSet<FTree>(TREE_SCORE_COMPARATOR);

                final TreeIterator iter = trees.iterator(true);
                progress.init(maxNumberOfFormulas);
                int counter=0;
                while (iter.hasNext()) {
                    final FTree tree = iter.next();
                    if (deisotope == IsotopePatternHandling.score) addIsoScore(isoFormulas, tree);

                    if (tree != null) {
                        treeSet.add(tree);
                        if (treeSet.size() > numberOfCandidates) treeSet.pollFirst();
                    }
                    if (iter.lastGraph()!=null)
                        progress.update(++counter, maxNumberOfFormulas, iter.lastGraph().getRoot().getChildren(0).getFormula().toString());
                }
                progress.finished();

                // check if at least one of the best N trees satisfies the tree-rejection-condition
                boolean satisfied = treeSizeScorer == null || modifiedTreeSizeScore >= MAX_TREESIZE_SCORE;
                if (!satisfied) {
                    final Iterator<FTree> treeIterator = treeSet.descendingIterator();
                    for (int k=0; k < computeNTrees; ++k) {
                        if (treeIterator.hasNext()) {
                            final FTree tree = treeIterator.next();
                            final double intensity = profile.fragmentationPatternAnalysis.getIntensityRatioOfExplainedPeaks(tree);
                            if (tree.numberOfVertices()>=MIN_NUMBER_OF_EXPLAINED_PEAKS || intensity >= MIN_EXPLAINED_INTENSITY) {
                                satisfied=true; break;
                            }
                        } else break;
                    }
                }
                if (satisfied) {
                    computedTrees.addAll(treeSet.descendingSet());
                    break;
                } else {
                    progress.info("Not enough peaks were explained. Repeat computation with less restricted constraints.");
                    modifiedTreeSizeScore += TREE_SIZE_INCREASE;
                    treeSizeScorer.setTreeSizeScore(modifiedTreeSizeScore);
                    computedTrees.clear();
                    // TODO!!!! find a smarter way to do this -_-
                    pinput = profile.fragmentationPatternAnalysis.preprocessing(experiment);
                }
            }

            if (recalibrating) {
                // now recalibrate the trees and recompute them another time...
                progress.info("recalibrate trees");
                progress.init(computedTrees.size());
                for (int k=0; k < computedTrees.size(); ++k) {
                    final FTree recalibratedTree = profile.fragmentationPatternAnalysis.recalibrate(computedTrees.get(k), true);
                    if (deisotope== IsotopePatternHandling.score) addIsoScore(isoFormulas, recalibratedTree);
                    computedTrees.set(k, recalibratedTree);
                    progress.update(k+1, computedTrees.size(), "recalibrate " + recalibratedTree.getRoot().getFormula().toString());
                }
                progress.finished();
            }

            Collections.sort(computedTrees, Collections.reverseOrder(TREE_SCORE_COMPARATOR));


            final ArrayList<IdentificationResult> list = new ArrayList<IdentificationResult>(outputSize);
            for (int k=0; k < Math.min(outputSize, computedTrees.size()); ++k) {
                final FTree tree = computedTrees.get(k);
                profile.fragmentationPatternAnalysis.recalculateScores(tree);
                list.add(new IdentificationResult(tree, k+1));
            }

            return list;
        } finally {
            treeSizeScorer.setTreeSizeScore(originalTreeSize);
        }
    }

    /**
     *
     * Identify the molecular formula of the measured compound as well as the ionization mode.
     * This method behaves like identify, but will try different ion modes and return the best trees over all different
     * ionizations. This method does not accept a whitelist, as for a neutral molecular formula candidate it is always possible to determine the
     * ionization mode.
     */
    public List<IdentificationResult> identifyPrecursorAndIonization(Ms2Experiment uexperiment, int numberOfCandidates, boolean recalibrating, IsotopePatternHandling deisotope) {
        ProcessedInput validatedInput = profile.fragmentationPatternAnalysis.performValidation(uexperiment);
        final MutableMs2Experiment experiment = validatedInput.getExperimentInformation();
        // first check if MS data is present;
        final List<IsotopePattern> candidates = lookAtMs1(experiment, deisotope!=IsotopePatternHandling.omit);
        int maxNumberOfFormulas = 0;
        final HashMap<MolecularFormula, Double> isoFormulas = new HashMap<MolecularFormula, Double>();
        final double optIsoScore;
        if (candidates.size() > 0) {
            Collections.sort(candidates, new Comparator<IsotopePattern>() {
                @Override
                public int compare(IsotopePattern o1, IsotopePattern o2) {
                    return Double.compare(o2.getBestScore(),o1.getBestScore());
                }
            });
            final IsotopePattern pattern = candidates.get(0);
            optIsoScore = filterCandidateList(pattern, isoFormulas);
        } else optIsoScore = 0d;

        final TreeSizeScorer treeSizeScorer = FragmentationPatternAnalysis.getByClassName(TreeSizeScorer.class, profile.fragmentationPatternAnalysis.getFragmentPeakScorers());
        final double originalTreeSize = (treeSizeScorer!=null ? treeSizeScorer.getTreeSizeScore() : 0d);
        double modifiedTreeSizeScore = originalTreeSize;
        final double MAX_TREESIZE_SCORE = originalTreeSize+MAX_TREESIZE_INCREASE;

        final ArrayList<FTree> computedTrees = new ArrayList<FTree>();

        final ArrayList<Ionization> possibleIonModes = new ArrayList<Ionization>();
        final PrecursorIonType vion = validatedInput.getExperimentInformation().getPrecursorIonType();
        for (Ionization ionMode : PeriodicTable.getInstance().getKnownIonModes(vion.getCharge())) {
            possibleIonModes.add(ionMode);
        }

        final TreeSet<FTree> treeSet = new TreeSet<FTree>(TREE_SCORE_COMPARATOR);

        try {
            while (true) {

                // try every ionization
                for (Ionization ionization : possibleIonModes) {

                    final PrecursorIonType ionType = (vion.isIonizationUnknown() ? PrecursorIonType.getPrecursorIonType(ionization) : vion);
                    experiment.setPrecursorIonType(ionType);
                    assert experiment.getMolecularFormula() == null;
                    experiment.setMoleculeNeutralMass(0d); // previous neutral mass is not correct anymore
                    final ProcessedInput pinput = profile.fragmentationPatternAnalysis.preprocessing(experiment.clone());
                    MultipleTreeComputation trees = profile.fragmentationPatternAnalysis.computeTrees(pinput);
                    trees = trees.inParallel(3);
                    if (isoFormulas.size() > 0 && optIsoScore>10) {
                        trees = trees.onlyWithIons(isoFormulas.keySet());
                    }
                    trees = trees.computeMaximal(numberOfCandidates).withoutRecalibration();

                    final TreeIterator iter = trees.iterator(true);
                    progress.init(maxNumberOfFormulas);
                    int counter=0;
                    while (iter.hasNext()) {
                        final FTree tree = iter.next();
                        if (deisotope == IsotopePatternHandling.score) addIsoScore(isoFormulas, tree);

                        if (tree != null) {
                            treeSet.add(tree);
                            if (treeSet.size() > numberOfCandidates) treeSet.pollFirst();
                        }
                        if (iter.lastGraph()!=null)
                            progress.update(++counter, maxNumberOfFormulas, iter.lastGraph().getRoot().getChildren(0).getFormula().toString() + " " + ionType.toString());
                    }
                    progress.finished();

                }

                // check if at least one of the best N trees satisfies the tree-rejection-condition
                boolean satisfied = treeSizeScorer == null || modifiedTreeSizeScore >= MAX_TREESIZE_SCORE;
                if (!satisfied) {
                    final Iterator<FTree> treeIterator = treeSet.descendingIterator();
                    for (int k=0; k < numberOfCandidates; ++k) {
                        if (treeIterator.hasNext()) {
                            final FTree tree = treeIterator.next();
                            final double intensity = profile.fragmentationPatternAnalysis.getIntensityRatioOfExplainedPeaks(tree);
                            if (tree.numberOfVertices()>=MIN_NUMBER_OF_EXPLAINED_PEAKS || intensity >= MIN_EXPLAINED_INTENSITY) {
                                satisfied=true; break;
                            }
                        } else break;
                    }
                }
                if (satisfied) {
                    computedTrees.addAll(treeSet.descendingSet());
                    break;
                } else {
                    progress.info("Not enough peaks were explained. Repeat computation with less restricted constraints.");
                    modifiedTreeSizeScore += TREE_SIZE_INCREASE;
                    treeSizeScorer.setTreeSizeScore(modifiedTreeSizeScore);
                    treeSet.clear();
                    computedTrees.clear();
                }
            }

            if (recalibrating) {
                // now recalibrate the trees and recompute them another time...
                progress.info("recalibrate trees");
                progress.init(computedTrees.size());
                for (int k=0; k < computedTrees.size(); ++k) {
                    final FTree recalibratedTree = profile.fragmentationPatternAnalysis.recalibrate(computedTrees.get(k), true);
                    if (deisotope== IsotopePatternHandling.score) addIsoScore(isoFormulas, recalibratedTree);
                    computedTrees.set(k, recalibratedTree);
                    progress.update(k+1, computedTrees.size(), "recalibrate " + recalibratedTree.getRoot().getFormula().toString());
                }
                progress.finished();
            }

            Collections.sort(computedTrees, Collections.reverseOrder(TREE_SCORE_COMPARATOR));


            final ArrayList<IdentificationResult> list = new ArrayList<IdentificationResult>(Math.min(numberOfCandidates, computedTrees.size()));
            for (int k=0; k < Math.min(numberOfCandidates, computedTrees.size()); ++k) {
                final FTree tree = computedTrees.get(k);
                profile.fragmentationPatternAnalysis.recalculateScores(tree);
                list.add(new IdentificationResult(tree, k+1));
            }

            return list;
        } finally {
            if (treeSizeScorer!=null) treeSizeScorer.setTreeSizeScore(originalTreeSize);
        }
    }

    public FormulaConstraints predictElements(Ms2Experiment experiment) {
        ProcessedInput pinput = getMs2Analyzer().performValidation(experiment);
        if (elementPrediction==null) return pinput.getMeasurementProfile().getFormulaConstraints();
        return elementPrediction.extendConstraints(pinput.getMeasurementProfile().getFormulaConstraints(), pinput.getExperimentInformation(), pinput.getMeasurementProfile());
    }

    /**
     * check MS spectrum. If an isotope pattern is found, check it's monoisotopic mass and update the ionmass field
     * if this field is null yet
     * If deisotope is set, start isotope pattern analysis
     * @return
     */
    protected List<IsotopePattern> lookAtMs1(MutableMs2Experiment experiment, boolean deisotope) {
        if (experiment.getIonMass()==0) {
            if (experiment.getMs1Spectra().size()==0)
                throw new RuntimeException("Please provide the parentmass of the measured compound");
            List<IsotopePattern> candidates = profile.isotopePatternAnalysis.deisotope(experiment, experiment.getIonMass());
            if (candidates.size() > 1) {
                // check if there is only one candidate with positive score
                IsotopePattern pattern = null;
                for (IsotopePattern pat : candidates) {
                    if (pat.getBestScore() >= 0) {
                        if (pattern!=null)
                            throw new RuntimeException("Please provide the parentmass of the measured compound");
                        pattern = pat;
                    }
                }
                candidates = Arrays.asList(pattern);
            }
            experiment.setIonMass(candidates.get(0).getMonoisotopicMass());
            return deisotope ? candidates : Collections.<IsotopePattern>emptyList();
        }
        return deisotope ? profile.isotopePatternAnalysis.deisotope(experiment, experiment.getIonMass()) : Collections.<IsotopePattern>emptyList();
    }

    protected void addIsoScore(HashMap<MolecularFormula, Double> isoFormulas, FTree tree) {
        final TreeScoring sc = tree.getAnnotationOrThrow(TreeScoring.class);
        if (isoFormulas.get(tree.getRoot().getFormula())!=null) {
            sc.addAdditionalScore(ISOTOPE_SCORE, isoFormulas.get(tree.getRoot().getFormula()));
        }
    }

    public IdentificationResult compute(Ms2Experiment experiment, MolecularFormula formula) {
        return compute(experiment, formula, true);
    }

    /**
     * Compute a fragmentation tree for the given MS/MS data using the given neutral molecular formula as explanation for the measured compound
     * @param experiment input data
     * @param formula neutral molecular formula of the measured compound
     * @param recalibrating true if spectra should be recalibrated during tree computation
     * @return A single instance of IdentificationResult containing the computed fragmentation tree
     */
    public IdentificationResult compute(Ms2Experiment experiment, MolecularFormula formula, boolean recalibrating) {
        ProcessedInput pinput = profile.fragmentationPatternAnalysis.preprocessing(experiment);
        final TreeSizeScorer treeSizeScorer = FragmentationPatternAnalysis.getByClassName(TreeSizeScorer.class, profile.fragmentationPatternAnalysis.getFragmentPeakScorers());
        final double originalTreeSize = (treeSizeScorer!=null ? treeSizeScorer.getTreeSizeScore() : 0d);
        double modifiedTreeSizeScore = originalTreeSize;
        final double MAX_TREESIZE_SCORE = originalTreeSize+MAX_TREESIZE_INCREASE;
        FTree tree = null;
        try {
            while (true) {
                tree = profile.fragmentationPatternAnalysis.computeTrees(pinput).withRecalibration(recalibrating).onlyWith(Arrays.asList(formula)).optimalTree();
                if (tree==null) return new IdentificationResult(null, 0);
                final double intensity = profile.fragmentationPatternAnalysis.getIntensityRatioOfExplainablePeaks(tree);
                if (treeSizeScorer == null || modifiedTreeSizeScore >= MAX_TREESIZE_SCORE || tree.numberOfVertices()>=MIN_NUMBER_OF_EXPLAINED_PEAKS || intensity >= MIN_EXPLAINED_INTENSITY) {
                    break;
                } else {
                    modifiedTreeSizeScore += TREE_SIZE_INCREASE;
                    treeSizeScorer.setTreeSizeScore(modifiedTreeSizeScore);
                    // TODO!!!! find a smarter way to do this -_-
                    pinput = profile.fragmentationPatternAnalysis.preprocessing(experiment);
                }
            }
            profile.fragmentationPatternAnalysis.recalculateScores(tree);
        } finally {
            treeSizeScorer.setTreeSizeScore(originalTreeSize);
        }
        return new IdentificationResult(tree, 0);
    }


    /*
        DATA STRUCTURES API CALLS
     */

    /**
     * Wraps an array of m/z values and and array of intensity values into a spectrum object that can be used by the SIRIUS library. The resulting spectrum is a lightweight view on the array, so changes in the array are reflected in the spectrum. The spectrum object itself is immutable.
     * @param mz mass to charge ratios
     * @param intensities intensity values. Can be normalized or absolute - SIRIUS will performNormalization them itself at later point
     * @return view on the arrays implementing the Spectrum interface
     */
    public Spectrum<Peak> wrapSpectrum(double[] mz, double[] intensities) {
        return Spectrums.wrap(mz, intensities);
    }

    /**
     * Lookup the symbol in the periodic table and returns the corresponding Element object or null if no element with this symbol exists.
     * @param symbol symbol of the element, e.g. H for hydrogen or Cl for chlorine
     * @return instance of Element class
     */
    public Element getElement(String symbol) {
        return table.getByName(symbol);
    }

    /**
     * Lookup the ionization name and returns the corresponding ionization object or null if no ionization with this name is registered. The name of an ionization has the syntax [M+ADDUCT]CHARGE, for example [M+H]+ or [M-H]-.
     *
     * Deprecated: Ionization is now for the ion-mode (protonation or deprotonation, number of charges, ...). Use
     *   getPrecursorIonType to get a PrecursorIonType object that contains adducts and in-source fragmentation as well as
     *   the ion mode of the precursor ion
     *
     * @param name name of the ionization
     * @return adduct object
     */
    @Deprecated
    public Ionization getIonization(String name) {
        return getPrecursorIonType(name).getIonization();
    }

    /**
     * Lookup the ionization name and returns the corresponding ionization object or null if no ionization with this name is registered. The name of an ionization has the syntax [M+ADDUCT]CHARGE, for example [M+H]+ or [M-H]-.
     * @param name name of the ionization
     * @return adduct object
     */
    public PrecursorIonType getPrecursorIonType(String name) {
        return table.ionByName(name);
    }


    /**
     * Charges are subclasses of Ionization. So they can be used everywhere as replacement for ionizations. A charge is very similar to the [M]+ and [M]- ionizations. However, the difference is that [M]+ describes an intrinsically charged compound where the Charge +1 describes an compound with unknown adduct.
     * @param charge either 1 for positive or -1 for negative charges.
     * @return
     */
    public Charge getCharge(int charge) {
        if (charge != -1 && charge != 1)
            throw new IllegalArgumentException("SIRIUS does not support multiple charged compounds");
        return new Charge(charge);
    }

    /**
     * Creates a Deviation object that describes a mass deviation as maximum of a relative term (in ppm) and an absolute term. Usually, mass accuracy is given as relative term in ppm, as measurement errors increase with higher masses. However, for very small compounds (and fragments!) these relative values might overestimate the mass accurary. Therefore, an absolute value have to be given.
     * @param ppm mass deviation as relative value (in ppm)
     * @param abs mass deviation as absolute value (m/z)
     * @return Deviation object
     */
    public Deviation getMassDeviation(int ppm, double abs) {
        return new Deviation(ppm, abs);
    }

    /**
     * Creates a Deviation object with the given relative term. The absolute term is implicitly given by applying the relative term on m/z 100.
     * @param ppm
     * @return
     */
    public Deviation getMassDeviation(int ppm) {
        return new Deviation(ppm);
    }

    /**
     * Parses a molecular formula from the given string
     * @param f molecular formula (e.g. in Hill notation)
     * @return immutable molecular formula object
     */
    public MolecularFormula parseFormula(String f) {
        return MolecularFormula.parse(f);
    }

    /**
     * Creates a Ms2Experiment object from the given MS and MS/MS spectra. A Ms2Experiment is NOT a single run or measurement, but a measurement of a concrete compound. So a MS spectrum might contain several Ms2Experiments. However, each MS/MS spectrum should have on precursor or parent mass. All MS/MS spectra with the same precursor together with the MS spectrum containing this precursor peak can be seen as one Ms2Experiment.
     * @param formula neutral molecular formula of the compound
     * @param ion ionization mode (can be an instance of Charge if the exact adduct is unknown)
     * @param ms1 the MS spectrum containing the isotope pattern of the measured compound. Might be null
     * @param ms2 a list of MS/MS spectra containing the fragmentation pattern of the measured compound
     * @return a MS2Experiment instance, ready to be analyzed by SIRIUS
     */
    public Ms2Experiment getMs2Experiment(MolecularFormula formula, Ionization ion, Spectrum<Peak> ms1, Spectrum... ms2) {
        return getMs2Experiment(formula, PrecursorIonType.getPrecursorIonType(ion), ms1, ms2);
    }

    /**
     * Creates a Ms2Experiment object from the given MS and MS/MS spectra. A Ms2Experiment is NOT a single run or measurement, but a measurement of a concrete compound. So a MS spectrum might contain several Ms2Experiments. However, each MS/MS spectrum should have on precursor or parent mass. All MS/MS spectra with the same precursor together with the MS spectrum containing this precursor peak can be seen as one Ms2Experiment.
     * @param formula neutral molecular formula of the compound
     * @param ion PrecursorIonType (contains ion mode as well as adducts and in-source fragmentations of the precursor ion)
     * @param ms1 the MS spectrum containing the isotope pattern of the measured compound. Might be null
     * @param ms2 a list of MS/MS spectra containing the fragmentation pattern of the measured compound
     * @return a MS2Experiment instance, ready to be analyzed by SIRIUS
     */
    public Ms2Experiment getMs2Experiment(MolecularFormula formula, PrecursorIonType ion, Spectrum<Peak> ms1, Spectrum... ms2) {
        final MutableMs2Experiment exp = (MutableMs2Experiment)getMs2Experiment(ion.neutralMassToPrecursorMass(formula.getMass()), ion, ms1, ms2);
        exp.setMolecularFormula(formula);
        return exp;
    }

    /**
     * Creates a Ms2Experiment object from the given MS and MS/MS spectra. A Ms2Experiment is NOT a single run or measurement, but a measurement of a concrete compound. So a MS spectrum might contain several Ms2Experiments. However, each MS/MS spectrum should have on precursor or parent mass. All MS/MS spectra with the same precursor together with the MS spectrum containing this precursor peak can be seen as one Ms2Experiment.
     * @param parentMass the measured mass of the precursor ion. Can be either the MS peak or (if present) a MS/MS peak
     * @param ion PrecursorIonType (contains ion mode as well as adducts and in-source fragmentations of the precursor ion)
     * @param ms1 the MS spectrum containing the isotope pattern of the measured compound. Might be null
     * @param ms2 a list of MS/MS spectra containing the fragmentation pattern of the measured compound
     * @return a MS2Experiment instance, ready to be analyzed by SIRIUS
     */
    public Ms2Experiment getMs2Experiment(double parentMass, PrecursorIonType ion, Spectrum<Peak> ms1, Spectrum... ms2) {
        final MutableMs2Experiment mexp = new MutableMs2Experiment();
        mexp.setPrecursorIonType(ion);
        for (Spectrum<Peak> spec : ms2) {
            mexp.getMs2Spectra().add(new MutableMs2Spectrum(spec, mexp.getIonMass(), CollisionEnergy.none(), 2));
        }
        return mexp;
    }

    /**
     * Creates a Ms2Experiment object from the given MS and MS/MS spectra. A Ms2Experiment is NOT a single run or measurement, but a measurement of a concrete compound. So a MS spectrum might contain several Ms2Experiments. However, each MS/MS spectrum should have on precursor or parent mass. All MS/MS spectra with the same precursor together with the MS spectrum containing this precursor peak can be seen as one Ms2Experiment.
     * @param parentMass the measured mass of the precursor ion. Can be either the MS peak or (if present) a MS/MS peak
     * @param ion ionization mode (can be an instance of Charge if the exact adduct is unknown)
     * @param ms1 the MS spectrum containing the isotope pattern of the measured compound. Might be null
     * @param ms2 a list of MS/MS spectra containing the fragmentation pattern of the measured compound
     * @return a MS2Experiment instance, ready to be analyzed by SIRIUS
     */
    public Ms2Experiment getMs2Experiment(double parentMass, Ionization ion, Spectrum<Peak> ms1, Spectrum... ms2) {
        return getMs2Experiment(parentMass, PrecursorIonType.getPrecursorIonType(ion), ms1, ms2);
    }

    /**
     * Formula Constraints consist of a chemical alphabet (a subset of the periodic table, determining which elements might occur in the measured compounds) and upperbounds for each of this elements. A formula constraint can be given like a molecular formula. Upperbounds are written in square brackets or omitted, if any number of this element should be allowed.
     * @param constraints string representation of the constraint, e.g. "CHNOP[5]S[20]"
     * @return formula constraint object
     */
    public FormulaConstraints getFormulaConstraints(String constraints) {
        return new FormulaConstraints(constraints);
    }

    /**
     * Decomposes a mass and return a list of all molecular formulas which ionized mass is near the measured mass.
     * The maximal distance between the neutral mass of the measured ion and the theoretical mass of the decomposed formula depends on the chosen profile. For qtof it is 10 ppm, for Orbitrap and FTICR it is 5 ppm.
     * @param mass mass of the measured ion
     * @param ion ionization mode (might be a Charge, in which case the decomposer will enumerate the ion formulas instead of the neutral formulas)
     * @param constr the formula constraints, defining the allowed elements and their upperbounds
     * @return list of molecular formulas which theoretical ion mass is near the given mass
     */
    public List<MolecularFormula> decompose(double mass, Ionization ion, FormulaConstraints constr) {
        return decompose(mass, ion, constr, getMs2Analyzer().getDefaultProfile().getAllowedMassDeviation());
    }

    /**
     * Decomposes a mass and return a list of all molecular formulas which ionized mass is near the measured mass
     * @param mass mass of the measured ion
     * @param ion ionization mode (might be a Charge, in which case the decomposer will enumerate the ion formulas instead of the neutral formulas)
     * @param constr the formula constraints, defining the allowed elements and their upperbounds
     * @param dev the allowed mass deviation of the measured ion from the theoretical ion masses
     * @return
     */
    public List<MolecularFormula> decompose(double mass, Ionization ion, FormulaConstraints constr, Deviation dev) {
        return getMs2Analyzer().getDecomposerFor(constr.getChemicalAlphabet()).decomposeToFormulas(ion.subtractFromMass(mass), dev, constr);
    }

    /**
     * Simulates an isotope pattern for the given molecular formula and the chosen ionization
     * @param compound neutral molecular formula
     * @param ion ionization mode (might be a Charge)
     * @return spectrum containing the theoretical isotope pattern of this compound
     */
    public Spectrum<Peak> simulateIsotopePattern(MolecularFormula compound, Ionization ion) {
        return getMs1Analyzer().getPatternGenerator().simulatePattern(compound, ion);
    }





    private double filterCandidateList(IsotopePattern candidate, HashMap<MolecularFormula, Double> formulas) {
        if (candidate.getCandidates().size()==0) return 0d;
        if (candidate.getBestScore() <= 0) return 0d;
        final double optscore = candidate.getBestScore();
        final ArrayList<ScoredMolecularFormula> xs = new ArrayList<ScoredMolecularFormula>(candidate.getCandidates());
        Collections.sort(xs, Collections.reverseOrder());
        int n = 1;
        for (; n < xs.size(); ++n) {
            final double score = xs.get(n).getScore();
            final double prev = xs.get(n-1).getScore();
            if (score <= 0 || score/optscore < 0.666 || score/prev < 0.5) break;
        }
        for (int i=0; i < n; ++i) formulas.put(xs.get(i).getFormula(), xs.get(i).getScore());
        return optscore;
    }

    private static Comparator<FTree> TREE_SCORE_COMPARATOR = new Comparator<FTree>() {
        @Override
        public int compare(FTree o1, FTree o2) {
            return Double.compare(o1.getAnnotationOrThrow(TreeScoring.class).getOverallScore(), o2.getAnnotationOrThrow(TreeScoring.class).getOverallScore());
        }
    };
}
