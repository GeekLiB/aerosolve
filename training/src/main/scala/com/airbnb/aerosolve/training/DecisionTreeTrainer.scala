package com.airbnb.aerosolve.training

import java.util

import com.airbnb.aerosolve.core.models.BoostedStumpsModel
import com.airbnb.aerosolve.core.models.DecisionTreeModel
import com.airbnb.aerosolve.core.Example
import com.airbnb.aerosolve.core.ModelRecord
import com.airbnb.aerosolve.core.util.Util
import com.typesafe.config.Config
import org.apache.spark.SparkContext
import org.apache.spark.rdd.RDD
import org.slf4j.{Logger, LoggerFactory}

import scala.util.Random
import scala.util.Try
import scala.collection.JavaConversions._

// Types of split criteria
object SplitCriteriaTypes extends Enumeration {
  val Classification, Regression, Multiclass = Value
}

// Split criteria instances
object SplitCriteria extends Enumeration {
  val Gini, InformationGain, Hellinger, Variance, MulticlassHellinger, MulticlassGini = Value

  def getCriteriaType(criteria : Value) : SplitCriteriaTypes.Value = {
    criteria match {
      case Gini => SplitCriteriaTypes.Classification
      case InformationGain => SplitCriteriaTypes.Classification
      case Hellinger => SplitCriteriaTypes.Classification
      case Variance => SplitCriteriaTypes.Regression
      case MulticlassHellinger => SplitCriteriaTypes.Multiclass
      case MulticlassGini => SplitCriteriaTypes.Multiclass
    }
  }

  def splitCriteriaFromName(name : String): SplitCriteria.Value = {
    name match {
      case "gini" => Gini
      case "information_gain" => InformationGain
      case "hellinger" => Hellinger
      case "variance" => Variance
      case "multiclass_hellinger" => MulticlassHellinger
      case "multiclass_gini" => MulticlassGini
    }
  }
}

// The decision tree is meant to be a prior for the spline model / linear model
object DecisionTreeTrainer {
  private final val log: Logger = LoggerFactory.getLogger("DecisionTreeTrainer")
  private val defaultRandom: Random = new Random()

  def train(
      sc : SparkContext,
      input : RDD[Example],
      config : Config,
      key : String,
      random : Random = defaultRandom) : DecisionTreeModel = {
    val candidateSize : Int = config.getInt(key + ".num_candidates")
    val rankKey : String = config.getString(key + ".rank_key")
    val rankThreshold : Double = config.getDouble(key + ".rank_threshold")
    val maxDepth : Int = config.getInt(key + ".max_depth")
    val minLeafCount : Int = config.getInt(key + ".min_leaf_items")
    val numTries : Int = config.getInt(key + ".num_tries")
    val splitCriteriaName : String = Try(config.getString(key + ".split_criteria"))
      .getOrElse("gini")

    val examples = LinearRankerUtils
        .makePointwiseFloat(input, config, key)
        .map(x => Util.flattenFeature(x.example(0)))
        .filter(x => x.contains(rankKey))
        .take(candidateSize)

    val ex = examples(0)
    val numFeatures = LinearRankerUtils.getNumFeatures(ex, rankKey)
    val maxFeatures : Int =  Try(config.getString(key + ".max_features")).getOrElse("all") match {
      case "all" => Int.MaxValue
      case "sqrt" => math.sqrt(numFeatures).ceil.toInt
      case "log2" => math.max(1, (math.log(numFeatures) / math.log(2)).ceil.toInt)
      case _ => Int.MaxValue
    }

    val stumps = new util.ArrayList[ModelRecord]()
    stumps.append(new ModelRecord)

    buildTree(
      stumps,
      examples,
      0,
      0,
      maxDepth,
      rankKey,
      rankThreshold,
      maxFeatures,
      numTries,
      minLeafCount,
      SplitCriteria.splitCriteriaFromName(splitCriteriaName),
      random
    )
    
    val model = new DecisionTreeModel()
    model.setStumps(stumps)

    model
  }
  
  def buildTree(
      stumps : util.ArrayList[ModelRecord],
      examples : Array[util.Map[java.lang.String, util.Map[java.lang.String, java.lang.Double]]],
      currIdx : Int,
      currDepth : Int,
      maxDepth : Int,
      rankKey : String,
      rankThreshold : Double,
      maxFeatures : Int,
      numTries : Int,
      minLeafCount : Int,
      splitCriteria : SplitCriteria.Value,
      random : Random = defaultRandom) : Unit = {
    if (currDepth >= maxDepth) {
      stumps(currIdx) = makeLeaf(examples, rankKey, rankThreshold, splitCriteria)
      return
    }

    val split = getBestSplit(
      examples,
      rankKey,
      rankThreshold,
      maxFeatures,
      numTries,
      minLeafCount,
      splitCriteria,
      random
    )

    if (split.isEmpty) {
      stumps(currIdx) = makeLeaf(examples, rankKey, rankThreshold, splitCriteria)
      return
    }

    // This is a split node.
    stumps(currIdx) = split.get
    val left = stumps.size
    stumps.append(new ModelRecord())
    val right = stumps.size
    stumps.append(new ModelRecord())
    stumps(currIdx).setLeftChild(left)
    stumps(currIdx).setRightChild(right)

    val (rightExamples, leftExamples) = examples.partition(
      x => BoostedStumpsModel.getStumpResponse(stumps(currIdx), x))

    buildTree(
      stumps,
      leftExamples,
      left,
      currDepth + 1,
      maxDepth,
      rankKey,
      rankThreshold,
      maxFeatures,
      numTries,
      minLeafCount,
      splitCriteria,
      random
    )

    buildTree(
      stumps,
      rightExamples,
      right,
      currDepth + 1,
      maxDepth,
      rankKey,
      rankThreshold,
      maxFeatures,
      numTries,
      minLeafCount,
      splitCriteria,
      random
    )
  }

  def makeLeaf(
      examples : Array[util.Map[java.lang.String, util.Map[java.lang.String, java.lang.Double]]],
      rankKey : String,
      rankThreshold : Double,
      splitCriteria : SplitCriteria.Value) = {
    val rec = new ModelRecord()

    SplitCriteria.getCriteriaType(splitCriteria) match {
      case SplitCriteriaTypes.Classification =>
        var numPos = 0.0
        var numNeg = 0.0

        for (example <- examples) {
          val label = example.get(rankKey).values().iterator().next() > rankThreshold
          if (label) numPos += 1.0 else numNeg += 1.0
        }

        val sum = numPos + numNeg

        if (sum > 0.0) {
          // Convert from percentage positive to the -1 to 1 range
          val frac = numPos / sum
          rec.setFeatureWeight(2.0 * frac - 1.0)
        } else {
          rec.setFeatureWeight(0.0)
        }

      case SplitCriteriaTypes.Regression =>
        var count : Double = 0.0
        var sum : Double = 0.0

        for (example <- examples) {
          val labelValue = example.get(rankKey).values().iterator().next()

          count += 1.0
          sum += labelValue
        }

        // In regression case, leaf is the average of all the associated values
        rec.setFeatureWeight(sum / count)

      case SplitCriteriaTypes.Multiclass =>
        val labelDistribution = new java.util.HashMap[java.lang.String, java.lang.Double]()
        rec.setLabelDistribution(labelDistribution)

        var sum = 0.0

        for (example <- examples) {
          for (kv <- example.get(rankKey).entrySet()) {
            val key = kv.getKey
            val value = kv.getValue

            val count = if (labelDistribution.containsKey(key)) {
              labelDistribution.get(key)
            } else {
              new java.lang.Double(0.0)
            }

            sum = sum + value
            labelDistribution.put(key, count + value)
          }
        }

        if (sum > 0.0) {
          val scale = 1.0 / sum

          for (kv <- labelDistribution.entrySet()) {
            val key = kv.getKey
            val value = kv.getValue

            labelDistribution.put(key, scale * value)
          }
        }
    }

    rec
  }

  // Returns the best split if one exists.
  def getBestSplit(
      examples : Array[util.Map[java.lang.String, util.Map[java.lang.String, java.lang.Double]]],
      rankKey : String,
      rankThreshold : Double,
      maxFeatures : Int,
      numTries : Int,
      minLeafCount : Int,
      splitCriteria : SplitCriteria.Value,
      random : Random = defaultRandom) : Option[ModelRecord] = {
    if (examples.length <= minLeafCount) {
      // If we're at or below the minLeafCount, then there's no point in splitting
      None
    } else {
      var bestRecord: Option[ModelRecord] = None
      var bestValue: Double = -1e10

      for (i <- 0 until numTries) {
        // Pick an example index randomly
        val idx = random.nextInt(examples.length)
        val ex = examples(idx)
        val candidateOpt = getCandidateSplit(ex, rankKey, maxFeatures, random)

        if (candidateOpt.isDefined) {
          val candidateValue = SplitCriteria.getCriteriaType(splitCriteria) match {
            case SplitCriteriaTypes.Classification =>
              evaluateClassificationSplit(
                examples, rankKey,
                rankThreshold,
                minLeafCount,
                splitCriteria, candidateOpt
              )
            case SplitCriteriaTypes.Regression =>
              evaluateRegressionSplit(
                examples, rankKey,
                minLeafCount,
                splitCriteria, candidateOpt
              )
            case SplitCriteriaTypes.Multiclass =>
              evaluateMulticlassSplit(
                examples, rankKey,
                minLeafCount,
                splitCriteria, candidateOpt
              )
          }

          if (candidateValue.isDefined && candidateValue.get > bestValue) {
            bestValue = candidateValue.get
            bestRecord = candidateOpt
          }
        }
      }

      bestRecord
    }
  }

  // Evaluate a classification-type split
  def evaluateClassificationSplit(
      examples : Array[util.Map[java.lang.String, util.Map[java.lang.String, java.lang.Double]]],
      rankKey : String,
      rankThreshold : Double,
      minLeafCount : Int,
      splitCriteria : SplitCriteria.Value,
      candidateOpt : Option[ModelRecord]): Option[Double] = {
    var leftPos : Double = 0.0
    var rightPos : Double = 0.0
    var leftNeg : Double = 0.0
    var rightNeg : Double = 0.0

    for (example <- examples) {
      val response = BoostedStumpsModel.getStumpResponse(candidateOpt.get, example)
      val label = example.get(rankKey).values().iterator().next() > rankThreshold

      if (response) {
        if (label) {
          rightPos += 1.0
        } else {
          rightNeg += 1.0
        }
      } else {
        if (label) {
          leftPos += 1.0
        } else {
          leftNeg += 1.0
        }
      }
    }

    val rightCount = rightPos + rightNeg
    val leftCount = leftPos + leftNeg

    if (rightCount >= minLeafCount && leftCount >= minLeafCount) {
      val p1 = rightPos / rightCount
      val n1 = rightNeg / rightCount
      val f1 = rightCount / (leftCount + rightCount)

      val p2 = leftPos / leftCount
      val n2 = leftNeg / leftCount
      val f2 = leftCount / (leftCount + rightCount)

      splitCriteria match {
        case SplitCriteria.Gini =>
          // Using negative gini since we are maximizing.
          val gini = -(
            f1 * (p1 * (1.0 - p1) + n1 * (1.0 - n1)) +
              f2 * (n2 * (1.0 - n2) + p2 * (1.0 - p2))
          )

          Some(gini)
        case SplitCriteria.InformationGain =>
          var ig = 0.0
          if (p1 > 0) {
            ig += f1 * p1 * scala.math.log(p1)
          }
          if (n1 > 0) {
            ig += f1 * n1 * scala.math.log(n1)
          }
          if (p2 > 0) {
            ig += f2 * p2 * scala.math.log(p2)
          }
          if (n2 > 0) {
            ig += f2 * n2 * scala.math.log(n2)
          }

          Some(ig)
        case SplitCriteria.Hellinger =>
          val scale = 1.0 / (leftCount * rightCount)
          // http://en.wikipedia.org/wiki/Bhattacharyya_distance
          val bhattacharyya =
            math.sqrt(leftPos * rightPos * scale) + math.sqrt(leftNeg * rightNeg * scale)
          // http://en.wikipedia.org/wiki/Hellinger_distance
          val hellinger = math.sqrt(1.0 - bhattacharyya)

          Some(hellinger)
      }
    } else {
      None
    }
  }

  def giniImpurity(dist : scala.collection.mutable.Map[String, Double]) : Double = {
    val sum = dist.values.sum
    val scale = 1.0 / (sum * sum)
    var impurity : Double = 0.0

    for (kv1 <- dist) {
      for (kv2 <- dist) {
        if (kv1._1 != kv2._1) {
          impurity += kv1._2 * kv2._2
        }
      }
    }

    impurity * scale
  }

  // Evaluate a multiclass classification-type split
  def evaluateMulticlassSplit(
      examples : Array[util.Map[java.lang.String, util.Map[java.lang.String, java.lang.Double]]],
      rankKey : String,
      minLeafCount : Int,
      splitCriteria : SplitCriteria.Value,
      candidateOpt : Option[ModelRecord]): Option[Double] = {
    val leftDist = scala.collection.mutable.HashMap[String, Double]()
    val rightDist = scala.collection.mutable.HashMap[String, Double]()

    var leftCount = 0
    var rightCount = 0

    for (example <- examples) {
      val response = BoostedStumpsModel.getStumpResponse(candidateOpt.get, example)
      for (kv <- example.get(rankKey).entrySet()) {
        val key = kv.getKey
        val value = kv.getValue

        if (response) {
          val v = rightDist.getOrElse(key, 0.0)
          rightDist.put(key, value + v)
          rightCount = rightCount + 1
        } else {
          val v = leftDist.getOrElse(key, 0.0)
          leftDist.put(key, value + v)
          leftCount = leftCount + 1
        }
      }
    }

    if (rightCount >= minLeafCount && leftCount >= minLeafCount) {
      splitCriteria match {
        case SplitCriteria.MulticlassHellinger =>
          val total = rightDist.values.sum * leftDist.values.sum
          val scale = 1.0 / total
          // http://en.wikipedia.org/wiki/Bhattacharyya_distance
          val bhattacharyya = rightDist
            .map(x => math.sqrt(scale * x._2 * leftDist.getOrElse(x._1, 0.0)))
            .sum
          // http://en.wikipedia.org/wiki/Hellinger_distance
          val hellinger = math.sqrt(1.0 - bhattacharyya)
          Some(hellinger)

        case SplitCriteria.MulticlassGini =>
          val impurity = giniImpurity(leftDist) + giniImpurity(rightDist)
          Some(-impurity)
      }
    } else {
      None
    }
  }

  // Evaluate a regression-type split
  // See http://www.stat.cmu.edu/~cshalizi/350-2006/lecture-10.pdf for overview of algorithm used
  def evaluateRegressionSplit(
      examples : Array[util.Map[java.lang.String, util.Map[java.lang.String, java.lang.Double]]],
      rankKey : String,
      minLeafCount : Int,
      splitCriteria : SplitCriteria.Value,
      candidateOpt : Option[ModelRecord]): Option[Double] = {
    var rightCount : Double = 0.0
    var rightMean : Double = 0.0
    var rightSumSq : Double = 0.0
    var leftCount : Double = 0.0
    var leftMean : Double = 0.0
    var leftSumSq : Double = 0.0

    for (example <- examples) {
      val response = BoostedStumpsModel.getStumpResponse(candidateOpt.get, example)
      val labelValue = example.get(rankKey).values().iterator().next()

      // Using Welford's Method for computing mean and sum-squared errors in numerically stable way;
      // more details can be found in
      // http://jonisalonen.com/2013/deriving-welfords-method-for-computing-variance
      //
      // See unit test for verification that it is consistent with standard, two-pass approach
      if (response) {
        rightCount += 1
        val delta = labelValue - rightMean
        rightMean += delta / rightCount
        rightSumSq += delta * (labelValue - rightMean)
      } else {
        leftCount += 1
        val delta = labelValue - leftMean
        leftMean += delta / leftCount
        leftSumSq += delta * (labelValue - leftMean)
      }
    }

    if (rightCount >= minLeafCount && leftCount >= minLeafCount) {
      splitCriteria match {
        case SplitCriteria.Variance =>
          Some(-(leftSumSq + rightSumSq))
      }
    } else {
      None
    }
  }

  // Returns a candidate split sampled from an example.
  def getCandidateSplit(
      ex : util.Map[java.lang.String, util.Map[java.lang.String, java.lang.Double]],
      rankKey : String,
      maxFeatures : Int,
      random : Random) : Option[ModelRecord] = {
    // Flatten the features and pick one randomly.
    var features = collection.mutable.ArrayBuffer[(String, String, Double)]()

    for (family <- ex) {
      if (!family._1.equals(rankKey)) {
        for (feature <- family._2) {
          features.append((family._1, feature._1, feature._2))
        }
      }
    }

    if (features.isEmpty) {
      None
    } else {
      // Use a random subset of features for each split
      features = random.shuffle(features).slice(0, maxFeatures)

      val idx = random.nextInt(features.size)
      val rec = new ModelRecord()

      rec.setFeatureFamily(features(idx)._1)
      rec.setFeatureName(features(idx)._2)
      rec.setThreshold(features(idx)._3)

      Some(rec)
    }
  }

  def trainAndSaveToFile(
      sc : SparkContext,
      input : RDD[Example],
      config : Config,
      key : String) = {
    val model = train(sc, input, config, key)
    TrainingUtils.saveModel(model, config, key + ".model_output")
  }
}
