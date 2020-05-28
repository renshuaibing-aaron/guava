

RateLimiter中采用惰性方式来计算两次请求之间生成多少新的 permit，这样省去了后台计算任务带来的开销。
最终的 requiredPermits 由两个部分组成：storedPermits 和 freshPermits 。SmoothBursty 中 storedPermits 都是一样的，不做区分。而 SmoothWarmingUp 类中将其分成两个区间：[0, thresholdPermits) 和 [thresholdPermits, maxPermits]，存在一个"热身"的阶段，thresholdPermits 是系统 stable 阶段和 cold 阶段的临界点。从 thresholdPermits 右边的部分拿走 permit 需要等待的时间更长。左半部分是一个矩形，由半部分是一个梯形。
RateLimiter 能够处理突发流量的请求，采取一种"预消费"的策略。
RateLimiter 只能用作单个JVM实例上接口或者方法的限流，不能用作全局流量控制。
