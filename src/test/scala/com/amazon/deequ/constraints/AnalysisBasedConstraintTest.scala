package com.amazon.deequ.constraints

import com.amazon.deequ.analyzers.{Analyzer, NumMatches, StateLoader, StatePersister}
import com.amazon.deequ.metrics.{DoubleMetric, Entity, Metric}
import com.amazon.deequ.utils.FixtureSupport
import org.apache.spark.sql.DataFrame
import org.scalamock.scalatest.MockFactory
import org.scalatest.{Matchers, PrivateMethodTester, WordSpec}

import scala.util.{Failure, Try}
import ConstraintUtils.calculate
import com.amazon.deequ.SparkContextSpec
import com.amazon.deequ.analyzers.runners.MetricCalculationException

class AnalysisBasedConstraintTest extends WordSpec with Matchers with SparkContextSpec
  with FixtureSupport with MockFactory with PrivateMethodTester {

  /**
    * Sample function to use as value picker
    *
    * @return Returns input multiplied by 2
    */
  def valueDoubler(value: Double): Double = {
    value * 2
  }

  /**
    * Sample analyzer that returns a 1.0 value if the given column exists and fails otherwise.
    *
    * @param column
    */
  case class SampleAnalyzer(column: String) extends Analyzer[NumMatches, DoubleMetric] {
    override def toFailureMetric(exception: Exception): DoubleMetric = {
      DoubleMetric(Entity.Column, "sample", column, Failure(MetricCalculationException
        .wrapIfNecessary(exception)))
    }


    override def calculate(
        data: DataFrame,
        stateLoader: Option[StateLoader],
        statePersister: Option[StatePersister])
      : DoubleMetric = {
      val value: Try[Double] = Try {
        require(data.columns.contains(column), s"Missing column $column")
        1.0
      }
      DoubleMetric(Entity.Column, "sample", column, value)
    }

    override def computeStateFrom(data: DataFrame): Option[NumMatches] = {
      throw new NotImplementedError()
    }


    override def computeMetricFrom(state: Option[NumMatches]): DoubleMetric = {
      throw new NotImplementedError()
    }
  }

  "Analysis based constraint" should {

    "should assert correctly on values if analysis is successful" in
      withSparkSession { sparkSession =>
        val df = getDfMissing(sparkSession)

        // Analysis result should equal to 1.0 for an existing column
        val resultA = calculate(AnalysisBasedConstraint[NumMatches, Double, Double](
          SampleAnalyzer("att1"), _ == 1.0), df)

        assert(resultA.status == ConstraintStatus.Success)
        assert(resultA.message.isEmpty)

        // Analysis result should equal to 1.0 for an existing column
        val resultB = calculate(AnalysisBasedConstraint[NumMatches, Double, Double](
          SampleAnalyzer("att1"), _ != 1.0), df)

        assert(resultB.status == ConstraintStatus.Failure)
        assert(resultB.message.contains("Value: 1.0 does not meet the constraint requirement!"))

        // Analysis should fail for a non existing column
        val resultC = calculate(AnalysisBasedConstraint[NumMatches, Double, Double](
          SampleAnalyzer("someMissingColumn"), _ == 1.0), df)

        assert(resultC.status == ConstraintStatus.Failure)
        assert(resultC.message.get.equals("requirement failed: Missing column someMissingColumn"))
      }

    "execute value picker on the analysis result value, if provided" in
      withSparkSession { sparkSession =>


      val df = getDfMissing(sparkSession)

      // Analysis result should equal to 100.0 for an existing column
      assert(calculate(AnalysisBasedConstraint[NumMatches, Double, Double](
        SampleAnalyzer("att1"), _ == 2.0, Some(valueDoubler)), df).status ==
        ConstraintStatus.Success)

      assert(calculate(AnalysisBasedConstraint[NumMatches, Double, Double](
        SampleAnalyzer("att1"), _ != 2.0, Some(valueDoubler)), df).status ==
        ConstraintStatus.Failure)

      // Analysis should fail for a non existing column
      assert(calculate(AnalysisBasedConstraint[NumMatches, Double, Double](
        SampleAnalyzer("someMissingColumn"), _ == 2.0, Some(valueDoubler)), df).status ==
        ConstraintStatus.Failure)
      }

    "get the analysis from the context, if provided" in withSparkSession { sparkSession =>
      val df = getDfMissing(sparkSession)

      val emptyResults = Map.empty[Analyzer[_, Metric[_]], Metric[_]]
      val validResults = Map[Analyzer[_, Metric[_]], Metric[_]](
        SampleAnalyzer("att1") -> SampleAnalyzer("att1").calculate(df),
        SampleAnalyzer("someMissingColumn") -> SampleAnalyzer("someMissingColumn").calculate(df)
      )

      // Analysis result should equal to 1.0 for an existing column
      assert(AnalysisBasedConstraint[NumMatches, Double, Double](SampleAnalyzer("att1"), _ == 1.0)
        .evaluate(validResults).status == ConstraintStatus.Success)
      assert(AnalysisBasedConstraint[NumMatches, Double, Double](SampleAnalyzer("att1"), _ != 1.0)
        .evaluate(validResults).status == ConstraintStatus.Failure)
      assert(AnalysisBasedConstraint[NumMatches, Double, Double](
          SampleAnalyzer("someMissingColumn"), _ != 1.0)
        .evaluate(validResults).status == ConstraintStatus.Failure)

      // Although assertion would pass, since analysis result is missing,
      // constraint fails with missing analysis message
      AnalysisBasedConstraint[NumMatches, Double, Double](SampleAnalyzer("att1"), _ == 1.0)
        .evaluate(emptyResults) match {
        case result =>
          assert(result.status == ConstraintStatus.Failure)
          assert(result.message.contains(AnalysisBasedConstraint.MissingAnalysis))
      }
    }

    "execute value picker on the analysis result value retrieved from context, if provided" in
      withSparkSession { sparkSession =>
        val df = getDfMissing(sparkSession)
        val validResults = Map[Analyzer[_, Metric[_]], Metric[_]](
          SampleAnalyzer("att1") -> SampleAnalyzer("att1").calculate(df))

        assert(AnalysisBasedConstraint[NumMatches, Double, Double](
            SampleAnalyzer("att1"), _ == 2.0, Some(valueDoubler))
          .evaluate(validResults).status == ConstraintStatus.Success)
      }


    "fail on analysis if value picker is provided but fails" in withSparkSession { sparkSession =>
      def problematicValuePicker(value: Double): Double = {
        throw new RuntimeException("Something wrong with this picker")
      }

      val df = getDfMissing(sparkSession)

      val emptyResults = Map.empty[Analyzer[_, Metric[_]], Metric[_]]
      val validResults = Map[Analyzer[_, Metric[_]], Metric[_]](
        SampleAnalyzer("att1") -> SampleAnalyzer("att1").calculate(df))

      val constraint = AnalysisBasedConstraint[NumMatches, Double, Double](
          SampleAnalyzer("att1"), _ == 1.0, Some(problematicValuePicker))

      calculate(constraint, df) match {
        case result =>
          assert(result.status == ConstraintStatus.Failure)
          assert(result.message.get.startsWith(AnalysisBasedConstraint.ProblematicMetricPicker))
      }

      constraint.evaluate(validResults) match {
        case result =>
          assert(result.status == ConstraintStatus.Failure)
          assert(result.message.get.startsWith(AnalysisBasedConstraint.ProblematicMetricPicker))
      }

      constraint.evaluate(emptyResults) match {
        case result =>
          assert(result.status == ConstraintStatus.Failure)
          assert(result.message.contains(AnalysisBasedConstraint.MissingAnalysis))
      }

    }

    "fail on failed assertion function with hint in exception message if provided" in
      withSparkSession { sparkSession =>

      val df = getDfMissing(sparkSession)

      val failingConstraint = AnalysisBasedConstraint[NumMatches, Double, Double](
          SampleAnalyzer("att1"), _ == 0.9, hint = Some("Value should be like ...!"))

      calculate(failingConstraint, df) match {
        case result =>
          assert(result.status == ConstraintStatus.Failure)
          assert(result.message.get == "Value: 1.0 does not meet the constraint requirement! " +
            "Value should be like ...!")
      }
    }

  }
}
