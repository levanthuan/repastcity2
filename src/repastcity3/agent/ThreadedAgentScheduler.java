/*
©Copyright 2012 Nick Malleson
This file is part of RepastCity.

RepastCity is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

RepastCity is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with RepastCity.  If not, see <http://www.gnu.org/licenses/>.
*/

package repastcity3.agent;

import java.util.logging.Level;
import java.util.logging.Logger;

import repastcity3.main.ContextManager;

public class ThreadedAgentScheduler {
	
	private static Logger LOGGER = Logger.getLogger(ThreadedAgentScheduler.class.getName());

	private boolean burglarsFinishedStepping;

	/**
	 * This is called once per iteration and goes through each burglar calling
	 * their step method. This is done (instead of using Repast scheduler) to
	 * allow multi-threading (each step method can be executed on a free core).
	 * This method actually just starts a ThreadController thread (which handles
	 * spawning threads to step burglars) and waits for it to finish
	 */
	public synchronized void agentStep() {		
		this.burglarsFinishedStepping = false;
		(new Thread(new ThreadController(this))).start();//run ThreadController
		while (!this.burglarsFinishedStepping) {
			try {
				this.wait(); // Wait for the ThreadController to call setBurglarsFinishedStepping().
			} catch (InterruptedException e) {
				LOGGER.log(Level.SEVERE, "", e);
				ContextManager.stopSim(e, ThreadedAgentScheduler.class);
			}// Đợi cho đến khi thread controller hoàn thành
		}
	}

	/**
	 * Used to tell the ContextCreator that all burglars have finished their
	 * step methods and it can continue doing whatever it was doing (it will be
	 * waiting while burglars are stepping).
	 */
	public synchronized void setBurglarsFinishedStepping() {
		this.burglarsFinishedStepping = true;
		this.notifyAll();
	}
}

/** Controls the allocation of <code>BurglarThread</code>s to free CPUs */
class ThreadController implements Runnable {
	
	private static Logger LOGGER = Logger.getLogger(ThreadController.class.getName());

	// A pointer to the scheduler, used to inform it when it can wake up
	private ThreadedAgentScheduler cc;

	private int numCPUs; // Số lượng CPU có thể được sử dụng
	private boolean[] cpuStatus; // Ghi lại cpu nào là free (true) hoặc bận (false)

	public ThreadController(ThreadedAgentScheduler cc) {
		this.cc = cc;
		this.numCPUs = Runtime.getRuntime().availableProcessors();
		// Set all CPU status to 'free'
		this.cpuStatus = new boolean[this.numCPUs];
		for (int i = 0; i < this.numCPUs; i++) {
			this.cpuStatus[i] = true;
		}
		// System.out.println("ThreadController found "+this.numCPUs+" CPUs");
	}

	/**
	 * Start the ThreadController. Iterate over all burglars, starting
	 * <code>BurglarThread</code>s on free CPUs. If no free CPUs then wait for a
	 * BurglarThread to finish.
	 */
	@Override
	public void run() {
		for (IAgent b : ContextManager.getAllAgents()) {
			boolean foundFreeCPU = false; // Find a free cpu to exectue on // Determine if there are no free CPUs so thread can wait for one to become free
			while (!foundFreeCPU) {
				synchronized (this) {
					// System.out.println("ThreadController looking for free cpu for burglar "+b.toString()+", "+Arrays.toString(cpuStatus));
					cpus: for (int i = 0; i < this.numCPUs; i++) {
						if (this.cpuStatus[i]) {
							// Start a new thread on the free CPU and set it's
							// status to false
							//System.out.println("ThreadController running burglar "+b.toString()+" on cpu "+i+". ");
							foundFreeCPU = true;
							this.cpuStatus[i] = false;
							(new Thread(new BurglarThread(this, i, b))).start();
							break cpus; // Stop looping over CPUs, have found a
										// free one for this burglar
						}
					}
					if (!foundFreeCPU) {
						this.waitForBurglarThread();
					}
				}
			}
		}

		// System.out.println("ThreadController finished looping burglars");

		// Have started stepping over all burglars, now wait for all to finish.
		boolean allFinished = false;
		while (!allFinished) {
			allFinished = true;
			synchronized (this) {
				// System.out.println("ThreadController checking CPU status: "+Arrays.toString(cpuStatus));
				cpus: for (int i = 0; i < this.cpuStatus.length; i++) {
					if (!this.cpuStatus[i]) {
						allFinished = false;
						break cpus;
					}
				} // for cpus
				if (!allFinished) {
					this.waitForBurglarThread();
				}
			}
		} // while !allFinished
			// Finished, tell the context creator to start up again.
			// System.out.println("ThreadController finished stepping all burglars (iteration "+GlobalVars.getIteration()+")"+Arrays.toString(cpuStatus));
		this.cc.setBurglarsFinishedStepping();
	}

	/**
	 *  Nguyên nhân ThreadController đợi một BurglarThred thông báo nó đã kết thúc và CPU đã trở nên tự do.
	 */
	private synchronized void waitForBurglarThread() {
		try {
			this.wait();
		} catch (InterruptedException e) {
			LOGGER.log(Level.SEVERE, "", e);
			ContextManager.stopSim(e, ThreadedAgentScheduler.class);
		}// Wait until the thread controller has finished

	}

	/**
	 * Tell this <code>ThreadController</code> that one of the CPUs is no free
	 * and it can stop waiting
	 * 
	 * @param cpuNumber
	 *            The CPU which is now free
	 */
	public synchronized void setCPUFree(int cpuNumber) {
		// System.out.println("ThreadController has been notified that CPU "+cpuNumber+" is now free");
		this.cpuStatus[cpuNumber] = true;
		this.notifyAll();
	}

}

/** Single thread to call a Burglar's step method */
class BurglarThread implements Runnable {
	
	private static Logger LOGGER = Logger.getLogger(BurglarThread.class.getName());

	private IAgent theburglar; // The burglar to step
	private ThreadController tc;
	private int cpuNumber; // The cpu that the thread is running on, used so
							// that ThreadController

	// private static int uniqueID = 0;
	// private int id;

	public BurglarThread(ThreadController tc, int cpuNumber, IAgent b) {
		this.tc = tc;
		this.cpuNumber = cpuNumber;
		this.theburglar = b;
		// this.id = BurglarThread.uniqueID++;
	}
	
	@Override
	public void run() {
		// System.out.println("BurglarThread "+id+" stepping burglar "+this.theburglar.toString()+" on CPU "+this.cpuNumber);
		try {
			this.theburglar.step();
		} catch (Exception ex) {
			LOGGER.log(Level.SEVERE, "ThreadedAgentScheduler caught an error, telling model to stop", ex);
			ContextManager.stopSim(ex, this.getClass());
		}
		// Tell the ThreadController that this thread has finished
		tc.setCPUFree(this.cpuNumber); // Tell the ThreadController that this
										// thread has finished
	}
}
