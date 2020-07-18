// irate函数
func funcIrate(vals []parser.Value, args parser.Expressions, enh *EvalNodeHelper) Vector {
	return instantValue(vals, enh.out, true)
}

// funcIrate -> instantValue
func instantValue(vals []parser.Value, out Vector, isRate bool) Vector {
	samples := vals[0].(Matrix)[0]
	// No sense in trying to compute a rate without at least two points. Drop
	// this Vector element.
	if len(samples.Points) < 2 {
		// 少于两个点是无效的range vector
		return out
	}

	// range vector里最后2个点
	lastSample := samples.Points[len(samples.Points)-1]
	previousSample := samples.Points[len(samples.Points)-2]

	var resultValue float64
	if isRate && lastSample.V < previousSample.V {
		// Counter reset.
		// 如果counter 中出现了旧值比新值大的情况，重置该counter
		// resultValue=最后选取的点的值
		resultValue = lastSample.V
	} else {
		// 最后一个值 - 最后第二个值
		resultValue = lastSample.V - previousSample.V
	}

	sampledInterval := lastSample.T - previousSample.T

	if isRate {
		// Convert to per-second.
		// 如果 counter 遇到了 重置点， 那么这里计算出来的值会【异常的大】
		resultValue /= float64(sampledInterval) / 1000
	}

	return append(out, Sample{
		Point: Point{V: resultValue},
	})
}

// rate函数入口
func funcRate(vals []Value, args Expressions, enh *EvalNodeHelper) Vector {
    // isRate = true
    return extrapolatedRate(vals, args, enh, true, true)
}

func extrapolatedRate(vals []parser.Value, args parser.Expressions, enh *EvalNodeHelper, isCounter bool, isRate bool) Vector {
	
	var (
		counterCorrection float64
		lastValue         float64
	)
	for _, sample := range samples.Points {
		// 由于counter存在reset的可能性，因此可能会出现0, 10, 5, ...这样的序列，
		// Prometheus认为从0到5实际的增值为10 + 5 = 15，而非5。
        // 这里的代码逻辑相当于将10累计到了couterCorrection中，最后补偿到总增值中。
		if isCounter && sample.V < lastValue {
			counterCorrection += lastValue
		}
		// lastValue指的是上一次迭代的值，也就是当前迭代sample.V的旧值
		lastValue = sample.V
	}
	resultValue := lastValue - samples.Points[0].V + counterCorrection

	// Duration between first/last samples and boundary of range.
	// 采样序列与用户请求的区间边界的距离。
	// durationToStart表示第一个采样点到区间头部的距离。
	// durationToEnd表示最后一个采样点到区间尾部的距离
	durationToStart := float64(samples.Points[0].T-rangeStart) / 1000
	durationToEnd := float64(rangeEnd-samples.Points[len(samples.Points)-1].T) / 1000
	// 采样序列的总时长。
	sampledInterval := float64(samples.Points[len(samples.Points)-1].T-samples.Points[0].T) / 1000
	// 采样序列的平均采样间隔，一般等于scrape interval。
	averageDurationBetweenSamples := sampledInterval / float64(len(samples.Points)-1)

	if isCounter && resultValue > 0 && samples.Points[0].V >= 0 {
		// Counters cannot be negative. If we have any slope at
		// all (i.e. resultValue went up), we can extrapolate
		// the zero point of the counter. If the duration to the
		// zero point is shorter than the durationToStart, we
		// take the zero point as the start of the series,
		// thereby avoiding extrapolation to negative counter
		// values.
		// 由于counter不能为负数，这里对零点位置作一个线性估计，
        // 确保durationToStart不会超过durationToZero。
		durationToZero := sampledInterval * (samples.Points[0].V / resultValue)
		if durationToZero < durationToStart {
			durationToStart = durationToZero
		}
	}

	// If the first/last samples are close to the boundaries of the range,
	// extrapolate the result. This is as we expect that another sample
	// will exist given the spacing between samples we've seen thus far,
	// with an allowance for noise.
	// *************** extrapolation核心部分 *****************
    // 将平均sample间隔乘以1.1作为extrapolation的判断间隔。
	extrapolationThreshold := averageDurationBetweenSamples * 1.1
	extrapolateToInterval := sampledInterval

    // 如果采样序列与用户请求的区间在头部的距离不超过阈值的话，直接补齐；
    // 如果超过阈值的话，只补齐一般的平均采样间隔。这里解决了上述的速率爆炸问题。
	if durationToStart < extrapolationThreshold {
		// 在scrape interval不发生变化、数据不缺失的情况下，
        // 基本都进入这个分支
		extrapolateToInterval += durationToStart
	} else {
		// 基本不会出现，除非scrape interval突然变很大，或者数据缺失。
		extrapolateToInterval += averageDurationBetweenSamples / 2
	}
	if durationToEnd < extrapolationThreshold {
		extrapolateToInterval += durationToEnd
	} else {
		extrapolateToInterval += averageDurationBetweenSamples / 2
	}
	// 对增值进行等比放大。
	resultValue = resultValue * (extrapolateToInterval / sampledInterval)
	if isRate {
		// 如果是求解rate，除以总的时长。
		resultValue = resultValue / ms.Range.Seconds()
	}

	return append(enh.out, Sample{
		Point: Point{V: resultValue},
	})
}