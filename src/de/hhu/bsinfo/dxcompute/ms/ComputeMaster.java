
package de.hhu.bsinfo.dxcompute.ms;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Vector;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import de.hhu.bsinfo.dxcompute.coord.BarrierMasterInternal;
import de.hhu.bsinfo.dxcompute.ms.messages.ExecuteTaskRequest;
import de.hhu.bsinfo.dxcompute.ms.messages.ExecuteTaskResponse;
import de.hhu.bsinfo.dxcompute.ms.messages.MasterSlaveMessages;
import de.hhu.bsinfo.dxcompute.ms.messages.SlaveJoinRequest;
import de.hhu.bsinfo.dxcompute.ms.messages.SlaveJoinResponse;
import de.hhu.bsinfo.dxram.boot.AbstractBootComponent;
import de.hhu.bsinfo.dxram.data.ChunkID;
import de.hhu.bsinfo.dxram.engine.DXRAMServiceAccessor;
import de.hhu.bsinfo.dxram.logger.LoggerComponent;
import de.hhu.bsinfo.dxram.nameservice.NameserviceComponent;
import de.hhu.bsinfo.dxram.net.NetworkComponent;
import de.hhu.bsinfo.dxram.net.NetworkErrorCodes;
import de.hhu.bsinfo.menet.AbstractMessage;
import de.hhu.bsinfo.menet.NetworkHandler.MessageReceiver;
import de.hhu.bsinfo.menet.NodeID;
import de.hhu.bsinfo.utils.Pair;

public class ComputeMaster extends ComputeMSBase implements MessageReceiver {
	private static final int MAX_TASK_COUNT = 100;

	private Vector<Short> m_signedOnSlaves = new Vector<Short>();
	private Lock m_joinLock = new ReentrantLock(false);
	private ConcurrentLinkedQueue<Task> m_tasks = new ConcurrentLinkedQueue<Task>();
	private AtomicInteger m_taskCount = new AtomicInteger(0);
	private int m_executeBarrierIdentifier;
	private BarrierMasterInternal m_executionBarrier;

	private AtomicLong m_payloadIdCounter = new AtomicLong(0);

	public ComputeMaster(final int p_computeGroupId, final long p_pingIntervalMs,
			final DXRAMServiceAccessor p_serviceAccessor,
			final NetworkComponent p_network,
			final LoggerComponent p_logger, final NameserviceComponent p_nameservice,
			final AbstractBootComponent p_boot) {
		super(ComputeRole.MASTER, p_computeGroupId, p_pingIntervalMs, p_serviceAccessor, p_network, p_logger,
				p_nameservice, p_boot);

		p_network.register(SlaveJoinRequest.class, this);

		m_executionBarrier = new BarrierMasterInternal(p_network, p_logger);

		start();
	}

	public ArrayList<Short> getConnectedSlaves() {
		@SuppressWarnings("unchecked")
		Vector<Short> tmp = (Vector<Short>) m_signedOnSlaves.clone();
		ArrayList<Short> ret = new ArrayList<Short>(tmp.size());
		for (Short s : tmp) {
			ret.add(s);
		}

		return ret;
	}

	public boolean submitTask(final Task p_task) {
		if (m_taskCount.get() < MAX_TASK_COUNT) {
			m_tasks.add(p_task);
			m_taskCount.incrementAndGet();
			// set unique payload id
			p_task.getPayload().setPayloadId(m_payloadIdCounter.getAndIncrement());
			return true;
		} else {
			return false;
		}
	}

	public int getNumberOfTasksInQueue() {
		return m_taskCount.get();
	}

	@Override
	public void run() {

		boolean loop = true;
		while (loop) {
			switch (m_state) {
				case STATE_SETUP:
					stateSetup();
					break;
				case STATE_IDLE:
					stateIdle();
					break;
				case STATE_EXECUTE:
					stateExecute();
					break;
				case STATE_ERROR_DIE:
					stateErrorDie();
					break;
				case STATE_TERMINATE:
					loop = false;
					break;
				default:
					assert 1 == 2;
					break;
			}
		}
	}

	@Override
	public void shutdown() {
		// shutdown main compute thread
		m_state = State.STATE_TERMINATE;
		try {
			join();
		} catch (final InterruptedException e) {}

		// invalidate entry in nameservice
		m_nameservice.register(-1, m_nameserviceMasterNodeIdKey);
	}

	@Override
	public void onIncomingMessage(final AbstractMessage p_message) {
		if (p_message != null) {
			if (p_message.getType() == MasterSlaveMessages.TYPE) {
				switch (p_message.getSubtype()) {
					case MasterSlaveMessages.SUBTYPE_SLAVE_JOIN_REQUEST:
						incomingSlaveJoinRequest((SlaveJoinRequest) p_message);
						break;
					default:
						break;
				}
			}
		}
	}

	private void stateSetup() {
		m_logger.info(getClass(), "Setting up master of compute group " + m_computeGroupId);

		// check first, if there is already a master registered for this compute group
		long id = m_nameservice.getChunkID(m_nameserviceMasterNodeIdKey, 0);
		if (id != -1) {
			m_logger.error(getClass(), "Cannot setup master for compute group id " + m_computeGroupId + ", node "
					+ NodeID.toHexString(ChunkID.getCreatorID(id)) + " is already master of group");
			m_state = State.STATE_ERROR_DIE;
			return;
		}

		// setup bootstrapping for other slaves
		// use the nameservice to store our node id
		m_nameservice.register(ChunkID.getChunkID(m_boot.getNodeID(), ChunkID.INVALID_ID),
				m_nameserviceMasterNodeIdKey);

		m_state = State.STATE_IDLE;

		m_logger.debug(getClass(), "Entering idle state");
	}

	private void stateIdle() {
		if (m_taskCount.get() > 0) {
			if (m_signedOnSlaves.size() < 1) {
				m_logger.warn(getClass(), "Got " + m_taskCount.get() + " tasks queued but no slaves");
				try {
					Thread.sleep(2000);
				} catch (final InterruptedException e) {}
			} else {
				m_state = State.STATE_EXECUTE;
			}
		} else {
			// check if we have to ping the slaves to check if they are still online
			if (m_lastPingMs + m_pingIntervalMs < System.currentTimeMillis()) {
				// check if slaves are still alive
				List<Short> onlineNodesList = m_boot.getIDsOfOnlineNodes();

				m_joinLock.lock();
				Iterator<Short> it = m_signedOnSlaves.iterator();
				while (it.hasNext()) {
					short slave = it.next();
					if (!onlineNodesList.contains(slave)) {
						m_logger.info(getClass(),
								"Slave " + NodeID.toHexString(slave) + " is not available anymore, removing.");
						it.remove();
					}
				}
				m_joinLock.unlock();

				m_lastPingMs = System.currentTimeMillis();
				m_logger.trace(getClass(), "Pinging slaves, " + m_signedOnSlaves.size() + " online.");
			}

			// do nothing
			Thread.yield();
		}
	}

	private void stateExecute() {
		// lock joining of further slaves
		m_joinLock.lock();

		// get next task
		m_taskCount.decrementAndGet();
		Task task = m_tasks.poll();
		AbstractTaskPayload taskPayload = task.getPayload();
		if (taskPayload == null) {
			m_logger.error(getClass(), "Cannot proceed with task " + task + ", missing payload.");
			m_state = State.STATE_IDLE;
			m_joinLock.unlock();
			return;
		}

		m_logger.info(getClass(),
				"Starting execution of task " + task + " with " + m_signedOnSlaves.size() + " slaves.");

		task.notifyListenersExecutionStarts();

		// send task to slaves
		int numberOfSlavesOnExecution = 0;
		m_executeBarrierIdentifier++;
		for (Short slaveId : m_signedOnSlaves) {
			// set incremental slave id, 0 based
			taskPayload.setSlaveId(numberOfSlavesOnExecution);
			// pass barrier identifier for syncing after task along
			ExecuteTaskRequest request = new ExecuteTaskRequest(slaveId, m_executeBarrierIdentifier % 2, taskPayload);

			NetworkErrorCodes err = m_network.sendSync(request);
			if (err != NetworkErrorCodes.SUCCESS) {
				m_logger.error(getClass(),
						"Sending task to slave " + NodeID.toHexString(slaveId) + " failed: " + err);
				// remove slave from list
				m_signedOnSlaves.remove(slaveId);
				continue;
			}

			ExecuteTaskResponse response = (ExecuteTaskResponse) request.getResponse();
			if (response.getStatusCode() != 0) {
				// exclude slave from execution
				m_logger.error(getClass(), "Slave " + NodeID.toHexString(slaveId) + " response "
						+ response.getStatusCode() + " on execution of task " + task
						+ " excluding from current execution");
			} else {
				numberOfSlavesOnExecution++;
			}
		}

		taskPayload.setSlaveId(-1);

		m_logger.info(getClass(),
				"Syncing with " + numberOfSlavesOnExecution + "/" + m_signedOnSlaves.size() + " slaves...");

		m_executionBarrier.execute(numberOfSlavesOnExecution, m_executeBarrierIdentifier % 2, -1);

		m_logger.debug(getClass(),
				"Syncing done.");

		// grab return codes from barrier
		ArrayList<Pair<Short, Long>> barrierData = m_executionBarrier.getBarrierData();
		ArrayList<Pair<Short, Integer>> returnCodes = new ArrayList<Pair<Short, Integer>>(barrierData.size());
		for (Pair<Short, Long> item : barrierData) {
			returnCodes.add(new Pair<Short, Integer>(item.first(), (int) item.second().longValue()));
		}

		task.setTaskExecutionResults(returnCodes);
		task.notifyListenersExecutionCompleted();

		m_state = State.STATE_IDLE;
		// allow further slaves to join
		m_joinLock.unlock();

		m_logger.debug(getClass(), "Entering idle state");
	}

	private void stateErrorDie() {
		m_logger.error(getClass(), "Master error state");
		try {
			Thread.sleep(1000);
		} catch (final InterruptedException e) {}
	}

	private void incomingSlaveJoinRequest(final SlaveJoinRequest p_message) {
		if (m_joinLock.tryLock()) {
			if (m_signedOnSlaves.contains(p_message.getSource())) {
				m_logger.warn(getClass(), "Joining slave, already joined: "
						+ NodeID.toHexString(p_message.getSource()));
			} else {
				m_signedOnSlaves.add(p_message.getSource());
			}

			SlaveJoinResponse response = new SlaveJoinResponse(p_message);
			response.setStatusCode((byte) 0);
			NetworkErrorCodes err = m_network.sendMessage(response);
			if (err != NetworkErrorCodes.SUCCESS) {
				m_logger.error(getClass(), "Sending response to join request of slave "
						+ NodeID.toHexString(p_message.getSource()) + "failed: " + err);
				// remove slave
				m_signedOnSlaves.remove(p_message.getSource());
			} else {
				m_logger.info(getClass(), "Slave " + NodeID.toHexString(p_message.getSource()) + " has joined");
			}

			m_joinLock.unlock();
		} else {
			m_logger.trace(getClass(), "Cannot join slave, master not in idle state.");

			// send response that joining is not possible currently
			SlaveJoinResponse response = new SlaveJoinResponse(p_message);
			response.setStatusCode((byte) 1);
			NetworkErrorCodes err = m_network.sendMessage(response);
			if (err != NetworkErrorCodes.SUCCESS) {
				m_logger.error(getClass(), "Sending response to join request of slave "
						+ NodeID.toHexString(p_message.getSource()) + "failed: " + err);
			}
		}
	}
}
