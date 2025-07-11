/*
 * Copyright (C) 2010-2018 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 *
 * This file is part of EvoSuite.
 *
 * EvoSuite is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published
 * by the Free Software Foundation, either version 3.0 of the License, or
 * (at your option) any later version.
 *
 * EvoSuite is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with EvoSuite. If not, see <http://www.gnu.org/licenses/>.
 */
package org.evosuite.ga.metaheuristics.mosa;

import org.evosuite.Properties;
import org.evosuite.coverage.CoverageCriteriaAnalyzer;
import org.evosuite.ga.ChromosomeFactory;
import org.evosuite.ga.FitnessFunction;
import org.evosuite.ga.archive.Archive;
import org.evosuite.ga.comparators.OnlyCrowdingComparator;
import org.evosuite.ga.metaheuristics.mosa.structural.MultiCriteriaManager;
import org.evosuite.ga.operators.ranking.CrowdingDistance;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestFitnessFunction;
import org.evosuite.testsuite.TestSuiteChromosome;
import org.evosuite.utils.LoggingUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Implementation of the DynaMOSA (Many Objective Sorting Algorithm) described
 * in the paper
 * "Automated Test Case Generation as a Many-Objective Optimisation Problem with
 * Dynamic Selection
 * of the Targets".
 *
 * @author Annibale Panichella, Fitsum M. Kifetew, Paolo Tonella
 */
public class DynaMOSA extends AbstractMOSA {

    private static final long serialVersionUID = 146182080947267628L;

    private static final Logger logger = LoggerFactory.getLogger(DynaMOSA.class);

    /**
     * Manager to determine the test goals to consider at each generation
     */
    protected MultiCriteriaManager goalsManager = null;

    protected CrowdingDistance<TestChromosome> distance = new CrowdingDistance<>();

    /**
     * Constructor based on the abstract class {@link AbstractMOSA}.
     *
     * @param factory
     */
    public DynaMOSA(ChromosomeFactory<TestChromosome> factory) {
        super(factory);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void evolve() {
        // Generate offspring, compute their fitness, update the archive and coverage
        // goals.
        List<TestChromosome> offspringPopulation = this.breedNextGeneration();

        // Create the union of parents and offspring
        List<TestChromosome> union = new ArrayList<>(this.population.size() + offspringPopulation.size());
        union.addAll(this.population);
        union.addAll(offspringPopulation);

        // Ranking the union
        logger.debug("Union Size = {}", union.size());

        // Ranking the union using the best rank algorithm (modified version of the non
        // dominated
        // sorting algorithm)
        this.rankingFunction.computeRankingAssignment(union, this.goalsManager.getCurrentGoals());

        // let's form the next population using "preference sorting and non-dominated
        // sorting" on the
        // updated set of goals
        int remain = Math.max(Properties.POPULATION, this.rankingFunction.getSubfront(0).size());
        int index = 0;
        this.population.clear();

        // Log the offspring population
        logPopulation("offspring population", offspringPopulation);

        // Obtain the first front
        List<TestChromosome> front = this.rankingFunction.getSubfront(index);

        // Successively iterate through the fronts (starting with the first
        // non-dominated front)
        // and insert their members into the population for the next generation. This is
        // done until
        // all fronts have been processed or we hit a front that is too big to fit into
        // the next
        // population as a whole.
        while ((remain > 0) && (remain >= front.size()) && !front.isEmpty()) {
            // Assign crowding distance to individuals
            this.distance.fastEpsilonDominanceAssignment(front, this.goalsManager.getCurrentGoals());

            // Add the individuals of this front
            this.population.addAll(front);

            // Decrement remain
            remain = remain - front.size();

            // Obtain the next front
            index++;
            if (remain > 0) {
                front = this.rankingFunction.getSubfront(index);
            }
        }

        // In case the population for the next generation has not been filled up
        // completely yet,
        // we insert the best individuals from the current front (the one that was too
        // big to fit
        // entirely) until there are no more free places left. To this end, and in an
        // effort to
        // promote diversity, we consider those individuals with a higher crowding
        // distance as
        // being better.
        if (remain > 0 && !front.isEmpty()) { // front contains individuals to insert
            this.distance.fastEpsilonDominanceAssignment(front, this.goalsManager.getCurrentGoals());
            front.sort(new OnlyCrowdingComparator<>());
            for (int k = 0; k < remain; k++) {
                this.population.add(front.get(k));
            }
        }

        // logger.debug("N. fronts = {}", ranking.getNumberOfSubfronts());
        // logger.debug("1* front size = {}", ranking.getSubfront(0).size());
        logger.debug("Covered goals = {}", goalsManager.getCoveredGoals().size());
        logger.debug("Current goals = {}", goalsManager.getCurrentGoals().size());
        logger.debug("Uncovered goals = {}", goalsManager.getUncoveredGoals().size());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void generateSolution() {

        LoggingUtils.getEvoLogger().info("\n--- START OF SECTION BEFORE ITERATION 0 ---\n");

        logger.debug("executing generateSolution function");

        // Set up the targets to cover, which are initially free of any control
        // dependencies.
        // We are trying to optimize for multiple targets at the same time.
        this.goalsManager = new MultiCriteriaManager(this.fitnessFunctions);

        LoggingUtils.getEvoLogger().info("* Initial Number of Goals in DynaMOSA = " +
                this.goalsManager.getCurrentGoals().size() + " / " + this.getTotalNumberOfGoals());

        logger.debug("Initial Number of Goals = " + this.goalsManager.getCurrentGoals().size());

        if (this.population.isEmpty()) {
            // Initialize the population by creating solutions at random.
            this.initializePopulation();
        }

        // Compute the fitness for each population member, update the coverage
        // information and the
        // set of goals to cover. Finally, update the archive.
        // this.calculateFitness(); // Not required, already done by
        // this.initializePopulation();

        // Calculate dominance ranks and crowding distance. This is required to decide
        // which
        // individuals should be used for mutation and crossover in the first iteration
        // of the main
        // search loop.
        this.rankingFunction.computeRankingAssignment(this.population, this.goalsManager.getCurrentGoals());
        for (int i = 0; i < this.rankingFunction.getNumberOfSubfronts(); i++) {
            this.distance.fastEpsilonDominanceAssignment(this.rankingFunction.getSubfront(i),
                    this.goalsManager.getCurrentGoals());
        }


        //  HERE IT WOULD PROBABLY BE POSSIBLE TO LOG THE COVERAGE OF THE INITIAL POPULATION AND THE GOALS!!
        logInitialGoals();
        logInitialCoverage();

        // Evolve the population generation by generation until all gaols have been
        // covered or the
        // search budget has been consumed.
        // while (!isFinished() && this.goalsManager.getUncoveredGoals().size() > 0) {

        LoggingUtils.getEvoLogger().info("\n--- END OF SECTION BEFORE ITERATION 0 ---\n");

        for (int i = 0; i < 500; ++i) {
            this.evolve();
            logIteration();

            this.currentIteration++;
            this.notifyIteration();
        }

        this.notifySearchFinished();
    }

    /**
     * Calculates the fitness for the given individual. Also updates the list of
     * targets to cover,
     * as well as the population of best solutions in the archive.
     *
     * @param c the chromosome whose fitness to compute
     */
    @Override
    protected void calculateFitness(TestChromosome c) {
        if (!isFinished()) {
            // this also updates the archive and the targets
            this.goalsManager.calculateFitness(c, this);
            this.notifyEvaluation(c);
        }
    }

    @Override
    public List<? extends FitnessFunction<TestChromosome>> getFitnessFunctions() {
        List<TestFitnessFunction> testFitnessFunctions = new ArrayList<>(goalsManager.getCoveredGoals());
        testFitnessFunctions.addAll(goalsManager.getUncoveredGoals());
        return testFitnessFunctions;
    }

    public void logIteration() {
        LoggingUtils.getEvoLogger().info("\n--- START OF ITERATION "+ this.currentIteration + " ---\n");

        logPopulation("population", this.population);
        logGoals();
        logArchive();
        logCoverage();

        LoggingUtils.getEvoLogger().info("\n--- END OF ITERATION "+ this.currentIteration + " ---\n");

    }

    public void logPopulation(String name, List<TestChromosome> population) {

        LoggingUtils.getEvoLogger().info("\n--- START OF POPULATION SECTION FOR ITERATION "+ this.currentIteration + " ---\n");


        StringBuilder currentPopulation = new StringBuilder(String.format("\n\"%s\": {", name));
        currentPopulation.append(String.format("\"iteration\": %d, individuals: %s\n", this.currentIteration,
                populationLogString(population)));
        currentPopulation.append(" }");
        LoggingUtils.getEvoLogger().info(currentPopulation.toString());

        LoggingUtils.getEvoLogger().info("\n--- END OF POPULATION SECTION FOR ITERATION "+ this.currentIteration + " ---\n");
    }

    private void appendGoals(StringBuilder builder, Set<TestFitnessFunction> goals) {
        builder.append(" [\n");
        for (TestFitnessFunction tff : goals) {
            builder.append(String.format("\n\"%s\",", tff.toString()));
            //builder.append(String.format("\nGoalName: \"%s\" GoalType: \"%s\",", tff.toString(), tff.getClassName()));
        }
        builder.deleteCharAt(builder.length() - 1);
        builder.append("\n],");
    }


    public void logGoals() {

        LoggingUtils.getEvoLogger().info("\n--- START OF GOALS SECTION FOR ITERATION "+ this.currentIteration + " ---\n");


        StringBuilder goalsstr = new StringBuilder();
        goalsstr.append(String.format(
                "\n\"Goals\": { \"iteration\": %d, \"uncovered\": %d, \"covered\": %d, ",
                this.currentIteration, this.goalsManager.getUncoveredGoals().size(),
                this.goalsManager.getCoveredGoals().size()));

        goalsstr.append(String.format("\"uncovered targets\": "));
        appendGoals(goalsstr, this.goalsManager.getUncoveredGoals());

        goalsstr.append(String.format("\n\"covered targets\": "));
        appendGoals(goalsstr, this.goalsManager.getCoveredGoals());

        goalsstr.append("\n\"current targets\": [");
        appendGoals(goalsstr, this.goalsManager.getCurrentGoals());
        goalsstr.deleteCharAt(goalsstr.length() - 1);

        goalsstr.append("}\n");

        goalsstr.append(String.format("\n\"Chromosome Goals\": { \"iteration\": %d, \"individuals\": [",
                this.currentIteration));
        for (TestChromosome tc : this.population) {
            goalsstr.append(String.format("\n{ \"id\": \"%s\", \"goals\": [", tc.getID().toString()));
            for (Map.Entry<FitnessFunction<TestChromosome>, Double> entry : tc.getFitnessValues().entrySet()) {
                goalsstr.append(String.format("\n { \"fitness\": \"%f\", \"goal\": \"%s\" },", entry.getValue(),
                        entry.getKey()));
            }
            goalsstr.deleteCharAt(goalsstr.length() - 1);
            goalsstr.append("\n  ]\n},");
        }
        goalsstr.deleteCharAt(goalsstr.length() - 1);
        goalsstr.append("\n] }\n");
        LoggingUtils.getEvoLogger().info(goalsstr.toString());


        LoggingUtils.getEvoLogger().info("\n--- END OF GOALS SECTION FOR ITERATION "+ this.currentIteration + " ---\n");

    }



    public void logArchive() {

        LoggingUtils.getEvoLogger().info("\n--- START OF ARCHIVE SECTION FOR ITERATION "+ this.currentIteration + " ---\n");

        StringBuilder archiveStr = new StringBuilder(
                String.format("\n\"Archive\": { iteration: %d, [", this.currentIteration));
        for (TestChromosome tc : Archive.getArchiveInstance().getSolutions()) {
            archiveStr.append(String.format("\n  \"%s\",", tc.getID().toString()));
        }
        archiveStr.deleteCharAt(archiveStr.length() - 1);
        archiveStr.append("\n] }\n");
        LoggingUtils.getEvoLogger().info(archiveStr.toString());

        LoggingUtils.getEvoLogger().info("\n--- END OF ARCHIVE SECTION FOR ITERATION "+ this.currentIteration + " ---\n");

    }

    public void logCoverage(){

        LoggingUtils.getEvoLogger().info("\n--- START OF COVERAGE SECTION FOR ITERATION "+ this.currentIteration + " ---\n");

        //obtain current archive
        Set<TestChromosome> currentArchive = Archive.getArchiveInstance().getSolutions();
        //create corresponding TestSuiteChromosome
        TestSuiteChromosome testSuiteChromosome = new TestSuiteChromosome();
        testSuiteChromosome.addTestChromosomes(currentArchive);

        //call coverage analysis function
        CoverageCriteriaAnalyzer.analyzeCoverage(testSuiteChromosome);

        LoggingUtils.getEvoLogger().info("\n--- END OF COVERAGE SECTION FOR ITERATION "+ this.currentIteration + " ---\n");

    }


    /** Compute and print coverage for the *initial* population.      */
    private void logInitialCoverage() {

        LoggingUtils.getEvoLogger().info("\n--- START OF INITIAL COVERAGE SECTION ---\n");

        // Build a synthetic TestSuiteChromosome from the current population
        TestSuiteChromosome initialSuite = new TestSuiteChromosome();
        initialSuite.addTestChromosomes(this.population);   // uses List<TestChromosome>

        // Delegates heavy lifting to EvoSuite
        CoverageCriteriaAnalyzer.analyzeCoverage(initialSuite);

        LoggingUtils.getEvoLogger().info("\n--- END OF INITIAL COVERAGE SECTION ---\n");
    }


    private void logInitialGoals() {

        LoggingUtils.getEvoLogger().info("\n--- START OF INITIAL GOALS SECTION ---\n");

        StringBuilder goalsstr = new StringBuilder();

        /* --------- headline numbers --------- */
        goalsstr.append(String.format(
                "\n\"Goals\": { \"phase\": \"initial-population\", "
                        + "\"uncovered\": %d, \"covered\": %d, ",
                this.goalsManager.getUncoveredGoals().size(),
                this.goalsManager.getCoveredGoals().size()));

        /* --------- lists of targets --------- */
        goalsstr.append("\"uncovered targets\": ");
        appendGoals(goalsstr, this.goalsManager.getUncoveredGoals());

        goalsstr.append("\n\"covered targets\": ");
        appendGoals(goalsstr, this.goalsManager.getCoveredGoals());

        goalsstr.append("\n\"current targets\": ");
        appendGoals(goalsstr, this.goalsManager.getCurrentGoals());
        goalsstr.deleteCharAt(goalsstr.length() - 1);     // trim final comma
        goalsstr.append("}\n");

        /* --------- per-chromosome goals --------- */
        goalsstr.append("\n\"Chromosome Goals\": { \"phase\": \"initial-population\", \"individuals\": [");
        for (TestChromosome tc : this.population) {
            goalsstr.append(String.format(
                    "\n{ \"id\": \"%s\", \"goals\": [", tc.getID()));

            for (Map.Entry<FitnessFunction<TestChromosome>, Double> entry :
                    tc.getFitnessValues().entrySet()) {

                goalsstr.append(String.format(
                        "\n { \"fitness\": \"%f\", \"goal\": \"%s\" },",
                        entry.getValue(), entry.getKey()));
            }
            goalsstr.deleteCharAt(goalsstr.length() - 1); // last comma
            goalsstr.append("\n  ]\n},");
        }
        goalsstr.deleteCharAt(goalsstr.length() - 1);     // final comma
        goalsstr.append("\n] }\n");

        LoggingUtils.getEvoLogger().info(goalsstr.toString());

        LoggingUtils.getEvoLogger().info("\n--- END OF INITIAL GOALS SECTION ---\n");
    }
}
