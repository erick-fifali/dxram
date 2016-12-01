/*
 * Copyright (C) 2016 Heinrich-Heine-Universitaet Duesseldorf, Institute of Computer Science, Department Operating Systems
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 */

package de.hhu.bsinfo.dxram.recovery.messages;

import java.nio.ByteBuffer;

import de.hhu.bsinfo.dxram.net.messages.DXRAMMessageTypes;
import de.hhu.bsinfo.ethnet.AbstractRequest;

/**
 * Recover Backup Range Request
 *
 * @author Kevin Beineke, kevin.beineke@hhu.de, 08.10.2015
 */
public class RecoverBackupRangeRequest extends AbstractRequest {

    // Attributes
    private short m_owner;
    private short[] m_backupPeers;
    private long m_firstChunkIDOrRangeID;

    // Constructors

    /**
     * Creates an instance of RecoverBackupRangeRequest
     */
    public RecoverBackupRangeRequest() {
        super();

        m_owner = (short) -1;
        m_backupPeers = null;
        m_firstChunkIDOrRangeID = -1;
    }

    /**
     * Creates an instance of RecoverBackupRangeRequest
     *
     * @param p_destination
     *     the destination
     * @param p_owner
     *     the NodeID of the owner
     * @param p_backupPeers
     *     the backup peers for to be recovered range
     * @param p_firstChunkIDOrRangeID
     *     the first ChunkID of the backup range or the RangeID for migrations
     */
    public RecoverBackupRangeRequest(final short p_destination, final short p_owner, final short[] p_backupPeers, final long p_firstChunkIDOrRangeID) {
        super(p_destination, DXRAMMessageTypes.RECOVERY_MESSAGES_TYPE, RecoveryMessages.SUBTYPE_RECOVER_BACKUP_RANGE_REQUEST, true);

        m_owner = p_owner;
        m_backupPeers = p_backupPeers;
        m_firstChunkIDOrRangeID = p_firstChunkIDOrRangeID;
    }

    // Getters

    /**
     * Get the owner
     *
     * @return the NodeID
     */
    public final short getOwner() {
        return m_owner;
    }

    /**
     * Get the backup peers
     *
     * @return the backup peers
     */
    public final short[] getBackupPeers() {
        return m_backupPeers;
    }

    /**
     * Get the ChunkID or RangeID
     *
     * @return the ChunkID or RangeID
     */
    public final long getFirstChunkIDOrRangeID() {
        return m_firstChunkIDOrRangeID;
    }

    @Override
    protected final int getPayloadLength() {
        return Short.BYTES + Byte.BYTES + m_backupPeers.length * Short.BYTES + Long.BYTES;
    }

    // Methods
    @Override
    protected final void writePayload(final ByteBuffer p_buffer) {
        p_buffer.putShort(m_owner);

        p_buffer.put((byte) m_backupPeers.length);
        for (short peer : m_backupPeers) {
            p_buffer.putShort(peer);
        }

        p_buffer.putLong(m_firstChunkIDOrRangeID);
    }

    @Override
    protected final void readPayload(final ByteBuffer p_buffer) {
        byte length;

        m_owner = p_buffer.getShort();

        length = p_buffer.get();
        m_backupPeers = new short[length];
        for (int i = 0; i < length; i++) {
            m_backupPeers[i] = p_buffer.getShort();
        }

        m_firstChunkIDOrRangeID = p_buffer.getLong();
    }

}
