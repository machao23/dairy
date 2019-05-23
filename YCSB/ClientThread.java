// 压测客户端的线程
public class ClientThread implements Runnable {
	
	private double targetOpsPerMs;
	private long targetOpsTickNs;
	
	public ClientThread(DB db, boolean dotransactions, Workload workload, Properties props, int opcount,
						  double targetperthreadperms, CountDownLatch completeLatch) {
		if (targetperthreadperms > 0) {
			// 一毫秒操作多少次
			targetOpsPerMs = targetperthreadperms;
			//每隔多少纳秒操作一次
			targetOpsTickNs = (long) (1000000 / targetOpsPerMs);
		}
	}
	
	@Override
	public void run() {
		long startTimeNanos = System.nanoTime();

        while (((opcount == 0) || (opsdone < opcount)) && !workload.isStopRequested()) {

          if (!workload.doTransaction(db, workloadstate)) {
            break;
          }

          opsdone++;
		  // 每执行完一次操作，根据qps反推出的操作间隔，停顿这个时长
          throttleNanos(startTimeNanos);
        }
	}
	
	private void throttleNanos(long startTimeNanos) {
		//throttle the operations
		if (targetOpsPerMs > 0) {
		  // delay until next tick
		  // 因为startTimeNanos不变，所以通过累计的ops * 间隔时长计算出需要停顿的时长
		  long deadline = startTimeNanos + opsdone * targetOpsTickNs;
		  sleepUntil(deadline);
		}
	}	
	
	private static void sleepUntil(long deadline) {
		while (System.nanoTime() < deadline) {
			LockSupport.parkNanos(deadline - System.nanoTime());
		}
	}
}

// 定时结束压测的线程
public class TerminatorThread extends Thread {
	public void run() {
		try {
		  Thread.sleep(maxExecutionTime * 1000);
		} catch (InterruptedException e) {
		  System.err.println("Could not wait until max specified time, TerminatorThread interrupted.");
		  return;
		}
		// 到了指定运行最大时长后，就结束整个压测
		System.err.println("Maximum time elapsed. Requesting stop for the workload.");
		workload.requestStop();
		System.err.println("Stop requested for workload. Now Joining!");
		for (Thread t : threads) {
		  while (t.isAlive()) {
			try {
			  t.join(waitTimeOutInMS);
			  if (t.isAlive()) {
				System.out.println("Still waiting for thread " + t.getName() + " to complete. " +
					"Workload status: " + workload.isStopRequested());
			  }
			} catch (InterruptedException e) {
			  // Do nothing. Don't know why I was interrupted.
			}
		  }
		}
	}
}