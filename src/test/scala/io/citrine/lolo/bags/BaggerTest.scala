package io.citrine.lolo.bags

import java.util.concurrent.{Callable, CancellationException, Executors, Future, TimeUnit}

import io.citrine.lolo.TestUtils
import io.citrine.lolo.stats.functions.Friedman
import io.citrine.lolo.trees.classification.ClassificationTreeLearner
import io.citrine.lolo.trees.regression.RegressionTreeLearner
import org.junit.Test
import org.scalatest.Assertions._

import scala.util.Random

/**
  * Created by maxhutch on 11/29/16.
  */
@Test
class BaggerTest {

  /**
    * Test the fit performance of the regression bagger
    */
  @Test
  def testRegressionBagger(): Unit = {
    val trainingData = TestUtils.binTrainingData(
      TestUtils.generateTrainingData(1024, 12, noise = 0.1, function = Friedman.friedmanSilverman),
      inputBins = Seq((0, 8))
    )
    val DTLearner = new RegressionTreeLearner(numFeatures = 3)
    val baggedLearner = new Bagger(DTLearner, numBags = trainingData.size)
    val RFMeta = baggedLearner.train(trainingData)
    val RF = RFMeta.getModel()

    assert(RFMeta.getLoss().get < 1.0, "Loss of bagger is larger than expected")

    val results = RF.transform(trainingData.map(_._1))
    val means = results.getExpected()
    val sigma: Seq[Double] = results.getUncertainty().get.asInstanceOf[Seq[Double]]
    assert(sigma.forall(_ >= 0.0))

    assert(results.getGradient().isEmpty, "Returned a gradient when there shouldn't be one")

    assert(RFMeta.getHypers().contains("maxDepth"))

    /* The first feature should be the most important */
    val importances = RFMeta.getFeatureImportance().get
    assert(importances(1) == importances.max)
  }

  /**
    * Test the fit performance of the classification bagger
    */
  @Test
  def testClassificationBagger(): Unit = {
    val trainingData = TestUtils.binTrainingData(
      TestUtils.generateTrainingData(1024, 12, noise = 0.1, function = Friedman.friedmanSilverman),
      inputBins = Seq((0, 8)), responseBins = Some(8)
    )
    val DTLearner = new ClassificationTreeLearner()
    val baggedLearner = new Bagger(DTLearner, numBags = trainingData.size / 2)
    val RFMeta = baggedLearner.train(trainingData)
    val RF = RFMeta.getModel()

    /* Inspect the results */
    val results = RF.transform(trainingData.map(_._1))
    val means = results.getExpected()
    assert(trainingData.map(_._2).zip(means).forall { case (a, p) => a == p })

    val uncertainty = results.getUncertainty()
    assert(uncertainty.isDefined)
    assert(trainingData.map(_._2).zip(uncertainty.get).forall { case (a, probs) =>
      val classProbabilities = probs.asInstanceOf[Map[Any, Double]]
      val maxProb = classProbabilities(a)
      maxProb > 0.5 && maxProb < 1.0 && Math.abs(classProbabilities.values.sum - 1.0) < 1.0e-6
    })
    assert(results.getGradient().isEmpty, "Returned a gradient when there shouldn't be one")

    assert(RFMeta.getHypers().contains("maxDepth"))

    /* The first feature should be the most important */
    val importances = RFMeta.getFeatureImportance().get
    assert(importances.slice(0, 5).min > importances.slice(5, importances.size).max)
  }

  /**
    * Test that the uncertainty metrics are properly calibrated
    *
    * This test is based on the "standard" RMSE, which is computed by dividing the error
    * by the predicted uncertainty.  On the exterior, these is an additional extrapolative
    * uncertainty that isn't captured well by this method, so we test the interior and the full
    * set independently
    */
  def testUncertaintyCalibration(): Unit = {
    val width = 0.10 // make the function more linear
    val nFeatures = 5
    val bagsPerRow = 4 // picked to be large enough that bias correction is small but model isn't too expensive
    val trainingData = TestUtils.generateTrainingData(128, nFeatures, xscale = width, seed = Random.nextLong())
    val DTLearner = new RegressionTreeLearner(numFeatures = nFeatures)
    val bias = new RegressionTreeLearner(maxDepth = 4)
    val baggedLearner = new Bagger(DTLearner, numBags = bagsPerRow * trainingData.size, biasLearner = Some(bias))
    val RFMeta = baggedLearner.train(trainingData)
    val RF = RFMeta.getModel()

    val interiorTestSet = TestUtils.generateTrainingData(128, nFeatures, xscale = width/2.0, xoff = width/4.0, seed = Random.nextLong())
    val fullTestSet = TestUtils.generateTrainingData(128, nFeatures, xscale = width, seed = Random.nextLong())

    val interiorStandardRMSE = BaggerTest.getStandardRMSE(interiorTestSet, RF)
    val fullStandardRMSE = BaggerTest.getStandardRMSE(fullTestSet, RF)
    println(s"Standard RMSE (int, full): ${interiorStandardRMSE} ${fullStandardRMSE}")
    assert(interiorStandardRMSE > 0.50, "Standard RMSE in the interior should be greater than 0.5")
    assert(interiorStandardRMSE < 1.50, "Standard RMSE in the interior should be less than 1.5")

    assert(fullStandardRMSE < 2.5, "Standard RMSE over the full domain should be less than 2.5")
    assert(fullStandardRMSE > 1.0, "Standard RMSE over the full domain should be greater than 1.0")
  }

  /**
    * Test the scores on a smaller example, because computing them all can be expensive.
    *
    * In general, we don't even know that the self-score (score on a prediction on oneself) is maximal.  For example,
    * consider a training point that is sandwiched between two other points, i.e. y in | x     x y x    x |.  However,
    * this training data is on a 2D grid, so we know the corners of that grid need to have maximal self-scores.  Those
    * are at indices 0, 7, 56, and 63.
    */
  @Test
  def testScores(): Unit = {
    val csv = TestUtils.readCsv("double_example.csv")
    val trainingData = csv.map(vec => (vec.init, vec.last.asInstanceOf[Double]))
    val DTLearner = new RegressionTreeLearner()
    val baggedLearner = new Bagger(DTLearner, numBags = trainingData.size * 16) // use lots of trees to reduce noise
    val RF = baggedLearner.train(trainingData).getModel()

    /* Call transform on the training data */
    val results = RF.transform(trainingData.map(_._1))
    val scores = results.getImportanceScores().get
    val corners = Seq(0, 7, 56, 63)
    assert(
      corners.forall(i => scores(i)(i) == scores(i).max),
      "One of the training corners didn't have the highest score"
    )
  }

  /**
    * Test that the bagged learner can be interrupted
    */
  @Test
  def testInterrupt(): Unit = {
    val trainingData = TestUtils.generateTrainingData(2048, 12, noise = 0.1, function = Friedman.friedmanSilverman)
    val DTLearner = new RegressionTreeLearner(numFeatures = 3)
    val baggedLearner = new Bagger(DTLearner, numBags = trainingData.size)

    // Create a future to run train
    val tmpPool = Executors.newFixedThreadPool(1)
    val fut: Future[BaggedTrainingResult] = tmpPool.submit(
      new Callable[BaggedTrainingResult] {
        override def call() = {
          val res = baggedLearner.train(trainingData)
          assert(false, "Training was not terminated")
          res
        }
      }
    )
    // Let the thread start
    Thread.sleep(1000)

    // Cancel it
    val start = System.currentTimeMillis()
    assert(fut.cancel(true), "Failed to cancel future")

    // Make sure we get either a cancellation of interrupted exception
    try {
      fut.get()
      assert(false, "Future completed")
    } catch {
      case _: CancellationException =>
      case _: InterruptedException =>
      case _: Throwable => assert(false, "Future threw an exception")
    }

    // Shutdown the pool
    tmpPool.shutdown()
    assert(tmpPool.awaitTermination(1, TimeUnit.MINUTES), "Thread pool didn't terminate after a minute!")

    // Did it halt fast enough?
    val totalTime = (System.currentTimeMillis() - start) * 1.0e-3
    assert(totalTime < 2.0, "Thread took too long to terminate")
  }
}

/**
  * Companion driver
  */
object BaggerTest {
  /**
    * Test driver
    *
    * @param argv args
    */
  def main(argv: Array[String]): Unit = {
    new BaggerTest()
      .testUncertaintyCalibration()
  }


  def getStandardRMSE(testSet: Seq[(Vector[Any], Double)], model: BaggedModel): Double = {
    val predictions = model.transform(testSet.map(_._1))
    val pva = testSet.map(_._2).zip(
      predictions.getExpected().asInstanceOf[Seq[Double]].zip(
        predictions.getUncertainty().get.asInstanceOf[Seq[Double]]
      )
    )
    val standardError = pva.map{ case (a: Double, (p: Double, u: Double)) =>
      Math.abs(a - p) / u
    }
    Math.sqrt(standardError.map(Math.pow(_, 2.0)).sum / testSet.size)
  }

}
