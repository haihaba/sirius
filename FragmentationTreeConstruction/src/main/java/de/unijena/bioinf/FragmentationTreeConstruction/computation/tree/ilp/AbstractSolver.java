package de.unijena.bioinf.FragmentationTreeConstruction.computation.tree.ilp;

import com.google.common.collect.BiMap;
import de.unijena.bioinf.ChemistryBase.chem.Ionization;
import de.unijena.bioinf.ChemistryBase.ms.ft.*;
import de.unijena.bioinf.FragmentationTreeConstruction.computation.tree.TreeBuilder;
import de.unijena.bioinf.FragmentationTreeConstruction.model.ProcessedInput;
import de.unijena.bioinf.FragmentationTreeConstruction.model.TreeScoring;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Created by Spectar on 13.11.2014.
 */
abstract public class AbstractSolver {

    final static int DO_NOTHING = 0;
    final static int SHALL_RETURN_NULL = 1;

    protected boolean built;

    protected final FGraph graph;
    protected List<Loss> losses;
    protected final int[] edgeIds; // contains variable indices (after 'computeoffsets')
    protected final int[] edgeOffsets; // contains: the first index j of edges starting from a given vertex i

    protected final ProcessedInput input;
    protected final TreeBuilder feasibleSolver;

    protected final double LP_LOWERBOUND;
    protected final int LP_TIMELIMIT;
    protected final int LP_NUM_OF_VARIABLES;
    protected final int LP_NUM_OF_VERTICES;


    ////////////////////////
    //--- CONSTRUCTORS ---//
    ////////////////////////


    /**
     * Minimal constructor
     * - initiate solver with given graph
     * - lower bound will be negative infinity
     * - no maximum computation time will be set
     * @param graph
     */
    public AbstractSolver(FGraph graph)
    {
        this(graph, null, Double.NEGATIVE_INFINITY, null, -1);
    }


    /**
     * optimal constructor
     * - initiate solver with given graph
     * - initiate solver with given lower bound
     * - no maximum computation time will be set
     * @param graph
     * @param lowerbound
     */
    public AbstractSolver(FGraph graph, double lowerbound)
    {
        this(graph, null, lowerbound, null, -1);
    }


    /**
     * Maximum constructor. May be used to test the correctness of any implemented solver
     *
     * @param graph
     * @param input
     * @param lowerbound
     * @param feasibleSolver
     * @param timeLimit
     */
    protected AbstractSolver(FGraph graph, ProcessedInput input, double lowerbound, TreeBuilder feasibleSolver, int timeLimit)
    {
        if (graph == null) throw new NullPointerException("Cannot solve graph: graph is NULL!");

        this.graph = graph;
        this.edgeIds = new int[graph.numberOfEdges()];
        this.edgeOffsets = new int[graph.numberOfVertices()];

        this.LP_LOWERBOUND = lowerbound;
        this.LP_TIMELIMIT = (timeLimit >= 0) ? timeLimit : 0;

        this.input = input;
        this.feasibleSolver = feasibleSolver;

        this.LP_NUM_OF_VERTICES = graph.numberOfVertices();
        this.LP_NUM_OF_VARIABLES = this.losses.size();

        this.built = false;
    }


    /////////////////////
    ///--- METHODS ---///
    /////////////////////

    /**
     * - this class should be implemented through abstract sub methods
     * - model.update() like used within the gurobi solver may be used within one of those, if necessary
     */
    public void build() {
        try {
            computeOffsets();
            assert (edgeOffsets == null && (edgeOffsets.length != 0 || losses.size() == 0)) : "Edge edgeOffsets were not calculated?!";

            if (feasibleSolver != null) {
                final FTree presolvedTree = feasibleSolver.buildTree(input, graph, LP_LOWERBOUND);
                defineVariablesWithStartValues(presolvedTree);
            } else {
                defineVariables();
            }

            setConstraints();
            applyLowerBounds();
            setObjective();
            built = true;
        } catch (Exception e) {
            throw new RuntimeException(String.valueOf(e.getMessage()), e);
        }
    }


    /**
     * - edgeOffsets will be used to access edges more efficiently
     * for each constraint i in array 'edgeOffsets': edgeOffsets[i] is the first index, where the constraint i is located
     * (inside 'var' and 'coefs')
     * Additionally, a new loss array will be computed
     */
    final void computeOffsets() {

        for (int k = 1; k < edgeOffsets.length; ++k)
            edgeOffsets[k] = edgeOffsets[k - 1] + graph.getFragmentAt(k - 1).getOutDegree();

        /*
         * for each edge: give it some unique id based on its source vertex id and its offset
         * therefor, the i-th edge of some vertex u will have the id: edgeOffsets[u] + i - 1, if i=1 is the first edge.
         * That way, 'edgeIds' is already sorted by source edge id's! An in O(E) time
          */
        ArrayList<Loss> newLossArr = new ArrayList<Loss>(this.losses.size()+1);

        for (int k = 0; k < losses.size(); ++k) {
            final int u = losses.get(k).getSource().getVertexId();
            edgeIds[edgeOffsets[u]++] = k;
            newLossArr.set(edgeOffsets[u]-1, losses.get(k)); // TODO: check, if that is necessary!
        }

        this.losses = newLossArr;

        // by using the loop-code above -> edgeOffsets[k] = 2*OutEdgesOf(k), so subtract that 2 away
        for (int k = 0; k < edgeOffsets.length; ++k)
            edgeOffsets[k] -= graph.getFragmentAt(k).getOutDegree();
        //TODO: optimize: edgeOffsets[k] /= 2;
    }


    //-- Methods to initiate the solver
    //-- Exception types may be override within subclasses, if needed

    protected void setConstraints() throws Exception {
        setTreeConstraint();
        setColorConstraint();
        setMinimalTreeSizeConstraint();
    }


    /**
     * Solve the optimal colorful subtree problem, using the chosen solver
     * Need constraints, variables, etc. to be set up
     * @return
     */
    public FTree solve() {
        try {
            if(!this.built)
                build();

            // pre-optimization, if needed (e.g. lower bounds)
            int signal = preBuildSolution();
            if(signal == AbstractSolver.SHALL_RETURN_NULL)
                return null;

            final FTree TREE = buildSolution();

            if(!isComputationCorrect(TREE, this.graph))
                throw new RuntimeException("Can't find a feasible solution: Solution is buggy");

            // free any memory, if necessary
            signal = pastBuildSolution();
            if(signal == AbstractSolver.SHALL_RETURN_NULL)
                return null;

            return TREE;
        } catch (Exception e) {
            throw new RuntimeException(String.valueOf(e.getMessage()), e);
        }
    }


    // functions used within 'build'
    abstract protected void defineVariables() throws Exception;
    abstract protected void defineVariablesWithStartValues( FTree presolvedTree) throws Exception;
    abstract protected void applyLowerBounds() throws Exception;

    // functions used within 'setConstrains'
    abstract protected void setTreeConstraint() throws Exception;
    abstract protected void setColorConstraint() throws Exception;
    abstract protected void setMinimalTreeSizeConstraint() throws Exception;

    // functions used within 'solve'
    abstract protected void setObjective() throws Exception;
    abstract protected int preBuildSolution() throws Exception;
    abstract protected int pastBuildSolution() throws Exception;
    abstract protected FTree buildSolution() throws Exception;



    ///////////////////////////
    ///--- CLASS-METHODS ---///
    ///////////////////////////





    protected static FTree newTree(FGraph graph, FTree tree, double rootScore) {
        return newTree(graph, tree, rootScore, rootScore);
    }

    protected static FTree newTree(FGraph graph, FTree tree, double rootScore, double scoring) {
        tree.addAnnotation(ProcessedInput.class, graph.getAnnotationOrThrow(ProcessedInput.class));
        tree.addAnnotation(Ionization.class, graph.getAnnotationOrThrow(Ionization.class));
        final TreeScoring treeScoring = new TreeScoring();
        tree.addAnnotation(TreeScoring.class, treeScoring);
        treeScoring.setOverallScore(scoring);
        treeScoring.setRootScore(rootScore);
        for (Map.Entry<Class<Object>, Object> entry : graph.getAnnotations().entrySet()) {
            tree.setAnnotation(entry.getKey(), entry.getValue());
        }
        if (graph.numberOfVertices() <= 2) {
            final Fragment graphVertex = graph.getFragmentAt(1);
            final Fragment treeVertex = tree.getFragmentAt(0);
            for (FragmentAnnotation<Object> x : graph.getFragmentAnnotations()) {
                tree.addFragmentAnnotation(x.getAnnotationType()).set(treeVertex, x.get(graphVertex));
            }
            for (LossAnnotation<Object> x : graph.getLossAnnotations()) {
                tree.addLossAnnotation(x.getAnnotationType()).set(treeVertex.getIncomingEdge(), x.get(graphVertex.getIncomingEdge()));
            }
        }
        return tree;
    }

    /**
     * Check, whether or not the given tree 'tree' is the optimal solution for the optimal colorful
     * subtree problem of the given graph 'graph'
     * @param tree
     * @param graph
     * @return
     */
    protected static boolean isComputationCorrect(FTree tree, FGraph graph) {
        double score = tree.getAnnotationOrThrow(TreeScoring.class).getOverallScore();
        final BiMap<Fragment, Fragment> fragmentMap = FTree.createFragmentMapping(tree, graph);
        for (Map.Entry<Fragment, Fragment> e : fragmentMap.entrySet()) {
            final Fragment t = e.getKey();
            final Fragment g = e.getValue();
            if (g.getParent().isRoot()) {
                score -= g.getIncomingEdge().getWeight();
            } else {
                final Loss in = e.getKey().getIncomingEdge();
                for (int k = 0; k < g.getInDegree(); ++k)
                    if (in.getSource().getFormula().equals(g.getIncomingEdge(k).getSource().getFormula())) {
                        score -= g.getIncomingEdge(k).getWeight();
                    }
            }
        }
        return Math.abs(score) < 1e-9d;
    }


    protected static class Stackitem {
        protected final Fragment treeNode;
        protected final Fragment graphNode;

        protected Stackitem(Fragment treeNode, Fragment graphNode) {
            this.treeNode = treeNode;
            this.graphNode = graphNode;
        }
    }
}
