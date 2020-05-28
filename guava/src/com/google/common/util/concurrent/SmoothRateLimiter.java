package com.google.common.util.concurrent;

import static java.lang.Math.min;
import static java.util.concurrent.TimeUnit.SECONDS;

import com.google.common.annotations.GwtIncompatible;
import com.google.common.math.LongMath;
import java.util.concurrent.TimeUnit;

@GwtIncompatible
abstract class SmoothRateLimiter extends RateLimiter {
  /*
   * How is the RateLimiter designed, and why?
   *
   * The primary feature of a RateLimiter is its "stable rate", the maximum rate that is should
   * allow at normal conditions. This is enforced by "throttling" incoming requests as needed, i.e.
   * compute, for an incoming request, the appropriate throttle time, and make the calling thread
   * wait as much.
   *
   * The simplest way to maintain a rate of QPS is to keep the timestamp of the last granted
   * request, and ensure that (1/QPS) seconds have elapsed since then. For example, for a rate of
   * QPS=5 (5 tokens per second), if we ensure that a request isn't granted earlier than 200ms after
   * the last one, then we achieve the intended rate. If a request comes and the last request was
   * granted only 100ms ago, then we wait for another 100ms. At this rate, serving 15 fresh permits
   * (i.e. for an acquire(15) request) naturally takes 3 seconds.
   *
   * It is important to realize that such a RateLimiter has a very superficial memory of the past:
   * it only remembers the last request. What if the RateLimiter was unused for a long period of
   * time, then a request arrived and was immediately granted? This RateLimiter would immediately
   * forget about that past underutilization. This may result in either underutilization or
   * overflow, depending on the real world consequences of not using the expected rate.
   *
   * Past underutilization could mean that excess resources are available. Then, the RateLimiter
   * should speed up for a while, to take advantage of these resources. This is important when the
   * rate is applied to networking (limiting bandwidth), where past underutilization typically
   * translates to "almost empty buffers", which can be filled immediately.
   *
   * On the other hand, past underutilization could mean that "the server responsible for handling
   * the request has become less ready for future requests", i.e. its caches become stale, and
   * requests become more likely to trigger expensive operations (a more extreme case of this
   * example is when a server has just booted, and it is mostly busy with getting itself up to
   * speed).
   *
   * To deal with such scenarios, we add an extra dimension, that of "past underutilization",
   * modeled by "storedPermits" variable. This variable is zero when there is no underutilization,
   * and it can grow up to maxStoredPermits, for sufficiently large underutilization. So, the
   * requested permits, by an invocation acquire(permits), are served from:
   *
   * - stored permits (if available)
   *
   * - fresh permits (for any remaining permits)
   *
   * How this works is best explained with an example:
   *
   * For a RateLimiter that produces 1 token per second, every second that goes by with the
   * RateLimiter being unused, we increase storedPermits by 1. Say we leave the RateLimiter unused
   * for 10 seconds (i.e., we expected a request at time X, but we are at time X + 10 seconds before
   * a request actually arrives; this is also related to the point made in the last paragraph), thus
   * storedPermits becomes 10.0 (assuming maxStoredPermits >= 10.0). At that point, a request of
   * acquire(3) arrives. We serve this request out of storedPermits, and reduce that to 7.0 (how
   * this is translated to throttling time is discussed later). Immediately after, assume that an
   * acquire(10) request arriving. We serve the request partly from storedPermits, using all the
   * remaining 7.0 permits, and the remaining 3.0, we serve them by fresh permits produced by the
   * rate limiter.
   *
   * We already know how much time it takes to serve 3 fresh permits: if the rate is
   * "1 token per second", then this will take 3 seconds. But what does it mean to serve 7 stored
   * permits? As explained above, there is no unique answer. If we are primarily interested to deal
   * with underutilization, then we want stored permits to be given out /faster/ than fresh ones,
   * because underutilization = free resources for the taking. If we are primarily interested to
   * deal with overflow, then stored permits could be given out /slower/ than fresh ones. Thus, we
   * require a (different in each case) function that translates storedPermits to throttling time.
   *
   * This role is played by storedPermitsToWaitTime(double storedPermits, double permitsToTake). The
   * underlying model is a continuous function mapping storedPermits (from 0.0 to maxStoredPermits)
   * onto the 1/rate (i.e. intervals) that is effective at the given storedPermits. "storedPermits"
   * essentially measure unused time; we spend unused time buying/storing permits. Rate is
   * "permits / time", thus "1 / rate = time / permits". Thus, "1/rate" (time / permits) times
   * "permits" gives time, i.e., integrals on this function (which is what storedPermitsToWaitTime()
   * computes) correspond to minimum intervals between subsequent requests, for the specified number
   * of requested permits.
   *
   * Here is an example of storedPermitsToWaitTime: If storedPermits == 10.0, and we want 3 permits,
   * we take them from storedPermits, reducing them to 7.0, and compute the throttling for these as
   * a call to storedPermitsToWaitTime(storedPermits = 10.0, permitsToTake = 3.0), which will
   * evaluate the integral of the function from 7.0 to 10.0.
   *
   * Using integrals guarantees that the effect of a single acquire(3) is equivalent to {
   * acquire(1); acquire(1); acquire(1); }, or { acquire(2); acquire(1); }, etc, since the integral
   * of the function in [7.0, 10.0] is equivalent to the sum of the integrals of [7.0, 8.0], [8.0,
   * 9.0], [9.0, 10.0] (and so on), no matter what the function is. This guarantees that we handle
   * correctly requests of varying weight (permits), /no matter/ what the actual function is - so we
   * can tweak the latter freely. (The only requirement, obviously, is that we can compute its
   * integrals).
   *
   * Note well that if, for this function, we chose a horizontal line, at height of exactly (1/QPS),
   * then the effect of the function is non-existent: we serve storedPermits at exactly the same
   * cost as fresh ones (1/QPS is the cost for each). We use this trick later.
   *
   * If we pick a function that goes /below/ that horizontal line, it means that we reduce the area
   * of the function, thus time. Thus, the RateLimiter becomes /faster/ after a period of
   * underutilization. If, on the other hand, we pick a function that goes /above/ that horizontal
   * line, then it means that the area (time) is increased, thus storedPermits are more costly than
   * fresh permits, thus the RateLimiter becomes /slower/ after a period of underutilization.
   *
   * Last, but not least: consider a RateLimiter with rate of 1 permit per second, currently
   * completely unused, and an expensive acquire(100) request comes. It would be nonsensical to just
   * wait for 100 seconds, and /then/ start the actual task. Why wait without doing anything? A much
   * better approach is to /allow/ the request right away (as if it was an acquire(1) request
   * instead), and postpone /subsequent/ requests as needed. In this version, we allow starting the
   * task immediately, and postpone by 100 seconds future requests, thus we allow for work to get
   * done in the meantime instead of waiting idly.
   *
   * This has important consequences: it means that the RateLimiter doesn't remember the time of the
   * _last_ request, but it remembers the (expected) time of the _next_ request. This also enables
   * us to tell immediately (see tryAcquire(timeout)) whether a particular timeout is enough to get
   * us to the point of the next scheduling time, since we always maintain that. And what we mean by
   * "an unused RateLimiter" is also defined by that notion: when we observe that the
   * "expected arrival time of the next request" is actually in the past, then the difference (now -
   * past) is the amount of time that the RateLimiter was formally unused, and it is that amount of
   * time which we translate to storedPermits. (We increase storedPermits with the amount of permits
   * that would have been produced in that idle time). So, if rate == 1 permit per second, and
   * arrivals come exactly one second after the previous, then storedPermits is _never_ increased --
   * we would only increase it for arrivals _later_ than the expected one second.
   */

  /**todo
   * This implements the following function where coldInterval = coldFactor * stableInterval.
   *
   * <pre>
   *          ^ throttling  请求的间隔时间
   *          |
   *    cold  +                  /
   * interval |                 /.
   *          |                / .
   *          |               /  .   ← "warmup period" is the area of the trapezoid between
   *          |              /   .     thresholdPermits and maxPermits
   *          |             /    .
   *          |            /     .
   *          |           /      .
   *   stable +----------/  WARM .
   * interval |          .   UP  .
   *          |          . PERIOD.
   *          |          .       .
   *        0 +----------+-------+--------------→ storedPermits  当前令牌桶中的令牌 storedPermits 分为两个区间：[0, thresholdPermits) 和 [thresholdPermits, maxPermits]
   *          0 thresholdPermits maxPermits
   * </pre>
   *
   *
   * 上图中横坐标是当前令牌桶中的令牌 storedPermits，前面说过 SmoothWarmingUp 将 storedPermits 分为
   * 两个区间：[0, thresholdPermits) 和 [thresholdPermits, maxPermits]。
   * 纵坐标是请求的间隔时间，stableInterval 就是 1 / QPS，例如设置的 QPS 为5，则 stableInterval 就是200ms，
   * coldInterval = stableInterval * coldFactor，这里的 coldFactor "hard-code"写死的是3。
   *
   * 当系统进入 cold 阶段时，图像会向右移，直到 storedPermits 等于 maxPermits；
   * 当系统请求增多，图像会像左移动，直到 storedPermits 为0
   * Before going into the details of this particular function, let's keep in mind the basics:
   *
   * <ol>
   *   <li>The state of the RateLimiter (storedPermits) is a vertical line in this figure.
   *   <li>When the RateLimiter is not used, this goes right (up to maxPermits)
   *   <li>When the RateLimiter is used, this goes left (down to zero), since if we have
   *       storedPermits, we serve from those first
   *   <li>When _unused_, we go right at a constant rate! The rate at which we move to the right is
   *       chosen as maxPermits / warmupPeriod. This ensures that the time it takes to go from 0 to
   *       maxPermits is equal to warmupPeriod.
   *   <li>When _used_, the time it takes, as explained in the introductory class note, is equal to
   *       the integral of our function, between X permits and X-K permits, assuming we want to
   *       spend K saved permits.
   * </ol>
   *
   * <p>In summary, the time it takes to move to the left (spend K permits), is equal to the area of
   * the function of width == K.
   *
   * <p>Assuming we have saturated demand, the time to go from maxPermits to thresholdPermits is
   * equal to warmupPeriod. And the time to go from thresholdPermits to 0 is warmupPeriod/2. (The
   * reason that this is warmupPeriod/2 is to maintain the behavior of the original implementation
   * where coldFactor was hard coded as 3.)
   *
   * <p>It remains to calculate thresholdsPermits and maxPermits.
   *
   * <ul>
   *   <li>The time to go from thresholdPermits to 0 is equal to the integral of the function
   *       between 0 and thresholdPermits. This is thresholdPermits * stableIntervals. By (5) it is
   *       also equal to warmupPeriod/2. Therefore
   *       <blockquote>
   *       thresholdPermits = 0.5 * warmupPeriod / stableInterval
   *       </blockquote>
   *   <li>The time to go from maxPermits to thresholdPermits is equal to the integral of the
   *       function between thresholdPermits and maxPermits. This is the area of the pictured
   *       trapezoid, and it is equal to 0.5 * (stableInterval + coldInterval) * (maxPermits -
   *       thresholdPermits). It is also equal to warmupPeriod, so
   *       <blockquote>
   *       maxPermits = thresholdPermits + 2 * warmupPeriod / (stableInterval + coldInterval)
   *       </blockquote>
   * </ul>
   */
  static final class SmoothWarmingUp extends SmoothRateLimiter {
    private final long warmupPeriodMicros;
    /**
     * The slope of the line from the stable interval (when permits == 0), to the cold interval
     * (when permits == maxPermits)
     */
    private double slope;

    private double thresholdPermits;
    private double coldFactor;

    SmoothWarmingUp(
        SleepingStopwatch stopwatch, long warmupPeriod, TimeUnit timeUnit, double coldFactor) {
      super(stopwatch);
      this.warmupPeriodMicros = timeUnit.toMicros(warmupPeriod);
      this.coldFactor = coldFactor;
    }

    @Override
    void doSetRate(double permitsPerSecond, double stableIntervalMicros) {
      double oldMaxPermits = maxPermits;
      double coldIntervalMicros = stableIntervalMicros * coldFactor;
      thresholdPermits = 0.5 * warmupPeriodMicros / stableIntervalMicros;
      maxPermits =
          thresholdPermits + 2.0 * warmupPeriodMicros / (stableIntervalMicros + coldIntervalMicros);
      slope = (coldIntervalMicros - stableIntervalMicros) / (maxPermits - thresholdPermits);
      if (oldMaxPermits == Double.POSITIVE_INFINITY) {
        // if we don't special-case this, we would get storedPermits == NaN, below
        storedPermits = 0.0;
      } else {
        storedPermits =
            (oldMaxPermits == 0.0)
                ? maxPermits // initial state is cold
                : storedPermits * maxPermits / oldMaxPermits;
      }
    }

    //SmoothWarmingUp 类中 storedPermitsToWaitTime 方法将 permitsToTake 分为两部分，
    // 一部分从 WARM UP PERIOD 部分拿，这部分是一个梯形，面积计算就是（上底 + 下底）* 高 / 2。
    // 另一部分从 stable 部分拿，它是一个长方形，面积就是 长 * 宽。最后返回两个部分的时间总和
    @Override
    long storedPermitsToWaitTime(double storedPermits, double permitsToTake) {
      double availablePermitsAboveThreshold = storedPermits - thresholdPermits;
      long micros = 0;
      // measuring the integral on the right part of the function (the climbing line)
      //如果当前 storedPermits 超过 availablePermitsAboveThreshold 则计算从 超过部分拿令牌所需要的时间（图中的 WARM UP PERIOD）
      if (availablePermitsAboveThreshold > 0.0) {
        // WARM UP PERIOD 部分计算的方法，这部分是一个梯形，梯形的面积计算公式是 “（上底 + 下底） * 高 / 2”
        double permitsAboveThresholdToTake = min(availablePermitsAboveThreshold, permitsToTake);
        // TODO(cpovirk): Figure out a good name for this variable.
        double length =
            permitsToTime(availablePermitsAboveThreshold)
                + permitsToTime(availablePermitsAboveThreshold - permitsAboveThresholdToTake);
        // 计算出从 WARM UP PERIOD 拿走令牌的
        micros = (long) (permitsAboveThresholdToTake * length / 2.0);

        // 剩余的令牌从 stable 部分拿
        permitsToTake -= permitsAboveThresholdToTake;
      }
      // measuring the integral on the left part of the function (the horizontal line)
      // stable 部分令牌获取花费的时间
      micros += (long) (stableIntervalMicros * permitsToTake);
      return micros;
    }

    // WARM UP PERIOD 部分 获取相应令牌所对应的的时间
    private double permitsToTime(double permits) {
      return stableIntervalMicros + permits * slope;
    }

    @Override
    double coolDownIntervalMicros() {
      return warmupPeriodMicros / maxPermits;
    }
  }

  /**
   * This implements a "bursty" RateLimiter, where storedPermits are translated to zero throttling.
   * The maximum number of permits that can be saved (when the RateLimiter is unused) is defined in
   * terms of time, in this sense: if a RateLimiter is 2qps, and this time is specified as 10
   * seconds, we can save up to 2 * 10 = 20 permits.
   */
  static final class SmoothBursty extends SmoothRateLimiter {
    /** The work (permits) of how many seconds can be saved up if this RateLimiter is unused? */
    final double maxBurstSeconds;

    SmoothBursty(SleepingStopwatch stopwatch, double maxBurstSeconds) {
      super(stopwatch);
      this.maxBurstSeconds = maxBurstSeconds;
    }

    @Override
    void doSetRate(double permitsPerSecond, double stableIntervalMicros) {
      double oldMaxPermits = this.maxPermits;
      maxPermits = maxBurstSeconds * permitsPerSecond;
      if (oldMaxPermits == Double.POSITIVE_INFINITY) {
        // if we don't special-case this, we would get storedPermits == NaN, below
        storedPermits = maxPermits;
      } else {
        storedPermits =
            (oldMaxPermits == 0.0)
                ? 0.0 // initial state
                : storedPermits * maxPermits / oldMaxPermits;
      }
    }


    //牛逼 直接返回0
    @Override
    long storedPermitsToWaitTime(double storedPermits, double permitsToTake) {
      return 0L;
    }

    @Override
    double coolDownIntervalMicros() {
      return stableIntervalMicros;
    }
  }

  //表明当前令牌桶中有多少令牌
  /** The currently stored permits. */
  double storedPermits;

  //表示令牌桶最大令牌数目
  /** The maximum number of stored permits. */
  double maxPermits;

  /**
   * stableIntervalMicros 等于 1/qps，它代表系统在稳定期间，两次请求之间间隔的微秒数。
   * 例如：如果我们设置的 qps 为5，则 stableIntervalMicros 为200ms
   * The interval between two unit requests, at our stable rate. E.g., a stable rate of 5 permits
   * per second has a stable interval of 200ms.
   */
  double stableIntervalMicros;

  /**
   * nextFreeTicketMicros 表示系统处理完当前请求后，下一次请求被许可的最短微秒数，如果在这之前有请求进来，则必须等待
   * The time when the next request (no matter its size) will be granted. After granting a request,
   * this is pushed further in the future. Large requests push this further than small requests.
   */
  private long nextFreeTicketMicros = 0L; // could be either in the past or future

  private SmoothRateLimiter(SleepingStopwatch stopwatch) {
    super(stopwatch);
  }

  /**
   * 设置速率
   * @param permitsPerSecond
   * @param nowMicros
   */
  @Override
  final void doSetRate(double permitsPerSecond, long nowMicros) {
    resync(nowMicros);
    double stableIntervalMicros = SECONDS.toMicros(1L) / permitsPerSecond;
    this.stableIntervalMicros = stableIntervalMicros;
    doSetRate(permitsPerSecond, stableIntervalMicros);
  }

  abstract void doSetRate(double permitsPerSecond, double stableIntervalMicros);

  @Override
  final double doGetRate() {
    return SECONDS.toMicros(1L) / stableIntervalMicros;
  }

  @Override
  final long queryEarliestAvailable(long nowMicros) {
    return nextFreeTicketMicros;
  }

  /**
   * 该方法返回当前请求需要等待的时间
   * @param requiredPermits
   * @param nowMicros
   * @return
   */
  @Override
  final long reserveEarliestAvailable(int requiredPermits, long nowMicros) {
    // 本次请求和上次请求之间间隔的时间是否应该有新的令牌生成，如果有则更新 storedPermits
    resync(nowMicros);
    long returnValue = nextFreeTicketMicros;

    // 本次请求的令牌数 requiredPermits 由两个部分组成：storedPermits 和 freshPermits，storedPermits 是令牌桶中已有的令牌
    // freshPermits 是需要新生成的令牌数
    double storedPermitsToSpend = min(requiredPermits, this.storedPermits);
    double freshPermits = requiredPermits - storedPermitsToSpend;

    // 分别计算从两个部分拿走的令牌各自需要等待的时间，然后总和作为本次请求需要等待的时间，SmoothBursty 中从 storedPermits 拿走的部分不需要等待时间

    /**
     * 对于需要从 storedPermits 中拿出来的部分则计算比较复杂，这个计算逻辑在 storedPermitsToWaitTime 方法中实现。
     * storedPermitsToWaitTime 方法在 SmoothBursty 和 SmoothWarmingUp 中有不同的实现。
     * storedPermitsToWaitTime 意思就是表示当前请求从 storedPermits 中拿出来的令牌数需要等待的时间，
     * 因为 SmoothBursty 中没有“热身”的概念， storedPermits 中有多少个就可以用多少个，不需要等待，因此 storedPermitsToWaitTime
     * 方法在 SmoothBursty 中返回的是0。而它在 SmoothWarmingUp 中的实现后面会着重分析
     */
    long storedPermitsToWaitTime= storedPermitsToWaitTime(this.storedPermits, storedPermitsToSpend);

     //freshPermits 部分的时间比较好计算：直接拿 freshPermits 乘以stableIntervalMicros 就可以得到
    long freshPermitsToWaitTime = (long) (freshPermits * stableIntervalMicros);

    long waitMicros =storedPermitsToWaitTime + freshPermitsToWaitTime;

    // 更新 nextFreeTicketMicros，这里更新的其实是下一次请求的时间，是一种“预消费”
    //算到了本次请求需要等待的时间之后，会将这个时间加到 nextFreeTicketMicros 中去
    this.nextFreeTicketMicros = LongMath.saturatedAdd(nextFreeTicketMicros, waitMicros);

    // 更新 storedPermits 从 storedPermits 减去本次请求从这部分拿走的令牌数量
    this.storedPermits -= storedPermitsToSpend;

    //todo  注意这个地方的返回值
    //  怎么返回的不是 waitMicros而是nextFreeTicketMicros
    //reserveEarliestAvailable 方法返回的是本次请求需要等待的时间，该方法中算出来的
    // waitMicros 按理来说是应该作为返回值的，但是这个方法返回的却是开始时的 nextFreeTicketMicros ，
    // 而算出来的 waitMicros 累加到 nextFreeTicketMicros 中去了。这里其实就是“预消费”，让下一次消费来为本次消费来“买单”

    return returnValue;
  }

  /**
   * Translates a specified portion of our currently stored permits which we want to spend/acquire,
   * into a throttling time. Conceptually, this evaluates the integral of the underlying function we
   * use, for the range of [(storedPermits - permitsToTake), storedPermits].
   *
   * <p>This always holds: {@code 0 <= permitsToTake <= storedPermits}
   */
  abstract long storedPermitsToWaitTime(double storedPermits, double permitsToTake);

  /**
   * Returns the number of microseconds during cool down that we have to wait to get a new permit.
   */
  abstract double coolDownIntervalMicros();


  //惰性计算
  /** Updates {@code storedPermits} and {@code nextFreeTicketMicros} based on the current time. */
  void resync(long nowMicros) {

    //首先判断当前时间是不是大于 nextFreeTicketMicros ，如果是则代表系统已经"cool down"， 这两次请求之间应该有新的 permit 生成
    // if nextFreeTicket is in the past, resync to now
    if (nowMicros > nextFreeTicketMicros) {

      //计算本次应该新添加的 permit 数量，这里分式的分母是 coolDownIntervalMicros 方法，它是一个抽象方法。
      // 在 SmoothBursty 和 SmoothWarmingUp 中分别有不同的实现。SmoothBursty 中返回的是 stableIntervalMicros 也即是 1 / QPS。
      // coolDownIntervalMicros 方法在 SmoothWarmingUp 中的计算方式为warmupPeriodMicros / maxPermits，
      // warmupPeriodMicros 是 SmoothWarmingUp 的“预热”时间
      double newPermits = (nowMicros - nextFreeTicketMicros) / coolDownIntervalMicros();

      storedPermits = min(maxPermits, storedPermits + newPermits);

      //设置 nextFreeTicketMicros 为 nowMicros
      nextFreeTicketMicros = nowMicros;
    }
  }
}
