package de.uniduesseldorf.dxram.core.lock;

import org.apache.log4j.Logger;

import de.uniduesseldorf.dxram.core.chunk.ChunkService;
import de.uniduesseldorf.dxram.core.chunk.messages.ChunkMessages;
import de.uniduesseldorf.dxram.core.engine.DXRAMException;
import de.uniduesseldorf.dxram.core.engine.DXRAMService;
import de.uniduesseldorf.dxram.core.engine.nodeconfig.NodeRole;
import de.uniduesseldorf.dxram.core.events.ConnectionLostListener.ConnectionLostEvent;
import de.uniduesseldorf.dxram.core.lock.messages.LockMessages;
import de.uniduesseldorf.dxram.core.lock.messages.LockRequest;
import de.uniduesseldorf.dxram.core.lock.messages.LockResponse;
import de.uniduesseldorf.dxram.core.lock.messages.UnlockMessage;
import de.uniduesseldorf.dxram.core.mem.Chunk;
import de.uniduesseldorf.dxram.core.mem.DataStructure;
import de.uniduesseldorf.dxram.core.mem.storage.MemoryManagerComponent;
import de.uniduesseldorf.dxram.core.net.NetworkComponent;
import de.uniduesseldorf.dxram.core.util.ChunkID;

import de.uniduesseldorf.menet.AbstractMessage;
import de.uniduesseldorf.menet.NetworkException;
import de.uniduesseldorf.utils.Contract;
import de.uniduesseldorf.utils.config.Configuration;

public class LockService extends DXRAMService {
	
	public static final String SERVICE_NAME = "Lock";
	
	private final Logger LOGGER = Logger.getLogger(LockService.class);
	
	private NetworkComponent m_network = null;
	private MemoryManagerComponent m_memoryManager = null;
	private LockComponent m_lock = null;
	
	private boolean m_statisticsEnabled = false;
	
	public LockService() {
		super(SERVICE_NAME);
	}
	
	@Override
	protected boolean startService(Configuration p_configuration) {
	
		m_network = getComponent(NetworkComponent.COMPONENT_IDENTIFIER);
		m_memoryManager = getComponent(MemoryManagerComponent.COMPONENT_IDENTIFIER);
		m_lock = getComponent(LockComponent.COMPONENT_IDENTIFIER);
		
		m_network.registerMessageType(ChunkMessages.TYPE, LockMessages.SUBTYPE_LOCK_REQUEST, LockRequest.class);
		m_network.registerMessageType(ChunkMessages.TYPE, LockMessages.SUBTYPE_LOCK_RESPONSE, LockResponse.class);
		m_network.registerMessageType(ChunkMessages.TYPE, LockMessages.SUBTYPE_UNLOCK_MESSAGE, UnlockMessage.class);
		
		// TODO get values from configuration
		
		return true;
	}


	@Override
	protected boolean shutdownService() 
	{
		m_network = null;
		
		return true;
	}
	
	public int lock(final DataStructure p_dataStructure) {
		return lock(p_dataStructure, false);
	}

	public int lock(final DataStructure p_dataStructure, final boolean p_readLock) {
		DefaultLock lock;
		short primaryPeer;
		LockRequest request;
		LockResponse response;
		boolean success = false;

		if (m_statisticsEnabled)
			Operation.LOCK.enter();

		if (getSystemData().getNodeRole().equals(NodeRole.SUPERPEER)) {
			LOGGER.error("a superpeer must not use/lock chunks");
		} else {
			m_memoryManager.lockAccess();
			if (m_memoryManager.isResponsible(p_dataStructure.getID())) {
				int size;
				int bytesRead;

				// Local lock
				lock = new DefaultLock(p_dataStructure.getID(), getSystemData().getNodeID(), p_readLock);
				m_lock.lock(lock);

				success = m_memoryManager.get(p_dataStructure);
				m_memoryManager.unlockAccess();
			} else {
				while (null == ret || -1 == ret.getChunkID()) {
					primaryPeer = m_lookup.get(p_chunkID).getPrimaryPeer();
					if (primaryPeer == getSystemData().getNodeID()) {
						int size;
						int bytesRead;

						// Local lock
						lock = new DefaultLock(p_chunkID, getSystemData().getNodeID(), p_readLock);
						m_lock.lock(lock);

						m_memoryManager.lockAccess();
						size = m_memoryManager.getSize(p_chunkID);
						ret = new Chunk(p_chunkID, size);
						bytesRead = m_memoryManager.get(p_chunkID, ret.getData().array(), 0, size);
						m_memoryManager.unlockAccess();
					} else {
						// Remote lock
						request = new LockRequest(primaryPeer, p_chunkID, p_readLock);
						request.setIgnoreTimeout(true);
						try {
							request.sendSync(m_network);
						} catch (final NetworkException e) {
							m_lookup.invalidate(p_chunkID);
							continue;
						}
						response = request.getResponse(LockResponse.class);
						if (response != null) {
							ret = response.getChunk();
						}
						if (-1 == ret.getChunkID()) {
							try {
								// Chunk is locked, wait a bit
								Thread.sleep(10);
							} catch (final InterruptedException e) {}
						}
					}
				}
			}
		}

		if (m_statisticsEnabled)
			Operation.LOCK.leave();

		return ret;
	}

	@Override
	public void unlock(final long p_chunkID) throws DXRAMException {
		short primaryPeer;
		UnlockMessage request;

		Operation.UNLOCK.enter();

		ChunkID.check(p_chunkID);

		if (NodeID.getRole().equals(Role.SUPERPEER)) {
			LOGGER.error("a superpeer must not use chunks");
		} else {
			if (m_memoryManager.isResponsible(p_chunkID)) {
				// Local release
				m_lock.unlock(p_chunkID, m_nodeID);
			} else {
				while (true) {
					primaryPeer = m_lookup.get(p_chunkID).getPrimaryPeer();
					if (primaryPeer == m_nodeID) {
						// Local release
						m_lock.unlock(p_chunkID, m_nodeID);
						break;
					} else {
						try {
							// Remote release
							request = new UnlockMessage(primaryPeer, p_chunkID);
							request.send(m_network);
						} catch (final NetworkException e) {
							m_lookup.invalidate(p_chunkID);
							continue;
						}
						break;
					}
				}
			}
		}

		Operation.UNLOCK.leave();
	}
	
	@Override
	public void triggerEvent(ConnectionLostEvent p_event) {
		// TODO move to lock service?
		Contract.checkNotNull(p_event, "no event given");

		try {
			m_lock.unlockAll(p_event.getSource());
		} catch (final DXRAMException e) {}
	}
	
	@Override
	public void onIncomingMessage(final AbstractMessage p_message) {
		LOGGER.trace("Entering incomingMessage with: p_message=" + p_message);

		if (p_message != null) {
			if (p_message.getType() == ChunkMessages.TYPE) {
				switch (p_message.getSubtype()) {
				case ChunkMessages.SUBTYPE_GET_REQUEST:
					incomingGetRequest((GetRequest) p_message);
					break;
				case ChunkMessages.SUBTYPE_PUT_REQUEST:
					incomingPutRequest((PutRequest) p_message);
					break;
				case ChunkMessages.SUBTYPE_REMOVE_REQUEST:
					incomingRemoveRequest((RemoveRequest) p_message);
					break;
				case ChunkMessages.SUBTYPE_LOCK_REQUEST:
					incomingLockRequest((LockRequest) p_message);
					break;
				case ChunkMessages.SUBTYPE_UNLOCK_MESSAGE:
					incomingUnlockMessage((UnlockMessage) p_message);
					break;
				case ChunkMessages.SUBTYPE_DATA_REQUEST:
					incomingDataRequest((DataRequest) p_message);
					break;
				case ChunkMessages.SUBTYPE_DATA_MESSAGE:
					incomingDataMessage((DataMessage) p_message);
					break;
				case ChunkMessages.SUBTYPE_MULTIGET_REQUEST:
					incomingMultiGetRequest((MultiGetRequest) p_message);
					break;
				case ChunkMessages.SUBTYPE_CHUNK_COMMAND_MESSAGE:
					incomingCommandMessage((ChunkCommandMessage) p_message);
					break;
				case ChunkMessages.SUBTYPE_CHUNK_COMMAND_REQUEST:
					incomingCommandRequest((ChunkCommandRequest) p_message);
					break;
				default:
					break;
				}
			}
		}

		LOGGER.trace("Exiting incomingMessage");
	}
}