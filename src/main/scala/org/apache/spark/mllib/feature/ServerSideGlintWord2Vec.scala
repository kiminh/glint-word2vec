/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.mllib.feature

import java.lang.{Iterable => JavaIterable}

import scala.collection.JavaConverters._
import scala.collection.mutable
import com.github.fommil.netlib.BLAS.{getInstance => blas}
import glint.Client
import glint.models.client.granular.GranularBigWord2VecMatrix
import org.json4s.DefaultFormats
import org.json4s.JsonDSL._
import org.json4s.jackson.JsonMethods._
import org.apache.spark.{SparkContext, TaskContext}
import org.apache.spark.annotation.Since
import org.apache.spark.api.java.JavaRDD
import org.apache.spark.broadcast.Broadcast
import org.apache.spark.internal.Logging
import org.apache.spark.mllib.linalg.{Vector, Vectors}
import org.apache.spark.mllib.util.{Loader, Saveable}
import org.apache.spark.rdd._
import org.apache.spark.sql.SparkSession
import org.apache.spark.util.BoundedPriorityQueue
import org.apache.spark.util.Utils
import org.apache.spark.util.random.XORShiftRandom

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration._

/**
  *  Entry in vocabulary
  */
private case class VocabWordCn(var word: String, var cn: Int)

/**
  * Word2Vec creates vector representation of words in a text corpus.
  * The algorithm first constructs a vocabulary from the corpus
  * and then learns vector representation of words in the vocabulary.
  * The vector representation can be used as features in
  * natural language processing and machine learning algorithms.
  *
  * We used skip-gram model in our implementation and hierarchical softmax
  * method to train the model. The variable names in the implementation
  * matches the original C implementation.
  *
  * For original C implementation, see https://code.google.com/p/word2vec/
  * For research papers, see
  * Efficient Estimation of Word Representations in Vector Space
  * and
  * Distributed Representations of Words and Phrases and their Compositionality.
  */
@Since("1.1.0")
class ServerSideGlintWord2Vec extends Serializable with Logging {

  private var vectorSize = 100
  private var learningRate = 0.025
  private var numPartitions = 1
  private var numIterations = 1
  private var seed = Utils.random.nextLong()
  private var minCount = 5
  private var maxSentenceLength = 1000

  private var batchSize = 50
  private var n = 5
  private var numParameterServers = 5
  private var parameterServerMasterHost = ""
  private var unigramTableSize = 100000000

  // default maximum payload size is 262144 bytes, akka.remote.OversizedPayloadException
  // use a twentieth of this as maximum message size to account for size of primitive types and overheads
  private val maximumMessageSize = 10000

  /**
    * Sets the maximum length (in words) of each sentence in the input data.
    * Any sentence longer than this threshold will be divided into chunks of
    * up to `maxSentenceLength` size (default: 1000)
    */
  @Since("2.0.0")
  def setMaxSentenceLength(maxSentenceLength: Int): this.type = {
    require(maxSentenceLength > 0,
      s"Maximum length of sentences must be positive but got ${maxSentenceLength}")
    this.maxSentenceLength = maxSentenceLength
    this
  }

  /**
    * Sets vector size (default: 100).
    */
  @Since("1.1.0")
  def setVectorSize(vectorSize: Int): this.type = {
    require(vectorSize > 0,
      s"vector size must be positive but got ${vectorSize}")
    this.vectorSize = vectorSize
    this
  }

  /**
    * Sets initial learning rate (default: 0.025).
    */
  @Since("1.1.0")
  def setLearningRate(learningRate: Double): this.type = {
    require(learningRate > 0,
      s"Initial learning rate must be positive but got ${learningRate}")
    this.learningRate = learningRate
    this
  }

  /**
    * Sets number of partitions (default: 1). Use a small number for accuracy.
    */
  @Since("1.1.0")
  def setNumPartitions(numPartitions: Int): this.type = {
    require(numPartitions > 0,
      s"Number of partitions must be positive but got ${numPartitions}")
    this.numPartitions = numPartitions
    this
  }

  /**
    * Sets number of iterations (default: 1), which should be smaller than or equal to number of
    * partitions.
    */
  @Since("1.1.0")
  def setNumIterations(numIterations: Int): this.type = {
    require(numIterations >= 0,
      s"Number of iterations must be nonnegative but got ${numIterations}")
    this.numIterations = numIterations
    this
  }

  /**
    * Sets random seed (default: a random long integer).
    */
  @Since("1.1.0")
  def setSeed(seed: Long): this.type = {
    this.seed = seed
    this
  }

  /**
    * Sets the window of words (default: 5)
    */
  @Since("1.6.0")
  def setWindowSize(window: Int): this.type = {
    require(window > 0,
      s"Window of words must be positive but got ${window}")
    require(batchSize * n * window <= maximumMessageSize,
      s"Batch size * n * window has to be below or equal to ${maximumMessageSize} to avoid oversized Akka payload")
    this.window = window
    this
  }

  /**
    * Sets minCount, the minimum number of times a token must appear to be included in the word2vec
    * model's vocabulary (default: 5).
    */
  @Since("1.3.0")
  def setMinCount(minCount: Int): this.type = {
    require(minCount >= 0,
      s"Minimum number of times must be nonnegative but got ${minCount}")
    this.minCount = minCount
    this
  }

  /**
    * Sets the mini batch size (default: 50)
    */
  def setBatchSize(batchSize: Int): this.type = {
    require(batchSize * n * window <= maximumMessageSize,
      s"Batch size * n * window has to be below or equal to ${maximumMessageSize} to avoid oversized Akka payload")
    this.batchSize = batchSize
    this
  }

  /**
    * Sets n, the number of random negative examples (default: 5)
    */
  def setN(n: Int): this.type = {
    require(batchSize * n * window <= maximumMessageSize,
      s"Batch size * n * window has to be below or equal to ${maximumMessageSize} to avoid oversized Akka payload")
    this.n = n
    this
  }

  /**
    * Sets the number of parameter servers to create (default: 5)
    */
  def setNumParameterServers(numParameterServers: Int): this.type = {
    this.numParameterServers = numParameterServers
    this
  }

  /**
    * Sets the host name of the master of the parameter servers.
    * Set to "" for automatic detection which may not always work and "127.0.0.1" for local testing
    * (default: "")
    */
  def setParameterServerMasterHost(parameterServerMasterHost: String): this.type = {
    this.parameterServerMasterHost = parameterServerMasterHost
    this
  }

  /**
    * Sets the size of the unigram table.
    * Only needs to be changed to a lower value if there is not enough memory for local testing.
    * (default: 100000000)
    */
  def setUnigramTableSize(unigramTableSize: Int): this.type = {
    this.unigramTableSize = unigramTableSize
    this
  }

  private val EXP_TABLE_SIZE = 1000
  private val MAX_EXP = 6
  private val MAX_CODE_LENGTH = 40

  /** context words from [-window, window] */
  private var window = 5

  private var trainWordsCount = 0L
  private var vocabSize = 0
  @transient private var vocab: Array[VocabWordCn] = null
  @transient private var vocabHash = mutable.HashMap.empty[String, Int]

  private def learnVocab[S <: Iterable[String]](dataset: RDD[S]): Unit = {
    val words = dataset.flatMap(x => x)

    vocab = words.map(w => (w, 1))
      .reduceByKey(_ + _)
      .filter(_._2 >= minCount)
      .map(x => VocabWordCn(x._1, x._2))
      .collect()
      .sortWith((a, b) => a.cn > b.cn)

    vocabSize = vocab.length
    require(vocabSize > 0, "The vocabulary size should be > 0. You may need to check " +
      "the setting of minCount, which could be large enough to remove all your words in sentences.")

    var a = 0
    while (a < vocabSize) {
      vocabHash += vocab(a).word -> a
      trainWordsCount += vocab(a).cn
      a += 1
    }
    logInfo(s"vocabSize = $vocabSize, trainWordsCount = $trainWordsCount")
  }

  private def createExpTable(): Array[Float] = {
    val expTable = new Array[Float](EXP_TABLE_SIZE)
    var i = 0
    while (i < EXP_TABLE_SIZE) {
      val tmp = math.exp((2.0 * i / EXP_TABLE_SIZE - 1.0) * MAX_EXP)
      expTable(i) = (tmp / (tmp + 1.0)).toFloat
      i += 1
    }
    expTable
  }

  private def getSigmoid(expTable: Broadcast[Array[Float]], f: Float, label: Float): Float = {
    if (f > MAX_EXP) {
      return label - 1
    }
    if (f < -MAX_EXP) {
      return label
    }

    val ind = ((f + MAX_EXP) * (EXP_TABLE_SIZE / MAX_EXP / 2.0)).toInt
    label - expTable.value(ind)
  }

  /**
    * Computes the vector representation of each word in vocabulary.
    * @param dataset an RDD of sentences,
    *                each sentence is expressed as an iterable collection of words
    * @return a ServerSideGlintWord2VecModel
    */
  @Since("1.1.0")
  def fit[S <: Iterable[String]](dataset: RDD[S]): ServerSideGlintWord2VecModel = {

    learnVocab(dataset)

    val sc = dataset.context

    val expTable = sc.broadcast(createExpTable())
    val bcVocabCns = sc.broadcast(vocab.map(v => v.cn))
    val bcVocabHash = sc.broadcast(vocabHash)
    try {
      doFit(dataset, sc, expTable, bcVocabCns, bcVocabHash)
    } finally {
      expTable.destroy(blocking = false)
      bcVocabCns.destroy(blocking = false)
      bcVocabHash.destroy(blocking = false)
    }
  }


  private def doFit[S <: Iterable[String]](
                                            dataset: RDD[S], sc: SparkContext,
                                            expTable: Broadcast[Array[Float]],
                                            bcVocabCns: Broadcast[Array[Int]],
                                            bcVocabHash: Broadcast[mutable.HashMap[String, Int]]) = {
    // each partition is a collection of sentences,
    // will be translated into arrays of Index integer
    val sentences: RDD[Array[Int]] = dataset.mapPartitions { sentenceIter =>
      // Each sentence will map to 0 or more Array[Int]
      sentenceIter.flatMap { sentence =>
        // Sentence of words, some of which map to a word index
        val wordIndexes = sentence.flatMap(bcVocabHash.value.get)
        // break wordIndexes into trunks of maxSentenceLength when has more
        wordIndexes.grouped(maxSentenceLength).map(_.toArray)
      }
    }

    val newSentences = sentences.repartition(numPartitions).cache()
    val initRandom = new XORShiftRandom(seed)

    if (vocabSize.toLong * vectorSize >= Int.MaxValue) {
      throw new RuntimeException("Please increase minCount or decrease vectorSize in ServerSideGlintWord2Vec" +
        " to avoid an OOM. You are highly recommended to make your vocabSize*vectorSize, " +
        "which is " + vocabSize + "*" + vectorSize + " for now, less than `Int.MaxValue`.")
    }

    @transient
    implicit val ec = ExecutionContext.Implicits.global

    @transient
    val (client, matrix) = Client.runWithWord2VecMatrixOnSpark(
      sc, parameterServerMasterHost, bcVocabCns, vectorSize, n, unigramTableSize, numParameterServers)
    val syn = new GranularBigWord2VecMatrix(matrix, maximumMessageSize)

    val totalWordsCounts = numIterations * trainWordsCount + 1
    var alpha = learningRate

    for (k <- 1 to numIterations) {
      val numWordsProcessedInPreviousIterations = (k - 1) * trainWordsCount

      val sentencesContext: RDD[Array[Array[Int]]] = newSentences.mapPartitionsWithIndex { (idx, sentenceIter) =>
        val random = new XORShiftRandom(seed ^ ((idx + 1) << 16) ^ ((-k - 1) << 8))
        sentenceIter.map { sentence =>
          sentence.indices.toArray.map { i =>
            val b = random.nextInt(window)
            val contextIndices = (Math.max(0, i - b) until Math.min(i + b, sentence.length)).filter(j => j != i)
            contextIndices.map(ci => sentence(ci)).toArray
          }
        }
      }

      newSentences.zip(sentencesContext).foreachPartition { iter =>
        @transient
        implicit val ec = ExecutionContext.Implicits.global

        val idx = TaskContext.getPartitionId()
        val random = new XORShiftRandom(seed ^ ((idx + 1) << 16) ^ ((-k - 1) << 8))

        iter.foldLeft((0L, 0L)) {
          case ((lastWordCount, wordCount), (sentence, sentenceContext)) =>
            var lwc = lastWordCount
            var wc = wordCount
            if (wordCount - lastWordCount > 10000) {
              lwc = wordCount
              alpha = learningRate *
                (1 - (numPartitions * wordCount.toDouble + numWordsProcessedInPreviousIterations) /
                  totalWordsCounts)
              if (alpha < learningRate * 0.0001) alpha = learningRate * 0.0001
              logInfo(s"wordCount = ${wordCount + numWordsProcessedInPreviousIterations}, " +
                s"alpha = $alpha")
            }
            wc += sentence.length

            // actual training - communicate with parameter servers
            val sentenceMiniBatches = sentence.sliding(batchSize, batchSize)
            val sentenceContextMiniBatches = sentenceContext.sliding(batchSize, batchSize)
            val miniBatchFutures = sentenceMiniBatches.zip(sentenceContextMiniBatches).map { case (wInput, wOutput) =>
              val seed = random.nextLong()
              syn.dotprod(wInput, wOutput, seed).map { case (fPlus, fMinus) =>
                val gPlus = fPlus.map(f => getSigmoid(expTable, f, 1.0f) * alpha.toFloat)
                val gMinus = fMinus.map(f => getSigmoid(expTable, f, 0.0f) * alpha.toFloat)
                syn.adjust(wInput, wOutput, gPlus, gMinus, seed)
              }
            }
            // the map here is important because simply using foreach would start all futures at the same time
            miniBatchFutures.map(Await.ready(_, 1 minute)).foreach(identity)

            (lwc, wc)
        }
      }
    }

    // pull all word vectors
    // this requires enough memory locally, but using a model based on a BigMatrix is currently not supported
    val pullRows = (for (i <- 0L until vocabSize) yield Array.fill(vectorSize)(i)).flatten.toArray
    val pullCols = Array.fill(vocabSize)((0L until vectorSize).toArray).flatten
    val pulledWordVectors = Await.result(syn.pull(pullRows, pullCols), 1 minute)

    client.terminateOnSpark(sc)

    newSentences.unpersist()

    val wordArray = vocab.map(_.word)
    new ServerSideGlintWord2VecModel(wordArray.zipWithIndex.toMap, pulledWordVectors)
  }

  /**
    * Computes the vector representation of each word in vocabulary (Java version).
    * @param dataset a JavaRDD of words
    * @return a ServerSideGlintWord2VecModel
    */
  @Since("1.1.0")
  def fit[S <: JavaIterable[String]](dataset: JavaRDD[S]): ServerSideGlintWord2VecModel = {
    fit(dataset.rdd.map(_.asScala))
  }

}

/**
  * ServerSideGlintWord2Vec model
  * @param wordIndex maps each word to an index, which can retrieve the corresponding
  *                  vector from wordVectors
  * @param wordVectors array of length numWords * vectorSize, vector corresponding
  *                    to the word mapped with index i can be retrieved by the slice
  *                    (i * vectorSize, i * vectorSize + vectorSize)
  */
@Since("1.1.0")
class ServerSideGlintWord2VecModel private[spark](
                                               private[spark] val wordIndex: Map[String, Int],
                                               private[spark] val wordVectors: Array[Float]) extends Serializable with Saveable {

  private val numWords = wordIndex.size
  // vectorSize: Dimension of each word's vector.
  private val vectorSize = wordVectors.length / numWords

  // wordList: Ordered list of words obtained from wordIndex.
  private val wordList: Array[String] = {
    val (wl, _) = wordIndex.toSeq.sortBy(_._2).unzip
    wl.toArray
  }

  // wordVecNorms: Array of length numWords, each value being the Euclidean norm
  //               of the wordVector.
  private val wordVecNorms: Array[Float] = {
    val wordVecNorms = new Array[Float](numWords)
    var i = 0
    while (i < numWords) {
      val vec = wordVectors.slice(i * vectorSize, i * vectorSize + vectorSize)
      wordVecNorms(i) = blas.snrm2(vectorSize, vec, 1)
      i += 1
    }
    wordVecNorms
  }

  @Since("1.5.0")
  def this(model: Map[String, Array[Float]]) = {
    this(ServerSideGlintWord2VecModel.buildWordIndex(model), ServerSideGlintWord2VecModel.buildWordVectors(model))
  }

  override protected def formatVersion = "1.0"

  @Since("1.4.0")
  def save(sc: SparkContext, path: String): Unit = {
    ServerSideGlintWord2VecModel.SaveLoadV1_0.save(sc, path, getVectors)
  }

  /**
    * Transforms a word to its vector representation
    * @param word a word
    * @return vector representation of word
    */
  @Since("1.1.0")
  def transform(word: String): Vector = {
    wordIndex.get(word) match {
      case Some(ind) =>
        val vec = wordVectors.slice(ind * vectorSize, ind * vectorSize + vectorSize)
        Vectors.dense(vec.map(_.toDouble))
      case None =>
        throw new IllegalStateException(s"$word not in vocabulary")
    }
  }

  /**
    * Find synonyms of a word; do not include the word itself in results.
    * @param word a word
    * @param num number of synonyms to find
    * @return array of (word, cosineSimilarity)
    */
  @Since("1.1.0")
  def findSynonyms(word: String, num: Int): Array[(String, Double)] = {
    val vector = transform(word)
    findSynonyms(vector, num, Some(word))
  }

  /**
    * Find synonyms of the vector representation of a word, possibly
    * including any words in the model vocabulary whose vector respresentation
    * is the supplied vector.
    * @param vector vector representation of a word
    * @param num number of synonyms to find
    * @return array of (word, cosineSimilarity)
    */
  @Since("1.1.0")
  def findSynonyms(vector: Vector, num: Int): Array[(String, Double)] = {
    findSynonyms(vector, num, None)
  }

  /**
    * Find synonyms of the vector representation of a word, rejecting
    * words identical to the value of wordOpt, if one is supplied.
    * @param vector vector representation of a word
    * @param num number of synonyms to find
    * @param wordOpt optionally, a word to reject from the results list
    * @return array of (word, cosineSimilarity)
    */
  private def findSynonyms(
                            vector: Vector,
                            num: Int,
                            wordOpt: Option[String]): Array[(String, Double)] = {
    require(num > 0, "Number of similar words should > 0")

    val fVector = vector.toArray.map(_.toFloat)
    val cosineVec = new Array[Float](numWords)
    val alpha: Float = 1
    val beta: Float = 0
    // Normalize input vector before blas.sgemv to avoid Inf value
    val vecNorm = blas.snrm2(vectorSize, fVector, 1)
    if (vecNorm != 0.0f) {
      blas.sscal(vectorSize, 1 / vecNorm, fVector, 0, 1)
    }
    blas.sgemv(
      "T", vectorSize, numWords, alpha, wordVectors, vectorSize, fVector, 1, beta, cosineVec, 1)

    var i = 0
    while (i < numWords) {
      val norm = wordVecNorms(i)
      if (norm == 0.0f) {
        cosineVec(i) = 0.0f
      } else {
        cosineVec(i) /= norm
      }
      i += 1
    }

    val pq = new BoundedPriorityQueue[(String, Float)](num + 1)(Ordering.by(_._2))

    var j = 0
    while (j < numWords) {
      pq += Tuple2(wordList(j), cosineVec(j))
      j += 1
    }

    val scored = pq.toSeq.sortBy(-_._2)

    val filtered = wordOpt match {
      case Some(w) => scored.filter(tup => w != tup._1)
      case None => scored
    }

    filtered
      .take(num)
      .map { case (word, score) => (word, score.toDouble) }
      .toArray
  }

  /**
    * Returns a map of words to their vector representations.
    */
  @Since("1.2.0")
  def getVectors: Map[String, Array[Float]] = {
    wordIndex.map { case (word, ind) =>
      (word, wordVectors.slice(vectorSize * ind, vectorSize * ind + vectorSize))
    }
  }

}

@Since("1.4.0")
object ServerSideGlintWord2VecModel extends Loader[ServerSideGlintWord2VecModel] {

  private def buildWordIndex(model: Map[String, Array[Float]]): Map[String, Int] = {
    model.keys.zipWithIndex.toMap
  }

  private def buildWordVectors(model: Map[String, Array[Float]]): Array[Float] = {
    require(model.nonEmpty, "Word2VecMap should be non-empty")
    val (vectorSize, numWords) = (model.head._2.length, model.size)
    val wordList = model.keys.toArray
    val wordVectors = new Array[Float](vectorSize * numWords)
    var i = 0
    while (i < numWords) {
      Array.copy(model(wordList(i)), 0, wordVectors, i * vectorSize, vectorSize)
      i += 1
    }
    wordVectors
  }

  private object SaveLoadV1_0 {

    val formatVersionV1_0 = "1.0"

    val classNameV1_0 = "org.apache.spark.mllib.feature.ServerSideGlintWord2VecModel"

    case class Data(word: String, vector: Array[Float])

    def load(sc: SparkContext, path: String): ServerSideGlintWord2VecModel = {
      val spark = SparkSession.builder().sparkContext(sc).getOrCreate()
      val dataFrame = spark.read.parquet(Loader.dataPath(path))
      // Check schema explicitly since erasure makes it hard to use match-case for checking.
      Loader.checkSchema[Data](dataFrame.schema)

      val dataArray = dataFrame.select("word", "vector").collect()
      val word2VecMap = dataArray.map(i => (i.getString(0), i.getSeq[Float](1).toArray)).toMap
      new ServerSideGlintWord2VecModel(word2VecMap)
    }

    def save(sc: SparkContext, path: String, model: Map[String, Array[Float]]): Unit = {
      val spark = SparkSession.builder().sparkContext(sc).getOrCreate()

      val vectorSize = model.values.head.length
      val numWords = model.size
      val metadata = compact(render(
        ("class" -> classNameV1_0) ~ ("version" -> formatVersionV1_0) ~
          ("vectorSize" -> vectorSize) ~ ("numWords" -> numWords)))
      sc.parallelize(Seq(metadata), 1).saveAsTextFile(Loader.metadataPath(path))

      // We want to partition the model in partitions smaller than
      // spark.kryoserializer.buffer.max
      val bufferSize = Utils.byteStringAsBytes(
        spark.conf.get("spark.kryoserializer.buffer.max", "64m"))
      // We calculate the approximate size of the model
      // We only calculate the array size, considering an
      // average string size of 15 bytes, the formula is:
      // (floatSize * vectorSize + 15) * numWords
      val approxSize = (4L * vectorSize + 15) * numWords
      val nPartitions = ((approxSize / bufferSize) + 1).toInt
      val dataArray = model.toSeq.map { case (w, v) => Data(w, v) }
      spark.createDataFrame(dataArray).repartition(nPartitions).write.parquet(Loader.dataPath(path))
    }
  }

  @Since("1.4.0")
  override def load(sc: SparkContext, path: String): ServerSideGlintWord2VecModel = {

    val (loadedClassName, loadedVersion, metadata) = Loader.loadMetadata(sc, path)
    implicit val formats = DefaultFormats
    val expectedVectorSize = (metadata \ "vectorSize").extract[Int]
    val expectedNumWords = (metadata \ "numWords").extract[Int]
    val classNameV1_0 = SaveLoadV1_0.classNameV1_0
    (loadedClassName, loadedVersion) match {
      case (classNameV1_0, "1.0") =>
        val model = SaveLoadV1_0.load(sc, path)
        val vectorSize = model.getVectors.values.head.length
        val numWords = model.getVectors.size
        require(expectedVectorSize == vectorSize,
          s"ServerSideGlintWord2VecModel requires each word to be mapped to a vector of size " +
            s"$expectedVectorSize, got vector of size $vectorSize")
        require(expectedNumWords == numWords,
          s"ServerSideGlintWord2VecModel requires $expectedNumWords words, but got $numWords")
        model
      case _ => throw new Exception(
        s"ServerSideGlintWord2VecModel.load did not recognize model with (className, format version):" +
          s"($loadedClassName, $loadedVersion).  Supported:\n" +
          s"  ($classNameV1_0, 1.0)")
    }
  }
}