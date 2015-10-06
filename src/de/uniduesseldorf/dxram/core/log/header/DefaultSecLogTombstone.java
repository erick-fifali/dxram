
package de.uniduesseldorf.dxram.core.log.header;

import de.uniduesseldorf.dxram.core.chunk.Chunk;
import de.uniduesseldorf.dxram.core.log.LogHandler;

/**
 * Implements a log entry header for removal (secondary log)
 * @author Kevin Beineke
 *         25.06.2015
 */
public class DefaultSecLogTombstone implements LogEntryHeaderInterface {

	// Attributes
	private static final short MAX_SIZE = LogHandler.LOG_ENTRY_TYP_SIZE + LogHandler.MAX_LOG_ENTRY_LID_SIZE + LogHandler.MAX_LOG_ENTRY_VER_SIZE;
	private static final byte LID_OFFSET = LogHandler.LOG_ENTRY_TYP_SIZE;

	// Constructors
	/**
	 * Creates an instance of TombstoneSecondaryLog
	 */
	public DefaultSecLogTombstone() {}

	// Methods
	@Override
	public byte[] createLogEntryHeader(final Chunk p_chunk, final byte p_rangeID, final short p_source) {
		System.out.println("Do not call createLogEntryHeader() for secondary log entries. Convert instead.");
		return null;
	}

	@Override
	public byte[] createTombstone(final long p_chunkID, final int p_version, final byte p_rangeID, final short p_source) {
		System.out.println("Do not call createTombstone() for secondary log entries. Convert instead.");
		return null;
	}

	@Override
	public short getType(final byte[] p_buffer, final int p_offset) {
		return (short) (p_buffer[p_offset] & 0x00FF);
	}

	@Override
	public byte getRangeID(final byte[] p_buffer, final int p_offset) {
		System.out.println("No RangeID available!");
		return -1;
	}

	@Override
	public short getSource(final byte[] p_buffer, final int p_offset) {
		System.out.println("No source available!");
		return -1;
	}

	@Override
	public short getNodeID(final byte[] p_buffer, final int p_offset) {
		System.out.println("No NodeID available!");
		return -1;
	}

	@Override
	public long getLID(final byte[] p_buffer, final int p_offset) {
		long ret = -1;
		final int offset = p_offset + LID_OFFSET;
		final byte length = (byte) ((getType(p_buffer, p_offset) & AbstractLogEntryHeader.LID_LENGTH_MASK) >> AbstractLogEntryHeader.LID_LENGTH_SHFT);

		if (length == 0) {
			ret = p_buffer[offset] & 0xff;
		} else if (length == 1) {
			ret = (p_buffer[offset] & 0xff) + ((p_buffer[offset + 1] & 0xff) << 8);
		} else if (length == 2) {
			ret = (p_buffer[offset] & 0xff) + ((p_buffer[offset + 1] & 0xff) << 8)
					+ ((p_buffer[offset + 2] & 0xff) << 16) + ((p_buffer[offset + 3] & 0xff) << 24);
		} else if (length == 3) {
			ret = (p_buffer[offset] & 0xff) + ((p_buffer[offset + 1] & 0xff) << 8) + ((p_buffer[offset + 2] & 0xff) << 16)
					+ (((long) p_buffer[offset + 3] & 0xff) << 24) + (((long) p_buffer[offset + 4] & 0xff) << 32)
					+ (((long) p_buffer[offset + 5] & 0xff) << 40);
		}

		return ret;
	}

	@Override
	public long getChunkID(final byte[] p_buffer, final int p_offset) {
		System.out.println("No ChunkID available!");
		return -1;
	}

	@Override
	public int getLength(final byte[] p_buffer, final int p_offset) {
		return 0;
	}

	@Override
	public int getVersion(final byte[] p_buffer, final int p_offset) {
		int ret = 1;
		final int offset = p_offset + getVEROffset(p_buffer, p_offset);
		final byte length = (byte) ((getType(p_buffer, p_offset) & AbstractLogEntryHeader.VER_LENGTH_MASK) >> AbstractLogEntryHeader.VER_LENGTH_SHFT);

		if (length == 1) {
			ret = p_buffer[offset] & 0xff;
		} else if (length == 2) {
			ret = (p_buffer[offset] & 0xff) + ((p_buffer[offset + 1] & 0xff) << 8);
		} else if (length == 3) {
			ret = (p_buffer[offset] & 0xff) + ((p_buffer[offset + 1] & 0xff) << 8)
					+ ((p_buffer[offset + 2] & 0xff) << 16);
		}

		return -ret;
	}

	@Override
	public long getChecksum(final byte[] p_buffer, final int p_offset) {
		System.out.println("No checksum available!");
		return -1;
	}

	@Override
	public boolean wasMigrated() {
		return false;
	}

	@Override
	public boolean isTombstone() {
		return true;
	}

	@Override
	public boolean isInvalid(final byte[] p_buffer, final int p_offset) {
		return (p_buffer[p_offset] & AbstractLogEntryHeader.INVALIDATION_MASK) == 2;
	}

	@Override
	public short getHeaderSize(final byte[] p_buffer, final int p_offset) {
		short ret;
		byte versionSize;

		versionSize = (byte) ((getType(p_buffer, p_offset) & AbstractLogEntryHeader.VER_LENGTH_MASK) >> AbstractLogEntryHeader.VER_LENGTH_SHFT);
		ret = (short) (getVEROffset(p_buffer, p_offset) + versionSize);

		return ret;
	}

	@Override
	public short getMaxHeaderSize() {
		return MAX_SIZE;
	}

	@Override
	public short getConversionOffset() {
		System.out.println("No conversion offset available!");
		return -1;
	}

	@Override
	public short getRIDOffset() {
		System.out.println("No RangeID available!");
		return -1;
	}

	@Override
	public short getSRCOffset() {
		System.out.println("No source available!");
		return -1;
	}

	@Override
	public short getNIDOffset() {
		System.out.println("No NodeID available!");
		return -1;
	}

	@Override
	public short getLIDOffset() {
		return LID_OFFSET;
	}

	@Override
	public short getLENOffset(final byte[] p_buffer, final int p_offset) {
		System.out.println("No length available, always 0!");
		return -1;
	}

	@Override
	public short getVEROffset(final byte[] p_buffer, final int p_offset) {
		short ret = LID_OFFSET;
		final byte localIDSize = (byte) ((getType(p_buffer, p_offset) & AbstractLogEntryHeader.LID_LENGTH_MASK) >> AbstractLogEntryHeader.LID_LENGTH_SHFT);

		switch (localIDSize) {
		case 0:
			ret += 1;
			break;
		case 1:
			ret += 2;
			break;
		case 2:
			ret += 4;
			break;
		case 3:
			ret += 6;
			break;
		default:
			System.out.println("Error: LocalID length unknown!");
			break;
		}

		return ret;
	}

	@Override
	public short getCRCOffset(final byte[] p_buffer, final int p_offset) {
		System.out.println("No checksum available!");
		return -1;
	}

	@Override
	public void print(final byte[] p_buffer, final int p_offset) {
		System.out.println("********************Tombstone for Secondary Log********************");
		System.out.println("* LocalID: " + getLID(p_buffer, p_offset));
		System.out.println("* Length: " + getLength(p_buffer, p_offset));
		System.out.println("* Version: " + getVersion(p_buffer, p_offset));
		System.out.println("*******************************************************************");
	}
}
