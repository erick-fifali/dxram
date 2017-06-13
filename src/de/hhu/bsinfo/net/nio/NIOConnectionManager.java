package de.hhu.bsinfo.net.nio;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.ArrayDeque;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import de.hhu.bsinfo.net.NodeMap;
import de.hhu.bsinfo.net.core.AbstractConnection;
import de.hhu.bsinfo.net.core.AbstractConnectionManager;
import de.hhu.bsinfo.net.core.DataReceiver;
import de.hhu.bsinfo.net.core.MessageCreator;
import de.hhu.bsinfo.net.core.MessageDirectory;
import de.hhu.bsinfo.net.core.NetworkException;
import de.hhu.bsinfo.net.core.RequestMap;
import de.hhu.bsinfo.utils.NodeID;

/**
 * Created by nothaas on 6/12/17.
 */
public class NIOConnectionManager extends AbstractConnectionManager {
    private static final Logger LOGGER = LogManager.getFormatterLogger(NIOConnectionManager.class.getSimpleName());

    private final short m_ownNodeId;
    private final int m_bufferSize;
    private final int m_flowControlWindowSize;
    private final int m_connectionTimeout;

    private final MessageDirectory m_messageDirectory;
    private final RequestMap m_requestMap;
    private final MessageCreator m_messageCreator;
    private final DataReceiver m_dataReceiver;

    private final NIOSelector m_nioSelector;
    private final BufferPool m_bufferPool;
    private final NodeMap m_nodeMap;
    private final ConnectionCreatorHelperThread m_connectionCreatorHelperThread;

    public NIOConnectionManager(final short p_ownNodeId, final int p_maxConnections, final int p_bufferSize, final int p_flowControlWindowSize,
            final int p_connectionTimeout, final NodeMap p_nodeMap, final MessageDirectory p_messageDirectory, final RequestMap p_requestMap,
            final MessageCreator p_messageCreator, final DataReceiver p_dataReciever) {
        super(p_maxConnections);

        m_ownNodeId = p_ownNodeId;
        m_bufferSize = p_bufferSize;
        m_flowControlWindowSize = p_flowControlWindowSize;
        m_connectionTimeout = p_connectionTimeout;

        m_nodeMap = p_nodeMap;
        m_messageDirectory = p_messageDirectory;
        m_requestMap = p_requestMap;
        m_messageCreator = p_messageCreator;
        m_dataReceiver = p_dataReciever;

        // #if LOGGER >= INFO
        LOGGER.info("Starting NIOSelector...");
        // #endif /* LOGGER >= INFO */

        m_bufferPool = new BufferPool(m_bufferSize);

        m_nioSelector = new NIOSelector(this, p_nodeMap.getAddress(p_nodeMap.getOwnNodeID()).getPort(), m_connectionTimeout, m_bufferSize);
        m_nioSelector.setName("Network-NIOSelector");
        m_nioSelector.start();

        // Start connection creator helper thread
        m_connectionCreatorHelperThread = new ConnectionCreatorHelperThread();
        m_connectionCreatorHelperThread.setName("Network-NIOConnectionCreatorHelper");
        m_connectionCreatorHelperThread.start();
    }

    @Override
    public void close() {
        LOGGER.info("ConnectionCreationHelperThread close...");
        m_connectionCreatorHelperThread.close();

        LOGGER.info("NIOSelector close...");
        m_nioSelector.close();
    }

    /**
     * Creates a new connection to the given destination
     *
     * @param p_destination
     *         the destination
     * @return a new connection
     * @throws IOException
     *         if the connection could not be created
     */
    @Override
    public AbstractConnection createConnection(final short p_destination, final AbstractConnection p_existingConnection) throws NetworkException {
        NIOConnection ret;
        ReentrantLock condLock;
        Condition cond;
        long timeStart;
        long timeNow;

        condLock = new ReentrantLock(false);
        cond = condLock.newCondition();

        if (p_existingConnection == null) {
            ret = new NIOConnection(m_ownNodeId, p_destination, m_bufferSize, m_flowControlWindowSize, m_messageCreator, m_messageDirectory, m_requestMap,
                    m_dataReceiver, m_bufferPool, m_nioSelector, m_nodeMap, condLock, cond);
        } else {
            ret = (NIOConnection) p_existingConnection;
        }

        ret.getPipeOut().createOutgoingChannel(p_destination);
        ret.connect();

        timeStart = System.currentTimeMillis();
        condLock.lock();
        while (!ret.getPipeOut().isConnected()) {
            if (ret.isConnectionCreationAborted()) {
                condLock.unlock();
                return null;
            }

            timeNow = System.currentTimeMillis();
            if (timeNow - timeStart > m_connectionTimeout) {
                // #if LOGGER >= DEBUG
                LOGGER.debug("Connection creation time-out. Interval %d ms might be to small", m_connectionTimeout);
                // #endif /* LOGGER >= DEBUG */

                condLock.unlock();
                throw new NetworkException("Connection creation timeout occurred");
            }
            try {
                cond.awaitNanos(1000);
            } catch (final InterruptedException e) { /* ignore */ }
        }
        condLock.unlock();

        m_nioSelector.changeOperationInterestAsync(new ChangeOperationsRequest(ret, NIOSelector.READ_FLOW_CONTROL));

        return ret;
    }

    @Override
    protected void closeConnection(final AbstractConnection p_connection, final boolean p_removeConnection) {
        SelectionKey key;

        NIOConnection connection = (NIOConnection) p_connection;

        if (connection.getPipeOut().getChannel() != null) {
            key = connection.getPipeOut().getChannel().keyFor(m_nioSelector.getSelector());
            if (key != null) {
                key.cancel();
            }

            try {
                connection.getPipeOut().getChannel().close();
            } catch (final IOException e) {
                // #if LOGGER >= ERROR
                LOGGER.error("Could not close connection to %s!", p_connection.getDestinationNodeId());
                // #endif /* LOGGER >= ERROR */
            }
        }

        if (connection.getPipeIn().getChannel() != null) {
            key = connection.getPipeIn().getChannel().keyFor(m_nioSelector.getSelector());
            if (key != null) {
                key.cancel();
            }

            try {
                connection.getPipeIn().getChannel().close();
            } catch (final IOException e) {
                // #if LOGGER >= ERROR
                LOGGER.error("Could not close connection to %s!", p_connection.getDestinationNodeId());
                // #endif /* LOGGER >= ERROR */
            }
        }

        connection.setConnected(false, true);
        connection.setConnected(false, false);

        if (p_removeConnection) {
            m_connectionCreatorHelperThread.pushJob(new ClosureJob(p_connection));
        }
    }

    /**
     * Creates a new connection, triggered by incoming key
     * m_buffer needs to be synchronized externally
     *
     * @param p_channel
     *         the channel of the connection
     * @throws IOException
     *         if the connection could not be created
     */
    void createConnection(final SocketChannel p_channel) throws IOException {
        short remoteNodeID;

        try {
            remoteNodeID = readRemoteNodeID(p_channel, m_nioSelector);

            // De-register SocketChannel until connection is created
            p_channel.register(m_nioSelector.getSelector(), 0);

            if (remoteNodeID != NodeID.INVALID_ID) {
                m_connectionCreatorHelperThread.pushJob(new CreationJob(remoteNodeID, p_channel));
            } else {
                throw new IOException();
            }
        } catch (final IOException e) {
            // #if LOGGER >= ERROR
            LOGGER.error("Could not create connection!");
            // #endif /* LOGGER >= ERROR */
            throw e;
        }
    }

    /**
     * Reads the NodeID of the remote node that creates this new connection
     *
     * @param p_channel
     *         the channel of the connection
     * @param p_nioSelector
     *         the NIOSelector
     * @return the NodeID
     * @throws IOException
     *         if the connection could not be created
     */
    private short readRemoteNodeID(final SocketChannel p_channel, final NIOSelector p_nioSelector) throws IOException {
        short ret;
        int bytes;
        int counter = 0;
        ByteBuffer buffer = ByteBuffer.allocateDirect(2);

        while (counter < buffer.capacity()) {
            bytes = p_channel.read(buffer);
            if (bytes == -1) {
                p_channel.keyFor(p_nioSelector.getSelector()).cancel();
                p_channel.close();
                return -1;
            }
            counter += bytes;
        }
        buffer.flip();
        ret = buffer.getShort();

        return ret;
    }

    /**
     * Helper class to encapsulate a job
     *
     * @author Kevin Beineke 22.06.2016
     */
    private static class Job {
        private byte m_id;

        /**
         * Creates an instance of Job
         *
         * @param p_id
         *         the static job identification
         */
        protected Job(final byte p_id) {
            m_id = p_id;
        }

        /**
         * Returns the job identification
         *
         * @return the job ID
         */
        public byte getID() {
            return m_id;
        }
    }

    /**
     * Helper class to encapsulate a job
     *
     * @author Kevin Beineke 22.06.2016
     */
    private static final class CreationJob extends Job {
        private short m_destination;
        private SocketChannel m_channel;

        /**
         * Creates an instance of Job
         *
         * @param p_destination
         *         the NodeID of destination
         */
        private CreationJob(final short p_destination, final SocketChannel p_channel) {
            super((byte) 0);
            m_destination = p_destination;
            m_channel = p_channel;
        }

        /**
         * Returns the destination
         *
         * @return the NodeID
         */
        public short getDestination() {
            return m_destination;
        }

        /**
         * Returns the SocketChannel
         *
         * @return the SocketChannel
         */
        SocketChannel getSocketChannel() {
            return m_channel;
        }
    }

    /**
     * Helper class to encapsulate a job
     *
     * @author Kevin Beineke 22.06.2016
     */
    private static final class ClosureJob extends Job {
        private AbstractConnection m_connection;

        /**
         * Creates an instance of Job
         *
         * @param p_connection
         *         the AbstractConnection
         */
        private ClosureJob(final AbstractConnection p_connection) {
            super((byte) 1);
            m_connection = p_connection;
        }

        /**
         * Returns the connection
         *
         * @return the AbstractConnection
         */
        public AbstractConnection getConnection() {
            return m_connection;
        }
    }

    /**
     * Helper thread that asynchronously executes commands for selector thread to avoid blocking it
     *
     * @author Kevin Beineke 22.06.2016
     */
    private class ConnectionCreatorHelperThread extends Thread {
        private ArrayDeque<Job> m_jobs = new ArrayDeque<Job>();
        private ReentrantLock m_lock = new ReentrantLock(false);
        private Condition m_jobAvailableCondition = m_lock.newCondition();

        private volatile boolean m_closed;

        @Override
        public void run() {
            short destination;
            AbstractConnection connection;
            Job job;

            while (!m_closed) {
                m_lock.lock();
                while (m_jobs.isEmpty()) {
                    try {
                        m_jobAvailableCondition.await();
                    } catch (final InterruptedException ignored) {
                        return;
                    }
                }

                job = m_jobs.pop();
                m_lock.unlock();

                if (job.getID() == 0) {
                    // 0: Create and add connection
                    CreationJob creationJob = (CreationJob) job;
                    destination = creationJob.getDestination();
                    SocketChannel channel = creationJob.getSocketChannel();

                    m_connectionCreationLock.lock();
                    if (m_openConnections == m_maxConnections) {
                        dismissRandomConnection();
                    }

                    connection = m_connections[destination & 0xFFFF];

                    if (connection == null) {
                        connection = createConnection(destination, channel);
                        m_connections[destination & 0xFFFF] = connection;
                        m_openConnections++;
                    } else {
                        bindIncomingChannel(channel, connection);
                    }

                    m_connectionCreationLock.unlock();
                } else {
                    // 1: Connection was closed by NIOSelectorThread (connection was faulty) -> Remove it
                    ClosureJob closeJob = (ClosureJob) job;
                    connection = closeJob.getConnection();

                    m_connectionCreationLock.lock();
                    AbstractConnection tmp = m_connections[connection.getDestinationNodeId() & 0xFFFF];
                    if (connection.equals(tmp)) {
                        m_connections[connection.getDestinationNodeId() & 0xFFFF] = null;
                        m_openConnections--;
                    }
                    m_connectionCreationLock.unlock();

                    // Trigger failure handling for remote node over faulty connection
                    m_listener.connectionLost(connection.getDestinationNodeId());
                }
            }
        }

        public void close() {
            m_closed = true;
            m_connectionCreatorHelperThread.interrupt();
            try {
                m_connectionCreatorHelperThread.join();
            } catch (final InterruptedException ignore) {

            }
        }

        /**
         * Push new job
         *
         * @param p_job
         *         the new job to add
         */
        private void pushJob(final Job p_job) {
            m_lock.lock();
            m_jobs.push(p_job);
            m_jobAvailableCondition.signalAll();
            m_lock.unlock();
        }

        private NIOConnection createConnection(final short p_destination, final SocketChannel p_channel) {
            NIOConnection ret;

            ret = new NIOConnection(m_ownNodeId, p_destination, m_bufferSize, m_flowControlWindowSize, m_messageCreator, m_messageDirectory, m_requestMap,
                    m_dataReceiver, m_bufferPool, m_nioSelector, m_nodeMap);
            ret.getPipeIn().bindIncomingChannel(p_channel);

            // Register connection as attachment
            m_nioSelector.changeOperationInterestAsync(new ChangeOperationsRequest(ret, NIOSelector.READ));
            ret.setConnected(true, false);

            return ret;
        }

        private void bindIncomingChannel(final SocketChannel p_channel, final AbstractConnection p_connection) {
            ((NIOConnection) p_connection).getPipeIn().bindIncomingChannel(p_channel);

            // Register connection as attachment
            m_nioSelector.changeOperationInterestAsync(new ChangeOperationsRequest((NIOConnection) p_connection, NIOSelector.READ));
            p_connection.setConnected(true, false);
        }
    }
}