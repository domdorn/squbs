/*
 * Licensed to Typesafe under one or more contributor license agreements.
 * See the AUTHORS file distributed with this work for
 * additional information regarding copyright ownership.
 * This file is licensed to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.squbs.concurrent.timeout

import java.lang.management.ManagementFactory

import akka.agent.Agent
import com.typesafe.scalalogging.LazyLogging

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

/**
 *
 * @param name name of the policy
 * @param initial initial(also max) value of the timeout duration
 * @param startOverCount max total transaction count for start over the statistics
 * @param ec implicit parameter of ExecutionContext
 */
abstract class TimeoutPolicy(name: Option[String], initial: FiniteDuration, startOverCount: Int)
                            (implicit ec: ExecutionContext) extends LazyLogging {
  require(initial != null, "initial duration is required")
  require(startOverCount > 0, "slidePoint should be positive")

  private val agent = Agent(Metrics(name, initial, startOverCount))

  private[timeout] def waitTime: FiniteDuration

  /**
   * The TimeoutTransaction allows for demarcating a unit of execution where time is measured.
   */
  class TimeoutTransaction() {

    /**
     * Demarcates the start of the transaction
     */
    private lazy val start = System.nanoTime()

    /**
     * The wait time or timeout needed for this transaction.
     */
    lazy val waitTime = {
      start
      TimeoutPolicy.this.waitTime
    }

    /**
     * Demarcates the end of the transaction.
     */
    def end(): Unit = {
      val timeTaken = System.nanoTime() - start
      if (timeTaken < 0) {
        //TODO only happened if user forgot to call waitTime first, should we throw an exception out?
        logger.warn("call end without call waitTime first, ignore this transaction")
      } else {
        val isTimeout = timeTaken > waitTime.toNanos
        TimeoutPolicy.this.update(timeTaken, isTimeout)
      }

    }
  }

  /**
   * Executes a piece of logic without a timeout transaction
   * @param f The logic to be executed, taking the intended wait time as an input
   * @tparam T The return type of the function f, and therefore the return type of this execution
   * @return The output of the function f
   */
  def execute[T](f: FiniteDuration => T): T = {
    val tx = this.transaction
    try {
      f(tx.waitTime)
    } finally {
      tx.end()
    }
  }

  /**
   * Obtains a new TimeoutTransaction object.
   * @return The newly created TimeoutTransaction object associated with this TimeoutPolicy.
   */
  def transaction = new TimeoutTransaction()


  /**
   * reset the policy, return the previous metrics
   * @param initial new initial value
   * @param newStartOverCount new start over count
   * @return statistics of before the reset operation
   */
  def reset(initial: Option[FiniteDuration] = None, newStartOverCount: Int = 0): Metrics = {
    val previous = agent()
    agent send {_.reset(initial, newStartOverCount)}
    previous
  }

  /**
   * The metrics reference holding the stats of past transactions.
   * @return
   */
  def metrics = agent()

  private[timeout] def update(time: Double, isTimeout: Boolean): Unit = agent send {_.update(time, isTimeout)}
}

/**
 * The timeout policy for a fixed timeout.
 * @param name name of the policy
 * @param initial initial(also max) value of the timeout duration
 * @param startOverCount max total transaction count for start over the statistics
 * @param ec implicit parameter of ExecutionContext
 */
class FixedTimeoutPolicy(name: Option[String], initial: FiniteDuration, startOverCount:Int)(implicit ec:ExecutionContext)
    extends TimeoutPolicy(name, initial, startOverCount) {
  override def waitTime: FiniteDuration = metrics.initial

}

/**
 * Timeout Policy by following sigma rules.
 * http://en.wikipedia.org/wiki/68%E2%80%9395%E2%80%9399.7_rule
 * @param name name of the policy
 * @param initial initial value of duration
 * @param startOverCount count for start over the statistic calculating
 * @param sigmaUnits unit of sigma, must be positive
 * @param minSamples min sample for the policy take effect
 */
class EmpiricalTimeoutPolicy(name: Option[String], initial: FiniteDuration, startOverCount:Int, sigmaUnits: Double,
                             minSamples: Int)(implicit ec:ExecutionContext)
    extends TimeoutPolicy(name, initial, startOverCount) {
  require(minSamples > 0, "miniSamples should be positive")
  require(sigmaUnits > 0, "sigmaUnits should be positive")

  override def waitTime: FiniteDuration = {

    val metrics = this.metrics
    val waitTime = if (metrics.totalCount > minSamples) {
      val standardDeviation = metrics.standardDeviation
      val averageTime = metrics.averageTime
      (averageTime + sigmaUnits * standardDeviation).ceil.nanoseconds
    } else metrics.initial
    if (waitTime > metrics.initial) metrics.initial else waitTime
  }
}

/**
 * Super type of all timeout rules.
 */
trait TimeoutRule

/**
 * Fixed timeout rule.
 */
object FixedTimeoutRule extends TimeoutRule

/**
 * Sigma or standard deviation-based timeout rule.
 * @param unit The units of sigme to allow
 */
case class SigmaTimeoutRule(unit: Double) extends TimeoutRule {
  require(unit > 0, "unit should be positive")
}

object PercentileTimeoutRule {

  /**
   * Creates a SigmaTimeoutRule from percentiles.
   * @param percent The percentile
   * @return The new SigmaTimeoutRule based on these percentiles.
   */
  def apply(percent: Double) = {
    require(percent > 0 && percent < 1, "percent should be in (0-1)")
    new SigmaTimeoutRule(MathUtil.erfInv(percent) * math.sqrt(2))
  }
}

/**
 * Factories for the Timeout policies.
 */
object TimeoutPolicy extends LazyLogging {
  val debugMode = ManagementFactory.getRuntimeMXBean.getInputArguments.toString.indexOf("jdwp") >= 0

  private val policyMap = new collection.mutable.WeakHashMap[String, TimeoutPolicy]

  /**
   * Creates a new TimeoutPolicy
   * @param name optional name of the timeout policy
   * @param initial the initial value of timeout duration, also would be the max value on using non-FixedTimeoutRule
   * @param rule rule for the timeout policy, default is FixedTimeoutRule
   * @param debug the timeout duration in debug mode
   * @param minSamples required on choosing EmpiricalTimeoutPolicy, which would be the gatekeeper of the statistics take effect. default value is 1000
   * @param startOverCount the max count for start over the statistics, default is Int.MaxValue
   * @return timeout policy
   */
  def apply(name: Option[String] = None, initial: FiniteDuration, rule: TimeoutRule = FixedTimeoutRule,
            debug: FiniteDuration = 1000 seconds, minSamples: Int = 1000, startOverCount: Int = Int.MaxValue)
           (implicit ec: ExecutionContext): TimeoutPolicy = {
    require(initial != null, "initial is required")
    require(debug != null, "debug is required")
    if (debugMode) {
      logger.warn("running in debug mode, use the debug duration instead")
      new FixedTimeoutPolicy(name, debug, startOverCount)
    } else {
      val policy = rule match {
        case FixedTimeoutRule => new FixedTimeoutPolicy(name, initial, startOverCount)
        case SigmaTimeoutRule(unit) => new EmpiricalTimeoutPolicy(name, initial, startOverCount, unit, minSamples)
      }

      name foreach (n => policyMap += (n -> policy))

      policy
    }
  }

//  def create(initial: FiniteDuration, rule: TimeoutRule = FixedTimeoutRule,
//            debug: FiniteDuration = 1000 seconds, minSamples: Int = 1000, startOverCount: Int = Int.MaxValue)
//           (implicit ec: ExecutionContext): TimeoutPolicy =
//    apply(None, initial, rule, debug, minSamples, startOverCount)
//
//  def createWithName(name: String, initial: FiniteDuration, rule: TimeoutRule = FixedTimeoutRule,
//            debug: FiniteDuration = 1000 seconds, minSamples: Int = 1000, startOverCount: Int = Int.MaxValue)
//           (implicit ec: ExecutionContext): TimeoutPolicy =
//    apply(Option(name), initial, rule, debug, minSamples, startOverCount)

  /**
   *
   * @return all of the metrics
   */
  def policyMetrics = policyMap mapValues (_.metrics)

  /**
   * Reset the timeout policy.
   * @param name name of the policy
   * @param initial new initial value, use previously if it's None
   * @return previous metrics
   */
  def resetPolicy(name: String, initial: Option[FiniteDuration] = None, startOverCount: Int = 0): Option[Metrics] = {
    policyMap.get(name).map(_.reset(initial, startOverCount))
  }

}


